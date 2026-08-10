// Shared multi-device second-factor recovery coordinator (ADR-0032
// "OR-among-keys"). When the wallet's second factor is ON, ANY ONE enrolled
// security key recovers the same 32-byte CEK (every device wraps the SAME CEK).
// This file is the single place that:
//
//   1. lists the enrolled factors (any mix of YubiKey / Ledger / Trezor),
//   2. lets the user pick WHICH enrolled device to confirm with when more than
//      one is enrolled,
//   3. routes by that device's kind to the right transport:
//        YUBIKEY        -> SecondFactorUnlock   (NFC tap + FIDO2 PIN)
//        LEDGER/TREZOR  -> HardwareSecondFactor (BLE connect + on-device approve)
//   4. returns the recovered CEK to the caller.
//
// It is reused by EVERY entropy-requiring unlock:
//   - reveal the recovery phrase (LocalKeyBackupReset),
//   - turn the second factor off (DevicesScreen),
//   - recover-then-add a NEW device (YubiKeyEnrollScreen / DevicesScreen):
//     the user confirms with an ALREADY-ENROLLED device to recover the existing
//     CEK, then the new device's wrappedCEK is sealed over that SAME CEK so no
//     device is ever orphaned (the CEK is never rotated on add).
//
// User-facing strings say "second factor" / "security key"; never the internal
// IdentitySandwich name.

package com.elabify.app.maknoon.yubikey
import com.elabify.app.maknoon.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import kotlinx.coroutines.launch

/**
 * Enrolled second factors (any kind) that carry a complete wrap envelope, in
 * registration order. Any ONE of these recovers the wallet (OR-among-keys).
 * A thin convenience over [SecondFactorUnlock.enrolledSecondFactors] so callers
 * that only have a [DeviceRegistry] do not need an Activity.
 */
fun enrolledSecondFactors(registry: DeviceRegistry): List<RegisteredDevice> =
    registry.devices.filter { it.promotions.identity?.hasSecondFactorWrap == true }

/**
 * Multi-device recovery dialog. Lists the enrolled factors; if more than one is
 * enrolled it first asks which device to confirm with, then routes by that
 * device's kind to the matching transport and recovers the CEK.
 *
 * @param exclude an enrolled device to OMIT from the choices. The recover-then-
 *   add flow passes the device being added (which is not yet enrolled, so this
 *   is usually a no-op) and, more importantly, callers can use it to force the
 *   user to confirm with a DIFFERENT key. Null = offer every enrolled factor.
 * @param onRecovered fires with the recovered 32-byte CEK. The caller then
 *   either rebuilds the sandwich (loadWithSecondFactor { cek }) or reuses the
 *   CEK to seal a new device (sealForSecondFactorEnroll(existingCek = cek)).
 */
@Composable
fun SecondFactorRecoverDialog(
    activity: FragmentActivity?,
    registry: DeviceRegistry,
    title: String,
    message: String,
    onRecovered: (cek: ByteArray) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
    exclude: RegisteredDevice? = null,
) {
    val factors = remember {
        enrolledSecondFactors(registry).filter { it.id != exclude?.id }
    }
    val noFactorMessage = stringResource(R.string.yubikey_no_second_factor_enrolled)

    // Which enrolled device the user will confirm with. Auto-selected when there
    // is exactly one; otherwise the user picks from the list first.
    var chosen by remember { mutableStateOf(factors.singleOrNull()) }

    if (factors.isEmpty()) {
        // No enrolled factor: the only paths left are the 24-word phrase /
        // encrypted backup. Surface that and dismiss.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            onError(noFactorMessage)
        }
        return
    }

    val target = chosen
    if (target == null) {
        SecondFactorPickerDialog(
            title = title,
            factors = factors,
            onPick = { chosen = it },
            onCancel = onCancel,
        )
        return
    }

    when (target.kind) {
        DeviceKind.YUBIKEY ->
            YubiKeyRecoverCekDialog(
                activity = activity,
                registry = registry,
                device = target,
                title = title,
                message = message,
                onRecovered = onRecovered,
                onError = onError,
                onCancel = onCancel,
            )
        DeviceKind.LEDGER, DeviceKind.TREZOR ->
            HardwareRecoverCekDialog(
                device = target,
                title = title,
                message = message,
                onRecovered = onRecovered,
                onError = onError,
                onCancel = onCancel,
            )
        else ->
            androidx.compose.runtime.LaunchedEffect(Unit) {
                onError("This device kind cannot be used as a second factor.")
            }
    }
}

/** Pick which enrolled security key to confirm with (shown only when >1). */
@Composable
private fun SecondFactorPickerDialog(
    title: String,
    factors: List<RegisteredDevice>,
    onPick: (RegisteredDevice) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.yubikey_choose_which_enrolled_security),
                    style = MaterialTheme.typography.bodyMedium,
                )
                factors.forEach { dev ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(dev) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(dev.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${dev.kind.displayName} - ${dev.serialDisplay}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** YubiKey (NFC) recovery: collect the PIN, tap, recompute, return the CEK. */
@Composable
private fun YubiKeyRecoverCekDialog(
    activity: FragmentActivity?,
    registry: DeviceRegistry,
    device: RegisteredDevice,
    title: String,
    message: String,
    onRecovered: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val keyDidNotUnlockMsg = stringResource(R.string.yubikey_key_did_not_unlock)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                PassphraseField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = stringResource(R.string.yubikey_security_key_pin),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                )
                if (busy) {
                    Text(
                        stringResource(R.string.yubikey_hold_your_security_key),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && activity != null && pin.isNotEmpty(),
                onClick = {
                    val act = activity ?: return@TextButton
                    busy = true
                    scope.launch {
                        val unlock = SecondFactorUnlock(act, registry)
                        val result = runCatching { unlock.recoverCekFor(device, pin.toCharArray()) }
                        busy = false
                        result.onSuccess { onRecovered(it) }
                            .onFailure {
                                onError(
                                    it.message
                                        ?: keyDidNotUnlockMsg,
                                )
                            }
                    }
                },
            ) { Text(stringResource(R.string.yubikey_tap_security_key)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** Ledger / Trezor (BLE) recovery: connect, approve on device, return the CEK. */
@Composable
private fun HardwareRecoverCekDialog(
    device: RegisteredDevice,
    title: String,
    message: String,
    onRecovered: (ByteArray) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val deviceDidNotUnlockMsg = stringResource(R.string.yubikey_device_did_not_unlock)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (device.kind) {
                        DeviceKind.LEDGER ->
                            "Unlock your Ledger and open the Ethereum app, then approve the signature."
                        else -> "Unlock your Trezor, then approve the signature on the device."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Text(
                        stringResource(R.string.yubikey_connecting_approve_the_signature, device.kind.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val hw = HardwareSecondFactor(context, device)
                        val result = runCatching { hw.recoverCek() }
                        busy = false
                        result.onSuccess { onRecovered(it) }
                            .onFailure {
                                onError(
                                    it.message
                                        ?: deviceDidNotUnlockMsg,
                                )
                            }
                    }
                },
            ) { Text(stringResource(R.string.yubikey_connect_and_approve)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
