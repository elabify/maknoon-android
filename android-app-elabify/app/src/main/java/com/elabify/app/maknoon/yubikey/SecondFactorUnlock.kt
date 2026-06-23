// Second-factor unlock helpers (ADR-0032). When the wallet's second factor is
// ON, the BIP39 root entropy is sealed under a hardware-wrapped CEK and is
// absent from a routine IdentitySandwich.load(). To recover it (reveal the
// recovery phrase, export an encrypted backup, turn the second factor off, or
// add another key reusing the existing CEK) the user must tap an enrolled
// security key.
//
// This is the app-side glue between the SDK's device-agnostic wrap layer
// (SecondFactorWrap / IdentitySandwich.loadWithSecondFactor) and the live
// YubiKey NFC transport (YubiKeyNfcController + YubiKeyClient). It owns nothing
// at rest; it taps a key, recomputes its hmac-secret for its stored
// (credentialId, salt), and returns the recovered CEK.
//
// User-facing strings say "second factor" / "security key"; never the internal
// sandwich name.

package com.elabify.app.maknoon.yubikey

import androidx.fragment.app.FragmentActivity
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.yubikey.YubiKeyClient
import com.elabify.musnad.identity.SecondFactorWrap
import com.yubico.yubikit.core.YubiKeyDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recovers the second-factor CEK by tapping an already-enrolled YubiKey.
 *
 * Bound to one Activity for its NFC radio ownership. Every method here is the
 * "tap a registered security key" step: it recomputes the device's hmac-secret
 * and unwraps that device's wrappedCEK. A wrong / foreign key throws (the GCM
 * tag fails); the caller surfaces "try another key" or fails closed.
 */
class SecondFactorUnlock(
    private val activity: FragmentActivity,
    private val registry: DeviceRegistry,
    private val client: YubiKeyClient = YubiKeyClient(),
) {

    class NoEnrolledKeyException :
        Exception("No security key is enrolled as a second factor.")

    class CredentialUnreadableException :
        Exception("A second-factor record is incomplete. Re-enroll the security key.")

    /** Enrolled devices that actually carry a complete second-factor wrap
     *  envelope (a credential id + salt + wrappedCEK), in registration order.
     *  Any one of these satisfies the factor (the OR-among-keys rule). ADR-0032
     *  routes by kind at recovery: YUBIKEY uses the NFC path below; LEDGER /
     *  TREZOR use [HardwareSecondFactor] (a deterministic BLE signature). */
    fun enrolledSecondFactors(): List<RegisteredDevice> =
        registry.devices.filter { it.promotions.identity?.hasSecondFactorWrap == true }

    /** Enrolled YubiKey devices only, for the NFC recovery path here.
     *  Ledger / Trezor devices are handled by [HardwareSecondFactor]. */
    fun enrolledYubiKeys(): List<RegisteredDevice> =
        enrolledSecondFactors().filter { it.kind == DeviceKind.YUBIKEY }

    /** Whether at least one security key (any kind) can unlock the entropy. */
    fun hasEnrolledSecondFactor(): Boolean = enrolledSecondFactors().isNotEmpty()

    /**
     * Tap any enrolled key and recover the CEK. The first device whose envelope
     * matches the tapped key wins. The caller has already shown a "tap your
     * security key" prompt and (for sensitive reveals) passed a biometric gate.
     *
     * @param pin the FIDO2 PIN the key has set (YubiKey 5 hmac-secret always
     *   needs UV); collected by the caller's UI.
     * @throws NoEnrolledKeyException if nothing is enrolled.
     * @throws Exception from the transport on a wrong / absent / refusing key.
     */
    suspend fun recoverCek(pin: CharArray): ByteArray {
        val candidates = enrolledYubiKeys()
        if (candidates.isEmpty()) throw NoEnrolledKeyException()
        val controller = YubiKeyNfcController(activity)
        return try {
            withContext(Dispatchers.IO) {
                val device: YubiKeyDevice = YubiKeyNfcController.awaitTap(controller)
                try {
                    recoverCekFor(candidates, device, pin)
                } finally {
                    controller.stop()
                }
            }
        } finally {
            controller.stop()
        }
    }

    /**
     * Recover the CEK for a specific enrolled device only. Used by the
     * recover-then-add flow (FIX 2) so the user taps the key they already
     * enrolled, and by the turn-off flow. The tapped key must match [device].
     */
    suspend fun recoverCekFor(device: RegisteredDevice, pin: CharArray): ByteArray {
        val controller = YubiKeyNfcController(activity)
        return try {
            withContext(Dispatchers.IO) {
                val tapped: YubiKeyDevice = YubiKeyNfcController.awaitTap(controller)
                try {
                    recoverCekFor(listOf(device), tapped, pin)
                } finally {
                    controller.stop()
                }
            }
        } finally {
            controller.stop()
        }
    }

    /** Try each candidate's envelope against the one tapped device; return the
     *  first CEK that unwraps. Runs on a worker thread (caller is on IO). */
    private fun recoverCekFor(
        candidates: List<RegisteredDevice>,
        tapped: YubiKeyDevice,
        pin: CharArray,
    ): ByteArray =
        // One open connection (one tap) for the whole recovery: verify the key,
        // then try each enrolled envelope's recompute against it. See
        // YubiKeyClient.useSession for why all CTAP work must stay in one block.
        client.useSession(tapped) { session ->
            client.verifyHmacSecret(session)
            var lastError: Throwable? = null
            for (dev in candidates) {
                val promo = dev.promotions.identity ?: continue
                val saltHex = promo.deviceSaltHex ?: continue
                val wrappedCekHex = promo.wrappedCekHex ?: continue
                val salt = hexToBytes(saltHex)
                try {
                    val secret = client.recomputeSecret(
                        session = session,
                        credentialIdHex = promo.credentialIdHex,
                        salt = salt,
                        deviceSerial = dev.serial,
                        pin = pin,
                    )
                    return@useSession SecondFactorWrap.unwrapCek(wrappedCekHex, secret, salt)
                } catch (e: Throwable) {
                    // Wrong device for this envelope (or its credential is not on
                    // the tapped key): try the next enrolled envelope.
                    lastError = e
                }
            }
            throw (lastError ?: CredentialUnreadableException())
        }
}
