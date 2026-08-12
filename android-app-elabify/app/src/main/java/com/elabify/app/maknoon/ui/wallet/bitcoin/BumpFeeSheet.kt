// RBF fee-bump sheet, ported from iOS BumpFeeSheet. Builds a replacement
// PSBT for an unconfirmed outgoing tx at a higher fee (BDK
// BumpFeeTxBuilder via engine.buildBumpFeePSBT), signs it (software in-app /
// hardware via the BLE hook), then broadcasts as a separate step. BDK
// preserves the original recipients + amounts; the user only picks the new
// fee rate.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinFeeEstimator
import com.elabify.musnad.wallet.bitcoin.BitcoinSigningHelpers
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignAppReadiness
import com.elabify.app.maknoon.ui.wallet.common.HardwareSignReadySheet
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletException
import com.elabify.musnad.wallet.bitcoin.FeeRecommended
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Copy lives in string RESOURCES, not in this enum: an enum has no Context
// and cannot call stringResource, so these chip labels shipped English in
// all 31 locales. Same shape as SendFeeMode in BitcoinSendScreen.kt (this
// enum has no persisted id, so no wire value to preserve here).
private enum class BumpFeeMode(@StringRes val labelRes: Int) {
    FASTEST(R.string.btc_fee_fastest),
    HALF_HOUR(R.string.btc_fee_half_hour),
    HOUR(R.string.btc_fee_hour),
    ECONOMY(R.string.btc_fee_economy),
    CUSTOM(R.string.walletc_custom);

    fun rate(rec: FeeRecommended?): Long = when (this) {
        FASTEST -> rec?.fastestFee ?: 0
        HALF_HOUR -> rec?.halfHourFee ?: 0
        HOUR -> rec?.hourFee ?: 0
        ECONOMY -> rec?.economyFee ?: 0
        CUSTOM -> 0
    }
}

private sealed class BumpState {
    object Idle : BumpState()
    object Signing : BumpState()
    data class Signed(val signed: String, val unsigned: String) : BumpState()
    object Broadcasting : BumpState()
    data class Done(val txid: String) : BumpState()
    data class Failed(val message: String) : BumpState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BumpFeeSheet(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    originalTxidHex: String,
    originalFeeSat: Long?,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var feeMode by remember { mutableStateOf(BumpFeeMode.FASTEST) }
    var customSatsPerVb by remember { mutableStateOf("20") }
    var feeRec by remember { mutableStateOf<FeeRecommended?>(null) }
    var state by remember { mutableStateOf<BumpState>(BumpState.Idle) }
    // Same missing-argument bug as BitcoinSendScreen: the hardware label
    // carries a %1$s for the device name.
    val fallbackDeviceName = stringResource(R.string.devices_fallback_device)
    val boundDeviceLabel = remember(descriptor.id) {
        (descriptor.kind as? BitcoinWalletKind.Hardware)?.let { hw ->
            DeviceRegistry(context).find(hw.deviceId)?.label
        } ?: fallbackDeviceName
    }
    val isSoftware = descriptor.softwareAccountOrNull() != null
    // The pre-sign device-ready confirmation (ADR-0033), opened for hardware
    // wallets when the user taps Sign. It hosts the readiness copy and the
    // conditional host-typed hidden-wallet passphrase entry (never stored).
    var showReadySheet by remember { mutableStateOf(false) }
    // A host-typed hidden hardware wallet must re-supply its passphrase to sign,
    // the same gate the send screen uses (HardwarePassphraseRef.needsHostPassphrase).
    val needsHostPassphrase = remember(descriptor.id) {
        HardwarePassphraseRef.fromJson(descriptor.hidden)?.needsHostPassphrase ?: false
    }

    LaunchedEffect(Unit) {
        val base = env.settings.mempoolURL(descriptor.network)
        feeRec = withContext(Dispatchers.IO) { BitcoinFeeEstimator.fetch(base) }
    }

    val effectiveSatsPerVb = when (feeMode) {
        BumpFeeMode.CUSTOM -> customSatsPerVb.trim().toLongOrNull() ?: 0
        else -> feeMode.rate(feeRec)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.btc_bump_fee)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row {
                    Text(stringResource(R.string.btc_replacing_tx), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(shortMiddle(originalTxidHex, 8, 6), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                }
                originalFeeSat?.let {
                    Row {
                        Text(stringResource(R.string.btc_original_fee), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("${formatBtc(it)} BTC", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(stringResource(R.string.btc_replacement_fee), style = MaterialTheme.typography.titleSmall)
                Row {
                    BumpFeeMode.values().forEach { mode ->
                        FilterChip(
                            selected = feeMode == mode,
                            onClick = { feeMode = mode },
                            label = { Text(stringResource(mode.labelRes)) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                if (feeMode == BumpFeeMode.CUSTOM) {
                    OutlinedTextField(
                        value = customSatsPerVb,
                        onValueChange = { customSatsPerVb = it },
                        label = { Text(stringResource(R.string.btc_sats_per_vb)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.btc_approx_sats_per_vb, effectiveSatsPerVb.toString()), style = MaterialTheme.typography.labelSmall)
                }
                when (val s = state) {
                    is BumpState.Signing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btc_signing))
                    }
                    is BumpState.Broadcasting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btc_broadcasting))
                    }
                    is BumpState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    is BumpState.Done -> Text(stringResource(R.string.btc_replaced_new_txid, shortMiddle(s.txid, 12, 0)), color = MaterialTheme.colorScheme.primary)
                    else -> {}
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is BumpState.Idle, is BumpState.Failed -> Button(
                    enabled = effectiveSatsPerVb > 0,
                    onClick = {
                        if (!isSoftware) {
                            // Hardware: open the pre-sign device-ready
                            // confirmation; signing runs on Continue (ADR-0033).
                            showReadySheet = true
                            return@Button
                        }
                        scope.launch {
                            state = BumpState.Signing
                            state = signBump(
                                engine, descriptor, originalTxidHex, effectiveSatsPerVb, isSoftware,
                                null, context,
                            )
                        }
                    },
                ) {
                    Text(
                        if (isSoftware) stringResource(R.string.btc_sign_replacement)
                        else stringResource(R.string.btc_sign_using_hardware, boundDeviceLabel),
                    )
                }
                is BumpState.Signed -> Button(onClick = {
                    scope.launch {
                        state = BumpState.Broadcasting
                        val r = broadcastBump(env, engine, descriptor, s.signed, s.unsigned, originalTxidHex)
                        state = r
                        if (r is BumpState.Done) { onDone() }
                    }
                }) { Text(stringResource(R.string.btc_broadcast_replacement)) }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )

    // Pre-sign device-ready confirmation for hardware fee-bumps (ADR-0033),
    // mirroring the send screen: readiness copy (open the Bitcoin / Bitcoin
    // Test app on a Ledger, or confirm on a Trezor) and, only for a host-typed
    // hidden wallet, the passphrase field. On Continue it runs the existing
    // hardware signing path with that passphrase (or null).
    if (showReadySheet) {
        val hw = descriptor.kind as? BitcoinWalletKind.Hardware
        val device = hw?.let { DeviceRegistry(context).find(it.deviceId) }
        val deviceGoneMsg = stringResource(R.string.btc_device_no_longer_registered)
        if (device == null) {
            showReadySheet = false
            state = BumpState.Failed(deviceGoneMsg)
        } else {
            HardwareSignReadySheet(
                deviceKind = device.kind,
                deviceLabel = device.label,
                deviceSerialDisplay = device.serialDisplay,
                readiness = HardwareSignAppReadiness.bitcoin(
                    isMainnet = descriptor.network == com.elabify.musnad.wallet.bitcoin.BitcoinNetwork.MAINNET,
                ),
                requiresHostPassphrase = needsHostPassphrase,
                onCancel = { showReadySheet = false },
                onContinue = { hostPassphrase ->
                    showReadySheet = false
                    scope.launch {
                        state = BumpState.Signing
                        state = signBump(
                            engine, descriptor, originalTxidHex, effectiveSatsPerVb, isSoftware,
                            hostPassphrase, context,
                        )
                    }
                },
            )
        }
    }
}

private suspend fun signBump(
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    originalTxidHex: String,
    feeRate: Long,
    isSoftware: Boolean,
    hostPassphrase: String?,
    context: android.content.Context,
): BumpState = withContext(Dispatchers.IO) {
    runCatching {
        val unsigned = engine.buildBumpFeePSBT(originalTxidHex, feeRate)
        val account = descriptor.softwareAccountOrNull()
        val signed = if (isSoftware && account != null) {
            val words = loadRecoveryWords(context) ?: throw BitcoinWalletException.SandwichRequired
            BitcoinSigningHelpers.signSoftware(unsigned, words, loadBip39Passphrase(context), account, descriptor.network)
        } else {
            // Hardware RBF: sign the replacement PSBT on the bound device over
            // the shared withHardwareDevice path, identical to the send screen
            // (prev-tx streaming + hidden-wallet passphrase re-apply).
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
        BumpState.Signed(signed, unsigned)
    }.getOrElse { BumpState.Failed(it.message ?: it.toString()) }
}

private suspend fun broadcastBump(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    signed: String,
    unsigned: String,
    originalTxidHex: String,
): BumpState = withContext(Dispatchers.IO) {
    runCatching {
        val url = env.settings.electrumURL(descriptor.network)
        val txid = engine.importSignedPSBTAndBroadcast(signed, unsigned, url)
        // Carry the original tx label over to the replacement, like iOS.
        env.labels.labelForOutput(originalTxidHex, 0L)?.takeIf { it.isNotEmpty() }?.let {
            env.labels.setLabelForOutput(it, txid, 0L)
        }
        BumpState.Done(txid)
    }.getOrElse { BumpState.Failed(it.message ?: it.toString()) }
}
