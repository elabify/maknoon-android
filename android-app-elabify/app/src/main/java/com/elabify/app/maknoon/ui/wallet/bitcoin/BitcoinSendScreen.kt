// Send screen, ported from iOS BitcoinSendView. Pay-to address (+ paste),
// label, amount (BTC / sats), fee tiers (Fastest / 30 min / 1 hour /
// Economy / Custom from BitcoinFeeEstimator), RBF toggle, coin-control
// (manual UTXO selection via the UTXO picker), a BDK-driven Max-drain
// preview, and a review block. The send flow runs as a two-step state
// machine: build the unsigned PSBT + sign (software signs in-app via
// BitcoinSigningHelpers; hardware surfaces the engine's BLE-not-implemented
// hook), then broadcast as a separate step so a flaky network at broadcast
// time doesn't lose the signature. An "Or sign offline (PSBT QR)" path
// opens the offline-PSBT sheet (the universal hardware-wallet route).

package com.elabify.app.maknoon.ui.wallet.bitcoin

import java.util.Locale

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.wallet.AddressFamily
import com.elabify.app.maknoon.ui.wallet.AddressNetworkGuard
import com.elabify.app.maknoon.ui.wallet.common.AssetOption
import com.elabify.app.maknoon.ui.wallet.common.AssetPicker
import com.elabify.app.maknoon.ui.wallet.common.AmountField
import com.elabify.app.maknoon.ui.wallet.common.FeeOption
import com.elabify.app.maknoon.ui.wallet.common.FeeSelector
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.FormSection
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignAppReadiness
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignReadySheet
import com.elabify.app.maknoon.ui.wallet.common.PrimaryActionButton
import com.elabify.app.maknoon.ui.wallet.common.authorizeSend
import com.elabify.app.maknoon.ui.settings.AddressBookNetwork
import com.elabify.app.maknoon.ui.settings.ContactsPickerSheet
import com.elabify.app.maknoon.ui.wallet.common.RecipientField
import com.elabify.app.maknoon.ui.wallet.common.ReviewRow
import com.elabify.app.maknoon.ui.wallet.common.SendFormScaffold
import com.elabify.app.maknoon.ui.wallet.common.WalletSelector
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.wallet.bitcoin.BitcoinFeeEstimator
import com.elabify.musnad.wallet.bitcoin.BitcoinSigningHelpers
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletException
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.musnad.wallet.bitcoin.FeeRecommended
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The fee tiers shown in the segmented picker, mirroring iOS FeeMode. `id`
 *  is the stable key carried through the shared FeeSelector; it is also the
 *  cross-platform wire value, so it must never change. */
private enum class SendFeeMode(val id: String) {
    FASTEST("fastest"),
    HALF_HOUR("halfHour"),
    HOUR("hour"),
    ECONOMY("economy"),
    CUSTOM("custom");

    // Copy lives in string RESOURCES, not in this enum: an enum has no
    // Context and cannot call stringResource, so the chip labels shipped
    // English in all 31 locales. Same shape as IDDocumentKind's
    // displayNameRes (iddocument/IDDocument.kt). `id` above is unaffected.
    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            FASTEST -> R.string.btc_fee_fastest
            HALF_HOUR -> R.string.btc_fee_half_hour
            HOUR -> R.string.btc_fee_hour
            ECONOMY -> R.string.btc_fee_economy
            CUSTOM -> R.string.walletc_custom
        }

    fun rate(rec: FeeRecommended?): Long = when (this) {
        FASTEST -> rec?.fastestFee ?: 0
        HALF_HOUR -> rec?.halfHourFee ?: 0
        HOUR -> rec?.hourFee ?: 0
        ECONOMY -> rec?.economyFee ?: 0
        CUSTOM -> 0
    }

    companion object {
        fun fromId(id: String): SendFeeMode = entries.firstOrNull { it.id == id } ?: HALF_HOUR
    }
}

/** The send state machine, mirroring iOS BitcoinSendView.SendState. */
private sealed class SendState {
    object Idle : SendState()
    object Signing : SendState()
    data class Signed(val signed: String, val unsigned: String) : SendState()
    object Broadcasting : SendState()
    data class Done(val txid: String) : SendState()

    /** A failure. When [retry] is non-null the signing already succeeded and
     *  only the broadcast RPC failed, so the transaction can be re-submitted
     *  WITHOUT re-signing (no second hardware prompt). When null the failure
     *  happened during build / sign and the user must start over. */
    data class Failed(val message: String, val retry: Signed? = null) : SendState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinSendScreen(
    env: BitcoinWalletEnv,
    active: BitcoinWalletDescriptor?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var engine by remember { mutableStateOf<BitcoinWalletEngine?>(null) }
    var balanceSat by remember { mutableStateOf<Long?>(null) }
    var address by remember { mutableStateOf("") }
    var showContacts by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    // The user's own Bitcoin wallets on the ACTIVE chain, resolved to receive
    // addresses, shown as the "Your wallets" section of the recipient picker.
    // Filtered to active.network so a Testnet3 send never lists Mainnet wallets
    // (and vice versa). Resolved lazily the first time the picker opens.
    var ownWallets by remember(active?.network) { mutableStateOf<List<com.elabify.app.maknoon.ui.settings.OwnWalletEntry>>(emptyList()) }
    var label by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }
    var denomination by remember { mutableStateOf("BTC") }
    // BTC price in the selected fiat, mainnet only; null hides the fiat option.
    var btcFiatUnit by remember(active?.network) { mutableStateOf<Double?>(null) }
    var feeMode by remember { mutableStateOf(SendFeeMode.HALF_HOUR) }
    var customSatsPerVb by remember { mutableStateOf("5") }
    var feeRec by remember { mutableStateOf<FeeRecommended?>(null) }
    var rbf by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    var coinControl by remember { mutableStateOf(false) }
    var selectedUtxos by remember { mutableStateOf<Set<UtxoKey>>(emptySet()) }
    var selectedUtxoTotal by remember { mutableStateOf(0L) }
    var maxDrainSat by remember { mutableStateOf(0L) }
    var showUtxoPicker by remember { mutableStateOf(false) }
    var showOffline by remember { mutableStateOf(false) }
    var sendState by remember { mutableStateOf<SendState>(SendState.Idle) }
    // The pre-sign device-ready confirmation (ADR-0033). Opened when the user
    // taps "Sign using hardware wallet"; it hosts the readiness copy and the
    // conditional host-typed hidden-wallet passphrase entry (never stored).
    var showReadySheet by remember { mutableStateOf(false) }

    val isSoftware = active?.softwareAccountOrNull() != null
    // The hardware sign button's label carries a %1$s for the device name. It
    // was fetched with no argument, so the raw placeholder rendered on the
    // button in EVERY language, English included. iOS passes
    // `boundDevice?.label ?? "device"` here; this is its peer.
    val fallbackDeviceName = stringResource(R.string.devices_fallback_device)
    val boundDeviceLabel = remember(active?.id) {
        (active?.kind as? BitcoinWalletKind.Hardware)?.let { hw ->
            DeviceRegistry(context).find(hw.deviceId)?.label
        } ?: fallbackDeviceName
    }
    // A host-typed hidden (second-factor) wallet needs the passphrase re-entered
    // before signing; on-device entry + standard wallets do not.
    val needsHostPassphrase = remember(active?.id) {
        active?.let { HardwarePassphraseRef.fromJson(it.hidden)?.needsHostPassphrase } ?: false
    }

    LaunchedEffect(active?.id) {
        val descriptor = active ?: return@LaunchedEffect
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val words = loadRecoveryWords(context)
                BitcoinWalletEngine.open(descriptor, env.filesDirPath, words, loadBip39Passphrase(context))
            }.getOrNull()
        }
        engine = opened
        opened?.let { e ->
            balanceSat = withContext(Dispatchers.IO) { runCatching { e.balance().total.toSat().toLong() }.getOrNull() }
        }
        val base = env.settings.mempoolURL(descriptor.network)
        feeRec = withContext(Dispatchers.IO) { BitcoinFeeEstimator.fetch(base) }
    }

    // BTC fiat unit price for the optional fiat denomination (mainnet only).
    LaunchedEffect(active?.network) {
        btcFiatUnit = if (active?.network?.coinType == 0L) FiatReference.unitPrice("bitcoin") else null
    }

    // Resolve the user's own wallets for the active chain into receive
    // addresses for the recipient picker, the first time it is opened. Each
    // resolve opens the BDK wallet (watch-only from the cached / hardware xpub)
    // and peeks the next-unused receive address; software wallets without a
    // cached xpub fall back to the unlocked recovery words. Runs off-main.
    LaunchedEffect(showContacts, active?.network) {
        if (!showContacts || ownWallets.isNotEmpty()) return@LaunchedEffect
        val net = active?.network ?: return@LaunchedEffect
        val words = withContext(Dispatchers.IO) { loadRecoveryWords(context) }
        val resolved = withContext(Dispatchers.IO) {
            env.store.wallets.filter { it.network == net }.mapNotNull { w ->
                runCatching {
                    val e = BitcoinWalletEngine.open(w, env.filesDirPath, words, loadBip39Passphrase(context))
                    val addr = e.nextUnusedReceiveAddress().address.toString()
                    com.elabify.app.maknoon.ui.settings.OwnWalletEntry(name = w.label, address = addr)
                }.getOrNull()
            }
        }
        ownWallets = resolved
    }

    val effectiveSatsPerVb: Long = remember(feeMode, customSatsPerVb, feeRec) {
        when (feeMode) {
            SendFeeMode.CUSTOM -> customSatsPerVb.trim().toLongOrNull() ?: 0
            else -> feeMode.rate(feeRec)
        }
    }
    val fiatId = FiatReference.fiatLabel()
    val amountSats: Long = remember(amountInput, denomination, btcFiatUnit, fiatId) {
        parseAmountSats(amountInput, denomination, fiatId, btcFiatUnit)
    }

    // Refresh the BDK max-drain preview when inputs change (mirrors iOS).
    LaunchedEffect(engine, effectiveSatsPerVb, coinControl, selectedUtxos) {
        val e = engine ?: return@LaunchedEffect
        val descriptor = active ?: return@LaunchedEffect
        if (effectiveSatsPerVb <= 0) { maxDrainSat = 0; return@LaunchedEffect }
        maxDrainSat = withContext(Dispatchers.IO) {
            runCatching {
                val placeholder = e.nextUnusedReceiveAddress().address.toString()
                val outpoints = if (coinControl && selectedUtxos.isNotEmpty()) selectedUtxos.toOutpoints() else null
                e.previewMaxDrainSat(placeholder, effectiveSatsPerVb, outpoints)
            }.getOrDefault(0L)
        }
        // Refresh selected-UTXO total.
        selectedUtxoTotal = withContext(Dispatchers.IO) {
            runCatching {
                e.listUnspent().filter { u ->
                    selectedUtxos.contains(UtxoKey(u.outpoint.txid.toString(), u.outpoint.vout.toInt()))
                }.sumOf { it.txout.value.toSat().toLong() }
            }.getOrDefault(0L)
        }
    }

    val estVbytes = remember(coinControl, selectedUtxos) {
        val inputs = if (coinControl && selectedUtxos.isNotEmpty()) selectedUtxos.size.toLong() else 1L
        11 + inputs * 68 + 31 * 2
    }
    val estFeeSat = if (effectiveSatsPerVb > 0) estVbytes * effectiveSatsPerVb else null
    val totalCostSat = if (amountSats > 0 && estFeeSat != null) amountSats + estFeeSat else null
    // Hard-block an address that clearly belongs to another network (e.g. a
    // pasted Ethereum/Tron address) at the UI, instead of only failing later
    // when BDK rejects it at build time.
    val wrongNetwork = AddressNetworkGuard.crossNetworkMismatch(address, AddressFamily.BITCOIN)
    val canSubmit = address.isNotEmpty() && wrongNetwork == null && amountSats > 0 && effectiveSatsPerVb > 0

    // Bitcoin's per-chain network tint (iOS uses .orange for the header chip
    // and the Network review row). MaknoonColors.warning is the design-token
    // orange.
    val btcTint = MaknoonColors.warning

    val overBalance = totalCostSat != null && balanceSat != null && totalCostSat > balanceSat!!

    SendFormScaffold(onCancel = onClose, title = stringResource(R.string.walletc_send)) {
        if (active == null) {
            FormSection { Text(stringResource(R.string.btc_no_wallet)) }
            return@SendFormScaffold
        }

        // 1. Header: active wallet + network chip (iOS headerSection).
        FormSection {
            WalletSelector(
                label = active.label,
                subtitle = if (isSoftware) null else stringResource(R.string.btc_hardware_wallet),
                networkName = active.network.displayName,
                networkTint = btcTint,
            )
        }

        // The host-typed hidden-wallet passphrase is no longer entered inline;
        // it is collected in the pre-sign HardwareSignReadySheet (ADR-0033),
        // fresh for the signing and never stored.

        // 2. Pay to: address row (paste + QR scan) + optional label (iOS payToSection).
        FormSection(header = stringResource(R.string.btc_pay_to)) {
            RecipientField(
                value = address,
                onValueChange = { address = stripBitcoinPrefix(it) },
                onPaste = {
                    clipboard.getText()?.text?.let { pasted ->
                        applyBitcoinPayload(
                            raw = pasted,
                            onAddress = { address = it },
                            onAmountBtc = { amountInput = it; denomination = "BTC" },
                        )
                    }
                },
                onScanQr = { showScanner = true },
                placeholder = stringResource(R.string.btc_bech32_placeholder),
                onPickContact = { showContacts = true },
                supporting = wrongNetwork?.let { fam ->
                    {
                        Text(
                            stringResource(R.string.wallet_wrong_network_address, fam.displayName, "Bitcoin"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
            if (showScanner) {
                BitcoinScanSheet(
                    onScanned = { payload ->
                        applyBitcoinPayload(
                            raw = payload,
                            onAddress = { address = it },
                            onAmountBtc = { amountInput = it; denomination = "BTC" },
                        )
                        showScanner = false
                    },
                    onDismiss = { showScanner = false },
                )
            }
            if (showContacts) {
                ContactsPickerSheet(
                    network = AddressBookNetwork.BITCOIN,
                    bitcoinNetwork = active.network,
                    ownWallets = ownWallets,
                    onPick = { address = it },
                    onDismiss = { showContacts = false },
                )
            }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.btc_label_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 3. Amount: amount field + BTC/sats denomination picker + Max + captions
        //    (iOS amountSection).
        FormSection(header = stringResource(R.string.walletc_amount)) {
            val denomOptions = buildList {
                add(AssetOption(id = "BTC", symbol = "BTC", label = "BTC"))
                add(AssetOption(id = "sats", symbol = "sats", label = "sats"))
                // Fiat denomination only on mainnet with a live price (btcFiatUnit).
                if (btcFiatUnit != null) add(AssetOption(id = fiatId, symbol = fiatId, label = fiatId))
            }
            val selectedDenom = denomOptions.firstOrNull { it.id == denomination } ?: denomOptions.first()
            val balanceLabel =
                if (coinControl && selectedUtxos.isNotEmpty()) {
                    stringResource(
                        R.string.btc_available_from_selected,
                        formatBtc(selectedUtxoTotal),
                        formatSats(selectedUtxoTotal),
                        selectedUtxos.size.toString(),
                    )
                } else {
                    balanceSat?.let { stringResource(R.string.btc_available, formatBtc(it), formatSats(it)) }
                        ?: stringResource(R.string.btc_available_loading)
                }
            AmountField(
                value = amountInput,
                onValueChange = { amountInput = it },
                onMax = if (maxDrainSat > 0) {
                    { amountInput = applyMax(maxDrainSat, denomination, fiatId, btcFiatUnit) }
                } else null,
                balanceLabel = balanceLabel,
                secondaryLabel = sendSecondaryLabel(amountSats, denomination, fiatId, btcFiatUnit),
                denomination = {
                    AssetPicker(
                        options = denomOptions,
                        selected = selectedDenom,
                        onSelect = { denomination = it.id },
                        label = stringResource(R.string.btc_unit),
                        modifier = Modifier.width(140.dp),
                    )
                },
            )
        }

        // 4. Fee: tier chips (Fastest / 30 min / 1 hour / Economy / Custom) +
        //    rate caption or custom sats/vB field (iOS feeSection).
        FormSection(header = stringResource(R.string.btc_fee)) {
            FeeSelector(
                options = SendFeeMode.entries.map { FeeOption(it.id, stringResource(it.labelRes)) },
                selected = feeMode.id,
                onSelect = { feeMode = SendFeeMode.fromId(it) },
                footer = {
                    if (feeMode == SendFeeMode.CUSTOM) {
                        OutlinedTextField(
                            value = customSatsPerVb,
                            onValueChange = { customSatsPerVb = it },
                            label = { Text(stringResource(R.string.btc_sats_per_vb)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            feeRec?.let { stringResource(R.string.btc_n_sats_per_vb, effectiveSatsPerVb.toString()) }
                                ?: stringResource(R.string.btc_loading_recommended_fees),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }

        // 5. Advanced: RBF toggle + coin control (iOS advancedSection).
        FormSection {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) stringResource(R.string.btc_advanced_collapse) else stringResource(R.string.btc_advanced_expand))
            }
            if (showAdvanced) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.btc_signal_rbf), Modifier.weight(1f))
                    Switch(checked = rbf, onCheckedChange = { rbf = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.btc_coin_control), Modifier.weight(1f))
                    Switch(checked = coinControl, onCheckedChange = { coinControl = it })
                }
                if (coinControl) {
                    OutlinedButton(onClick = { showUtxoPicker = true }) {
                        Text(if (selectedUtxos.isEmpty()) stringResource(R.string.btc_select_utxos) else pluralStringResource(R.plurals.btc_n_utxos_selected, selectedUtxos.size, selectedUtxos.size.toString()))
                    }
                }
            }
        }

        // 6. Review: Network, Pay to, Amount, Max fee, Total (iOS reviewSection).
        FormSection(header = stringResource(R.string.btc_review)) {
            ReviewRow(stringResource(R.string.common_network), active.network.displayName, valueColor = btcTint)
            ReviewRow(stringResource(R.string.btc_pay_to), if (address.isEmpty()) "-" else shortMiddle(address, 10, 8), mono = true)
            ReviewRow(stringResource(R.string.walletc_amount), if (amountSats > 0) "${formatBtc(amountSats)} BTC" else "-", mono = true)
            ReviewRow(stringResource(R.string.btc_max_fee), estFeeSat?.let { "${formatBtc(it)} BTC (${formatSats(it)})" } ?: "-", mono = true)
            ReviewRow(
                stringResource(R.string.btc_total),
                totalCostSat?.let { "${formatBtc(it)} BTC" } ?: "-",
                mono = true,
                valueColor = if (overBalance) MaknoonColors.error else null,
            )
            if (overBalance) {
                Text(
                    stringResource(R.string.btc_total_exceeds_balance),
                    color = MaknoonColors.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // 7. Error / status banner (iOS failed / status sections).
        when (val s = sendState) {
            is SendState.Failed -> Banner(title = s.message, variant = BannerVariant.ERROR)
            is SendState.Done -> Banner(
                title = stringResource(R.string.btc_broadcast_txid, shortMiddle(s.txid, 12, 0)),
                variant = BannerVariant.SUCCESS,
            )
            else -> {}
        }

        // 8. Primary action area (the send state machine, iOS primaryActionButtons).
        FormSection {
            when (val s = sendState) {
                is SendState.Idle -> {
                    PrimaryActionButton(
                        text = if (isSoftware) stringResource(R.string.btc_sign)
                        else stringResource(R.string.btc_sign_using_hardware, boundDeviceLabel),
                        enabled = canSubmit,
                        onClick = {
                            val e = engine ?: return@PrimaryActionButton
                            if (!isSoftware) {
                                // Hardware: open the pre-sign device-ready
                                // confirmation. Signing runs on Continue with
                                // the typed passphrase (ADR-0033).
                                showReadySheet = true
                                return@PrimaryActionButton
                            }
                            scope.launch {
                                // Software signing unlocks the local key; gate it
                                // behind biometric like iOS.
                                if (!authorizeSend(context, "Bitcoin")) return@launch
                                sendState = SendState.Signing
                                val result = signBitcoin(
                                    env, e, active, address, amountSats, effectiveSatsPerVb,
                                    rbf, if (coinControl) selectedUtxos else emptySet(), isSoftware,
                                    null, context,
                                )
                                sendState = result
                            }
                        },
                    )
                    OutlinedButton(
                        enabled = canSubmit,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showOffline = true },
                    ) { Text(if (isSoftware) stringResource(R.string.btc_sign_on_another_device) else stringResource(R.string.btc_sign_offline_psbt_qr)) }
                }
                is SendState.Signing -> PrimaryActionButton(
                    text = if (isSoftware) stringResource(R.string.btc_signing_locally) else stringResource(R.string.btc_signing_on_device),
                    loading = true,
                    onClick = {},
                )
                is SendState.Signed -> PrimaryActionButton(
                    text = stringResource(R.string.btc_broadcast_transaction),
                    onClick = {
                        val e = engine ?: return@PrimaryActionButton
                        scope.launch {
                            sendState = SendState.Broadcasting
                            val r = broadcastBitcoin(env, e, active, s.signed, s.unsigned, address, label)
                            sendState = r
                            if (r is SendState.Done) onClose()
                        }
                    },
                )
                is SendState.Broadcasting -> PrimaryActionButton(
                    text = stringResource(R.string.btc_broadcasting),
                    loading = true,
                    onClick = {},
                )
                is SendState.Done -> {}
                is SendState.Failed -> {
                    val retry = s.retry
                    if (retry != null) {
                        // Broadcast-only failure: the signed PSBT is intact, so
                        // re-push the SAME bytes (no re-sign, no device prompt).
                        PrimaryActionButton(
                            text = stringResource(R.string.btc_retry_broadcast),
                            onClick = {
                                val e = engine ?: return@PrimaryActionButton
                                scope.launch {
                                    sendState = SendState.Broadcasting
                                    val r = broadcastBitcoin(env, e, active, retry.signed, retry.unsigned, address, label)
                                    sendState = r
                                    if (r is SendState.Done) onClose()
                                }
                            },
                        )
                        TextButton(onClick = { sendState = SendState.Idle }) { Text(stringResource(R.string.common_start_over)) }
                    } else {
                        PrimaryActionButton(
                            text = stringResource(R.string.common_try_again),
                            onClick = { sendState = SendState.Idle },
                        )
                    }
                }
            }
        }
    }

    if (showUtxoPicker && active != null && engine != null) {
        UTXOPickerSheet(
            env = env,
            engine = engine!!,
            network = active.network,
            amountNeededSat = amountSats,
            selection = selectedUtxos,
            onApply = { selectedUtxos = it; showUtxoPicker = false },
            onDismiss = { showUtxoPicker = false },
        )
    }

    // Pre-sign device-ready confirmation for hardware wallets (ADR-0033). Shows
    // the readiness copy (open the Bitcoin / Bitcoin Test app on a Ledger, or
    // confirm on a Trezor) and, only for a host-typed hidden wallet, the
    // passphrase field. On Continue it runs the existing hardware signing path
    // with that passphrase (or null), which threads through resolveChoice ->
    // applyPassphraseMode so the hidden wallet's addresses match the device.
    if (showReadySheet && active != null && engine != null) {
        val hw = active.kind as? BitcoinWalletKind.Hardware
        val device = hw?.let { DeviceRegistry(context).find(it.deviceId) }
        val deviceGoneMsg = stringResource(R.string.btc_device_no_longer_registered)
        if (device == null) {
            showReadySheet = false
            sendState = SendState.Failed(deviceGoneMsg)
        } else {
            HardwareSignReadySheet(
                deviceKind = device.kind,
                deviceLabel = device.label,
                deviceSerialDisplay = device.serialDisplay,
                readiness = HardwareSignAppReadiness.bitcoin(
                    isMainnet = active.network == com.elabify.musnad.wallet.bitcoin.BitcoinNetwork.MAINNET,
                ),
                requiresHostPassphrase = needsHostPassphrase,
                onCancel = { showReadySheet = false },
                onContinue = { hostPassphrase ->
                    showReadySheet = false
                    val e = engine ?: return@HardwareSignReadySheet
                    scope.launch {
                        sendState = SendState.Signing
                        sendState = signBitcoin(
                            env, e, active, address, amountSats, effectiveSatsPerVb,
                            rbf, if (coinControl) selectedUtxos else emptySet(), isSoftware,
                            hostPassphrase, context,
                        )
                    }
                },
            )
        }
    }

    if (showOffline && active != null && engine != null && amountSats > 0) {
        BitcoinOfflinePSBTSheet(
            env = env,
            engine = engine!!,
            descriptor = active,
            recipient = address,
            amountSat = amountSats,
            feeRateSatsPerVb = effectiveSatsPerVb,
            enableRbf = rbf,
            onBroadcast = { showOffline = false; onClose() },
            onDismiss = { showOffline = false },
        )
    }
}

/** Build the unsigned PSBT and sign it. Software signs in-app via the BDK
 *  transient signer (after loading the seed); hardware wallets route the PSBT
 *  onto the bound device over BLE (signBitcoinHardwarePsbt: prev-tx streaming
 *  from the synced wallet, the descriptor's hidden / passphrase + derivation
 *  path re-applied, BIP44 / 49 / 84). A failed / rejected sign returns Failed
 *  and never broadcasts. Runs entirely on Dispatchers.IO. */
private suspend fun signBitcoin(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    address: String,
    amountSats: Long,
    feeRate: Long,
    rbf: Boolean,
    selected: Set<UtxoKey>,
    isSoftware: Boolean,
    hostPassphrase: String?,
    context: android.content.Context,
): SendState = withContext(Dispatchers.IO) {
    runCatching {
        val outpoints = if (selected.isNotEmpty()) selected.toOutpoints() else null
        val unsigned = engine.buildUnsignedPSBT(address, amountSats, feeRate, rbf, outpoints)
        val account = descriptor.softwareAccountOrNull()
        val signed = if (isSoftware && account != null) {
            val words = loadRecoveryWords(context) ?: throw BitcoinWalletException.SandwichRequired
            BitcoinSigningHelpers.signSoftware(unsigned, words, loadBip39Passphrase(context), account, descriptor.network)
        } else {
            val hw = descriptor.kind as? BitcoinWalletKind.Hardware
                ?: throw BitcoinWalletException.SendFailed("This wallet is not a hardware wallet.")
            val device = DeviceRegistry(context).find(hw.deviceId)
                ?: throw BitcoinWalletException.SendFailed(
                    "The hardware device for this wallet is no longer registered. Re-add it under Settings, Devices.",
                )
            signBitcoinHardwarePsbt(
                device = device,
                unsignedBase64 = unsigned,
                fingerprintHex = hw.accountFingerprint,
                accountXpub = hw.accountXpub,
                network = descriptor.network,
                hidden = descriptor.hidden,
                derivationPath = descriptor.derivationPath,
                hostEnteredPassphrase = hostPassphrase,
            )
        }
        SendState.Signed(signed, unsigned)
    }.getOrElse { SendState.Failed(it.message ?: it.toString()) }
}

private suspend fun broadcastBitcoin(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    signed: String,
    unsigned: String,
    address: String,
    label: String,
): SendState = withContext(Dispatchers.IO) {
    runCatching {
        val url = env.settings.electrumURL(descriptor.network)
        val txid = engine.importSignedPSBTAndBroadcast(signed, unsigned, url)
        val trimmed = label.trim()
        if (trimmed.isNotEmpty()) {
            env.labels.setLabelForAddress(trimmed, address)
            env.labels.setLabelForOutput(trimmed, txid, 0L)
        }
        SendState.Done(txid)
    }.getOrElse {
        // The signing already succeeded; only the broadcast failed. Keep the
        // signed PSBT so the user can re-push without re-signing (ADR-0033).
        SendState.Failed(it.message ?: it.toString(), retry = SendState.Signed(signed, unsigned))
    }
}

private fun parseAmountSats(input: String, denomination: String, fiatId: String, fiatUnit: Double?): Long {
    val t = input.trim()
    if (t.isEmpty()) return 0
    return when (denomination) {
        "BTC" -> t.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 100_000_000).toLong() } ?: 0
        "sats" -> t.toLongOrNull() ?: 0
        fiatId -> {
            // Fiat -> sats via the BTC unit price. The native amount is what gets signed.
            val u = fiatUnit ?: return 0
            val f = t.toDoubleOrNull()?.takeIf { it > 0 } ?: return 0
            ((f / u) * 100_000_000).toLong()
        }
        else -> 0
    }
}

/** The live-conversion caption under the amount field: the native equivalent
 *  when fiat is typed, or the fiat (and sats) equivalent when a native unit is. */
private fun sendSecondaryLabel(amountSats: Long, denomination: String, fiatId: String, fiatUnit: Double?): String? {
    if (amountSats <= 0) return null
    val fiatCap = fiatUnit?.let { "≈ " + FiatReference.format(amountSats / 100_000_000.0 * it) }
    return when (denomination) {
        fiatId -> "${formatBtc(amountSats)} BTC (${formatSats(amountSats)})"
        "sats" -> fiatCap
        else -> listOfNotNull(formatSats(amountSats), fiatCap).joinToString(" · ")
    }
}

private fun applyMax(maxSat: Long, denomination: String, fiatId: String, fiatUnit: Double?): String = when (denomination) {
    "sats" -> "$maxSat"
    fiatId -> fiatUnit?.let { String.format(Locale.US, "%.2f", maxSat / 100_000_000.0 * it) }
        ?: String.format(Locale.US, "%.8f", maxSat / 100_000_000.0)
    else -> String.format(Locale.US, "%.8f", maxSat / 100_000_000.0)
}

private fun stripBitcoinPrefix(s: String): String =
    com.elabify.app.maknoon.ui.wallet.PaymentURIStrip.bitcoin(s)

/**
 * Parse a scanned / pasted Bitcoin payload and apply it. Strips the `bitcoin:`
 * URI scheme, sets the recipient via [onAddress], and when the URI carried an
 * `amount=` parameter (BIP21, denominated in BTC) prefills it via [onAmountBtc].
 * A bare address (no scheme / no params) just sets the recipient. Other URI
 * params (label, message) are ignored.
 */
private fun applyBitcoinPayload(
    raw: String,
    onAddress: (String) -> Unit,
    onAmountBtc: (String) -> Unit,
) {
    onAddress(stripBitcoinPrefix(raw))
    bitcoinUriAmountBtc(raw)?.let(onAmountBtc)
}

/** Extract the BIP21 `amount` (BTC) from a `bitcoin:` URI, or null. */
private fun bitcoinUriAmountBtc(raw: String): String? {
    val s = raw.trim()
    val q = s.indexOf('?')
    if (q < 0) return null
    val query = s.substring(q + 1)
    val amount = query.split('&').firstNotNullOfOrNull { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) return@firstNotNullOfOrNull null
        val key = pair.substring(0, eq)
        if (!key.equals("amount", ignoreCase = true)) return@firstNotNullOfOrNull null
        pair.substring(eq + 1)
    } ?: return null
    return amount.toDoubleOrNull()?.takeIf { it > 0 }?.let { amount }
}

/** A camera scan sheet for the Send recipient, reusing the GMS-free
 *  MiniAppQrScanner (same scanner + camera-permission flow as ScanVerifierSheet).
 *  Fires [onScanned] once with the first decoded payload. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitcoinScanSheet(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var done by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.btc_scan_bitcoin_address), style = MaterialTheme.typography.titleMedium)
            com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner(
                continuous = false,
                onCode = { code ->
                    if (done) return@MiniAppQrScanner
                    done = true
                    onScanned(code)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
            )
            com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton(
                onCode = { code ->
                    if (done) return@QrPhotoPickerButton
                    done = true
                    onScanned(code)
                },
                onNoQr = {},
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}
