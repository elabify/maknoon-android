// Stateful host that binds a CommercePayDriver to the stateless CommercePaySheet
// (the Android analog of the inline driver binding in iOS ScanVerifierSheet.swift
// / CommercePaySheet.swift). Used by the cross-device Verify & Pay path: the
// holder scans the merchant's short URL in Identity -> Scan verifier, that URL
// resolves to a CommerceRequest, and this host runs the pipeline:
//   authenticate -> match credential -> enumerate payer wallets -> balances,
//   then on Confirm: disclose identity (sealed) FIRST, then broadcast payment.
//
// All blocking driver steps run off the main thread (Dispatchers.IO).

package com.elabify.app.maknoon.ui.miniapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.elabify.app.maknoon.miniapp.CommerceHolderContext
import com.elabify.app.maknoon.miniapp.CommerceRequest
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignAppReadiness
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignReadySheet
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import com.elabify.musnad.wallet.tron.TronWalletKind
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.elabify.app.maknoon.R

@Composable
fun CommercePayHost(
    ctx: CommerceHolderContext,
    request: CommerceRequest,
    responseBaseURL: String,
    onClose: () -> Unit,
    /** After a successful pay, take the payer to the wallet they paid from
     *  (chain key, e.g. "bitcoin") so they can watch the tx confirm. */
    onNavigateToWallet: (chain: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val loadRequestFailedMsg = stringResource(R.string.commerce_could_not_load_request)
    val paymentFailedMsg = stringResource(R.string.commerce_payment_failed)
    val signingFailedMsg = stringResource(R.string.commerce_signing_failed)
    val broadcastFailedMsg = stringResource(R.string.commerce_broadcast_failed)
    val driver = remember(request.requestId) {
        CommercePayDriver(ctx = ctx, request = request, responseBaseURL = responseBaseURL)
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var trust by remember { mutableStateOf<CommercePayTrust?>(null) }
    // Set when the selected wallet is hardware: drives the pre-sign "ready your
    // device / open the right app" sheet (+ Trezor passphrase field). Null = no
    // hardware step (software pays straight through).
    var readyOp by remember { mutableStateOf<HardwareReadyOp?>(null) }
    var matched by remember { mutableStateOf<JSONObject?>(null) }
    // Observable list so per-wallet balance updates recompose immediately.
    val candidates = remember { mutableStateListOf<CommercePayCandidate>() }
    var selectedId by remember { mutableStateOf<UUID?>(null) }
    var phaseLabel by remember { mutableStateOf<String?>("Checking the merchant...") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var paying by remember { mutableStateOf(false) }
    // Non-null after a hardware sign (awaiting the explicit Broadcast tap).
    var signedPrepared by remember { mutableStateOf<CommercePayDriver.Prepared?>(null) }
    // Set once settled: the on-chain ref + the chain to return to in the wallet.
    var doneTxid by remember { mutableStateOf<String?>(null) }
    var doneChain by remember { mutableStateOf<String?>(null) }

    // Pipeline: authenticate -> match -> candidates -> balances. Soft-fails into
    // errorText so the sheet always renders something actionable.
    LaunchedEffect(request.requestId) {
        try {
            val t = withContext(Dispatchers.IO) { driver.authenticate(context) }
            trust = t
            val m = withContext(Dispatchers.IO) { driver.matchCredential() }
            matched = m
            val built = withContext(Dispatchers.IO) { driver.buildCandidates() }
            candidates.clear()
            candidates.addAll(built.map { it.copy() }) // shows "..." until each balance lands
            selectedId = built.firstOrNull { it.payable }?.id ?: built.firstOrNull()?.id
            phaseLabel = null
            // Fetch balances per-wallet CONCURRENTLY and update each row as soon
            // as it lands (snapshot-list element set recomposes), instead of one
            // slow serial sweep that only paints after every RPC finishes.
            coroutineScope {
                built.mapIndexed { i, c ->
                    async(Dispatchers.IO) {
                        driver.fetchBalance(c)
                        withContext(Dispatchers.Main) {
                            if (i < candidates.size) candidates[i] = c.copy()
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            phaseLabel = null
            errorText = e.message ?: loadRequestFailedMsg
        }
    }

    val selected = candidates.firstOrNull { it.id == selectedId }
    val amountLine = selected?.let { c ->
        c.rail.amount?.let { "$it ${c.assetSymbol}" }
    }
    val networkLine = selected?.let { c -> "on ${c.networkLabel}" }
    val fiatLine = "${request.paymentTerms.fiatAmount} ${request.paymentTerms.fiatCode}"

    val confirmEnabled = !paying && doneTxid == null && signedPrepared == null &&
        matched != null &&
        selected != null && selected.payable && selected.sufficient

    // Software one-tap: prepare (sign) + broadcast in one go, then show success.
    fun softwarePay(cand: CommercePayCandidate, cred: JSONObject) {
        paying = true; errorText = null; phaseLabel = "Verifying and paying..."
        scope.launch {
            try {
                val txid = withContext(Dispatchers.IO) {
                    val p = driver.prepare(cand, cred, null)
                    driver.finalizeAndBroadcast(p).also { doneChain = p.chain }
                }
                doneTxid = txid; phaseLabel = null; paying = false
            } catch (e: Exception) {
                errorText = e.message ?: paymentFailedMsg; phaseLabel = null; paying = false
            }
        }
    }

    // Hardware: sign on-device now (after the ready sheet); stop at a Broadcast
    // button so the user explicitly sends.
    fun hardwareSign(cand: CommercePayCandidate, cred: JSONObject, hostPassphrase: String?) {
        paying = true; errorText = null; phaseLabel = "Signing on your device..."
        scope.launch {
            try {
                val p = withContext(Dispatchers.IO) { driver.prepare(cand, cred, hostPassphrase) }
                signedPrepared = p; phaseLabel = null; paying = false
            } catch (e: Exception) {
                errorText = e.message ?: signingFailedMsg; phaseLabel = null; paying = false
            }
        }
    }

    // Broadcast tap (hardware): post identity FIRST, then send on-chain.
    fun broadcastSigned() {
        val p = signedPrepared ?: return
        paying = true; errorText = null; phaseLabel = "Verifying and paying..."
        scope.launch {
            try {
                val txid = withContext(Dispatchers.IO) { driver.finalizeAndBroadcast(p) }
                doneChain = p.chain; doneTxid = txid; signedPrepared = null
                phaseLabel = null; paying = false
            } catch (e: Exception) {
                errorText = e.message ?: broadcastFailedMsg; phaseLabel = null; paying = false
            }
        }
    }

    val locked = paying || signedPrepared != null || doneTxid != null

    CommercePaySheet(
        merchantName = request.merchantName ?: "Point of Sale",
        trust = trust,
        requiredClaims = request.requiredClaims,
        claimValue = { key -> driver.claimText(matched, key) },
        hasMatch = matched != null,
        amountLine = amountLine,
        networkLine = networkLine,
        fiatLine = fiatLine,
        candidates = candidates,
        selectedId = selectedId,
        phaseLabel = phaseLabel,
        errorText = errorText,
        confirmEnabled = confirmEnabled,
        signedAwaitingBroadcast = signedPrepared != null && doneTxid == null,
        doneTxid = doneTxid,
        // Lock the wallet choice once signing/paying starts.
        onSelect = { if (!locked) selectedId = it },
        onConfirm = {
            val cand = selected ?: return@CommercePaySheet
            val cred = matched ?: return@CommercePaySheet
            // Hardware wallet: show the ready sheet first (Ledger app prompt +
            // Trezor passphrase). Software wallet: pay straight through.
            val hw = hardwareReadyOp(context, cand, cred)
            if (hw != null) readyOp = hw else softwarePay(cand, cred)
        },
        onBroadcast = { broadcastSigned() },
        onViewInWallet = {
            doneChain?.let { onNavigateToWallet(it) }
            onClose()
        },
        onClose = onClose,
        // Inset below the status bar / above the nav bar; without this the
        // scrollable form rendered flush under the status bar (too high).
        modifier = modifier.systemBarsPadding(),
    )

    // Pre-sign "ready your device" sheet for the selected hardware wallet.
    readyOp?.let { op ->
        HardwareSignReadySheet(
            deviceKind = op.device.kind,
            deviceLabel = op.device.label,
            deviceSerialDisplay = op.device.serialDisplay,
            readiness = op.readiness,
            requiresHostPassphrase = op.requiresHostPassphrase,
            onCancel = { readyOp = null },
            onContinue = { pass ->
                readyOp = null
                hardwareSign(op.candidate, op.cred, pass)
            },
        )
    }
}

/** Hardware-signing context for the ready sheet, or null when the selected
 *  candidate is a software wallet (only EVM + Bitcoin have hardware commerce). */
private data class HardwareReadyOp(
    val device: RegisteredDevice,
    val readiness: HardwareSignAppReadiness,
    val requiresHostPassphrase: Boolean,
    val candidate: CommercePayCandidate,
    val cred: JSONObject,
)

/** Build a HardwareReadyOp when [candidate] pays from a registered hardware
 *  wallet (resolving the device + the per-chain Ledger app + whether a host
 *  Trezor passphrase is needed); null for software wallets / missing devices. */
private fun hardwareReadyOp(
    context: android.content.Context,
    candidate: CommercePayCandidate,
    cred: JSONObject,
): HardwareReadyOp? {
    // EVM stores its passphrase ref as a wire-id String; Bitcoin as a JSON
    // object, so each branch parses its own form. Solana/Tron/Lightning commerce
    // are software-only (no hardware ready step).
    val deviceId: UUID
    val readiness: HardwareSignAppReadiness
    val requiresHostPassphrase: Boolean
    when (val w = candidate.wallet) {
        is PayWallet.Eth -> {
            val k = w.descriptor.kind as? EthereumWalletKind.Hardware ?: return null
            deviceId = k.deviceId
            readiness = HardwareSignAppReadiness.ethereum
            requiresHostPassphrase =
                HardwarePassphraseRef.fromWireId(w.descriptor.hidden)?.needsHostPassphrase ?: false
        }
        is PayWallet.Btc -> {
            val k = w.descriptor.kind as? BitcoinWalletKind.Hardware ?: return null
            deviceId = k.deviceId
            readiness = HardwareSignAppReadiness.bitcoin(w.network == BitcoinNetwork.MAINNET)
            requiresHostPassphrase =
                HardwarePassphraseRef.fromJson(w.descriptor.hidden)?.needsHostPassphrase ?: false
        }
        is PayWallet.Sol -> {
            val k = w.descriptor.kind as? SolanaWalletKind.Hardware ?: return null
            deviceId = k.deviceId
            readiness = HardwareSignAppReadiness.solana
            requiresHostPassphrase =
                HardwarePassphraseRef.fromJson(w.descriptor.hidden)?.needsHostPassphrase ?: false
        }
        is PayWallet.Trx -> {
            val k = w.descriptor.kind as? TronWalletKind.Hardware ?: return null
            deviceId = k.deviceId
            readiness = HardwareSignAppReadiness.tron
            requiresHostPassphrase =
                HardwarePassphraseRef.fromJson(w.descriptor.hidden)?.needsHostPassphrase ?: false
        }
        else -> return null
    }
    val device = DeviceRegistry(context.applicationContext).find(deviceId) ?: return null
    return HardwareReadyOp(device, readiness, requiresHostPassphrase, candidate, cred)
}
