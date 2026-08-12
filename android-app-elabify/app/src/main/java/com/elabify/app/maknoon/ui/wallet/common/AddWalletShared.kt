// Reusable Add-wallet UI building blocks (ADR-0033), shared across every
// chain's Add screen so they cannot drift. Bitcoin consumes them now; Solana /
// Tron reuse them next, and Ethereum can adopt them in its own follow-up.
//
//   * SourcePicker        - Software | Hardware FilterChips (lifted from
//                           AddEthereumWalletScreen).
//   * DevicePicker        - registered Ledger / Trezor chips, filtered by a
//                           passed predicate.
//   * AccountStepper      - the [- N +] account-index row.
//   * HiddenWalletSelector - the Trezor-only Standard / On device / Type here
//                           chips + a SecureField when host-typed, driven by
//                           the SDK HiddenWalletSelection enum (displayName /
//                           footer / isReady / choice). Promoted from the
//                           hand-rolled passphrase UI in
//                           DiscoverHardwareWalletsScreen so add + discover
//                           share one implementation.
//
// UI copy says "second factor" / "security key", never "Identity Sandwich".

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.trezor.HiddenWalletSelection

/** The Software | Hardware source of a new wallet. Generic across chains. */
enum class AddWalletSource { SOFTWARE, HARDWARE }

/**
 * A chain selector's menu order (ADR-0033): the primary mainnet first, then the
 * remaining mainnets alphabetically, then (after a separator) all testnets
 * alphabetically. Used by every chain dropdown (Bitcoin's Add Chain, the Auto
 * Discovery "Chain to scan") so testnets never interleave with mainnets.
 */
data class ChainMenuOrder<T>(val mainnets: List<T>, val testnets: List<T>)

fun <T> orderChainsForMenu(
    all: List<T>,
    primary: T,
    isTestnet: (T) -> Boolean,
    name: (T) -> String,
): ChainMenuOrder<T> {
    val mainnets = listOf(primary) +
        all.filter { it != primary && !isTestnet(it) }.sortedBy { name(it).lowercase() }
    val testnets = all.filter { isTestnet(it) }.sortedBy { name(it).lowercase() }
    return ChainMenuOrder(mainnets, testnets)
}

/**
 * UI-layer label for a [HiddenWalletSelection] chip (ADR-0033). The SDK enum's
 * own `displayName` ("Standard" / "On device" / "Type here") is kept intact for
 * semantics; this maps it to the agreed user-facing copy so the selector reads
 * as a "Passphrase" choice: None / On Device / Type Here. Shared by Add +
 * discover so both screens show the same labels.
 */
@Composable
internal fun hiddenSelectionLabel(sel: HiddenWalletSelection): String = when (sel) {
    HiddenWalletSelection.STANDARD -> stringResource(R.string.wallet_hidden_selection_standard)
    HiddenWalletSelection.ON_DEVICE -> stringResource(R.string.wallet_hidden_selection_on_device)
    HiddenWalletSelection.HOST_TYPED -> stringResource(R.string.wallet_hidden_selection_type_here)
}

/**
 * Masked passphrase entry with an eyeball toggle (ADR-0033). The text is hidden
 * by DEFAULT; tapping the eye reveals it so the user can verify what they typed
 * (a Trezor hidden-wallet passphrase is unforgiving: a typo silently opens a
 * different empty wallet). Shared by the Add hidden-wallet selector AND the
 * send / bump re-entry fields so the affordance is identical everywhere.
 */
@Composable
fun PassphraseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.wallet_passphrase),
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        supportingText = supportingText,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) stringResource(R.string.wallet_hide_passphrase) else stringResource(R.string.wallet_show_passphrase),
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Software | Hardware source picker (the two FilterChips lifted from
 * AddEthereumWalletScreen). [onSelect] is fired only when the chip actually
 * changes the selection so the caller can clear transient error text.
 */
@Composable
fun SourcePicker(
    selected: AddWalletSource,
    onSelect: (AddWalletSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_source),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == AddWalletSource.SOFTWARE,
                onClick = { if (selected != AddWalletSource.SOFTWARE) onSelect(AddWalletSource.SOFTWARE) },
                label = { Text(stringResource(R.string.wallet_software)) },
            )
            FilterChip(
                selected = selected == AddWalletSource.HARDWARE,
                onClick = { if (selected != AddWalletSource.HARDWARE) onSelect(AddWalletSource.HARDWARE) },
                label = { Text(stringResource(R.string.wallet_hardware)) },
            )
        }
    }
}

/**
 * Registered-device chips for the Hardware source. [devices] is already
 * filtered (the caller passes only Ledger / Trezor, or applies a per-chain
 * predicate). One chip per device, selected by id; [onSelect] fires on change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevicePicker(
    devices: List<RegisteredDevice>,
    selectedId: java.util.UUID?,
    onSelect: (RegisteredDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_device),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            devices.forEach { dev ->
                FilterChip(
                    selected = selectedId == dev.id,
                    onClick = { if (selectedId != dev.id) onSelect(dev) },
                    label = { Text(stringResource(R.string.wallet_device_label_kind, dev.label, dev.kind.displayName)) },
                )
            }
        }
    }
}

/**
 * The `[- N +]` account-index stepper, bounded to `[min, max]`. Generic across
 * chains (each derives one wallet per account index).
 */
@Composable
fun AccountStepper(
    account: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    min: Long = 0,
    max: Long = 20,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.wallet_account_label), modifier = Modifier.weight(1f))
        IconButton(onClick = { if (account > min) onChange(account - 1) }) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.wallet_decrease))
        }
        Text("$account", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { if (account < max) onChange(account + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.wallet_increase))
        }
    }
}

/**
 * Trezor-only hidden / passphrase ("second factor") selector, driven entirely
 * by the SDK [HiddenWalletSelection] enum (displayName / footer / isReady /
 * choice). Shows Standard / On device / Type here chips and, when host-typed,
 * a masked passphrase field. Renders nothing for a non-Trezor device. Promoted
 * from the hand-rolled UI in DiscoverHardwareWalletsScreen so add + discover
 * share one widget.
 */
@Composable
fun HiddenWalletSelector(
    deviceKind: DeviceKind?,
    selection: HiddenWalletSelection,
    onSelectionChange: (HiddenWalletSelection) -> Unit,
    hostPassphrase: String,
    onHostPassphraseChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (deviceKind != DeviceKind.TREZOR) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_passphrase),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HiddenWalletSelection.entries.forEach { sel ->
                FilterChip(
                    selected = selection == sel,
                    onClick = { if (enabled && selection != sel) onSelectionChange(sel) },
                    label = { Text(hiddenSelectionLabel(sel)) },
                )
            }
        }
        if (selection == HiddenWalletSelection.HOST_TYPED) {
            PassphraseField(
                value = hostPassphrase,
                onValueChange = onHostPassphraseChange,
                enabled = enabled,
            )
        }
        Text(
            selection.footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The single, prominent live connection-stage line for a hardware discover /
 * read (ADR-0033: "Discover surfaces explicit connection stages"). Renders an
 * icon + the human [HardwareStage.label] for [device], e.g. "Connecting to
 * Ledger Nano...", "Connected", "Confirm on your Trezor", "Scanning account
 * 2...", "Done". Pass the 0-based [account] for SCANNING. The spinner shows for
 * every in-progress stage; AWAITING_DEVICE gets a tap glyph (look at the
 * device) and DONE a check. Shared by both the inline (per-chain Add) and the
 * generic discover screens, and reused by Solana / Tron when their sweeps land.
 */
@Composable
fun HardwareStageLine(
    stage: HardwareStage,
    device: RegisteredDevice,
    account: Long?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (stage) {
            HardwareStage.AWAITING_DEVICE ->
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            HardwareStage.DONE ->
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            else ->
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stage.label(LocalContext.current, device, account),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
