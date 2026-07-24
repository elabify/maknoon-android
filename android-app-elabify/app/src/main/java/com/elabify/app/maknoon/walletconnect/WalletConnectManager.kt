// WalletConnect (EVM-only) manager, the Android mirror of the iOS
// WalletConnectManager (ADR-0049). Reown Android WalletKit already ships its
// own relay socket (Scarlet/OkHttp), so unlike iOS there is no custom socket:
// we initialise WalletKit, register a delegate, and bridge its callbacks to
// StateFlows the Compose UI observes.
//
// Every request routes through the EXISTING SDK signers behind the user's
// approval, never the mini-app bridge. This first Android cut signs with
// SOFTWARE wallets across the full method set (personal_sign / eth_sign,
// eth_signTypedData{,_v3,_v4} via the shared Rust hasher, eth_sendTransaction /
// eth_signTransaction with a wallet-managed nonce, wallet_switchEthereumChain).
// Hardware (Ledger / Trezor) dispatch returns a clear "being wired" error for
// now; the cores already expose the typed-data methods, so it is a follow-up.

package com.elabify.app.maknoon.walletconnect

import android.app.Application
import android.content.Context
import android.util.Log
import com.elabify.app.maknoon.BuildConfig
import com.elabify.app.maknoon.MaknoonApplication
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumDeviceSigner
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumStores
import com.elabify.app.maknoon.ui.wallet.ethereum.loadEthereumSandwich
import com.elabify.app.maknoon.ui.wallet.ethereum.signEthereumHardwareMessage
import com.elabify.app.maknoon.ui.wallet.ethereum.signEthereumHardwareTypedData
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumGasEstimator
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetworkID
import com.elabify.musnad.wallet.ethereum.EthereumCallDataDecoder
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import com.reown.android.Core
import com.reown.android.CoreClient
import com.reown.android.relay.ConnectionType
import com.reown.walletkit.client.Wallet
import com.reown.walletkit.client.WalletKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object WalletConnectManager {

    private const val TAG = "walletconnect"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Chains we advertise. generateApprovedNamespaces intersects with the dApp's
    // request, so listing extras is harmless.
    private val supportedChainIds = listOf(1L, 11155111L, 137L, 8453L, 42161L, 10L, 56L, 43114L)
    private val supportedMethods = listOf(
        "personal_sign", "eth_sign",
        "eth_signTypedData", "eth_signTypedData_v3", "eth_signTypedData_v4",
        "eth_sendTransaction", "eth_signTransaction",
        "wallet_switchEthereumChain", "wallet_addEthereumChain",
    )
    private val supportedEvents = listOf("chainChanged", "accountsChanged")

    // ---- published state the UI observes ----
    private val _sessions = MutableStateFlow<List<Wallet.Model.Session>>(emptyList())
    val sessions: StateFlow<List<Wallet.Model.Session>> = _sessions.asStateFlow()

    private val _pendingProposal = MutableStateFlow<Wallet.Model.SessionProposal?>(null)
    val pendingProposal: StateFlow<Wallet.Model.SessionProposal?> = _pendingProposal.asStateFlow()

    private val _pendingRequest = MutableStateFlow<PendingRequest?>(null)
    val pendingRequest: StateFlow<PendingRequest?> = _pendingRequest.asStateFlow()

    private val _relayConnected = MutableStateFlow(false)
    val relayConnected: StateFlow<Boolean> = _relayConnected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun clearError() { _lastError.value = null }
    fun reportError(message: String) { _lastError.value = message }

    // Non-null while a sign/broadcast is running; the UI shows it as a blocking
    // progress dialog ("Confirm on your device…" for hardware, "Signing…" for
    // software), mirroring the iOS "Confirm on your device" state.
    private val _signingMessage = MutableStateFlow<String?>(null)
    val signingMessage: StateFlow<String?> = _signingMessage.asStateFlow()

    // In-app diagnostics feed shown in the Advanced section, mirroring the iOS
    // LogStore "walletconnect" feed (Android also logs to logcat tag TAG). Ring
    // buffer of the last ~80 timestamped lines.
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val logTimeFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private fun logLine(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + "${logTimeFmt.format(java.util.Date())}  $msg").takeLast(80)
    }

    /** A request surfaced to the UI for approval. */
    data class PendingRequest(
        val topic: String,
        val requestId: Long,
        val method: String,
        val chainId: String,
        val address: String,
        val walletLabel: String?,
        val isHardware: Boolean,
        val requiresHostPassphrase: Boolean,
        val title: String,
        val preview: String,
        val raw: Wallet.Model.SessionRequest,
    )

    @Volatile private var initialized = false

    /** Persisted topic -> wallet-id binding so signing routes to the exact
     *  wallet even when two wallets (a Ledger and a hidden Trezor on one seed)
     *  share an address. Mirrors the iOS sessionWalletId map. */
    private fun bindingPrefs(ctx: Context) =
        ctx.getSharedPreferences("walletconnect.bindings.v1", Context.MODE_PRIVATE)
    private fun bindSession(ctx: Context, topic: String, walletId: String?) {
        if (walletId == null) return
        bindingPrefs(ctx).edit().putString(topic, walletId).apply()
    }
    private fun unbindSession(ctx: Context, topic: String) {
        bindingPrefs(ctx).edit().remove(topic).apply()
    }
    private fun boundWalletId(ctx: Context, topic: String): String? {
        // A request arrives on the SESSION topic. The binding may be stored under
        // the session topic OR (for sessions bound at approve, before the session
        // topic was known) under the session's PAIRING topic. Try direct, then
        // bridge session.topic -> session.pairingTopic. Without this bridge the
        // lookup misses and signing falls back to address match, which picks the
        // wrong wallet when a Ledger and a hidden Trezor share an address.
        bindingPrefs(ctx).getString(topic, null)?.let { return it }
        val pairing = runCatching { WalletKit.getListOfActiveSessions() }.getOrDefault(emptyList())
            .firstOrNull { it.topic == topic }?.pairingTopic
        return pairing?.let { bindingPrefs(ctx).getString(it, null) }
    }

    // ---- init (called from MaknoonApplication.onCreate) ----

    fun init(app: Application) {
        if (initialized) return
        val projectId = BuildConfig.WC_PROJECT_ID
        if (projectId.isBlank()) {
            Log.w(TAG, "no WC project id; skipping WalletConnect init")
            return
        }
        // Canonical WalletConnect/Reown relay host. NOTE: relay.walletconnect.org
        // failed DNS on a GrapheneOS Pixel ("No address associated with hostname");
        // relay.walletconnect.com is the long-stable documented host and resolves.
        // An optional self-hosted relay (Ethereum settings > Advanced) overrides
        // the host across all networks; it is read once here, so a change takes
        // effect on the next app launch.
        val relayHost = EthereumStores.settings(app).walletConnectRelayHost.trim()
        val relayUrl = if (relayHost.isEmpty()) {
            "wss://relay.walletconnect.com?projectId=$projectId"
        } else {
            "wss://$relayHost?projectId=$projectId"
        }
        logLine("init: relay=$relayUrl${if (relayHost.isEmpty()) "" else " (self-hosted)"}")
        val metadata = Core.Model.AppMetaData(
            name = "Maknoon",
            description = "Maknoon post-quantum identity and wallet",
            url = "https://elabify.com",
            icons = emptyList(),
            redirect = "maknoon://wc",
        )
        CoreClient.initialize(
            application = app,
            relayServerUrl = relayUrl,
            connectionType = ConnectionType.AUTOMATIC,
            metaData = metadata,
            onError = { err -> logLine("relay/core error: ${err.throwable.message}") },
        )
        WalletKit.initialize(
            Wallet.Params.Init(core = CoreClient),
            onSuccess = {
                initialized = true
                refreshSessions()
                logLine("WalletKit initialized")
            },
            onError = { err -> logLine("walletkit init error: ${err.throwable.message}") },
        )
        WalletKit.setWalletDelegate(delegate)
    }

    private val delegate = object : WalletKit.WalletDelegate {
        override fun onSessionProposal(
            sessionProposal: Wallet.Model.SessionProposal,
            verifyContext: Wallet.Model.VerifyContext,
        ) {
            _pendingProposal.value = sessionProposal
            logLine("proposal from ${sessionProposal.name.ifBlank { "an app" }}")
        }

        override fun onSessionRequest(
            sessionRequest: Wallet.Model.SessionRequest,
            verifyContext: Wallet.Model.VerifyContext,
        ) {
            logLine("request received: ${sessionRequest.request.method}")
            ingest(sessionRequest)
        }

        override fun onSessionDelete(sessionDelete: Wallet.Model.SessionDelete) {
            if (sessionDelete is Wallet.Model.SessionDelete.Success) {
                unbindSession(appCtx(), sessionDelete.topic)
            }
            logLine("session deleted")
            refreshSessions()
        }

        override fun onConnectionStateChange(state: Wallet.Model.ConnectionState) {
            _relayConnected.value = state.isAvailable
            logLine("relay ${if (state.isAvailable) "connected" else "disconnected"}")
        }

        override fun onError(error: Wallet.Model.Error) {
            logLine("error: ${error.throwable.message}")
        }

        override fun onSessionExtend(session: Wallet.Model.Session) {}
        override fun onSessionSettleResponse(response: Wallet.Model.SettledSessionResponse) { refreshSessions() }
        override fun onSessionUpdateResponse(response: Wallet.Model.SessionUpdateResponse) {}
        override fun onProposalExpired(proposal: Wallet.Model.ExpiredProposal) {}
        override fun onRequestExpired(request: Wallet.Model.ExpiredRequest) {}
    }

    private fun appCtx(): Context = MaknoonApplication.appContext

    fun refreshSessions() {
        _sessions.value = runCatching { WalletKit.getListOfActiveSessions() }.getOrDefault(emptyList())
    }

    // ---- pairing + proposal ----

    fun pair(uri: String) {
        val trimmed = uri.trim()
        logLine("pair: scanned len=${trimmed.length} relayConnected=${_relayConnected.value}")
        WalletKit.pair(
            Wallet.Params.Pair(trimmed),
            onSuccess = { logLine("paired, awaiting proposal") },
            onError = { err ->
                logLine("pair failed: ${err.throwable.message}")
                _lastError.value = "Could not connect: ${err.throwable.message}"
            },
        )
    }

    fun approveProposal() {
        val proposal = _pendingProposal.value ?: return
        _pendingProposal.value = null
        val ctx = appCtx()
        val active = EthereumStores.walletStore(ctx).activeWallet
        val address = active?.address
        if (address.isNullOrEmpty()) {
            _lastError.value = "Add an Ethereum wallet before connecting."
            WalletKit.rejectSession(
                Wallet.Params.SessionReject(proposal.proposerPublicKey, "no wallet"),
                onSuccess = {}, onError = {},
            )
            return
        }
        val accounts = supportedChainIds.map { "eip155:$it:$address" }
        val supported = mapOf(
            "eip155" to Wallet.Model.Namespace.Session(
                chains = supportedChainIds.map { "eip155:$it" },
                accounts = accounts,
                methods = supportedMethods,
                events = supportedEvents,
            ),
        )
        try {
            val approved = WalletKit.generateApprovedNamespaces(proposal, supported)
            WalletKit.approveSession(
                Wallet.Params.SessionApprove(
                    proposerPublicKey = proposal.proposerPublicKey,
                    namespaces = approved,
                ),
                onSuccess = {
                    refreshSessions()
                    // Bind BOTH the pairing topic and the resolved session topic to
                    // the active wallet id, so signing routes to this exact wallet
                    // (not just any wallet sharing its address, e.g. a Ledger vs a
                    // hidden Trezor on one seed). Requests arrive on the session topic.
                    bindSession(ctx, proposal.pairingTopic, active.id.toString())
                    val sessionTopic = _sessions.value.firstOrNull { it.pairingTopic == proposal.pairingTopic }?.topic
                    if (sessionTopic != null) bindSession(ctx, sessionTopic, active.id.toString())
                    logLine("approved: bound wallet '${active.label}' id=${active.id} session=${sessionTopic?.take(8) ?: "?"} pairing=${proposal.pairingTopic.take(8)}")
                },
                onError = { err -> _lastError.value = "Could not approve: ${err.throwable.message}" },
            )
        } catch (e: Throwable) {
            _lastError.value = "Could not approve: ${e.message}"
            WalletKit.rejectSession(
                Wallet.Params.SessionReject(proposal.proposerPublicKey, "namespace build failed"),
                onSuccess = {}, onError = {},
            )
        }
    }

    fun rejectProposal() {
        val proposal = _pendingProposal.value ?: return
        _pendingProposal.value = null
        WalletKit.rejectSession(
            Wallet.Params.SessionReject(proposal.proposerPublicKey, "User rejected"),
            onSuccess = {}, onError = {},
        )
    }

    // ---- requests ----

    private fun ingest(req: Wallet.Model.SessionRequest) {
        val method = req.request.method
        val ctx = appCtx()
        when (method) {
            "wallet_switchEthereumChain", "wallet_addEthereumChain" -> {
                val chainId = requestedChainId(req.request.params)
                if (chainId != null && networkForChain(ctx, chainId) != null) {
                    respondResult(req, "null")
                    emitChainChanged(req.topic, chainId)
                } else {
                    respondError(req, 4902, "No network configured for chain $chainId.")
                }
                return
            }
        }
        val address = addressFor(method, req.request.params)
        if (address == null) {
            respondError(req, -32602, "Bad request params")
            return
        }
        val boundId = boundWalletId(ctx, req.topic)
        val descriptor = resolveWallet(ctx, address, boundId)
        val isHardware = descriptor?.kind is EthereumWalletKind.Hardware
        logLine("$method: addr=${address.take(10)}.. -> wallet='${descriptor?.label ?: "?"}' ${if (isHardware) "hardware" else "software"} boundId=${boundId?.take(8) ?: "none"}")
        val chainId = req.chainId ?: ""
        val (title, preview) = previewFor(method, req.request.params, chainId)
        _pendingRequest.value = PendingRequest(
            topic = req.topic,
            requestId = req.request.id,
            method = method,
            chainId = chainId,
            address = address,
            walletLabel = descriptor?.label,
            isHardware = isHardware,
            requiresHostPassphrase = descriptor?.hidden != null && isHardware,
            title = title,
            preview = preview,
            raw = req,
        )
    }

    fun approveRequest(hostPassphrase: String? = null) {
        val pending = _pendingRequest.value ?: return
        _pendingRequest.value = null
        _signingMessage.value = if (pending.isHardware) "Confirm on your device…" else "Signing…"
        scope.launch {
            try {
                val result = sign(pending, hostPassphrase)
                // Reown inserts the result string as a raw JSON fragment (see the
                // "null" switch-chain result), so a signature must be a quoted
                // JSON string.
                respondResult(pending.raw, JSONObject.quote(result))
                logLine("responded ${pending.method} ok")
            } catch (e: Throwable) {
                logLine("${pending.method} failed: ${e.message}")
                _lastError.value = e.message ?: "Signing failed"
                respondError(pending.raw, 4001, e.message ?: "Signing failed")
            } finally {
                _signingMessage.value = null
            }
        }
    }

    fun rejectRequest() {
        val pending = _pendingRequest.value ?: return
        _pendingRequest.value = null
        respondError(pending.raw, 4001, "User rejected")
    }

    // ---- signing dispatch (software + hardware Ledger/Trezor) ----

    private suspend fun sign(pending: PendingRequest, hostPassphrase: String?): String {
        val ctx = appCtx()
        val descriptor = resolveWallet(ctx, pending.address, boundWalletId(ctx, pending.topic))
            ?: throw IllegalStateException("That address is not one of your wallets.")
        val params = JSONArray(pending.raw.request.params)
        return when (val kind = descriptor.kind) {
            is EthereumWalletKind.Software -> signSoftware(ctx, pending, descriptor, kind.account, params)
            is EthereumWalletKind.Hardware -> {
                val device = DeviceRegistry(ctx).find(kind.deviceId)
                    ?: throw IllegalStateException("The hardware device for this wallet is not registered. Re-add it under Settings, then try again.")
                logLine("sign hardware: wallet='${descriptor.label}' id=${descriptor.id} -> device='${device.label}' kind=${device.kind} serial=${device.serial} hidden=${descriptor.hidden != null}")
                signHardware(ctx, pending, descriptor, kind.account, device, params, hostPassphrase)
            }
        }
    }

    private fun signSoftware(
        ctx: Context,
        pending: PendingRequest,
        descriptor: EthereumWalletDescriptor,
        account: Long,
        params: JSONArray,
    ): String {
        val sandwich = loadEthereumSandwich(ctx)
            ?: throw IllegalStateException("Your wallet is locked. Unlock it and try again.")
        val words = sandwich.recoveryWords()
        return when (pending.method) {
            "personal_sign", "eth_sign" -> {
                val msgParam = if (pending.method == "personal_sign") params.optString(0) else params.optString(1)
                val bytes = dataFromHex(msgParam) ?: msgParam.toByteArray(Charsets.UTF_8)
                EthereumDescriptors.signPersonalMessage(
                    words = words, passphrase = sandwich.bip39Passphrase(), account = account, message = bytes,
                    derivationPath = descriptor.derivationPath,
                )
            }
            "eth_signTypedData", "eth_signTypedData_v3", "eth_signTypedData_v4" ->
                EthereumDescriptors.signTypedData(
                    words = words, passphrase = sandwich.bip39Passphrase(), account = account, typedDataJson = typedDataJson(params),
                    derivationPath = descriptor.derivationPath,
                )
            "eth_sendTransaction", "eth_signTransaction" ->
                buildAndSendTx(
                    ctx, pending, descriptor, params,
                    broadcast = pending.method == "eth_sendTransaction",
                    hardwareSigner = null, sandwich = sandwich, account = account,
                )
            else -> throw IllegalStateException("${pending.method} is not supported")
        }
    }

    private suspend fun signHardware(
        ctx: Context,
        pending: PendingRequest,
        descriptor: EthereumWalletDescriptor,
        account: Long,
        device: com.elabify.musnad.devices.RegisteredDevice,
        params: JSONArray,
        hostPassphrase: String?,
    ): String = when (pending.method) {
        "personal_sign", "eth_sign" -> {
            val msgParam = if (pending.method == "personal_sign") params.optString(0) else params.optString(1)
            val bytes = dataFromHex(msgParam) ?: msgParam.toByteArray(Charsets.UTF_8)
            signEthereumHardwareMessage(
                device = device, account = account, message = bytes,
                hidden = descriptor.hidden, derivationPath = descriptor.derivationPath,
                hostPassphrase = hostPassphrase,
            )
        }
        "eth_signTypedData", "eth_signTypedData_v3", "eth_signTypedData_v4" ->
            signEthereumHardwareTypedData(
                device = device, account = account, typedDataJson = typedDataJson(params),
                hidden = descriptor.hidden, derivationPath = descriptor.derivationPath,
                hostPassphrase = hostPassphrase,
            )
        "eth_sendTransaction", "eth_signTransaction" ->
            buildAndSendTx(
                ctx, pending, descriptor, params,
                broadcast = pending.method == "eth_sendTransaction",
                hardwareSigner = EthereumDeviceSigner(device = device, account = account, hostPassphrase = hostPassphrase),
                sandwich = null, account = account,
            )
        else -> throw IllegalStateException("${pending.method} is not supported")
    }

    /** Shared tx builder for software + hardware. Resolves chain (from the
     *  REQUEST's chainId, not the wallet's current network), nonce (wallet-managed,
     *  ignoring any dApp nonce), gas + fees, then signs via the software seed or
     *  the hardware device, broadcasting + recording a pending row for send. */
    private fun buildAndSendTx(
        ctx: Context,
        pending: PendingRequest,
        descriptor: EthereumWalletDescriptor,
        params: JSONArray,
        broadcast: Boolean,
        hardwareSigner: EthereumDeviceSigner?,
        sandwich: com.elabify.musnad.identity.IdentitySandwich?,
        account: Long,
    ): String {
        val tx = params.optJSONObject(0) ?: throw IllegalStateException("Bad transaction params")
        val to = tx.optStringOrNull("to") ?: throw IllegalStateException("Transaction is missing `to`")
        val chainId = chainIdFromCaip(pending.chainId) ?: descriptorChainId(ctx)
        val network = networkForChain(ctx, chainId)
            ?: throw IllegalStateException("No network configured for chain $chainId.")
        val rpcURL = network.rpcURL
        val wallet = EthereumWallet(descriptor)
        val value = runCatching { EthereumWeiValue.fromHex(tx.optStringOrNull("value") ?: "0x0") }
            .getOrDefault(EthereumWeiValue.ZERO)
        val data = dataFromHex(tx.optStringOrNull("data")) ?: ByteArray(0)
        // Refuse a transfer/transferFrom whose token recipient is the call target
        // (the token contract) - it would send the tokens to the contract itself.
        if (EthereumCallDataDecoder.transferTargetsCallee(to, data)) {
            throw IllegalStateException("This transaction would send tokens to the token contract itself. Refused to prevent loss.")
        }
        // WALLET-MANAGED nonce: ignore any dApp-supplied nonce (routinely stale).
        val nonce = wallet.pendingNonce(rpcURL)
        val gasLimit = parseHexLong(tx.optStringOrNull("gas") ?: tx.optStringOrNull("gasLimit"))
            ?: wallet.estimateGasUnits(to, value, if (data.isEmpty()) null else data, rpcURL)
        val fees = EthereumGasEstimator.estimate(rpcURL)
        val std = fees.firstOrNull { it.tier == EthereumGasEstimator.Tier.STANDARD } ?: fees[0]
        val payload: EthereumTxPlan.Payload =
            if (data.isEmpty()) EthereumTxPlan.Payload.Native else EthereumTxPlan.Payload.ContractCall(data)
        val result: String = if (hardwareSigner != null) {
            if (broadcast) {
                wallet.sendHardware(hardwareSigner, to, value, gasLimit, std.maxFeePerGas, std.maxPriorityFeePerGas, chainId, nonce, payload, rpcURL)
            } else {
                wallet.prepareHardware(hardwareSigner, to, value, gasLimit, std.maxFeePerGas, std.maxPriorityFeePerGas, chainId, nonce, payload)
            }
        } else {
            val sw = sandwich ?: throw IllegalStateException("Your wallet is locked. Unlock it and try again.")
            if (broadcast) {
                wallet.sendSoftware(sw, account, to, value, gasLimit, std.maxFeePerGas, std.maxPriorityFeePerGas, chainId, nonce, payload, rpcURL, derivationPath = descriptor.derivationPath)
            } else {
                wallet.prepareSoftware(sw, account, to, value, gasLimit, std.maxFeePerGas, std.maxPriorityFeePerGas, chainId, nonce, payload, derivationPath = descriptor.derivationPath)
            }
        }
        if (broadcast) {
            EthereumStores.walletStore(ctx).markPendingOutbound(
                senderWalletId = descriptor.id, txHash = result,
                senderAddress = descriptor.address ?: "", recipientAddress = to,
                weiValue = value.decimal.toBigInteger().toString(),
            )
        }
        return result
    }

    // ---- sessions ----

    fun disconnect(topic: String) {
        if (!_relayConnected.value) {
            logLine("disconnect: relay not connected; the relay must be up (foreground + network) to disconnect")
        }
        logLine("disconnect requested ${topic.take(8)}")
        WalletKit.disconnectSession(
            Wallet.Params.SessionDisconnect(topic),
            onSuccess = { logLine("disconnect ok"); unbindSession(appCtx(), topic); refreshSessions() },
            onError = { err ->
                logLine("disconnect failed: ${err.throwable.message}")
                _lastError.value = "Could not disconnect: ${err.throwable.message}"
            },
        )
    }

    fun resetAll() {
        logLine("reset: disconnecting all + clearing bindings")
        sessions.value.forEach { disconnect(it.topic) }
        bindingPrefs(appCtx()).edit().clear().apply()
        refreshSessions()
    }

    // ---- respond helpers ----

    private fun respondResult(req: Wallet.Model.SessionRequest, resultJson: String) {
        WalletKit.respondSessionRequest(
            Wallet.Params.SessionRequestResponse(
                sessionTopic = req.topic,
                jsonRpcResponse = Wallet.Model.JsonRpcResponse.JsonRpcResult(req.request.id, resultJson),
            ),
            onSuccess = {}, onError = { err -> Log.e(TAG, "respond error: ${err.throwable.message}") },
        )
    }

    private fun respondError(req: Wallet.Model.SessionRequest, code: Int, message: String) {
        WalletKit.respondSessionRequest(
            Wallet.Params.SessionRequestResponse(
                sessionTopic = req.topic,
                jsonRpcResponse = Wallet.Model.JsonRpcResponse.JsonRpcError(req.request.id, code, message),
            ),
            onSuccess = {}, onError = { err -> Log.e(TAG, "respond error: ${err.throwable.message}") },
        )
    }

    private fun emitChainChanged(topic: String, chainId: Long) {
        runCatching {
            WalletKit.emitSessionEvent(
                Wallet.Params.SessionEmit(
                    topic = topic,
                    event = Wallet.Model.SessionEvent("chainChanged", "0x" + chainId.toString(16)),
                    chainId = "eip155:$chainId",
                ),
                onSuccess = {}, onError = {},
            )
        }
    }

    // ---- resolution + parsing ----

    private fun resolveWallet(ctx: Context, address: String, walletId: String?): EthereumWalletDescriptor? {
        val wallets = EthereumStores.walletStore(ctx).wallets
        if (walletId != null) {
            val byId = wallets.firstOrNull { it.id.toString() == walletId }
            if (byId != null && byId.address?.lowercase() == address.lowercase()) return byId
        }
        return wallets.firstOrNull { (it.address ?: "").lowercase() == address.lowercase() }
    }

    private fun networkForChain(ctx: Context, chainId: Long): com.elabify.musnad.wallet.ethereum.ResolvedNetwork? {
        val store = EthereumStores.walletStore(ctx)
        val settings = EthereumStores.settings(ctx)
        val customs = EthereumStores.customs(ctx)
        val builtin = EthereumNetwork.values().firstOrNull { it.chainId == chainId }
        if (builtin != null) return store.resolve(EthereumNetworkID.Builtin(builtin), customs, settings)
        val custom = customs.networks.firstOrNull { it.chainId == chainId } ?: return null
        return store.resolve(EthereumNetworkID.Custom(custom.id), customs, settings)
    }

    private fun descriptorChainId(ctx: Context): Long {
        val store = EthereumStores.walletStore(ctx)
        val settings = EthereumStores.settings(ctx)
        val customs = EthereumStores.customs(ctx)
        return store.resolve(store.currentNetworkID, customs, settings).chainId
    }

    private fun chainIdFromCaip(caip: String?): Long? {
        val s = caip ?: return null
        return s.substringAfterLast(":").toLongOrNull()
    }

    private fun addressFor(method: String, paramsJson: String): String? {
        val params = runCatching { JSONArray(paramsJson) }.getOrNull() ?: return null
        return when (method) {
            "personal_sign" -> params.optStringOrNull(1)
            "eth_sign" -> params.optStringOrNull(0)
            "eth_signTypedData", "eth_signTypedData_v3", "eth_signTypedData_v4" ->
                addressFromTypedDataParams(params)
            "eth_sendTransaction", "eth_signTransaction" -> params.optJSONObject(0)?.optStringOrNull("from")
            else -> null
        }
    }

    private fun addressFromTypedDataParams(params: JSONArray): String? {
        val a = params.optStringOrNull(0)
        val b = params.optStringOrNull(1)
        return when {
            a != null && isAddress(a) -> a
            b != null && isAddress(b) -> b
            else -> a
        }
    }

    private fun typedDataJson(params: JSONArray): String {
        val a = params.optStringOrNull(0)
        val b = params.optStringOrNull(1)
        // [address, json] is the v4 norm; pick the element that is NOT the address.
        val jsonStr = when {
            a != null && isAddress(a) -> b
            b != null && isAddress(b) -> a
            else -> b ?: a
        }
        if (jsonStr != null) return jsonStr
        // Object form: re-serialize whichever element is a JSON object.
        params.optJSONObject(1)?.let { return it.toString() }
        params.optJSONObject(0)?.let { return it.toString() }
        throw IllegalStateException("Bad typed-data params")
    }

    private fun requestedChainId(paramsJson: String): Long? {
        val params = runCatching { JSONArray(paramsJson) }.getOrNull() ?: return null
        val obj = params.optJSONObject(0) ?: return null
        val hex = obj.optStringOrNull("chainId") ?: return null
        return parseHexLong(hex)
    }

    private fun previewFor(method: String, paramsJson: String, chainId: String): Pair<String, String> {
        val params = runCatching { JSONArray(paramsJson) }.getOrNull() ?: JSONArray()
        return when (method) {
            "personal_sign", "eth_sign" -> {
                val raw = if (method == "personal_sign") params.optString(0) else params.optString(1)
                val decoded = dataFromHex(raw)?.let { String(it, Charsets.UTF_8) } ?: raw
                "Sign message" to decoded
            }
            "eth_signTypedData", "eth_signTypedData_v3", "eth_signTypedData_v4" ->
                "Sign typed data" to typedDataPreview(typedDataJson(params))
            "eth_sendTransaction" -> "Approve transaction" to txPreview(params, chainId)
            "eth_signTransaction" -> "Sign transaction" to txPreview(params, chainId)
            else -> method to ""
        }
    }

    private fun txPreview(params: JSONArray, chainId: String): String {
        val tx = params.optJSONObject(0) ?: return chainId
        val to = tx.optStringOrNull("to") ?: "(contract creation)"
        val value = tx.optStringOrNull("value")
        val dataLen = (dataFromHex(tx.optStringOrNull("data"))?.size) ?: 0
        return buildString {
            append("Network: $chainId\n")
            append("To: $to\n")
            append("Value: ${ethDisplay(value)}\n")
            append(if (dataLen > 0) "Data: $dataLen bytes (contract call)" else "Data: none")
        }
    }

    private fun typedDataPreview(json: String): String = runCatching {
        val obj = JSONObject(json)
        buildString {
            obj.optJSONObject("domain")?.let { d ->
                d.optStringOrNull("name")?.let { append("Domain: $it\n") }
                if (d.has("chainId")) append("Chain: ${d.get("chainId")}\n")
            }
            obj.optStringOrNull("primaryType")?.let { append("Type: $it") }
        }.ifBlank { json }
    }.getOrDefault(json)

    private fun ethDisplay(hex: String?): String {
        val s0 = hex ?: return "0 ETH"
        val s = if (s0.startsWith("0x")) s0.substring(2) else s0
        if (s.isBlank() || s.all { it == '0' }) return "0 ETH"
        val wei = s.toBigIntegerOrNull(16) ?: return "see device"
        val eth = wei.toBigDecimal().movePointLeft(18)
        return eth.stripTrailingZeros().toPlainString() + " ETH"
    }

    private fun isAddress(s: String): Boolean {
        val h = if (s.startsWith("0x")) s.substring(2) else s
        return h.length == 40 && h.all { it.digitToIntOrNull(16) != null }
    }

    private fun parseHexLong(hex: String?): Long? {
        val h = hex ?: return null
        val s = if (h.startsWith("0x") || h.startsWith("0X")) h.substring(2) else h
        return runCatching { java.lang.Long.parseUnsignedLong(s, 16) }.getOrNull()
    }

    private fun dataFromHex(s: String?): ByteArray? {
        var h = s ?: return null
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2)
        if (h.isEmpty()) return ByteArray(0)
        if (h.length % 2 != 0) return null
        val out = ByteArray(h.length / 2)
        var i = 0
        while (i < h.length) {
            val hi = Character.digit(h[i], 16)
            val lo = Character.digit(h[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun JSONArray.optStringOrNull(index: Int): String? {
        if (index < 0 || index >= length() || isNull(index)) return null
        return opt(index) as? String
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}
