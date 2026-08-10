// Ethereum send. Ported from iOS EthereumSendView, restyled onto the shared
// send-form components (ui/wallet/common/*) so the section order + chrome match
// every other chain and the iOS Form exactly:
//
//   WalletSelector header (wallet name + indigo network chip)
//   Token picker (AssetPicker: native ETH/MATIC/... or any installed ERC-20)
//   Recipient (RecipientField): text + paste + QR scan, with ENS (.eth)
//     resolution against the mainnet gateway, resolved-0x preview, and
//     0x-format validation in the supporting slot
//   Amount (AmountField): + Max + Available caption
//   Network fee (FeeSelector): three-tier EIP-1559 gas picker
//     (Slow / Standard / Fast) with the base fee, priority tip, max fee per gas
//     (gwei) and gas-units breakdown in the footer
//   Review (ReviewRow): Network · Pay to · Amount · Max network fee ·
//     Worst-case total
//   Primary action (PrimaryActionButton): Send (software) /
//     Sign using device (hardware)
//   error / status (Banner)
//
// The state, ENS/gas/balance loading, validation, and the Max math match the
// software path exactly: the SDK hand-builds the EIP-1559 envelope and
// broadcasts via eth_sendRawTransaction. The signing step branches on the
// descriptor kind: a Software wallet signs from the seed-derived key
// (sendSoftware), a Hardware wallet builds the same envelope and routes the
// signature onto its Ledger / Trezor over BLE (sendHardware + EthereumDeviceSigner),
// reusing the proven discover-sweep / HardwareSecondFactor connection (make ->
// beginSession -> identifyDevice serial-guard -> sign -> endSession, with the
// stale-link retry). Either way the signed raw tx is what reaches the network.

package com.elabify.app.maknoon.ui.wallet.ethereum

import java.util.Locale

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.theme.Spacing
import androidx.compose.foundation.layout.width
import com.elabify.app.maknoon.ui.wallet.SelfSendGuard
import com.elabify.app.maknoon.ui.wallet.common.AmountField
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.AssetOption
import com.elabify.app.maknoon.ui.wallet.common.AssetPicker
import com.elabify.app.maknoon.ui.wallet.common.FeeOption
import com.elabify.app.maknoon.ui.wallet.common.FeeSelector
import com.elabify.app.maknoon.ui.wallet.common.FormSection
import com.elabify.app.maknoon.ui.wallet.common.PrimaryActionButton
import com.elabify.app.maknoon.ui.wallet.common.authorizeSend
import com.elabify.app.maknoon.ui.settings.AddressBookNetwork
import com.elabify.app.maknoon.ui.settings.ContactsPickerSheet
import com.elabify.app.maknoon.ui.wallet.common.RecipientField
import com.elabify.app.maknoon.ui.wallet.common.ReviewRow
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignAppReadiness
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignReadySheet
import com.elabify.app.maknoon.ui.wallet.common.SendFormScaffold
import com.elabify.app.maknoon.ui.wallet.common.WalletSelector
import com.elabify.musnad.wallet.ethereum.EIP55
import com.elabify.musnad.wallet.ethereum.ENSResolver
import com.elabify.musnad.wallet.ethereum.EthereumGasEstimator
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetworkID
import com.elabify.musnad.wallet.ethereum.EthereumToken
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private sealed interface EthSendState {
    data object Idle : EthSendState
    data object Working : EthSendState
    /** Signed but NOT yet broadcast (ADR-0033): the user reviews + taps Broadcast.
     *  Carries the signed raw tx + the data for the optimistic pending row. */
    data class Signed(
        val rawTx: String,
        val recipient: String,
        val weiValue: String,
        val tokenContract: String?,
        val tokenSymbol: String?,
        val tokenDecimals: Int?,
    ) : EthSendState
    data class Done(val txHash: String) : EthSendState

    /** A failure. When [retry] is non-null the signing already succeeded and
     *  only the broadcast RPC failed, so the same signed tx can be re-pushed
     *  WITHOUT re-signing (no second device prompt). Null => re-sign required. */
    data class Failed(val message: String, val retry: Signed? = null) : EthSendState
}

private fun isValidEthAddress(s: String): Boolean {
    val t = s.trim()
    if (!t.startsWith("0x") && !t.startsWith("0X")) return false
    val body = t.substring(2)
    if (body.length != 40 || !body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
    // EIP-55: a mixed-case address must match its checksum, so a mistyped
    // (wrong-case) character is caught instead of sent to a different,
    // valid-looking address. All-lower / all-upper carry no checksum.
    return EIP55.passesChecksum(t)
}

// Native chain tint for Ethereum (iOS uses .indigo for the network chip).
private val EthIndigo = Color(0xFF5856D6)

// Shown (and enforced in submit) when a token transfer would go to a contract.
private const val TOKEN_TO_CONTRACT_ERROR =
    "This address is a token contract. Sending tokens here would lose them permanently. Enter the recipient's wallet address."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumSendScreen(walletId: UUID, preselectTokenId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walletStore = remember { EthereumStores.walletStore(context) }
    val settings = remember { EthereumStores.settings(context) }
    val tokenStore = remember { EthereumStores.tokenStore(context) }
    val sandwich = remember { loadEthereumSandwich(context) }
    // Bumped whenever this screen changes the wallet's chain (after a scanned
    // EIP-681 code names another one) or adds a token, so the network and token
    // list below are re-read from the stores instead of staying on the snapshot
    // taken at first composition.
    var storeEpoch by remember { mutableIntStateOf(0) }
    val resolved = remember(storeEpoch) { resolveCurrentNetwork(context) }

    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    // Wallet-scoped token list (ADR-0060): the curated chain-wide defaults merged
    // with just this wallet's added/discovered tokens, matching iOS EthereumSendView.
    val availableTokens = remember(storeEpoch) { tokenStore.tokens(resolved, walletId) }
    val isHardware = descriptor?.kind is EthereumWalletKind.Hardware
    // Pre-sign device-ready sheet (ADR-0033) + whether this hardware wallet is a
    // host-typed hidden (passphrase) wallet that must re-supply its passphrase
    // for each signing (the sheet collects it; never stored).
    var showReadySheet by remember { mutableStateOf(false) }
    val needsHostPassphrase = remember(descriptor?.id) {
        com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
            .fromWireId(descriptor?.hidden)?.needsHostPassphrase ?: false
    }

    var recipient by remember { mutableStateOf("") }
    // Set when a scanned / pasted EIP-681 URI could not be applied (no valid
    // recipient address in the code, or an unconfigured chain). Shown under the field.
    var scanError by remember { mutableStateOf<String?>(null) }
    // Set when a scanned code names a chain other than the one this wallet is
    // on. Nothing from the code is applied until the user taps through: the same
    // contract address is a different token on a different chain.
    var pendingChainSwitch by remember { mutableStateOf<PendingChainSwitch?>(null) }
    // Set when a scanned code requests an ERC-20 this wallet does not have on
    // this chain. Drives EthereumScannedTokenSheet.
    var pendingTokenRequest by remember { mutableStateOf<ScannedTokenRequest?>(null) }
    // Set when the user chose to send a DIFFERENT contract than the scanned code
    // asked for. Shown next to the asset picker until the form is submitted.
    var substitutedTokenNote by remember { mutableStateOf<String?>(null) }
    // A scanned code waiting to be re-applied once a chain switch has settled.
    // `resolved` is a remember(storeEpoch) snapshot, so it only reflects the new
    // chain on the NEXT composition: re-parsing inside the same event handler
    // would still see the old chain and just re-raise the switch prompt.
    var reapplyAfterSwitch by remember { mutableStateOf<String?>(null) }
    // True when the current recipient is a contract address AND a token is
    // selected (a token transfer to a contract loses the tokens). Resolved
    // best-effort via eth_getCode when the recipient / token changes.
    var recipientIsContract by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var selectedToken by remember { mutableStateOf(preselectTokenId?.let { id -> availableTokens.firstOrNull { it.id == id } }) }
    // Native-coin fiat entry (mainnet only, never for ERC-20 tokens). ethUnitPrice
    // is the native coin's price in the selected fiat; null hides the fiat unit.
    var ethUnitPrice by remember { mutableStateOf<Double?>(null) }
    var fiatEntry by remember { mutableStateOf(false) }
    var nativeWeiHex by remember { mutableStateOf<String?>(null) }
    var tokenWeiHex by remember { mutableStateOf<String?>(null) }

    var tier by remember { mutableStateOf(EthereumGasEstimator.Tier.STANDARD) }
    var estimates by remember { mutableStateOf<List<EthereumGasEstimator.Estimate>>(emptyList()) }
    var gasUnits by remember { mutableStateOf<Long?>(null) }
    var gasLoading by remember { mutableStateOf(false) }

    var resolvedENS by remember { mutableStateOf<String?>(null) }
    var ensError by remember { mutableStateOf<String?>(null) }
    var ensResolving by remember { mutableStateOf(false) }

    var showScanner by remember { mutableStateOf(false) }

    // The user's own Ethereum wallets, shown first in the recipient picker
    // ("Your wallets"). Resolved off-thread when the picker opens (ADR-0033).
    var ownWallets by remember { mutableStateOf<List<com.elabify.app.maknoon.ui.settings.OwnWalletEntry>>(emptyList()) }
    LaunchedEffect(showContacts) {
        if (!showContacts || ownWallets.isNotEmpty()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            walletStore.wallets.mapNotNull { w ->
                runCatching {
                    val addr = w.address
                        ?: (w.kind as? EthereumWalletKind.Software)?.let { sw -> sandwich?.let { com.elabify.musnad.wallet.ethereum.EthereumDescriptors.addressFromSandwich(it, sw.account) } }
                        ?: return@mapNotNull null
                    com.elabify.app.maknoon.ui.settings.OwnWalletEntry(name = w.label, address = addr)
                }.getOrNull()
            }
        }
        ownWallets = resolved
    }

    var state by remember { mutableStateOf<EthSendState>(EthSendState.Idle) }

    fun denomTag(): String = selectedToken?.symbol ?: resolved.ticker

    // load balances + gas tiers. Keyed on the chain as well as the asset: a
    // scanned code can move the wallet to another chain, and the balances and
    // gas tiers from the old one are meaningless there.
    LaunchedEffect(walletId, selectedToken?.id, resolved.networkID.stableId) {
        if (descriptor == null) return@LaunchedEffect
        gasLoading = true
        withContext(Dispatchers.IO) {
            val wallet = EthereumWallet(descriptor)
            nativeWeiHex = runCatching { wallet.balance(resolved.rpcURL).hex }.getOrNull()
            tokenWeiHex = selectedToken?.let { runCatching { wallet.tokenBalance(it, resolved.rpcURL).hex }.getOrNull() }
            estimates = runCatching { EthereumGasEstimator.estimate(resolved.rpcURL) }.getOrDefault(emptyList())
        }
        gasLoading = false
    }

    // Native-coin fiat unit price (mainnet only; never for ERC-20). Reset the
    // fiat-entry toggle if a token is selected or no price is available.
    LaunchedEffect(selectedToken?.id, resolved.networkID.stableId) {
        ethUnitPrice = if (selectedToken == null) FiatReference.unitPrice(resolved.coinGeckoAssetId) else null
        if (ethUnitPrice == null) fiatEntry = false
    }

    // ENS resolution, debounced
    LaunchedEffect(recipient) {
        resolvedENS = null
        ensError = null
        val trimmed = recipient.trim()
        if (!ENSResolver.looksLikeName(trimmed)) { ensResolving = false; return@LaunchedEffect }
        ensResolving = true
        delay(400)
        val rpc = settings.effectiveENSRPCURL()
        val result = withContext(Dispatchers.IO) {
            runCatching { ENSResolver(rpc).resolve(trimmed) }
        }
        ensResolving = false
        result.onSuccess { resolvedENS = it }.onFailure { ensError = it.message ?: it.toString() }
    }

    val recipientIsAddress = isValidEthAddress(recipient)
    val effectiveRecipient: String? = if (recipientIsAddress) recipient.trim() else resolvedENS
    // When entering in fiat (native only), convert the typed fiat to the native
    // amount that actually gets signed; everything downstream stays in ether units.
    val fiatActive = fiatEntry && ethUnitPrice != null && selectedToken == null
    val typedAmount = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val parsedAmount = if (fiatActive) {
        typedAmount?.divide(java.math.BigDecimal.valueOf(ethUnitPrice!!), 18, java.math.RoundingMode.DOWN)
    } else typedAmount
    val editable = state is EthSendState.Idle || state is EthSendState.Failed
    val selectedEstimate = estimates.firstOrNull { it.tier == tier }

    // A token transfer to a contract sends the tokens to that contract, not a
    // person, so block it. The pure `tokenSendBlocksContract` catches the token's
    // own contract + any other installed token's contract instantly (no RPC);
    // `recipientIsContract` is the best-effort eth_getCode result below.
    val tok = selectedToken
    // Delegates to the pure, unit-tested `tokenSendBlocksContract` (in
    // EthereumPaymentUri.kt). recipientIsContract is the best-effort eth_getCode
    // result computed below.
    val tokenSendToContractBlocked = tok != null && effectiveRecipient != null &&
        tokenSendBlocksContract(
            recipient = effectiveRecipient,
            isTokenSend = true,
            selectedTokenContract = tok.contractAddress,
            knownTokenContracts = availableTokens.map { it.contractAddress },
            recipientHasCode = recipientIsContract,
        )

    // Probe the recipient with eth_getCode when a token is selected. Keyed on
    // the resolved recipient + token so a changed input relaunches (cancelling
    // the stale probe) and never applies an old result.
    LaunchedEffect(effectiveRecipient, selectedToken?.id) {
        recipientIsContract = false
        val d = descriptor ?: return@LaunchedEffect
        val r = effectiveRecipient ?: return@LaunchedEffect
        if (selectedToken == null || !isValidEthAddress(r)) return@LaunchedEffect
        val isContract = withContext(Dispatchers.IO) {
            runCatching { EthereumWallet(d).isContract(r, resolved.rpcURL) }.getOrDefault(false)
        }
        recipientIsContract = isContract
    }

    fun defaultGasUnits(): Long = if (selectedToken != null) 100_000 else 21_000
    fun gasUnitsUsed(): Long = gasUnits ?: defaultGasUnits()
    fun maxFeeWei(): EthereumWeiValue? = selectedEstimate?.let { it.maxFeePerGas * EthereumWeiValue.fromUInt64(gasUnitsUsed()) }

    val canSubmit = effectiveRecipient != null && parsedAmount != null && selectedEstimate != null &&
        editable && !tokenSendToContractBlocked

    fun availableCaption(): String? {
        val token = selectedToken
        if (token != null) {
            val hex = tokenWeiHex ?: return null
            return "Available: ${formatTokenBalanceHex(hex, token.decimals)} ${token.symbol}"
        }
        val hex = nativeWeiHex ?: return null
        val wei = runCatching { EthereumWeiValue.fromHex(hex) }.getOrNull() ?: return null
        return "Available: ${wei.ether.stripTrailingZeros().toPlainString()} ${resolved.ticker}"
    }

    fun applyMax() {
        val token = selectedToken
        if (token != null) {
            val hex = tokenWeiHex ?: return
            amount = formatTokenBalanceHex(hex, token.decimals)
        } else {
            val hex = nativeWeiHex ?: return
            val bal = runCatching { EthereumWeiValue.fromHex(hex) }.getOrNull() ?: return
            val fee = maxFeeWei() ?: EthereumWeiValue.ZERO
            if (bal <= fee) return
            val spendable = EthereumWeiValue.fromDecimal(bal.decimal.subtract(fee.decimal))
            val spendableEth = spendable.ether.setScale(18, java.math.RoundingMode.DOWN)
            amount = if (fiatEntry && ethUnitPrice != null) {
                String.format(Locale.US, "%.2f", spendableEth.toDouble() * ethUnitPrice!!)
            } else {
                spendableEth.stripTrailingZeros().toPlainString()
            }
        }
    }

    fun submit(hostPassphrase: String? = null) {
        val d = descriptor ?: return
        // Safety backstop: refuse a token transfer to a contract even if the UI
        // guard was somehow bypassed. Nothing is built / signed / sent.
        if (tokenSendToContractBlocked) {
            state = EthSendState.Failed(TOKEN_TO_CONTRACT_ERROR); return
        }
        val hwKind = d.kind as? EthereumWalletKind.Hardware
        val sw = sandwich
        // Software wallets need the unlocked seed; hardware wallets sign on the
        // device, so they need the bound device (looked up below) instead.
        if (hwKind == null && sw == null) {
            state = EthSendState.Failed("Identity is locked. Unlock from the Identity tab and retry."); return
        }
        // Resolve the bound device for a hardware wallet up front so a missing /
        // removed device fails before we build or broadcast anything.
        val hwDevice = hwKind?.let {
            com.elabify.musnad.devices.DeviceRegistry(context).find(it.deviceId)
        }
        if (hwKind != null && hwDevice == null) {
            state = EthSendState.Failed("The device that holds this wallet is not registered anymore. Re-register it under Settings, Devices."); return
        }
        val to = effectiveRecipient ?: return
        val amt = parsedAmount ?: return
        val est = selectedEstimate ?: return
        val account = hwKind?.account ?: (d.kind as? EthereumWalletKind.Software)?.account ?: 0L
        val token = selectedToken
        scope.launch {
            // Software signing unlocks the local seed, so gate it behind
            // biometric. A hardware send is gated by the device's own on-screen
            // confirmation (and the pre-sign sheet), matching the Bitcoin path.
            if (hwDevice == null && !authorizeSend(context, "Ethereum")) return@launch
            state = EthSendState.Working
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wallet = EthereumWallet(d)
                    val nonce = wallet.pendingNonce(resolved.rpcURL)
                    val (value, payload, txTo) = if (token != null) {
                        val raw = EthereumWeiValue.fromUnits(amt.toPlainString(), token.decimals)
                            ?: throw IllegalStateException("Bad token amount")
                        Triple(raw, EthereumTxPlan.Payload.Erc20(to) as EthereumTxPlan.Payload, token.contractAddress)
                    } else {
                        val raw = EthereumWeiValue.fromEther(amt.toPlainString())
                            ?: throw IllegalStateException("Bad ETH amount")
                        Triple(raw, EthereumTxPlan.Payload.Native as EthereumTxPlan.Payload, to)
                    }
                    val estGas = runCatching {
                        val callData = if (token != null) {
                            com.elabify.musnad.wallet.ethereum.EthereumABI.transferData(to, value)
                        } else null
                        wallet.estimateGasUnits(txTo, if (token != null) EthereumWeiValue.ZERO else value, callData, resolved.rpcURL)
                    }.getOrDefault(defaultGasUnits())
                    gasUnits = estGas
                    // SIGN only (no broadcast): the SDK returns the signed raw tx
                    // so the user can review + broadcast on a separate tap.
                    val rawTx = if (hwDevice != null) {
                        wallet.prepareHardware(
                            signer = EthereumDeviceSigner(
                                device = hwDevice,
                                account = account,
                                hostPassphrase = hostPassphrase,
                            ),
                            to = txTo,
                            value = value,
                            gasLimit = estGas,
                            maxFeePerGas = est.maxFeePerGas,
                            maxPriorityFeePerGas = est.maxPriorityFeePerGas,
                            chainId = resolved.chainId,
                            nonce = nonce,
                            payload = payload,
                        )
                    } else {
                        wallet.prepareSoftware(
                            sandwich = sw!!,
                            account = account,
                            to = txTo,
                            value = value,
                            gasLimit = estGas,
                            maxFeePerGas = est.maxFeePerGas,
                            maxPriorityFeePerGas = est.maxPriorityFeePerGas,
                            chainId = resolved.chainId,
                            nonce = nonce,
                            payload = payload,
                        )
                    }
                    EthSendState.Signed(
                        rawTx = rawTx,
                        recipient = to,
                        weiValue = value.decimal.toPlainString(),
                        tokenContract = token?.contractAddress,
                        tokenSymbol = token?.symbol,
                        tokenDecimals = token?.decimals,
                    )
                }
            }
            result.onSuccess { signed -> state = signed }
                .onFailure {
                    // Device errors (wrong device / user-rejected / transport)
                    // surface here; nothing is broadcast on a failed signature.
                    state = EthSendState.Failed(it.friendlyEthSendMessage())
                }
        }
    }

    // Broadcast the already-signed raw tx (separate user action, ADR-0033). No
    // biometric / device prompt: the signature already exists.
    fun broadcast(signed: EthSendState.Signed) {
        val d = descriptor ?: return
        state = EthSendState.Working
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { EthereumWallet(d).broadcast(signed.rawTx, resolved.rpcURL) }
            }
            result.onSuccess { hash ->
                walletStore.markPendingOutbound(
                    senderWalletId = d.id,
                    txHash = hash,
                    senderAddress = d.address ?: "",
                    recipientAddress = signed.recipient,
                    weiValue = signed.weiValue,
                    tokenContract = signed.tokenContract,
                    tokenSymbol = signed.tokenSymbol,
                    tokenDecimals = signed.tokenDecimals,
                )
                state = EthSendState.Done(hash)
            }.onFailure {
                // Broadcast-only failure: keep the signed tx so the user can
                // re-push without re-signing (ADR-0033).
                state = EthSendState.Failed(it.friendlyEthSendMessage(), retry = signed)
            }
        }
    }

    // Apply a scanned / pasted value: parse it as an EIP-681 URI so a token
    // payment (ethereum:0x<TOKEN>@<chain>/transfer?address=0x<RECIPIENT>&uint256=<amt>)
    // sets the RECIPIENT to the address query param (not the token contract) and
    // preselects the matching installed token + amount. A plain address / native
    // request falls through to just setting the recipient. This replaces the old
    // stripEthereumPrefix, which kept the URI target (the token contract) as the
    // recipient.
    fun applyScannedOrPastedUri(raw: String) {
        scanError = null
        pendingChainSwitch = null
        val parsed = EthereumUriParser.parse(raw)

        // Chain first. A contract address means a different token on every chain,
        // so matching or probing before the chain agrees can bind the send to an
        // entirely unrelated asset. Nothing is applied until the user confirms.
        val wantChain = parsed.chainId
        if (wantChain != null && wantChain != resolved.chainId) {
            val targetName = chainDisplayName(context, wantChain)
            if (targetName == null) {
                scanError = SCAN_CHAIN_UNCONFIGURED.format(wantChain)
                return
            }
            pendingChainSwitch = PendingChainSwitch(wantChain, targetName, raw)
            return
        }

        val contract = parsed.tokenContract
        if (contract != null) {
            // requestedSymbol is null here because reading symbol() is an RPC
            // round trip; the dialog does that and re-resolves for candidates.
            when (val match = EthereumScannedToken.resolve(contract, null, availableTokens)) {
                is EthereumScannedTokenMatch.AlreadyAdded -> {
                    selectedToken = match.token
                    substitutedTokenNote = null
                    parsed.amountBaseUnits?.let { base ->
                        runCatching {
                            amount = EthereumWeiValue.fromBigInteger(java.math.BigInteger(base))
                                .units(match.token.decimals).stripTrailingZeros().toPlainString()
                        }
                    }
                }
                else -> {
                    // Not in this wallet on this chain. Hand off to the dialog
                    // rather than dead-ending: it probes the contract and offers
                    // adding it or sending a token the wallet already holds.
                    val builtin = resolved.builtinNetwork()
                    if (builtin == null) {
                        scanError = SCAN_CUSTOM_NETWORK_TOKEN
                        return
                    }
                    pendingTokenRequest = ScannedTokenRequest(contract, parsed.recipient.trim(), parsed.amountBaseUnits)
                    return
                }
            }
        }
        val r = parsed.recipient.trim()
        if (isValidEthAddress(r) || ENSResolver.looksLikeName(r)) {
            recipient = r
        } else {
            scanError = "The scanned code did not contain a valid recipient address."
        }
    }

    // Move the wallet to the chain a scanned code named, then re-apply the code.
    // Everything asset- and fee-shaped is dropped first: it all belonged to the
    // previous chain.
    fun applyPendingChainSwitch(pending: PendingChainSwitch) {
        val target = EthereumNetwork.fromChainId(pending.chainId)
        val custom = if (target == null) {
            EthereumStores.customs(context).networks.firstOrNull { it.chainId == pending.chainId }
        } else null
        val id = when {
            target != null -> EthereumNetworkID.Builtin(target)
            custom != null -> EthereumNetworkID.Custom(custom.id)
            else -> return
        }
        walletStore.setCurrentNetworkID(id, walletId)
        selectedToken = null
        amount = ""
        estimates = emptyList()
        gasUnits = null
        nativeWeiHex = null
        tokenWeiHex = null
        substitutedTokenNote = null
        pendingChainSwitch = null
        // Re-apply on the next composition, when `resolved` reflects the new
        // chain (see reapplyAfterSwitch).
        reapplyAfterSwitch = pending.rawUri
        storeEpoch++
    }

    // Finish a chain switch: the recomposition triggered by storeEpoch has
    // re-resolved the network and token list, so the scanned code can be parsed
    // again against the chain it actually named.
    LaunchedEffect(storeEpoch) {
        val raw = reapplyAfterSwitch ?: return@LaunchedEffect
        reapplyAfterSwitch = null
        applyScannedOrPastedUri(raw)
    }

    // Apply what the user picked in EthereumScannedTokenSheet.
    fun applyScannedTokenChoice(choice: ScannedTokenChoice, request: ScannedTokenRequest) {
        storeEpoch++
        selectedToken = choice.token
        val from = choice.substitutedFrom
        substitutedTokenNote = if (from != null) {
            SCAN_SUBSTITUTED_NOTE.format(
                from.symbol, shortContract(from.contract),
                choice.token.symbol, shortContract(choice.token.contractAddress),
            )
        } else null
        // Carry the requested amount over only when both contracts use the same
        // decimals; otherwise the same integer is a different quantity, so the
        // user re-enters it.
        val base = request.amountBaseUnits
        if (base != null) {
            val requestedDecimals = from?.decimals ?: choice.token.decimals
            if (requestedDecimals == choice.token.decimals) {
                runCatching {
                    amount = EthereumWeiValue.fromBigInteger(java.math.BigInteger(base))
                        .units(choice.token.decimals).stripTrailingZeros().toPlainString()
                }
            } else {
                amount = ""
                substitutedTokenNote = ((substitutedTokenNote?.plus(" ")) ?: "") + SCAN_DECIMALS_MISMATCH
            }
        }
        val r = request.recipient
        if (isValidEthAddress(r) || ENSResolver.looksLikeName(r)) {
            recipient = r
        } else {
            scanError = "The scanned code did not contain a valid recipient address."
        }
    }

    // Token AssetPicker model. The native coin is always the first option; each
    // installed ERC-20 follows. `id` "native" is the stable key we switch on.
    val nativeOption = AssetOption(id = "native", symbol = resolved.ticker, label = stringResource(R.string.eth_asset_native, resolved.ticker))
    // Native first, then ERC-20s alphabetically by symbol (ADR-0033 Phase 2b round-2).
    val assetOptions = remember(availableTokens) {
        // Two contracts can share a symbol on one chain (native USDC and bridged
        // USDC.e both report "USDC"), which would otherwise render as two
        // identical rows. Add the contract tail to any symbol that repeats.
        val colliding = availableTokens.groupBy { it.symbol.lowercase() }
            .filterValues { it.size > 1 }
            .keys
        listOf(nativeOption) + availableTokens.sortedBy { it.symbol.lowercase() }.map {
            val base = "${it.symbol} · ${it.name}"
            val label = if (it.symbol.lowercase() in colliding) {
                "$base · ${shortContract(it.contractAddress)}"
            } else base
            AssetOption(id = it.id, symbol = it.symbol, label = label)
        }
    }
    val selectedOption = assetOptions.firstOrNull { it.id == (selectedToken?.id ?: "native") } ?: nativeOption

    SendFormScaffold(onCancel = onDone, title = stringResource(R.string.walletc_send)) {
        // network / header
        FormSection {
            WalletSelector(
                label = descriptor?.label ?: stringResource(R.string.eth_ethereum_wallet),
                networkName = resolved.displayName,
                networkTint = EthIndigo,
            )
        }

        // token / asset picker (multi-asset chain)
        if (availableTokens.isNotEmpty()) {
            FormSection(header = stringResource(R.string.eth_token)) {
                AssetPicker(
                    options = assetOptions,
                    selected = selectedOption,
                    onSelect = { opt ->
                        selectedToken = if (opt.id == "native") null else availableTokens.firstOrNull { it.id == opt.id }
                        substitutedTokenNote = null
                    },
                    label = stringResource(R.string.eth_token),
                )
                // Stays until the form is submitted: the scanned code named one
                // contract and the user chose to send another.
                substitutedTokenNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        // recipient (Pay to) with ENS
        FormSection(header = stringResource(R.string.eth_pay_to)) {
            RecipientField(
                value = recipient,
                onValueChange = { recipient = it; scanError = null },
                onPaste = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.text?.let { applyScannedOrPastedUri(it.toString()) }
                },
                onScanQr = { showScanner = true },
                placeholder = stringResource(R.string.eth_recipient_placeholder),
                onPickContact = { showContacts = true },
                supporting = {
                    when {
                        tokenSendToContractBlocked ->
                            Text(TOKEN_TO_CONTRACT_ERROR, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        scanError != null -> Text(scanError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        pendingChainSwitch != null -> {
                            val pending = pendingChainSwitch!!
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                Text(
                                    stringResource(R.string.eth_that_code_is_for, pending.displayName, resolved.displayName),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                TextButton(onClick = { applyPendingChainSwitch(pending) }) {
                                    Text(stringResource(R.string.eth_switch_and_continue, pending.displayName))
                                }
                            }
                        }
                        ensResolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.eth_resolving_ens), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        resolvedENS != null -> Text(stringResource(R.string.eth_resolves_to, shortHex(resolvedENS!!)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        ensError != null -> Text(ensError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        recipient.isNotEmpty() && !recipientIsAddress && !ENSResolver.looksLikeName(recipient) ->
                            Text(stringResource(R.string.eth_address_invalid), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        recipientIsAddress && descriptor?.address?.let {
                            SelfSendGuard.isSelfSend(recipient, listOf(it), caseInsensitive = true)
                        } == true ->
                            Text(stringResource(R.string.eth_self_send_warning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                },
            )
        }

        // amount
        FormSection(header = stringResource(R.string.walletc_amount)) {
            // Fiat unit entry only for the native coin on mainnet (ethUnitPrice set).
            val showUnitPicker = selectedToken == null && ethUnitPrice != null
            val fiatId = FiatReference.fiatLabel()
            val amountSecondary = when {
                !showUnitPicker || parsedAmount == null -> null
                fiatActive -> "≈ ${parsedAmount.stripTrailingZeros().toPlainString()} ${resolved.ticker}"
                else -> "≈ " + FiatReference.format(parsedAmount.toDouble() * ethUnitPrice!!)
            }
            AmountField(
                value = amount,
                onValueChange = { amount = it },
                onMax = { applyMax() },
                balanceLabel = availableCaption(),
                unitLabel = if (showUnitPicker) null else denomTag(),
                secondaryLabel = amountSecondary,
                denomination = if (showUnitPicker) {
                    {
                        val unitOpts = listOf(
                            AssetOption(id = "native", symbol = resolved.ticker, label = resolved.ticker),
                            AssetOption(id = fiatId, symbol = fiatId, label = fiatId),
                        )
                        AssetPicker(
                            options = unitOpts,
                            selected = unitOpts.first { it.id == (if (fiatEntry) fiatId else "native") },
                            onSelect = { fiatEntry = it.id == fiatId },
                            label = stringResource(R.string.eth_unit),
                            modifier = Modifier.width(140.dp),
                        )
                    }
                } else null,
            )
        }

        // network fee (EIP-1559 three-tier gas picker)
        FormSection(header = stringResource(R.string.eth_network_fee)) {
            FeeSelector(
                options = EthereumGasEstimator.Tier.entries.map { FeeOption(it.name, it.label) },
                selected = tier.name,
                onSelect = { id -> EthereumGasEstimator.Tier.entries.firstOrNull { it.name == id }?.let { tier = it } },
                footer = {
                    if (gasLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.eth_fetching_gas), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (selectedEstimate != null) {
                        val est = selectedEstimate
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            GasRow(stringResource(R.string.eth_base_fee), "${est.baseFeePerGas.displayGwei()} gwei")
                            GasRow(stringResource(R.string.eth_priority_tip), "${est.maxPriorityFeePerGas.displayGwei()} gwei")
                            GasRow(stringResource(R.string.eth_max_fee_per_gas), "${est.maxFeePerGas.displayGwei()} gwei")
                            GasRow(stringResource(R.string.eth_gas_units), gasUnitsUsed().toString())
                            maxFeeWei()?.let { GasRow(stringResource(R.string.eth_max_network_fee), it.display(resolved.ticker, maxDecimals = 8)) }
                        }
                    } else {
                        Text(stringResource(R.string.eth_gas_unavailable), style = MaterialTheme.typography.labelSmall, color = Color(0xFFF29900))
                    }
                },
            )
        }

        // review
        FormSection(header = stringResource(R.string.eth_review)) {
            ReviewRow(stringResource(R.string.common_network), resolved.displayName, valueColor = EthIndigo)
            ReviewRow(stringResource(R.string.eth_pay_to), effectiveRecipient?.let { shortHex(it) } ?: "-", mono = true)
            ReviewRow(stringResource(R.string.walletc_amount), "${amount.ifEmpty { "0" }} ${denomTag()}", mono = true)
            maxFeeWei()?.let { ReviewRow(stringResource(R.string.eth_max_network_fee), it.display(resolved.ticker, maxDecimals = 8), mono = true) }
            if (selectedToken == null) {
                val amtWei = parsedAmount?.let { EthereumWeiValue.fromEther(it.toPlainString()) }
                val total = if (amtWei != null && maxFeeWei() != null) amtWei + maxFeeWei()!! else null
                val balWei = nativeWeiHex?.let { runCatching { EthereumWeiValue.fromHex(it) }.getOrNull() }
                val over = total != null && balWei != null && total > balWei
                total?.let {
                    ReviewRow(
                        stringResource(R.string.eth_worst_case_total),
                        it.display(resolved.ticker, maxDecimals = 8),
                        valueColor = if (over) MaterialTheme.colorScheme.error else null,
                        mono = true,
                    )
                }
                if (over) {
                    Text(
                        stringResource(R.string.eth_worst_case_exceeds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // primary action + error / status
        when (val s = state) {
            is EthSendState.Idle, is EthSendState.Failed -> {
                val retry = (s as? EthSendState.Failed)?.retry
                if (retry != null) {
                    // Broadcast-only failure: re-push the SAME signed tx (no
                    // re-sign, no device prompt).
                    PrimaryActionButton(text = stringResource(R.string.eth_retry_broadcast), onClick = { broadcast(retry) })
                    Banner(title = s.message, variant = BannerVariant.ERROR)
                    TextButton(onClick = { state = EthSendState.Idle }) { Text(stringResource(R.string.common_start_over)) }
                } else {
                    PrimaryActionButton(
                        text = if (isHardware) stringResource(R.string.eth_sign_using_device) else stringResource(R.string.walletc_send),
                        onClick = {
                            // Hardware: open the pre-sign device-ready confirmation
                            // first (ADR-0033). Signing runs on Continue with the
                            // typed hidden-wallet passphrase (or null). Software
                            // submits directly (biometric-gated inside submit).
                            if (isHardware) showReadySheet = true else submit(null)
                        },
                        enabled = canSubmit,
                    )
                    if (s is EthSendState.Failed) {
                        Banner(title = s.message, variant = BannerVariant.ERROR)
                    }
                }
                if (isHardware) {
                    Text(
                        stringResource(R.string.eth_confirm_on_device),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is EthSendState.Signed -> {
                FormSection {
                    Banner(
                        title = stringResource(R.string.eth_signed_ready),
                        variant = BannerVariant.INFO,
                        body = stringResource(R.string.eth_signed_body),
                    )
                    PrimaryActionButton(text = stringResource(R.string.eth_broadcast_transaction), onClick = { broadcast(s) })
                    TextButton(onClick = { state = EthSendState.Idle }) { Text(stringResource(R.string.common_cancel)) }
                }
            }
            is EthSendState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.eth_working))
            }
            is EthSendState.Done -> {
                FormSection {
                    Banner(title = stringResource(R.string.eth_broadcast), variant = BannerVariant.SUCCESS, body = s.txHash)
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ethExplorerTxUrl(resolved.explorerURL, s.txHash)))) }
                    }) { Text(stringResource(R.string.common_view_on_explorer)) }
                    PrimaryActionButton(text = stringResource(R.string.common_done), onClick = onDone)
                }
            }
        }
    }

    // Pre-sign device-ready confirmation for hardware wallets (ADR-0033). Shows
    // the readiness copy (open the Ethereum app on a Ledger, or confirm on a
    // Trezor) and, only for a host-typed hidden wallet, the passphrase field. On
    // Continue it runs submit() with that passphrase (or null), which threads
    // through the descriptor's hidden config so the device derives the right
    // (possibly hidden) wallet.
    if (showReadySheet && descriptor != null) {
        val hw = descriptor.kind as? EthereumWalletKind.Hardware
        val device = hw?.let { com.elabify.musnad.devices.DeviceRegistry(context).find(it.deviceId) }
        if (device == null) {
            showReadySheet = false
            state = EthSendState.Failed(
                stringResource(R.string.eth_device_not_registered),
            )
        } else {
            HardwareSignReadySheet(
                deviceKind = device.kind,
                deviceLabel = device.label,
                deviceSerialDisplay = device.serialDisplay,
                readiness = HardwareSignAppReadiness.ethereum,
                requiresHostPassphrase = needsHostPassphrase,
                onCancel = { showReadySheet = false },
                onContinue = { hostPassphrase ->
                    showReadySheet = false
                    submit(hostPassphrase)
                },
            )
        }
    }

    // QR scanner sheet. Reuses the GMS-free camera2 + ZXing scanner. On a hit we
    // strip any EIP-681 prefix and drop the result into the recipient field
    // (ENS / 0x validation then runs in the LaunchedEffect above), matching the
    // iOS ChainScanSheet behaviour.
    if (showScanner) {
        ModalBottomSheet(onDismissRequest = { showScanner = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.walletc_scan_address), style = MaterialTheme.typography.titleMedium)
                MiniAppQrScanner(
                    continuous = false,
                    onCode = { code ->
                        applyScannedOrPastedUri(code)
                        showScanner = false
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                )
                QrPhotoPickerButton(
                    onCode = { code ->
                        applyScannedOrPastedUri(code)
                        showScanner = false
                    },
                    onNoQr = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { showScanner = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }

    // A scanned code asked for an ERC-20 this wallet does not have on this chain.
    // The dialog probes the contract and offers adding it or sending a token the
    // wallet already holds; it never substitutes silently.
    val tokenRequest = pendingTokenRequest
    val requestNetwork = resolved.builtinNetwork()
    if (tokenRequest != null && descriptor != null && requestNetwork != null) {
        EthereumScannedTokenSheet(
            request = tokenRequest,
            descriptor = descriptor,
            walletId = walletId,
            network = requestNetwork,
            resolved = resolved,
            added = availableTokens,
            onChoose = { choice -> applyScannedTokenChoice(choice, tokenRequest) },
            onDismiss = { pendingTokenRequest = null },
        )
    }

    if (showContacts) {
        ContactsPickerSheet(
            network = AddressBookNetwork.ETHEREUM,
            onPick = { recipient = it },
            onDismiss = { showContacts = false },
            ownWallets = ownWallets,
        )
    }
}

/** Friendly one-line message for a failed send. Device errors (wrong device,
 *  user-rejected, transport) get clear copy; everything else falls back to the
 *  exception message. */
private fun Throwable.friendlyEthSendMessage(): String = when (this) {
    is com.elabify.musnad.hardware.HardwareWalletException.UserCancelled ->
        "You declined the transaction on the device. Nothing was broadcast."
    is com.elabify.musnad.hardware.HardwareWalletException.Transport -> "Hardware transport error: $detail"
    is com.elabify.musnad.hardware.HardwareWalletException.NotImplemented ->
        "${kind.displayName}: Ethereum signing is not supported on this device."
    else -> message ?: toString()
}

@Composable
private fun GasRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}
