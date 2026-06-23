// YubiKey register + second-factor FIDO2 enroll (ADR-0032), built on
// yubikit-android's Ctap2Session + ClientPin (no hand-rolled CTAP2).
//
// Flow design (learned on device): a YubiKey is enrolled in a SINGLE tap.
// We do NOT read the Management serial first: many keys (the FIDO-only
// "Security Key" line, and NFC-restricted keys) have no Management applet and
// answer 0x6D00, and the second-factor wrap binds to the FIDO credential id,
// not the serial (the hmac-secret output is independent of the clientDataHash
// the serial feeds). A separate serial tap only added a second NFC pass, more
// "Transceive failed" connection drops, and a confusing screen that looked
// identical to the PIN screen. So: collect the PIN up front, then one tap runs
// openFido + makeCredential + getAssertion to completion. The registry identity
// falls back to the credential id when there is no serial.
//
// PIN handling (fixes the iOS bug): we read getInfo on the tap. If a clientPin
// is set we use the PIN the user entered; if hmac-secret needs UV but NO PIN is
// set, we tell the user to set one first instead of silently prompting for a
// non-existent PIN.
//
// NFC radio ownership: YubiKeyNfcController is the single owner while this
// screen is up (DisposableEffect releases it on exit), the same discipline the
// passport reader uses, so the two never fight over NfcAdapter reader-mode.

package com.elabify.app.maknoon.yubikey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.yubikey.YubiKeyClient
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where we are in the enroll flow. */
private enum class YubiKeyPhase {
    /** Collect a label + the FIDO2 PIN, then one tap enrolls. */
    DETAILS,

    /** Tap in progress: openFido + makeCredential + getAssertion in one pass. */
    ENROLLING,

    /** No FIDO2 PIN is set but hmac-secret needs UV: tell the user to set one. */
    MUST_SET_PIN,

    /** ADR-0032 (OR-among-keys): a second factor is already ON. Before enrolling
     *  this NEW key we must recover the EXISTING CEK by confirming with an
     *  already-enrolled device, so the new key's wrappedCEK seals the SAME CEK
     *  (the CEK is never rotated, so no enrolled key is orphaned). This phase
     *  shows the shared recovery picker / prompt. */
    RECOVER_EXISTING,

    /** Done. */
    DONE,
}

/**
 * YubiKey register + second-factor enroll. On success [onFinished] gets the
 * registered + promoted device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YubiKeyEnrollScreen(
    registry: DeviceRegistry,
    onFinished: (RegisteredDevice) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val client = remember { YubiKeyClient() }
    val store = remember { IdentityStore(context) }
    val controller = remember(activity) { activity?.let { YubiKeyNfcController(it) } }

    // Always release the NFC radio when the screen leaves.
    DisposableEffect(controller) {
        onDispose { controller?.stop() }
    }

    var phase by remember { mutableStateOf(YubiKeyPhase.DETAILS) }
    var label by remember { mutableStateOf(DeviceKind.YUBIKEY.displayName) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // ADR-0032 recover-then-add: the new key taps once to enroll, then its
    // wrappedCEK is sealed over [existingCek] when one was passed (2FA already
    // on, recovered from an already-enrolled device) or under a fresh CEK when
    // null (the very first factor). Passing the recovered CEK is what guarantees
    // the CEK is never rotated, so every previously enrolled key keeps working.
    val runEnroll: (existingCek: ByteArray?) -> Unit = runEnroll@{ existingCek ->
        val ctl = controller ?: run {
            error = "NFC is unavailable on this device."
            return@runEnroll
        }
        val finalLabel = label.trim().ifEmpty { DeviceKind.YUBIKEY.displayName }
        val pinForEnroll = pin
        scope.launch {
            phase = YubiKeyPhase.ENROLLING
            error = null
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val device: YubiKeyDevice = YubiKeyNfcController.awaitTap(ctl)
                    try {
                        // One open connection (one tap) does the whole enroll:
                        // verify hmac-secret, read the PIN requirement, then
                        // makeCredential + getAssertion. See YubiKeyClient.useSession.
                        client.useSession(device) { session ->
                            client.verifyHmacSecret(session)
                            when (client.pinRequirement(session)) {
                                YubiKeyClient.PinRequirement.PinMustBeSet ->
                                    EnrollOutcome.MustSetPin
                                YubiKeyClient.PinRequirement.PinSet -> {
                                    if (pinForEnroll.isEmpty()) {
                                        EnrollOutcome.NeedPin
                                    } else {
                                        EnrollOutcome.Ok(
                                            client.enroll(
                                                session = session,
                                                label = finalLabel,
                                                salt = YubiKeyClient.newWrapSalt(),
                                                deviceSerial = "", // serial not read; wrap binds to the credential
                                                pin = pinForEnroll.toCharArray(),
                                            ),
                                        )
                                    }
                                }
                                YubiKeyClient.PinRequirement.NotRequired -> {
                                    EnrollOutcome.Ok(
                                        client.enroll(
                                            session = session,
                                            label = finalLabel,
                                            salt = YubiKeyClient.newWrapSalt(),
                                            deviceSerial = "",
                                            pin = null,
                                        ),
                                    )
                                }
                            }
                        }
                    } finally {
                        ctl.stop()
                    }
                }
            }
            outcome.onFailure {
                android.util.Log.e("YubiKeyClient", "enroll outcome failed", it)
                ctl.stop()
                error = friendlyError(it)
                phase = YubiKeyPhase.DETAILS
            }.onSuccess { o ->
                when (o) {
                    EnrollOutcome.MustSetPin -> {
                        phase = YubiKeyPhase.MUST_SET_PIN
                    }
                    EnrollOutcome.NeedPin -> {
                        error = "This security key has a FIDO2 PIN set. Enter it above, then tap Enroll again."
                        phase = YubiKeyPhase.DETAILS
                    }
                    is EnrollOutcome.Ok -> {
                        // ADR-0032: wrap THIS key's hmac-secret over the shared
                        // CEK and record the full second-factor envelope.
                        //   - First factor (existingCek == null): generate a fresh
                        //     CEK, seal the entropy, flip 2FA on. load() returns the
                        //     entropy-bearing sandwich (2FA still off here).
                        //   - Add (existingCek != null): reuse the recovered CEK so
                        //     no enrolled key is orphaned. The entropy is sealed and
                        //     absent from a routine load(), so rebuild the sandwich
                        //     via loadWithSecondFactor { recoveredCek } to get it.
                        val sealResult = withContext(Dispatchers.IO) {
                            runCatching {
                                val sandwich = if (existingCek != null) {
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
                                IdentitySandwich.sealForSecondFactorEnroll(
                                    sandwich = sandwich,
                                    store = store,
                                    hmacSecret = o.result.secret,
                                    deviceSalt = hexToBytes(o.result.saltHex),
                                    existingCek = existingCek,
                                )
                            }
                        }
                        sealResult.onFailure {
                            android.util.Log.e("YubiKeyClient", "seal failed", it)
                            error = it.message
                                ?: "Could not protect the wallet with this security key."
                            phase = YubiKeyPhase.DETAILS
                        }.onSuccess { seal ->
                            // Register (idempotent), then record the full Identity
                            // promotion: credential id + the per-device wrap
                            // envelope. No serial: use the FIDO credential id as
                            // the stable per-key registry identity.
                            val device = registry.register(
                                kind = DeviceKind.YUBIKEY,
                                serial = o.result.credentialIdHex,
                                label = finalLabel,
                            )
                            registry.setIdentityPromotion(
                                deviceId = device.id,
                                promotion = RegisteredDevice.IdentityPromotion(
                                    credentialIdHex = o.result.credentialIdHex,
                                    enrolledAtEpochMs = System.currentTimeMillis(),
                                    wrapProtocolVersion = 2,
                                    deviceSaltHex = o.result.saltHex,
                                    wrappedCekHex = seal.wrappedCekHex,
                                ),
                            )
                            pin = ""
                            phase = YubiKeyPhase.DONE
                            onFinished(registry.find(device.id) ?: device)
                        }
                    }
                }
            }
        }
    }

    // Button entry point. When 2FA is already on we must NOT generate a fresh
    // CEK (that would orphan the enrolled keys): first recover the existing CEK
    // by confirming with an already-enrolled device (RECOVER_EXISTING), then
    // enroll this new key reusing that CEK. When 2FA is off this is the very
    // first factor: enroll directly under a fresh CEK.
    val enroll: () -> Unit = {
        error = null
        if (store.secondFactorEnabled()) {
            phase = YubiKeyPhase.RECOVER_EXISTING
        } else {
            runEnroll(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add a security key") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                YubiKeyPhase.DETAILS -> {
                    Icon(
                        Icons.Outlined.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        "Add a security key as a second factor",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "You will tap the key once to enroll it. After that, you tap it to unlock sensitive actions (recovery phrase, signing). Your wallet stays protected by your fingerprint plus this key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Name this key (e.g. \"Primary YubiKey\")") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("FIDO2 PIN") },
                        supportingText = {
                            Text("Most security keys have a FIDO2 PIN. Enter it here. Leave blank only if your key has no PIN.")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = enroll, modifier = Modifier.fillMaxWidth()) {
                        Text("Tap key to enroll")
                    }
                }

                YubiKeyPhase.ENROLLING -> {
                    Icon(
                        Icons.Outlined.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(96.dp),
                    )
                    Text(
                        "Hold the key against the phone",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Lay the security key flat against the top-back of the phone and keep it still until this finishes. Touch the key if it blinks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    CircularProgressIndicator()
                }

                YubiKeyPhase.MUST_SET_PIN -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Set a PIN on this security key first", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Enrollment needs user verification, and this key has no FIDO2 PIN set. Open Yubico Authenticator (or another FIDO2 tool), set a FIDO2 PIN on the key, then come back and tap Enroll again.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Button(
                        onClick = { phase = YubiKeyPhase.DETAILS },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("I have set a PIN, try again") }
                }

                YubiKeyPhase.RECOVER_EXISTING -> {
                    Icon(
                        Icons.Outlined.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        "Confirm with a key you already use",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Your wallet already has a second factor. Confirm with one of your enrolled " +
                            "security keys so this new key can be added alongside it. Any one of your " +
                            "keys will then unlock your wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // The shared coordinator lists the enrolled factors, lets the
                    // user pick one when more than one is enrolled, routes by kind,
                    // and returns the existing CEK. We then enroll the NEW key
                    // reusing that CEK so it is never rotated.
                    SecondFactorRecoverDialog(
                        activity = activity,
                        registry = registry,
                        title = "Confirm with an enrolled key",
                        message = "Confirm with one of your already-enrolled security keys to add this " +
                            "new key as another second factor.",
                        onRecovered = { cek -> runEnroll(cek) },
                        onError = { error = it; phase = YubiKeyPhase.DETAILS },
                        onCancel = { phase = YubiKeyPhase.DETAILS },
                    )
                }

                YubiKeyPhase.DONE -> {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(96.dp),
                    )
                    Text("Security key enrolled", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This key is now your second factor. You will tap it to unlock sensitive actions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                if (phase == YubiKeyPhase.DETAILS) {
                    OutlinedButton(onClick = enroll, modifier = Modifier.fillMaxWidth()) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

/** Turn a raw exception into guidance the user can act on. A dropped NFC tap
 *  ("Transceive failed") is the common case and just needs a steadier tap. */
private fun friendlyError(t: Throwable): String {
    val msg = t.message ?: ""
    return when {
        msg.contains("Transceive", ignoreCase = true) ||
            msg.contains("did not respond", ignoreCase = true) ||
            msg.contains("Tag was lost", ignoreCase = true) ->
            "The tap didn't complete. Lay the key flat against the top-back of the phone, hold it still, and tap Enroll again."
        msg.contains("0x6a80", ignoreCase = true) || msg.contains("PIN", ignoreCase = true) ->
            "The PIN was not accepted. Check your FIDO2 PIN and try again."
        else -> msg.ifEmpty { "YubiKey enrollment failed. Try the tap again." }
    }
}

/** makeCredential / getAssertion outcome shuttled back to the UI thread. */
private sealed class EnrollOutcome {
    object MustSetPin : EnrollOutcome()
    object NeedPin : EnrollOutcome()
    data class Ok(val result: YubiKeyClient.EnrollResult) : EnrollOutcome()
}
