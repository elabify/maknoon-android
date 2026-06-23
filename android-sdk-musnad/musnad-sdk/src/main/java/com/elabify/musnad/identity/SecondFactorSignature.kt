// Deterministic-device-signature second factor (ADR-0032 §Resolved decision 3).
//
// Ledger and Trezor over BLE do NOT expose FIDO2 hmac-secret on their wallet
// transport, so their second-factor secret is a DETERMINISTIC device signature
// over a fixed challenge at a fixed derivation path, SHA-256'd to the 32-byte
// secret the device-agnostic wrap layer ([SecondFactorWrap]) consumes. RFC6979
// ECDSA is deterministic, so the same device + the same challenge always yields
// the same signature, hence the same secret, so the wrappedCEK keeps decrypting.
//
// The challenge is a short, human-readable, all-ASCII message ("Maknoon second
// factor ID: <8 hex>", the 8 hex being the first 4 bytes of the per-enroll
// deviceSalt) so the hardware device shows plain text rather than an opaque hex
// blob; the FULL deviceSalt still binds the wrap key via the HKDF salt. Built
// identically at enroll and at every unlock. If this construction ever diverged
// between enroll and unlock the wrappedCEK would never decrypt again (lockout),
// so it lives in exactly one place, kept aligned with iOS (the reference).
//
// signMessage on both vendors is an ETH personal-message (EIP-191) sign at a
// FIXED account-0 derivation path (Ledger: signPersonalMessageForAccount(0);
// Trezor: sign_message_eth at account_path(0) with PassphraseSpec::Standard), so
// the secret is keyed by the device seed at that one fixed path and is
// independent of any hidden (passphrase) wallet, custom path, or account.

package com.elabify.musnad.identity

import java.security.MessageDigest

/**
 * Shared challenge construction + secret derivation for the Ledger / Trezor
 * deterministic-signature second factor. Pure helper: the caller owns the live
 * BLE connection and supplies a `signMessage` that signs at the fixed account-0
 * path.
 */
object SecondFactorSignature {

    /** Fixed message prefix. FIXED across platforms (iOS is the reference;
     *  this mirrors it) and across the enroll / unlock paths; never change it
     *  or already-enrolled devices lock out. The full message is this prefix
     *  plus an 8-hex ID (the first 4 bytes of the per-enroll deviceSalt). */
    const val MESSAGE_PREFIX = "Maknoon second factor ID: "

    /** challenge = "Maknoon second factor ID: <8 hex>" where the 8 hex chars
     *  are the first 4 bytes of deviceSalt. A short, human-readable, all-ASCII
     *  message so the hardware device shows plain text instead of an opaque hex
     *  blob; the FULL deviceSalt still binds the wrap key via the HKDF salt.
     *  Built identically at enroll and unlock so the device signs the same
     *  bytes both times. */
    fun challenge(deviceSalt: ByteArray): ByteArray {
        require(deviceSalt.size == 32) { "device salt must be 32 bytes" }
        val idHex = deviceSalt.copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
        return (MESSAGE_PREFIX + idHex).toByteArray(Charsets.UTF_8)
    }

    /**
     * secret = SHA-256(signMessage(challenge(deviceSalt))). The 32-byte
     * second-factor secret the wrap layer keys on. [signMessage] MUST be a
     * deterministic signature (RFC6979 ECDSA) at a fixed derivation path for the
     * same physical device, or the wrapped CEK will never decrypt again.
     *
     * @param signMessage signs the challenge with the connected device (the
     *   HardwareWallet.signMessage hook, account-0 ETH personal message).
     */
    suspend fun recomputeSecret(
        deviceSalt: ByteArray,
        signMessage: suspend (ByteArray) -> ByteArray,
    ): ByteArray {
        val rawSig = signMessage(challenge(deviceSalt))
        require(rawSig.isNotEmpty()) { "device returned an empty signature" }
        return MessageDigest.getInstance("SHA-256").digest(rawSig)
    }
}
