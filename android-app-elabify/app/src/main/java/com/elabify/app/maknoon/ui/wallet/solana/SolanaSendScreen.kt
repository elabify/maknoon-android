// Solana send screen, 1:1 with iOS SolanaSendView.swift. Restyled onto the
// shared send-form components (com.elabify.app.maknoon.ui.wallet.common.*) so
// the section order + controls are identical across every chain. The iOS
// SolanaSendView is an inset-grouped Form with this exact section sequence:
//
//   network/header  -> WalletSelector (wallet name + device sublabel + purple
//                       Solana NetworkChip)
//   token picker    -> AssetPicker ("SOL (native)" / each installed SPL token),
//                       multi-asset section only when tokens exist
//   recipient       -> RecipientField (base58 address + paste + QR scan)
//   amount          -> AmountField (decimal field + native unit + Max + balance)
//   advanced        -> priority fee (micro-lamports / compute unit) text field
//   review          -> ReviewRow rows (Network, Pay to, Amount, Network fee)
//   primary action  -> PrimaryActionButton (Send software / hardware stub)
//   error/status    -> Banner
//
// The build -> sign -> broadcast LOGIC, the state, the SDK engine calls
// (sendSoftware / sendSPLToken), the validation, the rent-exempt pre-flight
// guard, and the optimistic pending row are UNCHANGED from the verified
// Bitcoin-parity behavior. Only the presentation moved onto the shared
// components.

package com.elabify.app.maknoon.ui.wallet.solana

import android.content.Context
import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.Spacing
import androidx.compose.foundation.layout.width
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
import com.elabify.app.maknoon.ui.wallet.common.authorizeSend
import com.elabify.musnad.wallet.solana.SolanaDescriptorException
import com.elabify.musnad.wallet.solana.SolanaNameResolver
import com.elabify.app.maknoon.ui.wallet.AddressNetworkGuard
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.solana.SolanaPrimitives
import com.elabify.musnad.wallet.solana.SolanaSPLToken
import com.elabify.musnad.wallet.solana.SolanaWallet
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class SendState {
    object Idle : SendState()
    object Working : SendState()
    /** Signed but NOT yet broadcast (ADR-0033): user reviews + taps Broadcast. */
    data class Signed(
        val signedBase64: String,
        val recipient: String,
        val amountUnits: Long,
        val tokenMint: String?,
        val tokenSymbol: String?,
        val tokenDecimals: Int?,
    ) : SendState()
    data class Done(val signature: String) : SendState()

    /** A failure. When [retry] is non-null the signing already succeeded and
     *  only the broadcast RPC failed, so the same signed tx can be re-pushed
     *  WITHOUT re-signing (no second device prompt). Null => re-sign required. */
    data class Failed(val message: String, val retry: Signed? = null) : SendState()
}

/** Either native SOL or a tracked SPL token, for the asset picker. */
private sealed class SendAsset {
    object Native : SendAsset()
    data class Token(val token: SolanaSPLToken) : SendAsset()

    val label: String get() = when (this) {
        is Native -> "SOL"
        is Token -> token.symbol
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolanaSendScreen(
    descriptor: SolanaWalletDescriptor,
    onBack: () -> Unit,
    preselectMint: String? = null,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val network = env.walletStore.currentNetwork

    val isHardware = descriptor.kind is SolanaWalletKind.Hardware

    val tokens = remember(network) { env.tokenStore.tokens(network) }
    var asset by remember {
        mutableStateOf<SendAsset>(
            preselectMint?.let { m -> tokens.firstOrNull { it.mint == m }?.let { SendAsset.Token(it) } }
                ?: SendAsset.Native
        )
    }

    var recipient by remember { mutableStateOf("") }
    var showContacts by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    // Native-SOL fiat entry (mainnet only, never for SPL tokens). solUnitPrice is
    // one SOL in the selected fiat; null hides the fiat unit.
    var solUnitPrice by remember { mutableStateOf<Double?>(null) }
    var fiatEntry by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var priorityFee by remember { mutableStateOf("0") }
    var showScanner by remember { mutableStateOf(false) }

    var nativeLamports by remember { mutableStateOf<Long?>(null) }
    var splBalances by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    var state by remember { mutableStateOf<SendState>(SendState.Idle) }

    // Load balances for the spendable captions.
    LaunchedEffect(descriptor.id, network) {
        withContext(Dispatchers.IO) {
            runCatching {
                val w = env.openWallet(descriptor)
                nativeLamports = w.refreshBalance()
                val accs = runCatching { w.tokenAccounts() }.getOrDefault(emptyList())
                splBalances = accs.associate { it.mint to it.amount }
            }
        }
    }

    // Asset picker options: native SOL plus each installed SPL token, mirroring
    // the iOS "SOL (native)" / "<SYM> - <name>" Token picker rows.
    val assetOptions = remember(tokens, splBalances, nativeLamports) {
        buildList {
            add(
                AssetOption(
                    id = "sol",
                    symbol = "SOL",
                    label = context.getString(R.string.eth_asset_native, "SOL"),
                    balance = nativeLamports?.let { formatSol(it) },
                ),
            )
            // Native first, then tokens alphabetically by symbol (ADR-0033 Phase 2b round-2).
            tokens.sortedBy { it.symbol.lowercase() }.forEach { t ->
                add(
                    AssetOption(
                        id = t.id,
                        symbol = t.symbol,
                        label = "${t.symbol} - ${t.name}",
                        balance = splBalances[t.mint]?.let { t.format(it) },
                    ),
                )
            }
        }
    }
    val selectedOption = remember(asset, assetOptions) {
        when (val a = asset) {
            is SendAsset.Native -> assetOptions.first { it.id == "sol" }
            is SendAsset.Token -> assetOptions.firstOrNull { it.id == a.token.id } ?: assetOptions.first()
        }
    }

    // Balance caption under the amount field (iOS "Available: ..." line).
    val balanceLabel = when (val a = asset) {
        is SendAsset.Native -> nativeLamports?.let { stringResource(R.string.sol_available_sol, formatSol(it)) }
        is SendAsset.Token -> splBalances[a.token.mint]?.let { stringResource(R.string.sol_available_token, a.token.format(it), a.token.symbol) }
    }

    // Native-SOL fiat unit price (mainnet only; never for SPL tokens).
    LaunchedEffect(asset) {
        solUnitPrice = if (asset is SendAsset.Native) FiatReference.unitPrice(network.coinGeckoAssetId) else null
        if (solUnitPrice == null) fiatEntry = false
    }

    // When entering in fiat, convert to a SOL string; everything downstream
    // (parseSolToLamports, rent check) stays in SOL.
    val fiatActive = fiatEntry && solUnitPrice != null && asset is SendAsset.Native
    val solAmountInput: String = if (fiatActive) {
        amount.toDoubleOrNull()?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.9f", it / solUnitPrice!!) } ?: ""
    } else {
        amount
    }

    // Rent-exempt caption for native sends to a brand-new account (iOS guard).
    val rentExemptCaption: String? = run {
        if (asset !is SendAsset.Native) return@run null
        val lamports = parseSolToLamports(solAmountInput)
        if (lamports != null && lamports in 1 until SolanaWallet.RENT_EXEMPT_MINIMUM_LAMPORTS) {
            stringResource(R.string.sol_rent_exempt_caption)
        } else {
            null
        }
    }

    // SNS (.sol) resolution, debounced. SNS names live on Solana mainnet, so we
    // always resolve against the mainnet RPC even on devnet/testnet (mirrors ENS).
    var resolvedSNS by remember { mutableStateOf<String?>(null) }
    var snsResolving by remember { mutableStateOf(false) }
    var snsError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(recipient) {
        resolvedSNS = null; snsError = null
        val trimmed = recipient.trim()
        if (!SolanaNameResolver.looksLikeName(trimmed)) { snsResolving = false; return@LaunchedEffect }
        snsResolving = true
        delay(400)
        val rpc = env.settings.rpcURL(SolanaNetwork.MAINNET)
        val result = withContext(Dispatchers.IO) { runCatching { SolanaNameResolver(rpc).resolve(trimmed) } }
        snsResolving = false
        result.onSuccess { resolvedSNS = it }.onFailure { snsError = it.message ?: it.toString() }
    }
    // The address actually sent to: the resolved SNS owner, or the typed address.
    val effectiveRecipient: String = resolvedSNS ?: recipient.trim()
    val recipientResolvedOrValid =
        if (SolanaNameResolver.looksLikeName(recipient)) resolvedSNS != null
        else SolanaPrimitives.isValidAddress(recipient.trim())

    val canSubmit = solanaSendReady(
        recipientValidOrResolved = recipientResolvedOrValid,
        amountInput = solAmountInput,
        tokenDecimals = (asset as? SendAsset.Token)?.token?.decimals,
    )

    // Pre-sign device-ready sheet + host-typed-hidden detection (Trezor).
    var showReadySheet by remember { mutableStateOf(false) }
    val needsHostPassphrase = remember(descriptor.id) {
        com.elabify.musnad.hardware.trezor.HardwarePassphraseRef.fromJson(descriptor.hidden)?.needsHostPassphrase ?: false
    }
    // Own Solana wallets for the recipient picker's "Your wallets" section.
    val sandwich = remember { env.loadSandwich() }
    var ownWallets by remember { mutableStateOf<List<com.elabify.app.maknoon.ui.settings.OwnWalletEntry>>(emptyList()) }
    LaunchedEffect(showContacts) {
        if (!showContacts || ownWallets.isNotEmpty()) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            env.walletStore.wallets.mapNotNull { w ->
                runCatching {
                    val addr = when (val k = w.kind) {
                        is SolanaWalletKind.Hardware -> k.publicKeyBase58
                        is SolanaWalletKind.Software -> sandwich?.let { com.elabify.musnad.wallet.solana.SolanaDescriptors.addressFromSandwich(it, k.account) }
                    } ?: return@mapNotNull null
                    com.elabify.app.maknoon.ui.settings.OwnWalletEntry(name = w.label, address = addr)
                }.getOrNull()
            }
        }
        ownWallets = resolved
    }

    // Build / sign / broadcast. Hardware routes the signature onto the device
    // (SolanaDeviceSigner) re-applying the wallet's hidden passphrase + path;
    // software signs with the seed. Biometric gates software only.
    fun submit(hostPassphrase: String?) {
        state = SendState.Working
        val priority = priorityFee.toLongOrNull() ?: 0L
        scope.launch {
            if (!isHardware && !authorizeSend(context, "Solana")) { state = SendState.Idle; return@launch }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val w = env.openWallet(descriptor)
                    val hwKind = descriptor.kind as? SolanaWalletKind.Hardware
                    if (hwKind != null) {
                        val device = com.elabify.musnad.devices.DeviceRegistry(context).find(hwKind.deviceId)
                            ?: throw IllegalStateException("The device that holds this wallet is no longer registered. Re-register it under Settings, Devices.")
                        val signerBase58 = hwKind.publicKeyBase58
                        val signerPublicKey = com.elabify.musnad.wallet.solana.SolanaPrimitives.base58Decode(signerBase58)
                            ?: throw IllegalStateException("Could not decode the wallet address.")
                        val ledger = SolanaDeviceSigner(
                            device = device,
                            hidden = com.elabify.musnad.hardware.trezor.HardwarePassphraseRef.fromJson(descriptor.hidden),
                            derivationPath = descriptor.derivationPath,
                            hostPassphrase = hostPassphrase,
                        )
                        // SIGN only (no broadcast); user confirms broadcast.
                        when (val a = asset) {
                            is SendAsset.Native -> {
                                val lamports = parseSolToLamports(solAmountInput) ?: throw SolanaDescriptorException("Enter a valid SOL amount")
                                w.assertRentExemptForNativeTransfer(effectiveRecipient, lamports)
                                Triple(w.prepareHardwareNative(effectiveRecipient, lamports, priority, ledger, signerBase58, signerPublicKey, hwKind.account), lamports, null as SendAsset.Token?)
                            }
                            is SendAsset.Token -> {
                                val raw = parseTokenToRaw(amount, a.token.decimals) ?: throw SolanaDescriptorException("Enter a valid ${a.token.symbol} amount")
                                Triple(w.prepareHardwareSPLToken(a.token.mint, a.token.decimals, raw, effectiveRecipient, priority, ledger, signerBase58, signerPublicKey, hwKind.account), raw, a)
                            }
                        }
                    } else {
                        when (val a = asset) {
                            is SendAsset.Native -> {
                                val lamports = parseSolToLamports(solAmountInput) ?: throw SolanaDescriptorException("Enter a valid SOL amount")
                                w.assertRentExemptForNativeTransfer(effectiveRecipient, lamports)
                                Triple(w.prepareSoftware(effectiveRecipient, lamports, priority), lamports, null as SendAsset.Token?)
                            }
                            is SendAsset.Token -> {
                                val raw = parseTokenToRaw(amount, a.token.decimals) ?: throw SolanaDescriptorException("Enter a valid ${a.token.symbol} amount")
                                Triple(w.prepareSoftwareSPLToken(mint = a.token.mint, decimals = a.token.decimals, rawAmount = raw, recipient = effectiveRecipient, priorityFeeMicroLamports = priority), raw, a)
                            }
                        }
                    }
                }
            }
            result
                .onSuccess { (signedBase64, amountUnits, tok) ->
                    state = SendState.Signed(
                        signedBase64 = signedBase64,
                        recipient = effectiveRecipient,
                        amountUnits = amountUnits,
                        tokenMint = tok?.token?.mint,
                        tokenSymbol = tok?.token?.symbol,
                        tokenDecimals = tok?.token?.decimals,
                    )
                }
                .onFailure { state = SendState.Failed(it.message ?: it.toString()) }
        }
    }

    // Broadcast the already-signed tx (separate user action, ADR-0033).
    fun broadcast(s: SendState.Signed) {
        state = SendState.Working
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { env.openWallet(descriptor).broadcastSignedBase64(s.signedBase64) }
            }
            result.onSuccess { sig ->
                runCatching {
                    val from = withContext(Dispatchers.IO) { env.openWallet(descriptor).resolvedAddress() }
                    env.walletStore.markPendingOutbound(
                        senderWalletId = descriptor.id,
                        signature = sig,
                        senderAddress = from,
                        recipientAddress = s.recipient,
                        lamports = s.amountUnits,
                        tokenMint = s.tokenMint,
                        tokenSymbol = s.tokenSymbol,
                        tokenDecimals = s.tokenDecimals,
                    )
                }
                state = SendState.Done(sig)
            }.onFailure {
                // Broadcast-only failure: keep the signed tx for a re-push
                // without re-signing (ADR-0033).
                state = SendState.Failed(it.message ?: it.toString(), retry = s)
            }
        }
    }

    SendFormScaffold(onCancel = onBack) {
        // network/header
        FormSection {
            WalletSelector(
                label = descriptor.label,
                subtitle = if (isHardware) stringResource(R.string.sol_hardware_device) else null,
                networkName = network.displayName,
                networkTint = MaknoonBrand.accent,
            )
        }

        // token picker (multi-asset only)
        if (tokens.isNotEmpty()) {
            FormSection(header = stringResource(R.string.sol_token)) {
                AssetPicker(
                    options = assetOptions,
                    selected = selectedOption,
                    label = stringResource(R.string.walletc_send),
                    onSelect = { opt ->
                        asset = if (opt.id == "sol") {
                            SendAsset.Native
                        } else {
                            tokens.firstOrNull { it.id == opt.id }?.let { SendAsset.Token(it) } ?: SendAsset.Native
                        }
                    },
                )
            }
        }

        // recipient
        FormSection(header = stringResource(R.string.sol_recipient)) {
            RecipientField(
                value = recipient,
                onValueChange = { recipient = it },
                onPaste = { clipboard.getText()?.text?.let { recipient = stripSolanaPrefix(it) } },
                onScanQr = { showScanner = true },
                placeholder = stringResource(R.string.sol_recipient_placeholder),
                onPickContact = { showContacts = true },
                supporting = {
                    when {
                        snsResolving -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.sol_resolving_name), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        resolvedSNS != null -> Text(stringResource(R.string.sol_resolves_to, shortAddress(resolvedSNS!!)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        snsError != null -> Text(snsError!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        recipient.isNotBlank() && !SolanaNameResolver.looksLikeName(recipient) &&
                            AddressNetworkGuard.detect(recipient) != null -> Text(
                            stringResource(
                                R.string.wallet_wrong_network_address,
                                AddressNetworkGuard.detect(recipient)!!.displayName,
                                "Solana",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        recipient.isNotBlank() && !SolanaNameResolver.looksLikeName(recipient) && !isValidSolanaAddress(recipient) -> Text(
                            stringResource(R.string.sol_invalid_address),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }

        // amount
        FormSection(header = stringResource(R.string.walletc_amount)) {
            // Fiat unit entry only for native SOL on mainnet (solUnitPrice set).
            val showUnitPicker = asset is SendAsset.Native && solUnitPrice != null
            val fiatId = FiatReference.fiatLabel()
            val typedAmt = amount.toDoubleOrNull()?.takeIf { it > 0 }
            val amountSecondary = when {
                !showUnitPicker || typedAmt == null -> null
                fiatActive -> "≈ ${String.format(Locale.US, "%.6f", typedAmt / solUnitPrice!!)} SOL"
                else -> "≈ " + FiatReference.format(typedAmt * solUnitPrice!!)
            }
            AmountField(
                value = amount,
                onValueChange = { amount = it },
                onMax = null,
                unitLabel = if (showUnitPicker) null else asset.label,
                secondaryLabel = amountSecondary,
                denomination = if (showUnitPicker) {
                    {
                        val solLabel = stringResource(R.string.sol_sol_ticker)
                        val unitOpts = listOf(
                            AssetOption(id = "sol", symbol = solLabel, label = solLabel),
                            AssetOption(id = fiatId, symbol = fiatId, label = fiatId),
                        )
                        AssetPicker(
                            options = unitOpts,
                            selected = unitOpts.first { it.id == (if (fiatEntry) fiatId else "sol") },
                            onSelect = { fiatEntry = it.id == fiatId },
                            label = stringResource(R.string.sol_unit),
                            modifier = Modifier.width(140.dp),
                        )
                    }
                } else null,
                balanceLabel = balanceLabel,
            )
            if (rentExemptCaption != null) {
                Text(
                    rentExemptCaption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // advanced
        FormSection(header = stringResource(R.string.sol_advanced)) {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showAdvanced) stringResource(R.string.sol_hide_priority_fee) else stringResource(R.string.sol_show_priority_fee))
            }
            if (showAdvanced) {
                Text(
                    stringResource(R.string.sol_priority_fee_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = priorityFee,
                    onValueChange = { priorityFee = it.filter { c -> c.isDigit() } },
                    placeholder = { Text("0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.sol_priority_fee_help),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // review
        FormSection(header = stringResource(R.string.sol_review)) {
            ReviewRow(
                label = stringResource(R.string.common_network),
                value = network.displayName,
                valueColor = MaknoonBrand.accent,
            )
            ReviewRow(
                label = stringResource(R.string.sol_pay_to),
                value = if (recipient.isBlank()) "-" else shortAddress(recipient, 6, 4),
                mono = true,
            )
            ReviewRow(
                label = stringResource(R.string.walletc_amount),
                value = if (amount.isBlank()) "-" else "$amount ${asset.label}",
                mono = true,
            )
            ReviewRow(
                label = stringResource(R.string.sol_network_fee),
                value = priorityFeeReviewValue(context, priorityFee),
            )
        }

        // primary action + error/status
        when (val s = state) {
            is SendState.Idle, is SendState.Failed -> {
                val retry = (s as? SendState.Failed)?.retry
                if (s is SendState.Failed) {
                    Banner(
                        variant = BannerVariant.ERROR,
                        title = if (retry != null) stringResource(R.string.sol_broadcast_failed) else stringResource(R.string.sol_send_failed),
                        body = s.message,
                    )
                }
                if (retry != null) {
                    // Broadcast-only failure: re-push the SAME signed tx (no
                    // re-sign, no device prompt).
                    PrimaryActionButton(text = stringResource(R.string.sol_retry_broadcast), onClick = { broadcast(retry) })
                    OutlinedButton(onClick = { state = SendState.Idle }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_start_over)) }
                } else {
                    PrimaryActionButton(
                        text = if (isHardware) stringResource(R.string.sol_sign_using_device) else stringResource(R.string.walletc_send),
                        enabled = canSubmit,
                        onClick = {
                            // Hardware: open the pre-sign device-ready sheet first;
                            // software submits directly (biometric-gated in submit).
                            if (isHardware) showReadySheet = true else submit(null)
                        },
                    )
                }
            }

            is SendState.Signed -> {
                Banner(
                    variant = BannerVariant.INFO,
                    title = stringResource(R.string.sol_signed_ready),
                    body = stringResource(R.string.sol_signed_ready_body),
                )
                PrimaryActionButton(text = stringResource(R.string.sol_broadcast_transaction), onClick = { broadcast(s) })
                OutlinedButton(onClick = { state = SendState.Idle }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
            }

            is SendState.Working -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.sol_building_signing_broadcasting), style = MaterialTheme.typography.bodyMedium)
                }
            }

            is SendState.Done -> {
                Banner(
                    variant = BannerVariant.SUCCESS,
                    title = stringResource(R.string.sol_sent),
                    body = s.signature,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = { copyToClipboard(context, "Signature", s.signature) }) { Text(stringResource(R.string.sol_copy_signature)) }
                    PrimaryActionButton(text = stringResource(R.string.common_done), onClick = onBack, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showScanner) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showScanner = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.sol_scan_address), style = MaterialTheme.typography.titleMedium)
                MiniAppQrScanner(
                    continuous = false,
                    onCode = { code ->
                        recipient = stripSolanaPrefix(code)
                        showScanner = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp)),
                )
                QrPhotoPickerButton(
                    onCode = { code ->
                        recipient = stripSolanaPrefix(code)
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

    if (showContacts) {
        ContactsPickerSheet(
            network = AddressBookNetwork.SOLANA,
            onPick = { recipient = it },
            onDismiss = { showContacts = false },
            ownWallets = ownWallets,
        )
    }

    // Pre-sign device-ready confirmation for hardware wallets (ADR-0033).
    if (showReadySheet) {
        val hw = descriptor.kind as? SolanaWalletKind.Hardware
        val device = hw?.let { com.elabify.musnad.devices.DeviceRegistry(context).find(it.deviceId) }
        if (device == null) {
            showReadySheet = false
            state = SendState.Failed("The device that holds this wallet is no longer registered. Re-register it under Settings, Devices.")
        } else {
            com.elabify.app.maknoon.ui.wallet.common.HardwareSignReadySheet(
                deviceKind = device.kind,
                deviceLabel = device.label,
                deviceSerialDisplay = device.serialDisplay,
                readiness = com.elabify.app.maknoon.ui.wallet.common.HardwareSignAppReadiness.solana,
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

/** Review-row value for the priority fee (iOS priorityFeeReviewValue). */
private fun priorityFeeReviewValue(context: Context, priorityFee: String): String {
    val micro = priorityFee.toLongOrNull() ?: 0L
    return if (micro == 0L) {
        context.getString(R.string.sol_priority_fee_default)
    } else {
        context.getString(R.string.sol_priority_fee_value, micro.toString())
    }
}

/** Strip a "solana:" URI prefix + query string from a scanned / pasted value
 *  (iOS stripSolanaPrefix). */
private fun stripSolanaPrefix(s: String): String =
    com.elabify.app.maknoon.ui.wallet.PaymentURIStrip.solana(s)

/** Strict Solana address check for the recipient validation caption: a real
 *  base58 decode to a 32-byte key (SolanaPrimitives), so the caption agrees
 *  with the submit gate and a well-formed address from another network (Tron /
 *  Bitcoin base58 decode to 25 bytes) is flagged, not just charset-checked. */
private fun isValidSolanaAddress(s: String): Boolean =
    SolanaPrimitives.isValidAddress(s.trim())
