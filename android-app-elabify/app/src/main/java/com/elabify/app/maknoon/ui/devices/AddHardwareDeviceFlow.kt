// "Pick a hardware device, then pair it" flow, ported 1:1 from iOS
// AddHardwareDeviceFlow + RegisterDeviceSheet.
//
// Step 1: pick a vendor (Ledger or Trezor; both are wallet-capable BLE
//         devices). Mirrors AddHardwareDeviceFlow's vendor list scoped to
//         the wallet-capable kinds.
// Step 2: connect over BLE just long enough to read the device's stable
//         serial via HardwareWallet.identifyDevice(), pinning one session
//         (beginSession/endSession) so the connect + read share a link.
//         Mirrors RegisterDeviceSheet.identify().
// Step 3: confirm the serial, edit the label, register into DeviceRegistry.
//         Registration is idempotent on (kind, serial) so re-running on the
//         same physical device returns the existing record.
//
// After a successful Ledger/Trezor register we offer the Bitcoin discover
// sweep right away (iOS autoDiscoverBitcoin): onFinished returns the freshly
// registered device so the parent can present DiscoverHardwareWalletsScreen.

package com.elabify.app.maknoon.ui.devices

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.elabify.app.maknoon.R
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.HardwareWalletFactory
import com.elabify.musnad.hardware.trezor.TrezorCredentialStore
import com.elabify.musnad.hardware.trezor.TrezorHardwareWallet
import com.elabify.musnad.hardware.trezor.TrezorPairingCoordinator
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import com.elabify.app.maknoon.yubikey.YubiKeyEnrollScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Vendors offered by the register picker. Ledger / Trezor are wallet-capable
 *  BLE devices; YubiKey is an identity-only FIDO2 key registered + enrolled
 *  over NFC (a different path, branched in [AddHardwareDeviceFlow]). */
private val registrableHardwareKinds = listOf(DeviceKind.LEDGER, DeviceKind.TREZOR, DeviceKind.YUBIKEY)

/**
 * The pick-vendor -> pair -> confirm -> register flow.
 *
 * @param onFinished called once registration succeeds. The argument is the
 *   freshly registered device when it is a wallet-capable hardware device
 *   the caller should immediately offer to discover wallets on (Ledger /
 *   Trezor), or null when no discover sweep should follow.
 * @param onCancel the user backed out without registering anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddHardwareDeviceFlow(
    registry: DeviceRegistry,
    onFinished: (discoverTarget: RegisteredDevice?) -> Unit,
    onCancel: () -> Unit,
) {
    var pendingKind by remember { mutableStateOf<DeviceKind?>(null) }

    when (val kind = pendingKind) {
        null ->
            VendorPicker(
                onPick = { pendingKind = it },
                onCancel = onCancel,
            )
        // A YubiKey is an identity key, not a wallet: it does NOT run the
        // Ledger/Trezor BLE wallet path (and never the MockHardwareWallet).
        // It reads its serial + runs a FIDO2 hmac-secret enroll over NFC.
        // No Bitcoin discover sweep follows, so onFinished gets null.
        DeviceKind.YUBIKEY ->
            YubiKeyEnrollScreen(
                registry = registry,
                onFinished = { _ -> onFinished(null) },
                onCancel = { pendingKind = null },
            )
        else ->
            RegisterDeviceSheet(
                registry = registry,
                kind = kind,
                onRegistered = { device -> onFinished(device) },
                onCancel = { pendingKind = null },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VendorPicker(
    onPick: (DeviceKind) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_register_device)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Listed alphabetically by vendor name (Ledger, Trezor, YubiKey).
            registrableHardwareKinds.sortedBy { it.displayName }.forEach { kind ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(kind) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (kind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kind.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (kind == DeviceKind.YUBIKEY) {
                                stringResource(R.string.devices_vendor_yubikey_desc)
                            } else {
                                stringResource(R.string.devices_vendor_wallet_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
                HorizontalDivider()
            }
            Text(
                stringResource(R.string.devices_vendor_picker_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Connect + read serial + confirm + register, mirroring iOS RegisterDeviceSheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterDeviceSheet(
    registry: DeviceRegistry,
    kind: DeviceKind,
    onRegistered: (RegisteredDevice) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext

    var phase by remember { mutableStateOf(RegisterPhase.READY) }
    var serial by remember { mutableStateOf("") }
    var blePeripheralId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf(kind.displayName) }
    var error by remember { mutableStateOf<String?>(null) }

    // Trezor CodeEntry pairing: the device shows a 6-digit code and the
    // Rust pairing flow calls back through this coordinator for it. Mirrors
    // iOS RegisterDeviceSheet's TrezorPairingCoordinator + TrezorCodeEntrySheet.
    val pairingCoordinator = remember { TrezorPairingCoordinator() }
    val awaitingCode by pairingCoordinator.awaitingCode.collectAsState()

    if (awaitingCode) {
        TrezorCodeEntryDialog(
            onSubmit = { pairingCoordinator.submit(it) },
            onCancel = { pairingCoordinator.cancel() },
        )
    }

    // The actual connect + read-serial round-trip. Hoisted so it can run
    // both directly (permissions already granted) and from the permission
    // launcher's grant callback.
    val startConnect: () -> Unit = {
        scope.launch {
            phase = RegisterPhase.CONNECTING
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    identify(kind, appContext, pairingCoordinator)
                }
            }
            result.onSuccess { (s, peripheral) ->
                serial = s
                blePeripheralId = peripheral
                label = kind.displayName
                phase = RegisterPhase.CAPTURED
            }.onFailure {
                error = it.friendlyMessage()
                phase = RegisterPhase.READY
            }
        }
    }

    // On Android 12+ BLUETOOTH_SCAN / BLUETOOTH_CONNECT are runtime
    // permissions; without them the SDK's BLE scan + GATT connect fail
    // silently. Request them just before the first connect, then proceed.
    // (CoreBluetooth prompts implicitly on iOS; Android needs this explicit
    // gate.) Below API 31 these are install-time and always granted.
    // Resolved in composable scope: the launcher callback below is a plain
    // lambda, where stringResource() is not callable.
    val blePermissionDenied = stringResource(R.string.devices_ble_permission_required, kind.displayName)
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) {
            startConnect()
        } else {
            error = blePermissionDenied
        }
    }

    val connect: () -> Unit = {
        val missing = missingBlePermissions(appContext)
        if (missing.isEmpty()) startConnect() else blePermissionLauncher.launch(missing)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_register_kind, kind.displayName)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (kind == DeviceKind.LEDGER) Icons.Filled.Memory else Icons.Filled.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(kind.displayName, style = MaterialTheme.typography.titleSmall)
                        // The body carries a %1$s for the transport. It was
                        // fetched with no argument, so the raw placeholder
                        // rendered on screen in EVERY language, English
                        // included. iOS supplies its `transportName` here; this
                        // is the Android peer of that switch.
                        Text(
                            stringResource(
                                R.string.devices_register_card_body,
                                when (kind) {
                                    DeviceKind.YUBIKEY -> "NFC"
                                    DeviceKind.LEDGER, DeviceKind.TREZOR ->
                                        stringResource(R.string.devices_transport_bluetooth)
                                    DeviceKind.SEEDSIGNER ->
                                        stringResource(R.string.devices_transport_camera)
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (phase) {
                RegisterPhase.READY -> {
                    Text(
                        when (kind) {
                            DeviceKind.LEDGER -> stringResource(R.string.devices_unlock_ledger)
                            DeviceKind.TREZOR -> stringResource(R.string.devices_unlock_trezor)
                            else -> stringResource(R.string.devices_unlock_generic)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = connect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.devices_connect_read_serial)) }
                }

                RegisterPhase.CONNECTING ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.devices_connecting_approve),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                RegisterPhase.CAPTURED -> {
                    Text(stringResource(R.string.devices_serial), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(serial, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.devices_label_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = serial.isNotEmpty(),
                        onClick = {
                            val finalLabel = label.trim().ifEmpty { kind.displayName }
                            // Idempotent on (kind, serial); upgrades the
                            // stored BLE peripheral id in place if changed.
                            val record = registry.register(
                                kind = kind,
                                serial = serial,
                                label = finalLabel,
                                blePeripheralId = blePeripheralId,
                            )
                            onRegistered(record)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.devices_register_button)) }
                    Text(
                        stringResource(R.string.devices_register_dedup_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private enum class RegisterPhase { READY, CONNECTING, CAPTURED }

/** BLE runtime permissions not yet granted, for the API level. On Android
 *  12+ (API 31) BLUETOOTH_SCAN + BLUETOOTH_CONNECT are runtime; below that
 *  they are install-time, so the list is always empty. */
private fun missingBlePermissions(context: android.content.Context): Array<String> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
    return listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        .toTypedArray()
}

/**
 * Connect to [kind] over its transport just long enough to read a stable
 * serial, returning (serial, blePeripheralId). Mirrors iOS
 * RegisterDeviceSheet.identify():
 *
 *  - Ledger has no THP-style pairing; a single APDU round-trip brings the
 *    GATT link up and the BLE MAC is the stable serial.
 *  - Trezor needs THP CodeEntry pairing to reach an encrypted session and
 *    read its real device_id, so we run establishPairedSession (prompting
 *    for the 6-digit code via [pairingCoordinator]) and persist the
 *    reconnect credential so later identity/signing ops reconnect silently.
 *    On an emulator the factory returns the mock (no real serial), so we
 *    fall back to identifyDevice() for any non-Trezor client.
 */
private suspend fun identify(
    kind: DeviceKind,
    appContext: android.content.Context,
    pairingCoordinator: TrezorPairingCoordinator,
): Pair<String, String?> {
    val wallet = HardwareWalletFactory.make(kind.hardwareWalletKind())
    // Pin the session so the connect + reads reuse one connection (one
    // handshake), like iOS beginSession()/endSession().
    wallet.beginSession()
    try {
        if (kind == DeviceKind.TREZOR && wallet is TrezorHardwareWallet) {
            val credentialStore = TrezorCredentialStore(appContext)
            val hostKey = credentialStore.hostStaticKey()
            // Explicit Register always does a FRESH CodeEntry pairing: resuming a
            // stored credential here is what broke re-register after a delete (a
            // stale credential makes the device drop the link before any THP
            // write). The fresh credential is saved below for later silent ops.
            val session = wallet.establishPairedSession(
                hostStaticPriv = hostKey,
                codeProvider = pairingCoordinator,
                storedCredential = null,
            )
            // Persist the fresh credential so later identity/signing ops
            // reconnect without re-entering the on-device code.
            credentialStore.saveCredential(session.credential)
            return session.serial to session.blePeripheralId
        }
        val s = wallet.identifyDevice()
        return s to wallet.currentBlePeripheralId
    } finally {
        wallet.endSession()
    }
}

/** 6-digit Trezor CodeEntry prompt, mirroring iOS TrezorCodeEntrySheet. */
@Composable
private fun TrezorCodeEntryDialog(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    val digits = code.filter { it.isDigit() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.devices_pair_trezor)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.devices_pair_trezor_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PassphraseField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                    label = stringResource(R.string.devices_pair_code_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = digits.length >= 6,
                onClick = { onSubmit(code) },
            ) { Text(stringResource(R.string.devices_pair_button)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
