// Vendor-neutral device management, ported 1:1 from iOS DevicesView +
// AddHardwareDeviceFlow + RegisterDeviceSheet + DiscoverHardwareWalletsView +
// DeviceDetailView + RemoveFromSandwichSheet.
//
// Entry point: DevicesScreen(). It hosts these sub-flows:
//
//   1. The registered-devices list (mirrors DevicesView). Each row shows
//      the device kind, label, truncated serial, and per-chain promotion
//      badges, and is tappable: tapping a row opens the per-device detail
//      screen (mirrors iOS DevicesView -> DeviceDetailView). The list stays
//      clean (no inline promote / demote / remove); those actions live on
//      the detail screen. A "Register device" button opens the add flow.
//
//   2. The per-device detail screen (mirrors iOS DeviceDetailView). Three
//      sections: device info (kind, serial, registered date, rename), the
//      second-factor section (gated on the device kind supporting identity:
//      "Add as second factor" when not enrolled, "Active second factor" +
//      "Remove from second factor" when enrolled), and a danger-zone "Remove
//      device".
//
//   3. The add-hardware flow (mirrors AddHardwareDeviceFlow +
//      RegisterDeviceSheet): pick Ledger or Trezor, connect over BLE just
//      long enough to read the device's stable serial (identifyDevice()),
//      confirm + label, then register into DeviceRegistry. Registration is
//      a lightweight handshake; it does NOT promote the device into any
//      network or into the second factor (those are separate, explicit
//      user actions).
//
//   4. The discover-wallets flow (mirrors DiscoverHardwareWalletsView):
//      pin one BLE session (beginSession/endSession) and walk account
//      indices on the device, reading each account's xpub/address via the
//      HardwareWallet contract, surfacing accounts the user can pull in.
//
// SECURITY MODEL FOR DEMOTE (mirrors iOS RemoveFromSandwichSheet): removing
// a device from the second factor requires STEP-UP AUTH. The user picks ANY
// currently-enrolled device to authorize the removal (the device being
// removed is the common case and IS offered; a different enrolled device is
// also allowed, e.g. to remove a lost device) and proves possession by
// reproducing its own wrap secret (YubiKey: NFC tap + PIN; Ledger / Trezor:
// connect + approve). That recovery IS the proof of possession. This applies
// to EVERY demote, including non-last: a drive-by attacker on an unlocked
// phone could otherwise silently strip enrolled devices because the wrap-
// envelope edit costs nothing. Biometric alone is NOT sufficient.
//
// This is the app module: the SDK api-exposes the hardware + devices
// types. Get Context via LocalContext. The orchestrator (MaknoonRoot)
// routes to DevicesScreen(); this file does NOT wire itself in.

package com.elabify.app.maknoon.ui.devices

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.devices.Capability
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.yubikey.HardwareSecondFactor
import com.elabify.app.maknoon.yubikey.SecondFactorRecoverDialog
import com.elabify.app.maknoon.yubikey.SecondFactorUnlock
import com.elabify.app.maknoon.yubikey.enrolledSecondFactors
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.hardware.trezor.TrezorCredentialStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.identity.SecondFactorWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/**
 * Single entry composable for the Devices screen. The orchestrator routes
 * here. [registry] is the SDK-owned, persisted device list; pass the one
 * long-lived instance the host holds (the same object every other screen
 * observes). [onBack] pops back to the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    registry: DeviceRegistry,
    onBack: () -> Unit = {},
) {
    // Bump to force a re-read of the (mutable, SDK-side) device list after
    // any register / discover / rename / promote / demote / remove round-trip.
    // Mirrors the iOS @Observable store invalidation.
    var version by remember { mutableIntStateOf(0) }
    val devices = remember(version) { registry.devices }

    // Scope + context + error state for persisting a hardware-discover
    // selection into the per-chain wallet stores (see the Discover route).
    val discoverScope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    var discoverError by remember { mutableStateOf<List<String>?>(null) }

    // Which sub-flow is showing. List = the device list. Mirrors the iOS
    // navigation stack (DevicesView -> DeviceDetailView) + sheets.
    var route by remember { mutableStateOf<DevicesRoute>(DevicesRoute.List) }

    when (val r = route) {
        DevicesRoute.List -> Unit
        is DevicesRoute.Detail ->
            // Detail is keyed on id so the screen always re-reads the live
            // device after a promote / demote / rename mutates the registry.
            DeviceDetailScreen(
                registry = registry,
                deviceId = r.deviceId,
                version = version,
                onMutated = { version++ },
                onRemoved = { version++; route = DevicesRoute.List },
                onBack = { route = DevicesRoute.List },
            )
        is DevicesRoute.Add ->
            AddHardwareDeviceFlow(
                registry = registry,
                onFinished = { discoverTarget ->
                    version++
                    route = if (discoverTarget != null) {
                        DevicesRoute.Discover(discoverTarget)
                    } else {
                        DevicesRoute.List
                    }
                },
                onCancel = { route = DevicesRoute.List },
            )
        is DevicesRoute.Discover ->
            DiscoverHardwareWalletsScreen(
                registry = registry,
                device = r.device,
                onDone = { selected ->
                    // Persist the user's selection into the per-chain wallet
                    // stores (mirrors iOS DiscoverHardwareWalletsView.addSelected
                    // and the per-chain Discover views). An empty list = the user
                    // closed without adding; just return to the list. The persist
                    // touches the SharedPreferences-backed stores (I/O) so it runs
                    // off the main thread; on completion we bump version (so the
                    // device list re-reads the new promotions) and pop back.
                    if (selected.isEmpty()) {
                        version++
                        route = DevicesRoute.List
                    } else {
                        discoverScope.launch {
                            val failures = withContext(Dispatchers.IO) {
                                persistDiscoveredSelection(
                                    context = appContext,
                                    registry = registry,
                                    device = r.device,
                                    selected = selected,
                                )
                            }
                            discoverError = failures.takeIf { it.isNotEmpty() }
                            version++
                            route = DevicesRoute.List
                        }
                    }
                },
            )
    }

    // Surface a brief error if any account failed to persist, rather than
    // silently dropping it. Per-account successes still land in the stores.
    discoverError?.let { messages ->
        AlertDialog(
            onDismissRequest = { discoverError = null },
            title = { Text(stringResource(R.string.devices_some_wallets_not_added)) },
            text = {
                Text(
                    messages.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { TextButton(onClick = { discoverError = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    if (route is DevicesRoute.List) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.devices_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { route = DevicesRoute.Add }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.devices_register_device))
                        }
                    },
                )
            },
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (devices.isEmpty()) {
                    item { EmptyDevicesCard() }
                } else {
                    items(devices, key = { it.id }) { dev ->
                        // The row is a navigation target (mirrors iOS
                        // DevicesView's NavigationLink to DeviceDetailView).
                        // Per-device actions (promote / demote / remove) live
                        // on the detail screen; the list stays clean.
                        DeviceRow(
                            dev,
                            onClick = { route = DevicesRoute.Detail(dev.id) },
                        )
                        HorizontalDivider()
                    }
                }

                item {
                    OutlinedButton(
                        onClick = { route = DevicesRoute.Add },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(stringResource(R.string.devices_register_device))
                    }
                }

            }
        }
    }
}

// ===========================================================================
// DeviceDetailScreen (mirrors iOS DeviceDetailView). Three sections:
//
//   1. infoSection      -> kind, registered date, serial, rename
//   2. secondFactorSection (gated on Capability.IDENTITY) ->
//        not enrolled : "Add as second factor" (PromoteToSecondFactorDialog)
//        enrolled     : "Active second factor" + destructive "Remove from
//                        second factor" (RemoveFromSecondFactorDialog step-up)
//   3. dangerSection    -> "Remove device" (full unregister; wallets survive
//                          in watch-only). If the device currently carries a
//                          wrap, removal safely turns the second factor off
//                          first (anti-brick), then forgets the device.
//
// User-facing copy says "second factor" / "security key"; never the internal
// IdentitySandwich name.
// ===========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailScreen(
    registry: DeviceRegistry,
    deviceId: UUID,
    version: Int,
    onMutated: () -> Unit,
    onRemoved: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }

    // Always read the live device by id, re-reading whenever the registry
    // version bumps, so the section state reflects a just-applied mutation.
    val device = remember(version, deviceId) { registry.find(deviceId) }

    var renaming by remember { mutableStateOf(false) }
    var renameDraft by remember(device?.label) { mutableStateOf(device?.label ?: "") }

    // The device being added as a second factor (drives the promote dialog).
    var promoteTarget by remember { mutableStateOf<RegisteredDevice?>(null) }
    // The device being removed from the second factor (drives the step-up
    // RemoveFromSecondFactorDialog). Mirrors iOS removeAuthForDeviceId.
    var removeFromFactorTarget by remember { mutableStateOf<RegisteredDevice?>(null) }
    // The device pending full unregister (danger zone). For a device that is
    // currently a second factor, removal routes through the step-up sheet
    // first (anti-brick: never registry.remove a device while it still holds
    // the only wrap with 2FA on).
    var removeDeviceTarget by remember { mutableStateOf<RegisteredDevice?>(null) }
    var removeDeviceConfirm by remember { mutableStateOf<RegisteredDevice?>(null) }

    /** Forget the device + clear vendor credential. Called once any required
     *  turn-off / demote step-up has run (or the device was never a second
     *  factor). Wallets created from the device stay in the wallet list in
     *  watch-only mode (matching iOS's footer wording). */
    fun forget(dev: RegisteredDevice) {
        registry.remove(dev.id)
        // Drop a removed Trezor's reconnection credential so a later
        // re-register pairs fresh instead of resuming a stale credential the
        // device will reject.
        if (dev.kind == DeviceKind.TREZOR) {
            runCatching { TrezorCredentialStore(context).clearCredential() }
        }
    }

    promoteTarget?.let { dev ->
        PromoteToSecondFactorDialog(
            activity = context as? FragmentActivity,
            registry = registry,
            store = store,
            device = dev,
            onPromoted = { promoteTarget = null; onMutated() },
            onCancel = { promoteTarget = null },
        )
    }

    // Standalone demote ("Remove from second factor"), with step-up auth.
    removeFromFactorTarget?.let { dev ->
        RemoveFromSecondFactorDialog(
            activity = context as? FragmentActivity,
            registry = registry,
            store = store,
            deviceToRemove = dev,
            onCompleted = { removeFromFactorTarget = null; onMutated() },
            onCancel = { removeFromFactorTarget = null },
        )
    }

    // Danger-zone "Remove device". If the device currently carries a wrap, the
    // step-up demote must run first (turning 2FA off if it was the last
    // factor), then we forget the device. Otherwise just confirm + forget.
    removeDeviceTarget?.let { dev ->
        // Device is a current second factor: route through the step-up sheet
        // first, then forget. Same any-enrolled-device authorizer model.
        RemoveFromSecondFactorDialog(
            activity = context as? FragmentActivity,
            registry = registry,
            store = store,
            deviceToRemove = dev,
            onCompleted = {
                removeDeviceTarget = null
                forget(dev)
                onRemoved()
            },
            onCancel = { removeDeviceTarget = null },
        )
    }

    removeDeviceConfirm?.let { dev ->
        AlertDialog(
            onDismissRequest = { removeDeviceConfirm = null },
            title = { Text(stringResource(R.string.devices_remove_confirm_title, dev.label)) },
            text = {
                Text(stringResource(R.string.devices_remove_confirm_message))
            },
            confirmButton = {
                TextButton(onClick = {
                    removeDeviceConfirm = null
                    forget(dev)
                    onRemoved()
                }) { Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { removeDeviceConfirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device?.label ?: stringResource(R.string.devices_device)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        if (device == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    stringResource(R.string.devices_no_longer_registered),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            // --- info section ---
            item {
                DetailSectionHeader(stringResource(R.string.devices_device_info))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (device.kind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(device.kind.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.devices_registered_on, formatDate(device.registeredAtEpochMs)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column {
                            Text(
                                stringResource(R.string.devices_serial),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                device.serial,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (renaming) {
                            OutlinedTextField(
                                value = renameDraft,
                                onValueChange = { renameDraft = it },
                                label = { Text(stringResource(R.string.common_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    registry.rename(device.id, renameDraft.trim())
                                    renaming = false
                                    onMutated()
                                }) { Text(stringResource(R.string.common_save)) }
                                TextButton(onClick = {
                                    renameDraft = device.label
                                    renaming = false
                                }) { Text(stringResource(R.string.common_cancel)) }
                            }
                        } else {
                            OutlinedButton(onClick = { renaming = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.devices_rename))
                            }
                        }
                    }
                }
            }

            // --- second-factor section (gated on Capability.IDENTITY) ---
            if (Capability.IDENTITY in device.kind.capabilities) {
                item {
                    DetailSectionHeader(stringResource(R.string.devices_second_factor))
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (device.promotions.identity?.hasSecondFactorWrap == true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(stringResource(R.string.devices_active_second_factor), style = MaterialTheme.typography.titleSmall)
                                        device.promotions.identity?.let { promo ->
                                            Text(
                                                stringResource(R.string.devices_enrolled_on, formatDate(promo.enrolledAtEpochMs)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { removeFromFactorTarget = device },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Filled.KeyOff, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.devices_remove_from_second_factor))
                                }
                            } else {
                                Text(
                                    stringResource(promotionPrompt(device.kind)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedButton(
                                    onClick = { promoteTarget = device },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text(stringResource(R.string.devices_add_as_second_factor))
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(promotionFooter(device.kind)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }

            // --- danger zone ---
            item {
                DetailSectionHeader(stringResource(R.string.devices_danger_zone))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = {
                                // If the device currently holds a wrap, removal
                                // must demote it first (step-up auth; turns 2FA
                                // off if it was the last factor) before we forget
                                // it. Otherwise a plain confirm + forget.
                                if (device.promotions.identity?.hasSecondFactorWrap == true) {
                                    removeDeviceTarget = device
                                } else {
                                    removeDeviceConfirm = device
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.devices_remove_device))
                        }
                    }
                }
                Text(
                    stringResource(R.string.devices_remove_device_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

private fun formatDate(epochMs: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

@StringRes
private fun promotionPrompt(kind: DeviceKind): Int = when (kind) {
    DeviceKind.YUBIKEY -> R.string.devices_promote_prompt_yubikey
    DeviceKind.LEDGER -> R.string.devices_promote_prompt_ledger
    DeviceKind.TREZOR -> R.string.devices_promote_prompt_trezor
    DeviceKind.SEEDSIGNER -> R.string.devices_promote_prompt_seedsigner
}

@StringRes
private fun promotionFooter(kind: DeviceKind): Int = when (kind) {
    DeviceKind.YUBIKEY -> R.string.devices_promote_footer_yubikey
    DeviceKind.LEDGER, DeviceKind.TREZOR -> R.string.devices_promote_footer_ble
    DeviceKind.SEEDSIGNER -> R.string.devices_promote_footer_seedsigner
}

// ===========================================================================
// RemoveFromSecondFactorDialog (mirrors iOS RemoveFromSandwichSheet). The
// STEP-UP AUTH gate for removing a device from the second factor.
//
// Without this gate, a drive-by attacker with an unlocked phone could silently
// strip enrolled devices by tapping "Remove" repeatedly: the wrap-envelope edit
// costs nothing on the multi-device branch. So EVERY demote (last or not) goes
// through here.
//
// Any currently-enrolled device can authorize the removal:
//   - Removing your own device (most common): pick the device-being-removed as
//     the authorizer. One tap, done. (Unlike the recover-then-add flow, demote
//     does NOT exclude the device being removed: it must be offered.)
//   - Removing a lost / damaged device: pick a DIFFERENT enrolled device. The
//     "any one of N enrolled devices unlocks" property extends to "any one of N
//     enrolled devices can demote another".
//
// The authorization proof is a successful recovery of the CEK from the
// authorizing device's OWN wrap blob: the only way to produce that secret is to
// hold the device and pass its PIN (YubiKey) or button press (Ledger / Trezor)
// right now. SecondFactorRecoverDialog (with exclude = null) IS that proof.
//
// On success, in order:
//   1. setIdentityPromotion(deviceToRemove.id, null) -- clears ONLY that
//      device's wrap; the device stays registered and wallet-usable.
//   2. If the removed device was the LAST enrolled second factor, use the
//      recovered CEK -> loadWithSecondFactor { cek } -> entropy ->
//      disableSecondFactor(entropy, passphrase). The device stays registered.
//
// Cancel = abort with NO changes (never half-applied).
// User-facing copy says "second factor" / "security key".
// ===========================================================================
@Composable
private fun RemoveFromSecondFactorDialog(
    activity: FragmentActivity?,
    registry: DeviceRegistry,
    store: IdentityStore,
    deviceToRemove: RegisteredDevice,
    onCompleted: () -> Unit,
    onCancel: () -> Unit,
) {
    val removalFailedMsg = stringResource(R.string.devices_could_not_complete_removal)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Apply the demotion once the step-up auth has produced the recovered CEK.
    // The CEK is BOTH the proof of possession AND the material we need to
    // re-seal the entropy under the plain key store if this was the last
    // factor. Ordering matters: clear the wrap first, then check whether any
    // OTHER device still has a wrap. If none, turn 2FA off using this CEK.
    fun applyDemote(recoveredCek: ByteArray) {
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                // 1. Clear ONLY this device's wrap. Keeps it registered and its
                //    wallet promotions (bitcoin/ethereum/solana/tron) intact.
                registry.setIdentityPromotion(deviceToRemove.id, null)

                // 2. Was it the last enrolled second factor? Re-read the live
                //    registry AFTER clearing this device's wrap.
                val othersRemain = registry.devices.any {
                    it.id != deviceToRemove.id &&
                        it.promotions.identity?.hasSecondFactorWrap == true
                }
                if (!othersRemain && store.secondFactorEnabled()) {
                    // Last factor: re-seal the entropy under the plain key store
                    // so the wallet stays usable (anti-brick). Use the CEK we
                    // already recovered as the step-up proof -- no second tap.
                    val sandwich = IdentitySandwich.loadWithSecondFactor(store) { recoveredCek }
                        ?: throw IllegalStateException(
                            "Could not unlock with this security key.",
                        )
                    val entropy = sandwich.rootEntropy()
                    val passphrase = store.loadPassphrase() ?: ""
                    withContext(Dispatchers.IO) {
                        store.disableSecondFactor(entropy, passphrase)
                    }
                }
            }
            busy = false
            result.onSuccess { onCompleted() }
                .onFailure {
                    // The wrap was already cleared in step 1. If the last-factor
                    // re-seal failed we surface the error; the user can retry
                    // turning 2FA off via the recovery phrase / backup path. The
                    // demoted device's wrap stays cleared, which is the intended
                    // direction (never re-add a wrap silently).
                    error = it.message
                        ?: removalFailedMsg
                }
        }
    }

    if (error != null && !busy) {
        AlertDialog(
            onDismissRequest = { onCancel() },
            title = { Text(stringResource(R.string.devices_removal_failed)) },
            text = {
                Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { onCancel() }) { Text(stringResource(R.string.common_close)) }
            },
        )
        return
    }

    // Step-up auth: any enrolled device authorizes the removal. Crucially we do
    // NOT exclude deviceToRemove -- it must be offered as an authorizer (the
    // common case). A different enrolled device is also allowed (lost-device
    // case). The dialog auto-selects when exactly one factor is enrolled and
    // shows a picker when more than one is enrolled. SecondFactorRecoverDialog
    // surfaces the "no enrolled key -> use 24-word phrase / backup" path itself.
    SecondFactorRecoverDialog(
        activity = activity,
        registry = registry,
        title = stringResource(R.string.devices_authorize_removal),
        message = stringResource(R.string.devices_authorize_removal_message, deviceToRemove.label),
        exclude = null,
        onRecovered = { cek -> applyDemote(cek) },
        onError = { error = it },
        onCancel = onCancel,
    )
}

// ---------------------------------------------------------------------------
// PromoteToSecondFactorDialog (ADR-0032 "OR-among-keys"). Promotes a registered
// Ledger / Trezor to the wallet's second factor using a deterministic device
// signature, supporting ANY number of enrolled keys of ANY mix of types:
//
//   - 2FA OFF (first factor): load the unlocked sandwich, generate a fresh
//     deviceSalt, connect + sign the fixed challenge on the device to derive the
//     secret, seal the entropy under a FRESH CEK, flip the store to 2FA on, and
//     record the per-device wrap envelope.
//   - 2FA ON (add): FIRST recover the EXISTING CEK by confirming with an
//     already-enrolled device (the shared recovery coordinator routes by kind);
//     then connect + sign the NEW device and seal its wrappedCEK over that SAME
//     CEK (existingCek = recovered). The CEK is NEVER rotated, so every already-
//     enrolled key keeps working.
//
// User-facing copy says "second factor", never the internal sandwich name.
// ---------------------------------------------------------------------------
@Composable
private fun PromoteToSecondFactorDialog(
    activity: FragmentActivity?,
    registry: DeviceRegistry,
    store: IdentityStore,
    device: RegisteredDevice,
    onPromoted: () -> Unit,
    onCancel: () -> Unit,
) {
    val addSecondFactorFailedMsg = stringResource(R.string.devices_could_not_add_second_factor)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Read once when the dialog opens: is a second factor already enrolled?
    val alreadyEnrolled = remember { store.secondFactorEnabled() }
    // When adding to an existing factor, the shared recovery dialog runs first.
    var recovering by remember { mutableStateOf(false) }

    // Seal this device's wrappedCEK. [existingCek] != null reuses the recovered
    // CEK (add path); null generates a fresh CEK (first factor). The CEK is the
    // same for every enrolled device, so add never orphans an existing key.
    fun promoteWith(existingCek: ByteArray?) {
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                val sandwich = withContext(Dispatchers.IO) {
                    if (existingCek != null) {
                        IdentitySandwich.loadWithSecondFactor(store) { existingCek }
                            ?: throw IllegalStateException(
                                "Could not unlock with your existing security key.",
                            )
                    } else {
                        IdentitySandwich.load(store)
                            ?: throw IllegalStateException(
                                "No identity to protect. Create your identity first.",
                            )
                    }
                }
                // Fresh per-enroll salt; compute the deterministic secret over the
                // device (the user approves on it).
                val deviceSalt = SecondFactorWrap.newDeviceSalt()
                val secret = HardwareSecondFactor(context, device).computeSecret(deviceSalt)
                val seal = withContext(Dispatchers.IO) {
                    IdentitySandwich.sealForSecondFactorEnroll(
                        sandwich = sandwich,
                        store = store,
                        hmacSecret = secret,
                        deviceSalt = deviceSalt,
                        existingCek = existingCek,
                    )
                }
                registry.setIdentityPromotion(
                    deviceId = device.id,
                    promotion = RegisteredDevice.IdentityPromotion(
                        credentialIdHex = device.serial,
                        enrolledAtEpochMs = System.currentTimeMillis(),
                        wrapProtocolVersion = 2,
                        deviceSaltHex = deviceSalt.toHex(),
                        wrappedCekHex = seal.wrappedCekHex,
                    ),
                )
            }
            busy = false
            result.onSuccess { onPromoted() }
                .onFailure {
                    error = it.message
                        ?: addSecondFactorFailedMsg
                }
        }
    }

    // 2FA already on: first confirm with an already-enrolled device to recover
    // the existing CEK (exclude THIS device, which is not yet enrolled anyway),
    // then promote reusing that CEK.
    if (recovering) {
        SecondFactorRecoverDialog(
            activity = activity,
            registry = registry,
            title = stringResource(R.string.devices_confirm_with_enrolled_key),
            message = stringResource(R.string.devices_confirm_with_enrolled_key_message, device.kind.displayName),
            exclude = device,
            onRecovered = { cek -> recovering = false; promoteWith(cek) },
            onError = { error = it; recovering = false },
            onCancel = { recovering = false; onCancel() },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(stringResource(R.string.devices_add_as_second_factor_title, device.label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (alreadyEnrolled) {
                        stringResource(R.string.devices_promote_body_enrolled, device.kind.displayName)
                    } else {
                        stringResource(R.string.devices_promote_body_first, device.kind.displayName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when (device.kind) {
                        DeviceKind.LEDGER ->
                            stringResource(R.string.devices_promote_approve_ledger)
                        else -> stringResource(R.string.devices_promote_approve_trezor)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (busy) {
                    Text(
                        stringResource(R.string.devices_promote_connecting, device.kind.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    error = null
                    if (alreadyEnrolled) {
                        recovering = true
                    } else {
                        promoteWith(null)
                    }
                },
            ) { Text(stringResource(R.string.devices_connect_and_add)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// ===========================================================================
// persistDiscoveredSelection (mirrors iOS DiscoverHardwareWalletsView.addSelected
// + the per-chain Discover views). For each selected account it routes by chain,
// builds the chain's Hardware wallet descriptor, dedups against wallets already
// bound to THIS device on the SAME network (skipped, not duplicated), adds it to
// that chain's process-wide wallet store (the same store the per-chain
// WalletsScreen renders), and records the device->wallet promotion on the
// registry so the device row shows the chain badge. Runs on Dispatchers.IO
// (the stores write SharedPreferences + the Bitcoin path resolves a descriptor).
//
// Returns a list of human messages for accounts that could NOT be persisted
// (an unexpected store error). A skipped-because-duplicate account is NOT an
// error and is omitted. An empty return means every selected account was added
// or was already present.
// ===========================================================================
internal fun persistDiscoveredSelection(
    context: android.content.Context,
    registry: DeviceRegistry,
    device: RegisteredDevice,
    selected: List<DiscoveredHardwareAccount>,
): List<String> {
    val failures = mutableListOf<String>()
    for (acct in selected) {
        runCatching {
            when (acct.chain) {
                DiscoverChain.ETHEREUM -> persistEthereum(context, registry, device, acct)
                DiscoverChain.SOLANA -> persistSolana(context, registry, device, acct)
                DiscoverChain.TRON -> persistTron(context, registry, device, acct)
                DiscoverChain.BITCOIN -> persistBitcoin(context, registry, device, acct)
            }
        }.onFailure {
            failures.add(
                "${acct.chainDisplayName} account ${acct.account}: ${it.message ?: it.toString()}",
            )
        }
    }
    return failures
}

/** Ethereum: Hardware(deviceId, account, address). EOAs are chain-agnostic
 *  (one key -> the same EIP-55 address on every EVM chain), so a single
 *  descriptor covers every EVM network; we add it with its current network
 *  set to the CHOSEN sweep network (e.g. Sepolia) so the new wallet opens on
 *  the network the user discovered it on. Dedup on (deviceId, account) via the
 *  store's hasHardwareWallet (one EVM wallet spans all networks). */
private fun persistEthereum(
    context: android.content.Context,
    registry: DeviceRegistry,
    device: RegisteredDevice,
    acct: DiscoveredHardwareAccount,
) {
    val store = com.elabify.app.maknoon.ui.wallet.ethereum.EthereumStores.walletStore(context)
    // Re-link recovery (ADR-0033): repoint a same-address wallet that points at
    // a stale device id instead of creating a duplicate.
    val orphan = store.wallets.firstOrNull { w ->
        val k = w.kind as? com.elabify.musnad.wallet.ethereum.EthereumWalletKind.Hardware
        k != null && k.address.equals(acct.identity, ignoreCase = true) &&
            k.deviceId != device.id && registry.find(k.deviceId) == null
    }
    if (orphan != null) {
        store.relinkDeviceId(orphan.id, device.id, HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId)?.wireId)
        registry.addEthereumWallet(device.id, orphan.id)
        return
    }
    if (store.hasHardwareWallet(device.id, acct.account)) return
    val net = (SweepNetwork.fromRawValue(acct.chain, acct.networkRawValue) as SweepNetwork.Ethereum).network
    val descriptor = com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor(
        label = "${device.label} Ethereum #${acct.account}",
        kind = com.elabify.musnad.wallet.ethereum.EthereumWalletKind.Hardware(
            deviceId = device.id,
            account = acct.account,
            address = acct.identity,
        ),
        cachedAddress = acct.identity,
    ).apply {
        // Record the Trezor hidden-wallet config the sweep read this account
        // under so a host-typed hidden wallet re-prompts on send (Ethereum
        // stores hidden as the bare wire id String, not a JSONObject). Null =
        // standard wallet, exactly as the manual Add path leaves it.
        hidden = HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId)?.wireId
    }
    // makeActive = false: discovery should not hijack the user's active wallet.
    store.add(descriptor, initialNetwork = net, makeActive = false)
    registry.addEthereumWallet(device.id, descriptor.id)
}

/** Solana: Hardware(deviceId, account, publicKeyBase58). Descriptor is
 *  cluster-agnostic; add on the CHOSEN cluster (e.g. Devnet). Dedup on
 *  (deviceId, account, cluster) so the same account can be added on Mainnet
 *  and Devnet separately (Solana has no public hasHardwareWallet helper). */
private fun persistSolana(
    context: android.content.Context,
    registry: DeviceRegistry,
    device: RegisteredDevice,
    acct: DiscoveredHardwareAccount,
) {
    val store = com.elabify.app.maknoon.ui.wallet.solana.SolanaEnv.get(context).walletStore
    val net = (SweepNetwork.fromRawValue(acct.chain, acct.networkRawValue) as SweepNetwork.Solana).network
    // Re-link recovery (ADR-0033): a wallet with this exact account public key
    // already exists but points at a stale device id (e.g. the device was
    // removed + re-added, minting a new id, orphaning the wallet). The matching
    // pubkey proves it is the same key this device derives, so repoint it to
    // this device instead of creating a duplicate.
    val orphan = store.wallets.firstOrNull { w ->
        val k = w.kind as? com.elabify.musnad.wallet.solana.SolanaWalletKind.Hardware
        k != null && k.publicKeyBase58 == acct.identity &&
            k.deviceId != device.id && registry.find(k.deviceId) == null
    }
    if (orphan != null) {
        store.relinkDeviceId(orphan.id, device.id, HardwarePassphraseRef.toJson(HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId)))
        registry.addSolanaWallet(device.id, orphan.id)
        return
    }
    val exists = store.wallets.any { w ->
        val k = w.kind as? com.elabify.musnad.wallet.solana.SolanaWalletKind.Hardware
        k != null && k.deviceId == device.id && k.account == acct.account &&
            store.currentNetworkByWallet[w.id] == net
    }
    if (exists) return
    val descriptor = com.elabify.musnad.wallet.solana.SolanaWalletDescriptor(
        label = "${device.label} Solana #${acct.account}",
        kind = com.elabify.musnad.wallet.solana.SolanaWalletKind.Hardware(
            deviceId = device.id,
            account = acct.account,
            publicKeyBase58 = acct.identity,
        ),
    ).apply {
        // Record the Trezor hidden-wallet config the sweep read this account
        // under (JSONObject {"ref": wireId}) so a host-typed hidden wallet
        // re-prompts on send. Null = standard, as the manual Add path leaves it.
        hidden = HardwarePassphraseRef.toJson(HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId))
    }
    store.add(descriptor, initialNetwork = net, makeActive = false)
    registry.addSolanaWallet(device.id, descriptor.id)
}

/** Tron: Hardware(deviceId, account, addressBase58Check). Network-agnostic
 *  descriptor; add on the CHOSEN network (e.g. Nile). Dedup on
 *  (deviceId, account, network) so the same account can be added on Mainnet
 *  and Nile separately. */
private fun persistTron(
    context: android.content.Context,
    registry: DeviceRegistry,
    device: RegisteredDevice,
    acct: DiscoveredHardwareAccount,
) {
    val store = com.elabify.app.maknoon.ui.wallet.tron.TronStores.walletStore(context)
    val net = (SweepNetwork.fromRawValue(acct.chain, acct.networkRawValue) as SweepNetwork.Tron).network
    // Re-link recovery (ADR-0033): repoint a same-address wallet that points at
    // a stale device id instead of creating a duplicate.
    val orphan = store.wallets.firstOrNull { w ->
        val k = w.kind as? com.elabify.musnad.wallet.tron.TronWalletKind.Hardware
        k != null && k.addressBase58Check == acct.identity &&
            k.deviceId != device.id && registry.find(k.deviceId) == null
    }
    if (orphan != null) {
        store.relinkDeviceId(orphan.id, device.id, HardwarePassphraseRef.toJson(HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId)))
        registry.addTronWallet(device.id, orphan.id)
        return
    }
    val exists = store.wallets.any { w ->
        val k = w.kind as? com.elabify.musnad.wallet.tron.TronWalletKind.Hardware
        k != null && k.deviceId == device.id && k.account == acct.account &&
            store.currentNetworkByWallet[w.id] == net
    }
    if (exists) return
    val descriptor = com.elabify.musnad.wallet.tron.TronWalletDescriptor(
        label = "${device.label} Tron #${acct.account}",
        kind = com.elabify.musnad.wallet.tron.TronWalletKind.Hardware(
            deviceId = device.id,
            account = acct.account,
            addressBase58Check = acct.identity,
        ),
    ).apply {
        // Record the Trezor hidden-wallet config the sweep read this account
        // under (JSONObject {"ref": wireId}) so a host-typed hidden wallet
        // re-prompts on send. Null = standard, as the manual Add path leaves it.
        hidden = HardwarePassphraseRef.toJson(HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId))
    }
    store.add(descriptor, initialNetwork = net, makeActive = false)
    registry.addTronWallet(device.id, descriptor.id)
}

/** Bitcoin: identity is the ACCOUNT XPUB. The sweep ran on the CHOSEN network
 *  (Mainnet / Testnet3 / Signet) using that network's coin type, and read the
 *  device master fingerprint once in-session, carried on the account; persist a
 *  Hardware (watch-only) descriptor on that SAME network (so the descriptor is
 *  built for the chosen bdk Network at sync time) with the fingerprint + xpub
 *  WITHOUT re-touching the device. Dedup on (deviceId, network, xpub),
 *  mirroring iOS. */
private fun persistBitcoin(
    context: android.content.Context,
    registry: DeviceRegistry,
    device: RegisteredDevice,
    acct: DiscoveredHardwareAccount,
) {
    val network = (SweepNetwork.fromRawValue(acct.chain, acct.networkRawValue) as SweepNetwork.Bitcoin).network
    val store = com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletEnv.create(context).store
    val xpub = acct.identity
    // Re-link recovery (ADR-0033): repoint a same-xpub wallet that points at a
    // stale device id instead of creating a duplicate. Matched on the account
    // xpub (the derived key), so it is provably the same device + account.
    val orphan = store.wallets.firstOrNull { w ->
        val k = w.kind as? com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind.Hardware
        w.network == network && k != null && k.accountXpub == xpub &&
            k.deviceId != device.id && registry.find(k.deviceId) == null
    }
    if (orphan != null) {
        store.relinkDeviceId(orphan.id, device.id, HardwarePassphraseRef.toJson(HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId)))
        registry.addBitcoinWallet(device.id, orphan.id)
        return
    }
    val exists = store.wallets.any { w ->
        val k = w.kind as? com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind.Hardware
        w.network == network && k != null && k.deviceId == device.id && k.accountXpub == xpub
    }
    if (exists) return
    val fingerprint = acct.bitcoinFingerprint
        ?: throw IllegalStateException("missing device fingerprint from the sweep")
    val descriptor = com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor(
        label = "${device.label} Bitcoin #${acct.account}",
        kind = com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind.Hardware(
            deviceId = device.id,
            accountFingerprint = fingerprint,
            accountXpub = xpub,
            account = acct.account,
        ),
        network = network,
    ).apply {
        // Record the Trezor hidden-wallet config the sweep read this account
        // under (JSONObject {"ref": wireId}) so a host-typed hidden wallet's
        // send screen re-prompts (needsHostPassphrase) instead of signing the
        // standard wallet and being rejected by the device. Null = standard,
        // exactly as AddBitcoinWalletScreen.createHardware leaves a standard add.
        hidden = HardwarePassphraseRef.toJson(HardwarePassphraseRef.fromWireId(acct.hiddenRefWireId))
    }
    store.add(descriptor, makeActive = false)
    registry.addBitcoinWallet(device.id, descriptor.id)
}

/** What the Devices entry composable is currently showing. */
private sealed interface DevicesRoute {
    data object List : DevicesRoute
    data class Detail(val deviceId: UUID) : DevicesRoute
    data object Add : DevicesRoute
    data class Discover(val device: RegisteredDevice) : DevicesRoute
}

// MARK: -- list cells

@Composable
private fun EmptyDevicesCard() {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.devices_none_registered_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.devices_none_registered_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeviceRow(
    dev: RegisteredDevice,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (dev.kind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dev.label, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.devices_kind_serial, dev.kind.displayName, dev.serialDisplay),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dev.promotions.identity?.hasSecondFactorWrap == true) {
                    PromotionBadge(stringResource(R.string.devices_badge_second_factor))
                } else if (dev.promotions.identity != null) {
                    PromotionBadge(stringResource(R.string.devices_badge_identity))
                }
                if (dev.promotions.bitcoinWalletIds.isNotEmpty()) PromotionBadge(stringResource(R.string.devices_badge_bitcoin))
                if (dev.promotions.ethereumWalletIds.isNotEmpty()) PromotionBadge(stringResource(R.string.devices_badge_ethereum))
                if (dev.promotions.solanaWalletIds.isNotEmpty()) PromotionBadge(stringResource(R.string.devices_badge_solana))
                if (dev.promotions.tronWalletIds.isNotEmpty()) PromotionBadge(stringResource(R.string.devices_badge_tron))
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PromotionBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
