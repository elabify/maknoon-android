// EIP-1193 "eth" namespace handler (window.ethereum). Multi-chain across the
// app's known EVM networks, starting on Sepolia. Android port of the iOS
// Web3BridgeHandler.swift, adapted to the Android mini-app bridge (string-in /
// string-out JSON over MiniAppNamespaceHandler).
//
// Reads (chainId, balances, calls, gas) proxy straight to the active network's
// EthereumRPCClient with no approval. Privileged calls each route through the
// shared ApprovalGate for explicit consent before any key is touched:
//   * eth_requestAccounts -> connect approval, returns the active address
//   * personal_sign / eth_sign -> EIP-191 message signing
//   * eth_signTypedData_v4 (+ v3) -> EIP-712 typed-data signing (0.6.3 hasher)
//   * eth_sendTransaction -> native OR contract call (calldata decoded for the
//     approval sheet when recognized, shown verbatim otherwise; never blind-signed)
//   * wallet_switchEthereumChain / addEthereumChain -> switch across known chains
//
// Signs with the active wallet whether software (identity sandwich) or hardware
// (Ledger / Trezor over BLE), routing through the same native signers the send
// flow uses. Scope, by design: only chains already configured in the app (no
// arbitrary RPC registration). Anything else returns EIP-1193 4200.
//
// Device-auth (BiometricPrompt) is the sheet's responsibility, the same way
// the iOS coordinator runs Face ID inside its approve() before resuming. This
// handler only blocks on the gate and trusts that an approved result means the
// user authenticated. Nothing here ever hands key material back to JS.

package com.elabify.app.maknoon.miniapp

import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumDeviceSigner
import com.elabify.app.maknoon.ui.wallet.ethereum.signEthereumHardwareMessage
import com.elabify.app.maknoon.ui.wallet.ethereum.signEthereumHardwareTypedData
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.wallet.ethereum.EthereumCallDataDecoder
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumGasEstimator
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumRPCClient
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Provides the engine pieces the handler needs, mirroring the iOS HolderStore
 * surface (sandwich, ethereumSettings, ethereumWalletStore, devices). The app
 * wires a concrete instance through the MiniAppHandlerFactory; keeping it an
 * interface keeps this handler free of an Android Context dependency.
 */
interface MiniAppWeb3Environment {
    /** The user's active Ethereum wallet, or null when none exists. */
    val activeWallet: EthereumWalletDescriptor?

    /** All EVM wallets, for the connect-time picker. */
    val wallets: List<EthereumWalletDescriptor>

    /** Per-network RPC / explorer overrides plus defaults. */
    val settings: EthereumSettings

    /**
     * The unlocked identity seed source. Null when no identity exists yet or
     * the wallet is locked. Mirrors iOS `store.sandwich`.
     */
    fun sandwich(): IdentitySandwich?

    /** Resolve the paired hardware device by id, or null if not registered. */
    fun device(deviceId: UUID): RegisteredDevice?

    /** Make [id] the active EVM wallet (the connect-time picker's choice). */
    fun setActiveWallet(id: UUID)

    companion object {
        /**
         * Default environment over a live [EthereumWalletStore] + [EthereumSettings],
         * a device resolver, and a sandwich-loading lambda. The app builds this in
         * the factory where the stores + Context are already in scope.
         */
        fun of(
            walletStore: EthereumWalletStore,
            settings: EthereumSettings,
            deviceResolver: (UUID) -> RegisteredDevice?,
            sandwichLoader: () -> IdentitySandwich?,
        ): MiniAppWeb3Environment = object : MiniAppWeb3Environment {
            override val activeWallet: EthereumWalletDescriptor? get() = walletStore.activeWallet
            override val wallets: List<EthereumWalletDescriptor> get() = walletStore.wallets
            override val settings: EthereumSettings = settings
            override fun sandwich(): IdentitySandwich? = sandwichLoader()
            override fun device(deviceId: UUID): RegisteredDevice? = deviceResolver(deviceId)
            override fun setActiveWallet(id: UUID) = walletStore.setActive(id)
        }
    }
}

/**
 * The "eth" namespace handler. Construct one per WebView in the
 * MiniAppHandlerFactory, gated by the "evm" grant.
 */
class Web3BridgeHandler(
    private val env: MiniAppWeb3Environment,
    private val gate: ApprovalGate,
    private val appTitle: String,
    /** Wired by the host to record a pending tx on the SHARED wallet store after
     *  a broadcast (the env here uses a throwaway store the UI does not read).
     *  (senderWalletId, txHash, senderAddress, recipient, weiValue, network). */
    private val onBroadcast: ((java.util.UUID, String, String, String, String, EthereumNetwork) -> Unit)? = null,
) : MiniAppNamespaceHandler {

    override val namespace = "eth"

    // Base permission (reads + connect + chain switch); writes and signing
    // require a stronger token, enforced per-method below (ADR-0057).
    override val requiredPermission: String? = "wallet.ethereum.read"

    /**
     * Per-method permission: reads/connect/switch need wallet.ethereum.read,
     * sends need wallet.ethereum.write, signing needs wallet.ethereum.sign. So
     * an app that declared only read is genuinely denied writes and signing.
     */
    override fun requiredPermissionFor(method: String): String? = when (method) {
        "eth_sendTransaction" -> "wallet.ethereum.write"
        "personal_sign", "eth_sign", "eth_signTypedData", "eth_signTypedData_v3", "eth_signTypedData_v4" ->
            "wallet.ethereum.sign"
        else -> "wallet.ethereum.read"
    }

    // The active EVM network for this session. Starts on Sepolia and can move
    // across the app's known EVM networks via wallet_switchEthereumChain.
    @Volatile private var currentNetwork: EthereumNetwork = EthereumNetwork.SEPOLIA

    /** One connect approval per WebView session, like the iOS `connected` flag. */
    @Volatile private var connected = false

    /**
     * Optional hook the host wires to push an EIP-1193 `chainChanged` event to
     * the page after a successful chain switch. The host already has an emit
     * path (MiniAppBridge.emitEvent -> window.__maknoonEmit); wiring this closure
     * to it is a small follow-up.
     */
    var onChainChanged: ((String) -> Unit)? = null

    private val rpcURL: String get() = env.settings.rpcURL(currentNetwork)
    private val chainIdHex: String get() = "0x" + currentNetwork.chainId.toString(16)

    override suspend fun handle(method: String, argsJson: String): String {
        val params = parseParamsArray(argsJson)
        return when (method) {
            // --- chain identity (no approval) ---
            "eth_chainId" -> JSONObject.quote(chainIdHex)
            "net_version" -> JSONObject.quote(currentNetwork.chainId.toString())

            // --- accounts ---
            "eth_accounts" -> if (connected) {
                JSONArray().put(activeAddress()).toString()
            } else {
                JSONArray().toString()
            }
            "eth_requestAccounts" -> requestAccounts()

            // --- reads (no approval) ---
            "eth_blockNumber" -> JSONObject.quote("0x" + rpc().blockNumber().toString(16))
            "eth_getBalance" -> {
                val addr = (params.optStringOrNull(0)) ?: activeAddress()
                JSONObject.quote("0x" + io { rpc().getBalance(addr) }.hex)
            }
            "eth_gasPrice" -> JSONObject.quote("0x" + io { rpc().gasPrice() }.hex)
            "eth_getTransactionCount" -> {
                val addr = (params.optStringOrNull(0)) ?: activeAddress()
                JSONObject.quote("0x" + io { rpc().transactionCount(addr, "pending") }.toString(16))
            }
            "eth_call" -> {
                val tx = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("eth_call requires { to, data }")
                val to = tx.optStringOrNull("to")
                    ?: throw MiniAppBridgeError.invalidParams("eth_call requires { to, data }")
                val data = dataFromHex(tx.optStringOrNull("data")) ?: ByteArray(0)
                // Forward `from` (caller-dependent reads like the v4 Quoter need
                // it); default to the connected wallet.
                val from = tx.optStringOrNull("from") ?: env.activeWallet?.address
                JSONObject.quote(io { rpc().ethCall(to, data, from) })
            }
            "eth_estimateGas" -> {
                val tx = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("eth_estimateGas requires { to }")
                val to = tx.optStringOrNull("to")
                    ?: throw MiniAppBridgeError.invalidParams("eth_estimateGas requires { to }")
                val value = weiOrZero(tx.optStringOrNull("value"))
                val data = dataFromHex(tx.optStringOrNull("data"))
                val from = tx.optStringOrNull("from") ?: activeAddress()
                val units = io { rpc().estimateGas(from, to, value, data) }
                JSONObject.quote("0x" + units.toString(16))
            }

            // --- signing (approval + device auth in the sheet) ---
            "personal_sign" -> personalSign(params.optStringOrNull(0)) // [message, address]
            "eth_sign" -> personalSign(params.optStringOrNull(1))      // [address, message]
            "eth_sendTransaction" -> {
                val tx = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("eth_sendTransaction requires a tx object")
                sendTransaction(tx)
            }
            "eth_signTypedData_v4", "eth_signTypedData_v3", "eth_signTypedData" ->
                signTypedData(params)

            // --- chain switching ---
            // We only move between chains already known to the app; we do not
            // register arbitrary RPCs, so add behaves like switch for a known chain.
            "wallet_switchEthereumChain", "wallet_addEthereumChain" -> switchChain(params)

            else -> throw MiniAppBridgeError.unsupported("eth.$method")
        }
    }

    // ---- privileged flows ----

    private suspend fun requestAccounts(): String {
        if (!connected) {
            // Offer the app's EVM wallets so the user can pick which to connect
            // (software or hardware); the sheet returns the chosen id, we make it
            // active, then return that wallet's address.
            val choices = JSONArray()
            env.wallets.forEach { w ->
                val addr = w.address
                if (!addr.isNullOrEmpty()) {
                    choices.put(
                        JSONObject().put("id", w.id.toString()).put("label", w.label).put("address", addr),
                    )
                }
            }
            if (choices.length() == 0) {
                throw MiniAppBridgeError.unauthorized("no Ethereum wallet in this app")
            }
            val result = gate.request(
                kind = "web3",
                payloadJson = JSONObject()
                    .put("action", "connect")
                    .put("address", env.activeWallet?.address ?: "")
                    .put("wallets", choices)
                    .put("activeId", env.activeWallet?.id?.toString() ?: JSONObject.NULL)
                    .toString(),
                appTitle = appTitle,
            )
            JSONObject(result).optStringOrNull("walletId")?.let { picked ->
                runCatching { UUID.fromString(picked) }.getOrNull()?.let { env.setActiveWallet(it) }
            }
            connected = true
        }
        return JSONArray().put(activeAddress()).toString()
    }

    private suspend fun personalSign(messageParam: String?): String {
        val raw = messageParam
            ?: throw MiniAppBridgeError.invalidParams("message must be a string")
        val message = dataFromHex(raw) ?: raw.toByteArray(Charsets.UTF_8)
        val descriptor = activeDescriptor()
        val account = accountOf(descriptor)

        val preview = String(message.take(200).toByteArray(), Charsets.UTF_8)
        gate.request(
            kind = "web3",
            payloadJson = JSONObject()
                .put("action", "signMessage")
                .put("preview", preview.ifEmpty { raw })
                .toString(),
            appTitle = appTitle,
        )

        val signed = when (val kind = descriptor.kind) {
            is EthereumWalletKind.Software -> {
                val sandwich = env.sandwich()
                    ?: throw MiniAppBridgeError.unauthorized("wallet is locked")
                io {
                    try {
                        EthereumDescriptors.signPersonalMessage(
                            words = sandwich.recoveryWords(),
                            account = account,
                            message = message,
                            derivationPath = descriptor.derivationPath,
                        )
                    } catch (e: Throwable) {
                        throw MiniAppBridgeError.internalError(e.message ?: "personal_sign failed")
                    }
                }
            }
            is EthereumWalletKind.Hardware -> {
                val device = env.device(kind.deviceId)
                    ?: throw MiniAppBridgeError.unauthorized("the paired device for this wallet was not found")
                withContext(Dispatchers.IO) {
                    try {
                        signEthereumHardwareMessage(
                            device = device,
                            account = account,
                            message = message,
                            hidden = descriptor.hidden,
                            derivationPath = descriptor.derivationPath,
                        )
                    } catch (e: MiniAppBridgeError) {
                        throw e
                    } catch (e: Throwable) {
                        throw MiniAppBridgeError.internalError(e.message ?: "personal_sign failed")
                    }
                }
            }
        }
        return JSONObject.quote(signed)
    }

    private suspend fun sendTransaction(tx: JSONObject): String {
        val to = tx.optStringOrNull("to")?.takeIf { it.isNotEmpty() }
            ?: throw MiniAppBridgeError.invalidParams("eth_sendTransaction requires `to`")
        // Contract calldata is allowed but never blind-signed: it is decoded for
        // the approval sheet when recognized, and shown verbatim otherwise.
        val dataBytes = dataFromHex(tx.optStringOrNull("data")) ?: ByteArray(0)
        val value = weiOrZero(tx.optStringOrNull("value"))
        val descriptor = activeDescriptor()
        val account = accountOf(descriptor)

        val decoded = if (dataBytes.isEmpty()) null else EthereumCallDataDecoder.decode(to, dataBytes)
        val dataHex = if (dataBytes.isEmpty()) null else {
            "0x" + dataBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        // Approval before any chain prep / signing.
        gate.request(
            kind = "web3",
            payloadJson = JSONObject()
                .put("action", "sendTransaction")
                .put("to", to)
                .put("amountEth", value.display(ticker = "ETH", maxDecimals = 6))
                .put("network", currentNetwork.displayName)
                .apply {
                    if (decoded != null) put("summary", decoded.summary)
                    if (dataHex != null) put("dataHex", dataHex)
                }
                .toString(),
            appTitle = appTitle,
        )

        return io {
            val wallet = EthereumWallet(descriptor)
            val nonce = wallet.pendingNonce(rpcURL)
            val gasLimit = providedGasLimit(tx.optStringOrNull("gas"))
                ?: wallet.estimateGasUnits(
                    to = to,
                    value = value,
                    data = if (dataBytes.isEmpty()) null else dataBytes,
                    rpcURL = rpcURL,
                )
            val fees = EthereumGasEstimator.estimate(rpcURL)
            val std = fees.firstOrNull { it.tier == EthereumGasEstimator.Tier.STANDARD } ?: fees[0]
            val payload = if (dataBytes.isEmpty()) {
                EthereumTxPlan.Payload.Native
            } else {
                EthereumTxPlan.Payload.ContractCall(dataBytes)
            }

            try {
                val hash = when (val kind = descriptor.kind) {
                    is EthereumWalletKind.Software -> {
                        val sandwich = env.sandwich()
                            ?: throw MiniAppBridgeError.unauthorized("wallet is locked")
                        wallet.sendSoftware(
                            sandwich = sandwich,
                            account = account,
                            to = to,
                            value = value,
                            gasLimit = gasLimit,
                            maxFeePerGas = std.maxFeePerGas,
                            maxPriorityFeePerGas = std.maxPriorityFeePerGas,
                            chainId = currentNetwork.chainId,
                            nonce = nonce,
                            payload = payload,
                            rpcURL = rpcURL,
                            derivationPath = descriptor.derivationPath,
                        )
                    }
                    is EthereumWalletKind.Hardware -> {
                        val device = env.device(kind.deviceId)
                            ?: throw MiniAppBridgeError.unauthorized("the paired device for this wallet was not found")
                        // Arbitrary contract calls (approve, swap) blind-sign on the
                        // device: the raw calldata shows on-screen, not a decoded amount.
                        val rawTx = wallet.prepareHardware(
                            signer = EthereumDeviceSigner(device = device, account = account),
                            to = to,
                            value = value,
                            gasLimit = gasLimit,
                            maxFeePerGas = std.maxFeePerGas,
                            maxPriorityFeePerGas = std.maxPriorityFeePerGas,
                            chainId = currentNetwork.chainId,
                            nonce = nonce,
                            payload = payload,
                        )
                        wallet.broadcast(rawTx, rpcURL)
                    }
                }
                // Record an optimistic pending row + point the wallet at this
                // chain on the SHARED store, so opening the wallet (incl. via
                // walletView.open after a swap) shows the tx confirming.
                onBroadcast?.invoke(
                    descriptor.id, hash, descriptor.address ?: "", to,
                    value.bigInteger.toString(), currentNetwork,
                )
                JSONObject.quote(hash)
            } catch (e: MiniAppBridgeError) {
                throw e
            } catch (e: Throwable) {
                throw MiniAppBridgeError.internalError(e.message ?: "eth_sendTransaction failed")
            }
        }
    }

    private suspend fun switchChain(params: JSONArray): String {
        val req = params.optJSONObject(0)
            ?: throw MiniAppBridgeError.invalidParams("requires { chainId }")
        val want = req.optStringOrNull("chainId")?.lowercase()
            ?: throw MiniAppBridgeError.invalidParams("requires { chainId }")
        val hexDigits = if (want.startsWith("0x")) want.substring(2) else want
        val wantId = runCatching { java.lang.Long.parseUnsignedLong(hexDigits, 16) }.getOrNull()
            ?: throw MiniAppBridgeError.invalidParams("chainId must be 0x-hex")
        if (wantId == currentNetwork.chainId) return "null"
        val target = EthereumNetwork.fromChainId(wantId)
            ?: throw MiniAppBridgeError(4902, "Chain 0x${wantId.toString(16)} is not configured in this wallet.")

        // Switch silently, no confirmation sheet: this only ever moves between
        // chains already configured in the wallet (unknown chains 4902 above),
        // and any actual transaction still shows its chain in its own approval.
        currentNetwork = target
        onChainChanged?.invoke("0x" + target.chainId.toString(16))
        return "null"
    }

    private suspend fun signTypedData(params: JSONArray): String {
        // MetaMask order is [address, jsonString]; some callers pass [jsonString]
        // or an already-parsed object. Accept all three.
        val json = params.optStringOrNull(1)
            ?: params.optStringOrNull(0)
            ?: params.optJSONObject(1)?.toString()
            ?: params.optJSONObject(0)?.toString()
            ?: throw MiniAppBridgeError.invalidParams("eth_signTypedData_v4 requires typed-data JSON")
        val descriptor = activeDescriptor()
        val account = accountOf(descriptor)

        gate.request(
            kind = "web3",
            payloadJson = JSONObject()
                .put("action", "signTypedData")
                .put("domain", typedDataDomainName(json))
                .put("preview", json.take(400))
                .toString(),
            appTitle = appTitle,
        )

        val signed = when (val kind = descriptor.kind) {
            is EthereumWalletKind.Software -> {
                val sandwich = env.sandwich()
                    ?: throw MiniAppBridgeError.unauthorized("wallet is locked")
                io {
                    try {
                        EthereumDescriptors.signTypedData(
                            words = sandwich.recoveryWords(),
                            account = account,
                            typedDataJson = json,
                            derivationPath = descriptor.derivationPath,
                        )
                    } catch (e: Throwable) {
                        throw MiniAppBridgeError.internalError(e.message ?: "eth_signTypedData_v4 failed")
                    }
                }
            }
            is EthereumWalletKind.Hardware -> {
                val device = env.device(kind.deviceId)
                    ?: throw MiniAppBridgeError.unauthorized("the paired device for this wallet was not found")
                withContext(Dispatchers.IO) {
                    try {
                        signEthereumHardwareTypedData(
                            device = device,
                            account = account,
                            typedDataJson = json,
                            hidden = descriptor.hidden,
                            derivationPath = descriptor.derivationPath,
                        )
                    } catch (e: MiniAppBridgeError) {
                        throw e
                    } catch (e: Throwable) {
                        throw MiniAppBridgeError.internalError(e.message ?: "eth_signTypedData_v4 failed")
                    }
                }
            }
        }
        return JSONObject.quote(signed)
    }

    private fun typedDataDomainName(json: String): String {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return ""
        val domain = obj.optJSONObject("domain") ?: return ""
        return domain.optString("name", "")
    }

    // ---- helpers ----

    private fun rpc(): EthereumRPCClient =
        EthereumRPCClient.orNull(rpcURL)
            ?: throw MiniAppBridgeError.internalError("Sepolia RPC URL is invalid")

    private fun activeDescriptor(): EthereumWalletDescriptor =
        env.activeWallet
            ?: throw MiniAppBridgeError.unauthorized("no Ethereum wallet in this app")

    private fun activeAddress(): String {
        val addr = activeDescriptor().address
        if (addr.isNullOrEmpty()) {
            throw MiniAppBridgeError.unauthorized("active Ethereum wallet has no address")
        }
        return addr
    }

    /** The account index for either wallet kind (hardware signs on the device). */
    private fun accountOf(descriptor: EthereumWalletDescriptor): Long = when (val kind = descriptor.kind) {
        is EthereumWalletKind.Software -> kind.account
        is EthereumWalletKind.Hardware -> kind.account
    }

    private fun providedGasLimit(gasHex: String?): Long? {
        val h = gasHex ?: return null
        val s = if (h.startsWith("0x") || h.startsWith("0X")) h.substring(2) else h
        return runCatching { java.lang.Long.parseUnsignedLong(s, 16) }.getOrNull()
    }

    private fun weiOrZero(hex: String?): EthereumWeiValue =
        runCatching { EthereumWeiValue.fromHex(hex ?: "0x0") }.getOrDefault(EthereumWeiValue.ZERO)

    /** Run a blocking engine call off the dispatch thread. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: MiniAppBridgeError) {
            throw e
        } catch (e: Throwable) {
            throw MiniAppBridgeError.internalError(e.message ?: "RPC error")
        }
    }

    /**
     * The JS `params` for an EIP-1193 request is an array. The shim serializes
     * `args.params` straight through, so argsJson is that array (or "null").
     */
    private fun parseParamsArray(argsJson: String): JSONArray {
        val trimmed = argsJson.trim()
        if (trimmed.isEmpty() || trimmed == "null") return JSONArray()
        return runCatching { JSONArray(trimmed) }.getOrDefault(JSONArray())
    }

    private fun JSONArray.optStringOrNull(index: Int): String? {
        if (index < 0 || index >= length() || isNull(index)) return null
        val v = opt(index)
        return v as? String
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }

    /** Decode a 0x-hex string to bytes. Returns empty for "0x"/"" and null on bad hex. */
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
}
