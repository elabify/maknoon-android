// Post-quantum encrypted backup, byte-compatible with the iOS iCloudBackup
// blob so a backup made on iPhone restores on Android and vice-versa.
//
// Blob (JSON):
//   { v:3, kdf:"pbkdf2-sha256", iter:600000,
//     salt: b64(16B),
//     combined: b64(nonce12 || ciphertext || tag16),   // AES-256-GCM
//     sigAlg:"ML-DSA-65", masterPk: b64(1952B), signature: b64(3309B over combined) }
//
//   key = PBKDF2-HMAC-SHA256(NFKC(passphrase) UTF-8, salt, 600000, 32B)
//
// v3 adds the post-quantum authentication block: the holder's master ML-DSA-65
// key signs `combined`, and the master public key is embedded. Restorers verify
// the signature before decrypting. v1/v2 (no signature) still open.

package com.elabify.musnad.backup

import android.util.Base64
import com.elabify.core.Bip39
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.crypto.Pbkdf2
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import uniffi.pq_crypto_core.mldsa65PublicKey
import uniffi.pq_crypto_core.mldsa65Sign
import uniffi.pq_crypto_core.mldsa65VerifySignature

class BackupException(message: String) : Exception(message)

object EncryptedBackup {
    private const val KDF = "pbkdf2-sha256"
    private const val ITERATIONS = 600_000
    private const val KEY_LEN = 32
    private const val SALT_LEN = 16
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val SIG_ALG = "ML-DSA-65"

    /** Produce a v3 blob. `entropy` is the 32-byte BIP-39 entropy whose master
     *  key signs the ciphertext (same key as the Identity Sandwich). */
    fun encrypt(plaintext: ByteArray, passphrase: String, entropy: ByteArray): ByteArray {
        require(entropy.size == 32) { "entropy must be 32 bytes" }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val combined = nonce + cipher.doFinal(plaintext) // nonce || ct || tag

        val words = Bip39.mnemonicFromEntropy(entropy)
        val masterPk = MasterKey.publicKey(words, passphrase)
        val signature = MasterKey.sign(words, passphrase, combined)

        val blob = JSONObject()
            .put("v", 3)
            .put("kdf", KDF)
            .put("iter", ITERATIONS)
            .put("salt", b64(salt))
            .put("combined", b64(combined))
            .put("sigAlg", SIG_ALG)
            .put("masterPk", b64(masterPk))
            .put("signature", b64(signature))
        return blob.toString().toByteArray(Charsets.UTF_8)
    }

    /** Open a v1/v2/v3 blob, verifying the v3 signature first. */
    fun decrypt(blobBytes: ByteArray, passphrase: String): ByteArray {
        val blob = try {
            JSONObject(String(blobBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("blob is not valid JSON")
        }
        val v = blob.optInt("v", -1)
        if (v !in 1..3 || blob.optString("kdf") != KDF) {
            throw BackupException("unsupported blob version $v / kdf ${blob.optString("kdf")}")
        }
        val salt = dec(blob.getString("salt"))
        val combined = dec(blob.getString("combined"))

        if (v == 3) {
            if (blob.optString("sigAlg") != SIG_ALG) throw BackupException("unsupported signature algorithm")
            val pk = dec(blob.getString("masterPk"))
            val sig = dec(blob.getString("signature"))
            if (!mldsa65VerifySignature(pk, sig, combined)) {
                throw BackupException("ML-DSA signature failed verification (tampered, or different master key)")
            }
        }

        val key = deriveKey(passphrase, salt)
        val nonce = combined.copyOfRange(0, NONCE_LEN)
        val body = combined.copyOfRange(NONCE_LEN, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return try {
            cipher.doFinal(body)
        } catch (e: Exception) {
            throw BackupException("wrong passphrase, or tampered blob")
        }
    }

    /** Cross-check that the v3 master pubkey matches the one derived from
     *  `entropy` + passphrase (catches a right-passphrase/wrong-blob mixup). */
    fun verifyMasterBinding(blobBytes: ByteArray, passphrase: String, entropy: ByteArray): Boolean {
        val blob = JSONObject(String(blobBytes, Charsets.UTF_8))
        if (blob.optInt("v") != 3) return true
        val embedded = dec(blob.getString("masterPk"))
        val derived = mldsa65PublicKey(Bip39.masterSeed(Bip39.mnemonicFromEntropy(entropy), passphrase))
        return embedded.contentEquals(derived)
    }

    /** Decrypt-and-discard: throws on any failure. Mirrors iOS verify(). */
    fun verify(blobBytes: ByteArray, passphrase: String) {
        decrypt(blobBytes, passphrase)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val pw = Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
        return Pbkdf2.hmacSha256(pw, salt, ITERATIONS, KEY_LEN)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
