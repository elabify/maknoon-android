// Device-agnostic second-factor abstraction, per ADR-0032 §Device-kind support.
//
// The wrap layer ([SecondFactorWrap]) only needs a stable 32-byte secret for a
// given (credential, salt). Each hardware kind plugs in here:
//
//   - YubiKey: FIDO2 hmac-secret. The 32-byte output is deterministic per
//     (credential, salt), so enroll and every later unlock recompute the same
//     bytes. The Android implementation wraps yubikit's Ctap2Session; see the
//     app's YubiKey enroll / unlock screens, which build a per-tap adapter.
//   - Ledger / Trezor (a LATER task): a deterministic device signature over a
//     fixed challenge at a fixed derivation path (RFC6979 ECDSA / Ed25519 are
//     deterministic, so the recompute is stable), SHA-256'd to 32 bytes. They
//     implement this same interface; the wrap layer does not change.
//
// This interface is intentionally tiny so it can live in the SDK while the
// live transport (NFC reader-mode ownership, a fresh session per tap) stays in
// the app. The app supplies a concrete [SecondFactorKey] bound to one tapped
// device for one operation.

package com.elabify.musnad.identity

/**
 * Parameters identifying which enrolled credential to recompute the secret for.
 * For YubiKey these come straight off [com.elabify.musnad.devices.RegisteredDevice.IdentityPromotion]
 * plus the device serial recorded at registration.
 */
data class SecondFactorRecomputeParams(
    /** FIDO2 credential id (hex) the device returned at enroll. */
    val credentialIdHex: String,
    /** The 32-byte hmac-secret salt persisted on the promotion (deviceSaltHex). */
    val deviceSalt: ByteArray,
    /** The device serial folded into the FIDO2 clientDataHash at enroll time.
     *  Empty string for FIDO-only keys with no readable Management serial. */
    val deviceSerial: String,
) {
    override fun equals(other: Any?): Boolean =
        other is SecondFactorRecomputeParams &&
            credentialIdHex == other.credentialIdHex &&
            deviceSalt.contentEquals(other.deviceSalt) &&
            deviceSerial == other.deviceSerial

    override fun hashCode(): Int {
        var h = credentialIdHex.hashCode()
        h = 31 * h + deviceSalt.contentHashCode()
        h = 31 * h + deviceSerial.hashCode()
        return h
    }
}

/**
 * A hardware device that can produce a stable 32-byte secret for a given
 * (credential, salt). [SecondFactorWrap] derives the per-device wrap key from
 * this secret; nothing else about the device leaks into the wrap.
 *
 * Implementations are expected to be bound to a single, already-tapped /
 * already-connected device for one operation (the transport owner in the app
 * does the tapping and session lifecycle).
 */
fun interface SecondFactorKey {
    /**
     * Recompute the 32-byte second-factor secret for [params]. MUST be
     * deterministic for the same physical device + params across calls, or the
     * wrapped CEK will never decrypt again. May suspend (NFC / BLE round-trip).
     *
     * @throws Exception if the device is wrong, absent, or refuses (the unlock
     *   path treats any throw as "this key did not satisfy the factor").
     */
    suspend fun recomputeSecret(params: SecondFactorRecomputeParams): ByteArray
}
