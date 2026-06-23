// Pre-connect dialog for the hardware ADD + DISCOVER (read) paths (ADR-0033).
//
// It is the read-path twin of HardwareSignReadySheet (which is the SEND path).
// When the user taps "Add wallet" or "Discover existing wallets" with a TREZOR
// selected, this dialog opens FIRST and hosts the hidden-wallet / passphrase
// selector (None / On Device / Type Here, with the masked eyeball field). The
// one passphrase choice it collects applies to BOTH the single-account add and
// the discover sweep, which is exactly why it lives in the connection dialog
// instead of inline in the form: the form never made it clear the passphrase
// drove discover too.
//
// After the user taps Continue the caller starts the BLE operation and feeds the
// live [HardwareStage] back in; the dialog swaps the selector for the shared
// HardwareStageLine (Connecting -> Connected -> Confirm on device -> Scanning N
// -> Done) so the device-ready / on-device-passphrase wait is visible. The
// caller dismisses the dialog when the operation finishes (add: navigates away;
// discover: closes and renders the results checklist below the form).
//
// LEDGER never opens this dialog: its passphrase lives on the device, so the
// caller runs the operation directly with PassphraseChoice.Standard. The dialog
// is therefore Trezor-only in practice; HiddenWalletSelector renders nothing for
// a non-Trezor device as a backstop.
//
// The passphrase is used for this one operation and is NEVER stored; the wallet
// records only its hidden CONFIG (HardwarePassphraseRef) on the descriptor.
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.trezor.HiddenWalletSelection

/** Why the connection dialog opened, used only for the title / copy. */
enum class HardwareConnectPurpose { ADD, DISCOVER }

/**
 * Trezor pre-connect dialog hosting the passphrase selector + the live
 * connection stage. See the file header for the full contract.
 *
 * @param running false while collecting the passphrase; true once the caller has
 *        started the BLE operation (the dialog then shows [stage] instead of the
 *        selector + Continue).
 * @param stage live connection stage while [running]; null shows a generic
 *        spinner via [HardwareStageLine]'s default.
 * @param onContinue fired when the user confirms; the caller reads
 *        `selection.choice(hostPassphrase)` and starts the operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HardwareConnectDialog(
    device: RegisteredDevice,
    purpose: HardwareConnectPurpose,
    selection: HiddenWalletSelection,
    onSelectionChange: (HiddenWalletSelection) -> Unit,
    hostPassphrase: String,
    onHostPassphraseChange: (String) -> Unit,
    running: Boolean,
    stage: HardwareStage?,
    stageAccount: Long?,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    // A running BLE op must not be torn down by an outside-tap dismiss.
    ModalBottomSheet(onDismissRequest = { if (!running) onCancel() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when (purpose) {
                    HardwareConnectPurpose.ADD -> stringResource(R.string.wallet_add_wallet)
                    HardwareConnectPurpose.DISCOVER -> stringResource(R.string.wallet_discover_wallets)
                },
                style = MaterialTheme.typography.titleLarge,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (device.kind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp).size(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(device.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.wallet_device_kind_serial, device.kind.displayName, device.serialDisplay),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!running) {
                // Passphrase selector (Trezor). The same choice drives add AND
                // discover, which the dialog placement makes explicit.
                HiddenWalletSelector(
                    deviceKind = device.kind,
                    selection = selection,
                    onSelectionChange = onSelectionChange,
                    hostPassphrase = hostPassphrase,
                    onHostPassphraseChange = onHostPassphraseChange,
                )
                Text(
                    stringResource(R.string.wallet_wake_device_hint, device.kind.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = selection.isReady(hostPassphrase),
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_continue)) }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_cancel)) }
            } else {
                // Operation in flight: show the live connection stage.
                if (stage != null) {
                    HardwareStageLine(stage = stage, device = device, account = stageAccount)
                }
            }
        }
    }
}
