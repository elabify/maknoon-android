// Pre-sign "device ready" confirmation, shown before a hardware-wallet SEND
// opens BLE to a Ledger or Trezor (ADR-0033, Hardware UX). Ported to mirror
// iOS DeviceReadyConfirmationSheet: one extra tap up front so the user can
// unlock the device and open the right on-device app before the BLE timer
// starts, replacing the cryptic "wrong app" / "device asleep" errors the raw
// APDU path surfaces.
//
// It ALSO carries the conditional hidden-wallet passphrase entry. A Trezor
// hidden (passphrase) wallet that signs WITHOUT re-applying its passphrase
// derives the STANDARD wallet's addresses, and the device rejects the PSBT
// with "Input does not match scriptPubKey". So a host-typed hidden wallet must
// re-supply its passphrase here, fresh for this one signing. The passphrase is
// never stored: the wallet remembers only its hidden CONFIG on the descriptor
// (HardwarePassphraseRef onDevice | hostEntry | null); the secret is supplied
// at every signing. On-device-passphrase wallets show NO field (the Trezor
// prompts for it) but still show the readiness text.
//
// The sheet is pure UI. Call sites own the state machine: open it when the
// user taps "Sign using hardware wallet", and on Continue run the existing
// hardware signing path with the typed passphrase (or null).
//
// UI copy says "second factor" / "security key", never "Identity Sandwich".

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.devices.DeviceKind

/**
 * The on-device app a signing op needs open, plus whether that app is a testnet
 * variant. Each chain's send screen builds one of these and hands it to
 * [HardwareSignReadySheet], which renders the kind-specific readiness text.
 *
 * For Bitcoin the Ledger app name differs by network: "Bitcoin" on mainnet,
 * "Bitcoin Test" on Testnet3 / Signet. Other chains pass a single app name
 * (Ethereum / Solana / Tron) with [isTestnetVariant] = false.
 */
data class HardwareSignAppReadiness(
    /** The on-device app name the user opens on a Ledger, e.g. "Bitcoin",
     *  "Bitcoin Test", "Ethereum", "Solana", "Tron". Trezor has no per-app
     *  selection, so this is only used in the Ledger instructions. */
    val ledgerAppName: String,
    /** True when [ledgerAppName] is the testnet build (informational; the name
     *  already encodes it for Bitcoin). */
    val isTestnetVariant: Boolean = false,
) {
    companion object {
        /** Bitcoin readiness: "Bitcoin" on mainnet, "Bitcoin Test" otherwise. */
        fun bitcoin(isMainnet: Boolean): HardwareSignAppReadiness =
            HardwareSignAppReadiness(
                ledgerAppName = if (isMainnet) "Bitcoin" else "Bitcoin Test",
                isTestnetVariant = !isMainnet,
            )

        val ethereum = HardwareSignAppReadiness("Ethereum")
        val solana = HardwareSignAppReadiness("Solana")
        val tron = HardwareSignAppReadiness("Tron")
    }
}

/**
 * Pre-sign device-ready confirmation (ADR-0033). Reusable by every chain's send
 * screen. Renders kind-specific readiness instructions and, ONLY when
 * [requiresHostPassphrase], the shared masked [PassphraseField].
 *
 * Continue is enabled when no passphrase is required, or when one has been
 * typed; it calls [onContinue] with the trimmed passphrase (null when none is
 * required). [onCancel] aborts. The passphrase is used for this signing only
 * and is never stored.
 *
 * @param deviceKind LEDGER or TREZOR. Other kinds never reach this sheet.
 * @param deviceLabel the user's label for the bound device (header row).
 * @param deviceSerialDisplay the truncated serial (header subtitle).
 * @param readiness which on-device app to open (drives the Ledger copy).
 * @param requiresHostPassphrase true only for a host-typed hidden wallet
 *        (HardwarePassphraseRef.needsHostPassphrase). On-device-passphrase and
 *        standard wallets pass false and see no field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareSignReadySheet(
    deviceKind: DeviceKind,
    deviceLabel: String,
    deviceSerialDisplay: String,
    readiness: HardwareSignAppReadiness,
    requiresHostPassphrase: Boolean,
    onContinue: (hostPassphrase: String?) -> Unit,
    onCancel: () -> Unit,
    // When true, the user has confirmed and a BLE signature is in flight: show a
    // "waiting for your device" spinner instead of the passphrase field + buttons,
    // and ignore dismiss. Used by the mini-app hardware-sign flow to keep the
    // sheet up across the device sign; send screens leave it false.
    signing: Boolean = false,
) {
    // Re-typed each presentation; the sheet is rebuilt by the caller's show
    // flag so this never carries over between signings.
    var passphrase by remember { mutableStateOf("") }
    val passphraseReady = !requiresHostPassphrase || passphrase.trim().isNotEmpty()

    ModalBottomSheet(onDismissRequest = { if (!signing) onCancel() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (signing) R.string.wallet_signing else R.string.wallet_prepare_device),
                style = MaterialTheme.typography.titleLarge,
            )

            // Device header: label + kind / serial.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (deviceKind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp).size(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(deviceLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.wallet_device_kind_serial, deviceKind.displayName, deviceSerialDisplay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (signing) {
                // BLE signature in flight: spinner + "confirm on your device". The
                // sheet stays up (non-dismissable) so the mini-app's own progress
                // text is not revealed until the signature is done.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Text(
                        stringResource(R.string.wallet_waiting_for_device, deviceKind.displayName),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                return@Column
            }

            Text(
                readinessInstructions(deviceKind, readiness),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Hidden (second-factor) wallet: re-enter the host-typed passphrase
            // for this signing. Shown only for a HOST_ENTRY hidden ref; the
            // value is handed back on Continue and never stored.
            if (requiresHostPassphrase) {
                Text(
                    stringResource(R.string.wallet_hidden_wallet),
                    style = MaterialTheme.typography.titleSmall,
                )
                PassphraseField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = stringResource(R.string.wallet_hidden_wallet_passphrase),
                )
                Text(
                    stringResource(R.string.wallet_reenter_hidden_passphrase),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                timeoutHint(deviceKind),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                enabled = passphraseReady,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onContinue(if (requiresHostPassphrase) passphrase.trim() else null) },
            ) { Text(stringResource(R.string.common_continue)) }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel,
            ) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/** Kind-specific "get the device ready" copy. Ledger names the app to open
 *  (the chain's app, "Bitcoin Test" for testnet Bitcoin); Trezor confirms on
 *  the device itself. */
private fun readinessInstructions(kind: DeviceKind, readiness: HardwareSignAppReadiness): String =
    when (kind) {
        DeviceKind.LEDGER ->
            "Unlock your Ledger and open the ${readiness.ledgerAppName} app. Tap Continue when ready."
        DeviceKind.TREZOR ->
            "Unlock your Trezor and confirm on the device. Tap Continue when ready."
        // YubiKey / SeedSigner never reach this sheet (NFC-gated / air-gapped).
        else -> "Unlock your device. Tap Continue when ready."
    }

private fun timeoutHint(kind: DeviceKind): String = when (kind) {
    DeviceKind.LEDGER, DeviceKind.TREZOR ->
        "The device has a short window to respond once connected. If the link drops or it times out, you can retry."
    else -> ""
}
