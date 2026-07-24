// Tron send. Ported from iOS TronSendView.swift:
//
//   network/header (WalletSelector + red network chip)
//   token picker (TRX native or any installed TRC-20)   [AssetPicker]
//   recipient: text + paste + scan-prefix-strip          [RecipientField]
//   amount: + Max + Available caption                    [AmountField]
//   advanced: fee limit (TRX)
//   review: Network · Pay to · Amount · Network fee       [ReviewRow]
//   primary action: Send (software) / Sign using device (hardware)
//
// The presentation now composes the shared common/* send components so the
// section order and styling match the other chains and the iOS Form exactly.
// Build / sign / broadcast logic, state, SDK engine calls, validation, and the
// hardware gate are unchanged from the prior version.
//
// Software path is one-tap: the SDK's sendNative / sendTRC20 build the
// canonical tx server-side, sign SHA256(raw_data) with the seed-derived
// secp256k1 key, splice the signature, and broadcast, all inside the one
// blocking call (run here on Dispatchers.IO). Hardware Tron is Ledger-
// only and the BLE signing transport is a later phase, so the hardware
// button surfaces the engine's "not implemented in this build" hook
// (TronWallet.sendNative throws for a Hardware kind), exactly like iOS
// leaves the device-signing seam unwired in this build.

package com.elabify.app.maknoon.ui.wallet.tron

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.settings.OwnWalletEntry
import com.elabify.app.maknoon.ui.theme.Spacing
import androidx.compose.foundation.layout.width
import com.elabify.app.maknoon.ui.wallet.AddressFamily
import com.elabify.app.maknoon.ui.wallet.AddressNetworkGuard
import com.elabify.app.maknoon.ui.wallet.SelfSendGuard
import com.elabify.app.maknoon.ui.wallet.common.AmountField
import com.elabify.app.maknoon.ui.wallet.common.AssetOption
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.AssetPicker
import com.elabify.app.maknoon.ui.wallet.common.FormSection
import com.elabify.app.maknoon.ui.wallet.common.PrimaryActionButton
import com.elabify.app.maknoon.ui.settings.AddressBookNetwork
import com.elabify.app.maknoon.ui.settings.ContactsPickerSheet
import com.elabify.app.maknoon.ui.wallet.common.RecipientField
import com.elabify.app.maknoon.ui.wallet.common.ReviewRow
import com.elabify.app.maknoon.ui.wallet.common.SendFormScaffold
import com.elabify.app.maknoon.ui.wallet.common.WalletSelector
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignAppReadiness
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignReadySheet
import com.elabify.app.maknoon.ui.wallet.common.authorizeSend
import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.wallet.tron.TronAddressCodec
import com.elabify.musnad.wallet.tron.TronDescriptors
import com.elabify.musnad.wallet.tron.TronTRC20Token
import com.elabify.musnad.wallet.tron.TronTRC20TransferBuilder
import com.elabify.musnad.wallet.tron.TronWallet
import com.elabify.musnad.wallet.tron.TronWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale
import java.math.RoundingMode
import java.util.UUID

private sealed interface SendUiState {
    data object Idle : SendUiState
    data object Signing : SendUiState
    data object Broadcasting : SendUiState
    /** Signed but NOT yet broadcast (ADR-0033): user reviews + taps Broadcast. */
    data class Signed(
        val signed: com.elabify.musnad.wallet.tron.TronDescriptors.TronUnsignedAndSignature,
        val recipient: String,
        val sunOrRaw: String,
        val tokenContract: String?,
        val tokenSymbol: String?,
        val tokenDecimals: Int?,
    ) : SendUiState
    data class Done(val txid: String) : SendUiState

    /** A failure. When [retry] is non-null the signing already succeeded and
     *  only the broadcast RPC failed, so the same signed tx can be re-pushed
     *  WITHOUT re-signing (no second device prompt). Null => re-sign required. */
    data class Failed(val message: String, val retry: Signed? = null) : SendUiState
}

// Stable id for the native-TRX option in the AssetPicker. TRC-20 options use
// their token id; this matches the iOS "trx" tag for the native row.
private const val NATIVE_ASSET_ID = "trx"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronSendScreen(walletId: UUID, preselectTokenId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walletStore = remember { TronStores.walletStore(context) }
    val settings = remember { TronStores.settings(context) }
    val tokenStore = remember { TronStores.tokenStore(context) }
    val sandwich = remember { loadTronSandwich(context) }

    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    val network = remember { walletStore.currentNetwork }
    val availableTokens = remember { tokenStore.tokens(network) }
    val isHardware = descriptor?.kind is TronWalletKind.Hardware

    var recipient by remember { mutableStateOf("") }
    var showContacts by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var feeLimitTRX by remember { mutableStateOf("1") }
    var selectedToken by remember { mutableStateOf(preselectTokenId?.let { id -> availableTokens.firstOrNull { it.id == id } }) }
    // Native-TRX fiat entry (mainnet only, never for TRC-20). trxUnitPrice is one
    // TRX in the selected fiat; null hides the fiat unit.
    var trxUnitPrice by remember { mutableStateOf<Double?>(null) }
    var fiatEntry by remember { mutableStateOf(false) }
    var nativeSun by remember { mutableStateOf<Long?>(null) }
    var tokenRawBalance by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<SendUiState>(SendUiState.Idle) }
    // Pre-sign device-ready sheet + whether this hardware wallet is a host-typed
    // hidden (passphrase) wallet (collected in the sheet; never stored).
    var showReadySheet by remember { mutableStateOf(false) }
    val needsHostPassphrase = remember(descriptor?.id) {
        HardwarePassphraseRef.fromJson(descriptor?.hidden)?.needsHostPassphrase ?: false
    }
    var showScanner by remember { mutableStateOf(false) }
    // The user's own Tron wallets, resolved to addresses, shown first in the
    // recipient picker ("Your wallets"). Resolved off-thread when the sheet opens.
    var ownWallets by remember { mutableStateOf<List<OwnWalletEntry>>(emptyList()) }
    LaunchedEffect(showContacts) {
        if (!showContacts || ownWallets.isNotEmpty()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            walletStore.wallets.mapNotNull { w ->
                runCatching {
                    val addr = when (val k = w.kind) {
                        is TronWalletKind.Hardware -> k.addressBase58Check
                        is TronWalletKind.Software -> sandwich?.let { TronDescriptors.addressFromSandwich(it, k.account) }
                    } ?: return@mapNotNull null
                    OwnWalletEntry(name = w.label, address = addr)
                }.getOrNull()
            }
        }
        ownWallets = resolved
    }

    // load balances for Available / Max
    LaunchedEffect(walletId, selectedToken?.id) {
        if (descriptor == null) return@LaunchedEffect
        val rpcURL = settings.rpcURL(network)
        withContext(Dispatchers.IO) {
            runCatching {
                val wallet = TronWallet(descriptor, network, rpcURL, sandwich)
                val sender = wallet.resolvedAddress()
                nativeSun = wallet.refreshBalance()
                tokenRawBalance = selectedToken?.let {
                    runCatching { TronTRC20TransferBuilder.balance(sender, it.contract, rpcURL) }.getOrNull()
                }
            }
        }
    }

    // Native-TRX fiat unit price (mainnet only; never for TRC-20).
    LaunchedEffect(selectedToken?.id) {
        trxUnitPrice = if (selectedToken == null) FiatReference.unitPrice(network.coinGeckoAssetId) else null
        if (trxUnitPrice == null) fiatEntry = false
    }

    val recipientValid = recipient.isNotEmpty() && TronAddressCodec.isValid(recipient)
    // When entering in fiat, convert to TRX; everything downstream stays in TRX.
    val fiatActive = fiatEntry && trxUnitPrice != null && selectedToken == null
    val typedAmount = amount.toDoubleOrNull()?.takeIf { it > 0 }
    val parsedAmount = if (fiatActive) typedAmount?.let { it / trxUnitPrice!! } else typedAmount
    val editable = state is SendUiState.Idle || state is SendUiState.Failed
    val canSubmit = recipientValid && parsedAmount != null && editable

    fun denomTag(): String = selectedToken?.symbol ?: "TRX"

    fun availableCaption(): String? {
        val token = selectedToken
        if (token != null) {
            val raw = tokenRawBalance ?: return null
            return "Available: ${token.format(raw)} ${token.symbol}"
        }
        val s = nativeSun ?: return null
        return "Available: ${formatTrx(s)} TRX"
    }

    fun maxValue(): String? {
        val token = selectedToken
        if (token != null) {
            val raw = tokenRawBalance ?: return null
            val units = BigDecimal(raw).divide(BigDecimal.TEN.pow(token.decimals)).setScale(token.decimals, RoundingMode.DOWN)
            return units.stripTrailingZeros().toPlainString()
        }
        val s = nativeSun ?: return null
        val feeSun = trxToSun(feeLimitTRX.toDoubleOrNull() ?: 1.0)
        if (s <= feeSun) return null
        return formatTrx(s - feeSun)
    }

    fun submit(hostPassphrase: String? = null) {
        val d = descriptor ?: return
        if (!isHardware && sandwich == null) {
            state = SendUiState.Failed("Identity is locked. Unlock from the Identity tab and retry.")
            return
        }
        val amt = parsedAmount ?: return
        scope.launch {
            // Software signing unlocks the local seed -> biometric gate. A
            // hardware send is gated by the device's own confirmation + the
            // pre-sign sheet, matching Ethereum.
            if (!isHardware && !authorizeSend(context, "Tron")) return@launch
            state = SendUiState.Signing
            val rpcURL = settings.rpcURL(network)
            val feeLimitSun = trxToSun(feeLimitTRX.toDoubleOrNull() ?: 1.0)
            val token = selectedToken
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wallet = TronWallet(d, network, rpcURL, sandwich)
                    val sender = wallet.resolvedAddress()
                    // Tron rejects a self-transfer on-chain (only burns the fee),
                    // so hard-block sending to your own address.
                    if (SelfSendGuard.isSelfSend(recipient, listOf(sender), caseInsensitive = false)) {
                        throw IllegalStateException(
                            "You're sending to your own Tron address. Tron rejects a self-transfer, so this would only burn the fee. Enter the recipient's address.",
                        )
                    }
                    // Build the unsigned tx (same for software + hardware), then
                    // SIGN only (no broadcast) so the user confirms broadcast.
                    val (unsigned, rawOrSun) = if (token != null) {
                        // Exact base units from the typed decimal string (no binary
                        // Double). The fiat path formats the price-converted value to
                        // the token's decimals first (inherently approximate).
                        val rawAmount = (
                            if (!fiatActive) tronTokenToRaw(amount, token.decimals)
                            else tronTokenToRaw(String.format(Locale.US, "%.${token.decimals}f", amt), token.decimals)
                        ) ?: throw IllegalStateException("Enter a valid ${token.symbol} amount")
                        wallet.prepareHardwareTRC20(
                            contractAddress = token.contract,
                            recipient = recipient,
                            rawAmount = rawAmount,
                            feeLimitSun = maxOf(feeLimitSun, 10_000_000),
                            senderBase58 = sender,
                        ) to rawAmount
                    } else {
                        val sunAmount = (
                            if (!fiatActive) tronTokenToRaw(amount, 6)
                            else tronTokenToRaw(String.format(Locale.US, "%.6f", amt), 6)
                        )?.toLongOrNull() ?: throw IllegalStateException("Enter a valid amount")
                        wallet.prepareHardwareNative(
                            recipient = recipient,
                            sunAmount = sunAmount,
                            senderBase58 = sender,
                        ) to sunAmount.toString()
                    }
                    val hwKind = d.kind as? TronWalletKind.Hardware
                    val signed = if (hwKind != null) {
                        // Hardware: sign raw_data on the device (Ledger OR Trezor),
                        // re-applying the descriptor's hidden / passphrase + path.
                        val device = DeviceRegistry(context).find(hwKind.deviceId)
                            ?: throw IllegalStateException("The device that holds this wallet is no longer registered. Re-register it under Settings, Devices.")
                        val choice = HardwarePassphraseRef.resolveChoice(HardwarePassphraseRef.fromJson(d.hidden), hostPassphrase)
                        val sig = withHardwareDevice(device, choice, d.derivationPath) { w ->
                            w.signTronTransaction(unsigned.rawData, hwKind.account)
                        }
                        TronDescriptors.assembleHardwareSignature(unsigned = unsigned, r = sig.r, s = sig.s, v = byteArrayOf(sig.v.toByte()))
                    } else {
                        // Software: sign raw_data with the seed-derived key.
                        val s = sandwich ?: throw IllegalStateException("Identity is locked.")
                        val acct = (d.kind as? TronWalletKind.Software)?.account ?: 0L
                        TronDescriptors.signUnsignedFromSandwich(s, acct, unsigned)
                    }
                    signed to rawOrSun
                }
            }
            result.onSuccess { (signed, rawOrSun) ->
                state = SendUiState.Signed(
                    signed = signed,
                    recipient = recipient,
                    sunOrRaw = rawOrSun,
                    tokenContract = token?.contract,
                    tokenSymbol = token?.symbol,
                    tokenDecimals = token?.decimals,
                )
            }.onFailure {
                state = SendUiState.Failed(it.message ?: it.toString())
            }
        }
    }

    // Broadcast the already-signed tx (separate user action, ADR-0033).
    fun broadcast(s: SendUiState.Signed) {
        val d = descriptor ?: return
        state = SendUiState.Broadcasting
        scope.launch {
            val rpcURL = settings.rpcURL(network)
            val result = withContext(Dispatchers.IO) {
                runCatching { TronWallet(d, network, rpcURL, sandwich).broadcastHardwareSignature(s.signed) }
            }
            result.onSuccess { txid ->
                walletStore.markPendingOutbound(
                    senderWalletId = d.id,
                    txID = txid,
                    senderAddress = "",
                    recipientAddress = s.recipient,
                    sunAmount = s.sunOrRaw.toLongOrNull() ?: 0L,
                    tokenContract = s.tokenContract,
                    tokenSymbol = s.tokenSymbol,
                    tokenDecimals = s.tokenDecimals,
                )
                state = SendUiState.Done(txid)
            }.onFailure {
                // Broadcast-only failure: keep the signed tx for a re-push
                // without re-signing (ADR-0033).
                state = SendUiState.Failed(it.message ?: it.toString(), retry = s)
            }
        }
    }

    // Build the AssetPicker option list: native TRX first, then each installed
    // TRC-20. The selected option mirrors `selectedToken` (null -> native).
    val assetOptions = remember(availableTokens) {
        buildList {
            add(AssetOption(id = NATIVE_ASSET_ID, symbol = "TRX", label = "TRX (native)"))
            // Native first, then tokens alphabetically by symbol (ADR-0033 Phase 2b round-2).
            availableTokens.sortedBy { it.symbol.lowercase() }.forEach { token ->
                add(AssetOption(id = token.id, symbol = token.symbol, label = "${token.symbol} - ${token.name}"))
            }
        }
    }
    val selectedAsset = assetOptions.firstOrNull { it.id == (selectedToken?.id ?: NATIVE_ASSET_ID) } ?: assetOptions.first()

    val deviceLabel = descriptor?.label
    val primaryLabel = if (isHardware) stringResource(R.string.trx_sign_using_device) else stringResource(R.string.walletc_send)
    val loading = state is SendUiState.Signing || state is SendUiState.Broadcasting

    SendFormScaffold(onCancel = onDone) {
        // network / header
        FormSection {
            WalletSelector(
                label = descriptor?.label ?: stringResource(R.string.trx_tron_wallet),
                networkName = network.displayName,
                networkTint = TronRed,
                subtitle = if (isHardware) deviceLabel else null,
            )
        }

        // token picker (multi-asset only)
        if (availableTokens.isNotEmpty()) {
            FormSection(header = stringResource(R.string.trx_token)) {
                AssetPicker(
                    options = assetOptions,
                    selected = selectedAsset,
                    onSelect = { opt ->
                        selectedToken = if (opt.id == NATIVE_ASSET_ID) null else availableTokens.firstOrNull { it.id == opt.id }
                    },
                    label = stringResource(R.string.trx_token),
                )
            }
        }

        // recipient
        FormSection(header = stringResource(R.string.trx_recipient)) {
            RecipientField(
                value = recipient,
                onValueChange = { recipient = it },
                onPaste = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.text?.let { recipient = stripTronPrefix(it.toString()) }
                },
                onScanQr = { showScanner = true },
                placeholder = stringResource(R.string.trx_t_prefixed_address),
                onPickContact = { showContacts = true },
                supporting = if (recipient.isNotEmpty() && !recipientValid) {
                    {
                        val fam = AddressNetworkGuard.detect(recipient)
                        Text(
                            if (fam != null && fam != AddressFamily.TRON)
                                stringResource(R.string.wallet_wrong_network_address, fam.displayName, "Tron")
                            else stringResource(R.string.trx_invalid_tron_address),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    null
                },
            )
        }

        // amount
        FormSection(header = stringResource(R.string.walletc_amount)) {
            // Fiat unit entry only for native TRX on mainnet (trxUnitPrice set).
            val showUnitPicker = selectedToken == null && trxUnitPrice != null
            val fiatId = FiatReference.fiatLabel()
            val amountSecondary = when {
                !showUnitPicker || parsedAmount == null -> null
                fiatActive -> "≈ ${formatTrx(trxToSun(parsedAmount))} TRX"
                else -> "≈ " + FiatReference.format(parsedAmount * trxUnitPrice!!)
            }
            AmountField(
                value = amount,
                onValueChange = { amount = it },
                onMax = if (maxValue() != null) {
                    {
                        maxValue()?.let { mv ->
                            amount = if (fiatActive) {
                                String.format("%.2f", (mv.toDoubleOrNull() ?: 0.0) * trxUnitPrice!!)
                            } else mv
                        }
                    }
                } else {
                    null
                },
                balanceLabel = availableCaption(),
                unitLabel = if (showUnitPicker) null else denomTag(),
                secondaryLabel = amountSecondary,
                denomination = if (showUnitPicker) {
                    {
                        val unitOpts = listOf(
                            AssetOption(id = NATIVE_ASSET_ID, symbol = "TRX", label = "TRX"),
                            AssetOption(id = fiatId, symbol = fiatId, label = fiatId),
                        )
                        AssetPicker(
                            options = unitOpts,
                            selected = unitOpts.first { it.id == (if (fiatEntry) fiatId else NATIVE_ASSET_ID) },
                            onSelect = { fiatEntry = it.id == fiatId },
                            label = stringResource(R.string.trx_unit),
                            modifier = Modifier.width(140.dp),
                        )
                    }
                } else null,
            )
        }

        // advanced: fee limit (TRX)
        FormSection(header = stringResource(R.string.trx_advanced)) {
            Text(
                stringResource(R.string.trx_fee_limit_trx),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = feeLimitTRX,
                onValueChange = { feeLimitTRX = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.trx_fee_limit_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // review
        FormSection(header = stringResource(R.string.trx_review)) {
            ReviewRow(stringResource(R.string.common_network), network.displayName, valueColor = TronRed)
            ReviewRow(stringResource(R.string.trx_pay_to), if (recipient.isEmpty()) "-" else shortHash(recipient), mono = true)
            ReviewRow(stringResource(R.string.walletc_amount), "${amount.ifEmpty { "0" }} ${denomTag()}", mono = true)
            ReviewRow(stringResource(R.string.trx_network_fee), stringResource(R.string.trx_fee_at_most, feeLimitTRX))
        }

        // primary action + error / status
        FormSection {
            when (val s = state) {
                is SendUiState.Done -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF34A853))
                        Text(stringResource(R.string.trx_broadcast), color = Color(0xFF34A853), style = MaterialTheme.typography.titleSmall)
                    }
                    Text(s.txid, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = {
                        val base = settings.explorerURL(network).trim().trimEnd('/')
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/#/transaction/${s.txid}"))) }
                    }) { Text(stringResource(R.string.common_view_on_explorer)) }
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
                }
                is SendUiState.Signed -> {
                    Text(
                        stringResource(R.string.trx_signed_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PrimaryActionButton(
                        text = stringResource(R.string.trx_broadcast_transaction),
                        onClick = { broadcast(s) },
                        enabled = state !is SendUiState.Broadcasting,
                        loading = loading,
                    )
                    TextButton(onClick = { state = SendUiState.Idle }) { Text(stringResource(R.string.common_cancel)) }
                }
                else -> {
                    val retry = (s as? SendUiState.Failed)?.retry
                    if (retry != null) {
                        // Broadcast-only failure: re-push the SAME signed tx (no
                        // re-sign, no device prompt).
                        PrimaryActionButton(
                            text = stringResource(R.string.trx_retry_broadcast),
                            onClick = { broadcast(retry) },
                            enabled = state !is SendUiState.Broadcasting,
                            loading = loading,
                        )
                        Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { state = SendUiState.Idle }) { Text(stringResource(R.string.common_start_over)) }
                    } else {
                        PrimaryActionButton(
                            text = primaryLabel,
                            onClick = {
                                // Hardware: open the pre-sign device-ready sheet first
                                // (ADR-0033). Signing runs on Continue with the typed
                                // hidden-wallet passphrase (or null). Software submits
                                // directly (biometric-gated inside submit).
                                if (isHardware) showReadySheet = true else submit(null)
                            },
                            enabled = canSubmit,
                            loading = loading,
                        )
                        if (s is SendUiState.Failed) {
                            Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    if (showContacts) {
        ContactsPickerSheet(
            network = AddressBookNetwork.TRON,
            onPick = { recipient = it },
            onDismiss = { showContacts = false },
            ownWallets = ownWallets,
        )
    }

    // QR scan sheet (GMS-free camera2 + ZXing). A scanned tron: URI is stripped
    // to the bare base58check address before it fills the recipient.
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
                        recipient = stripTronPrefix(code)
                        showScanner = false
                    },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                )
                QrPhotoPickerButton(
                    onCode = { code ->
                        recipient = stripTronPrefix(code)
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

    // Pre-sign device-ready confirmation for hardware wallets (ADR-0033): the
    // readiness copy + (only for a host-typed hidden wallet) the passphrase
    // field. On Continue it runs submit() with that passphrase (or null).
    if (showReadySheet && descriptor != null) {
        val hw = descriptor.kind as? TronWalletKind.Hardware
        val device = hw?.let { DeviceRegistry(context).find(it.deviceId) }
        if (device == null) {
            showReadySheet = false
            state = SendUiState.Failed(
                "The device that holds this wallet is no longer registered. Re-register it under Settings, Devices.",
            )
        } else {
            HardwareSignReadySheet(
                deviceKind = device.kind,
                deviceLabel = device.label,
                deviceSerialDisplay = device.serialDisplay,
                readiness = HardwareSignAppReadiness.tron,
                requiresHostPassphrase = needsHostPassphrase,
                onCancel = { showReadySheet = false },
                onContinue = { hostPassphrase ->
                    showReadySheet = false
                    submit(hostPassphrase)
                },
            )
        }
    }
}
