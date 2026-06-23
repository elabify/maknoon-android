// Hardware second-factor wrap for the wallet's single root secret (the BIP39
// entropy), per ADR-0032. This is the device-agnostic crypto layer: it knows
// nothing about YubiKey / Ledger / Trezor transports, only the byte-exact
// envelope format the cross-platform contract pins.
//
// User-facing this is "a second factor" / "a security key"; never surface the
// internal IdentitySandwich name in any UI string.
//
// Crypto (byte-identical with the iOS twin and the ADR-0032 §Crypto block):
//
//   CEK            = random 32 bytes
//   sealedEntropy  = AES-256-GCM(key=CEK, msg=entropy)            (stored once)
//   per device i:
//     deviceSalt_i = random 32 bytes
//     hmacSecret_i = secondFactorKey.recomputeSecret(credId_i, deviceSalt_i, serial_i, pin)  (32 bytes)
//     wrapKey_i    = HKDF-SHA256(ikm=hmacSecret_i, salt=deviceSalt_i, info="maknoon-2fa-wrap-v2", len=32)
//     wrappedCEK_i = AES-256-GCM(key=wrapKey_i, msg=CEK)
//
// The CEK is never persisted in plaintext: it exists only transiently in
// memory after a successful hardware unlock. sealedEntropy is stored once;
// each enrolled device adds only a small wrappedCEK_i, so add/remove touches
// one device only (the OR-among-keys property).
//
// AEAD framing: AES/GCM/NoPadding with a fresh random 12-byte nonce, stored as
// nonce(12) || ciphertext || tag(16) and hex-encoded for JSON. This is a
// PLAIN JCA cipher keyed by the CEK / wrapKey we derive here, NOT the
// StrongBox-bound AndroidSecureStore key (those keys are the secret material,
// not a device-bound key). At rest the whole IdentityStore is still sealed by
// AndroidSecureStore on top of this.

package com.elabify.musnad.identity

import com.elabify.core.hkdfSha256
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Device-agnostic second-factor wrap. Pure crypto + envelope helpers; the
 * transport (FIDO2 hmac-secret for YubiKey, a deterministic device signature
 * for Ledger / Trezor) is owned by [SecondFactorKey] implementations.
 */
object SecondFactorWrap {

    /** HKDF info string. FIXED across platforms; do not change. */
    const val WRAP_INFO = "maknoon-2fa-wrap-v2"

    private const val GCM_NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_LEN = 32

    /** A fresh 32-byte Content Encryption Key. Caller seals the entropy under
     *  it once and wraps it per device; never persists it in the clear. */
    fun newCek(): ByteArray = randomBytes(KEY_LEN)

    /** A fresh 32-byte per-device hmac-secret salt. */
    fun newDeviceSalt(): ByteArray = randomBytes(KEY_LEN)

    /** sealedEntropy = AES-256-GCM(key=CEK, msg=entropy). Returns the framed
     *  blob nonce(12)||ct||tag, hex-encoded for storage. */
    fun sealEntropy(entropy: ByteArray, cek: ByteArray): String {
        require(entropy.size == 32) { "entropy must be 32 bytes" }
        require(cek.size == KEY_LEN) { "CEK must be 32 bytes" }
        return aesGcmSeal(cek, entropy).toHex()
    }

    /** Inverse of [sealEntropy]. Throws on a wrong CEK (GCM tag mismatch). */
    fun openEntropy(sealedEntropyHex: String, cek: ByteArray): ByteArray {
        require(cek.size == KEY_LEN) { "CEK must be 32 bytes" }
        val out = aesGcmOpen(cek, hexToBytes(sealedEntropyHex))
        require(out.size == 32) { "recovered entropy must be 32 bytes" }
        return out
    }

    /**
     * Derive the per-device wrap key and seal the CEK under it. Returns the
     * framed wrappedCEK hex for [RegisteredDevice.IdentityPromotion.wrappedCekHex].
     *
     * @param hmacSecret the 32-byte secret the device's [SecondFactorKey]
     *   returned for (credId, deviceSalt).
     * @param deviceSalt the 32-byte salt that produced [hmacSecret] (also the
     *   HKDF salt; persisted on the promotion).
     */
    fun wrapCek(cek: ByteArray, hmacSecret: ByteArray, deviceSalt: ByteArray): String {
        require(cek.size == KEY_LEN) { "CEK must be 32 bytes" }
        require(hmacSecret.size == KEY_LEN) { "hmac-secret must be 32 bytes" }
        require(deviceSalt.size == KEY_LEN) { "device salt must be 32 bytes" }
        val wrapKey = wrapKey(hmacSecret, deviceSalt)
        return aesGcmSeal(wrapKey, cek).toHex()
    }

    /**
     * Recover the CEK for one device: derive its wrap key from the recomputed
     * hmac-secret + salt, decrypt its wrappedCEK. Throws on a wrong / foreign
     * key (GCM tag mismatch), which the unlock path catches to try another
     * device or fail closed.
     */
    fun unwrapCek(wrappedCekHex: String, hmacSecret: ByteArray, deviceSalt: ByteArray): ByteArray {
        require(hmacSecret.size == KEY_LEN) { "hmac-secret must be 32 bytes" }
        require(deviceSalt.size == KEY_LEN) { "device salt must be 32 bytes" }
        val wrapKey = wrapKey(hmacSecret, deviceSalt)
        val cek = aesGcmOpen(wrapKey, hexToBytes(wrappedCekHex))
        require(cek.size == KEY_LEN) { "recovered CEK must be 32 bytes" }
        return cek
    }

    /** wrapKey_i = HKDF-SHA256(ikm=hmacSecret, salt=deviceSalt, info=WRAP_INFO, len=32). */
    private fun wrapKey(hmacSecret: ByteArray, deviceSalt: ByteArray): ByteArray =
        hkdfSha256(
            ikm = hmacSecret,
            salt = deviceSalt,
            info = WRAP_INFO.toByteArray(Charsets.UTF_8),
            length = KEY_LEN,
        )

    // -- AEAD framing: nonce(12) || ciphertext || tag(16) --

    private fun aesGcmSeal(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = randomBytes(GCM_NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return nonce + cipher.doFinal(plaintext)
    }

    private fun aesGcmOpen(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > GCM_NONCE_LEN) { "sealed blob too short" }
        val nonce = blob.copyOfRange(0, GCM_NONCE_LEN)
        val ct = blob.copyOfRange(GCM_NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(ct)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
