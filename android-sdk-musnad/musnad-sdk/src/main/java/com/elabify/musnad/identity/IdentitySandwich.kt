// The Identity Sandwich, ported from iOS IdentitySandwich.swift:
//   - master ML-DSA-65 key, derived BIP-39-standard from entropy + passphrase
//     (reconstructed in memory only to sign; never persisted in the clear),
//   - a software ML-DSA-65 ephemeral key (Android has no Keystore ML-DSA, so
//     the iOS Secure-Enclave ephemeral becomes a software key whose seed is
//     StrongBox-sealed at rest),
//   - a 24h delegation cert: the master signs the ephemeral key so day-to-day
//     presentations use the fast ephemeral path, no master reconstruction.
//
// holderDID depends only on the master public key, so it is stable across
// delegation renewals and across passphrase-aware recovery on a new device.

package com.elabify.musnad.identity

import com.elabify.core.Bip39
import com.elabify.musnad.backup.EncryptedBackup
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import java.security.SecureRandom
import org.json.JSONObject
import uniffi.pq_crypto_core.mldsa65PublicKey
import uniffi.pq_crypto_core.mldsa65Sign

class IdentitySandwich private constructor(
    val masterPublicKey: ByteArray,
    val ephemeralPublicKey: ByteArray,
    private val ephemeralSeed: ByteArray,
    // ADR-0032: when the second factor is ON, the root entropy is sealed under
    // a hardware-wrapped CEK and is ABSENT from a routine [load]. The sandwich
    // is still fully usable for everything that only needs the ephemeral key +
    // the master public key + the delegation cert (presentation signing,
    // holderDid, showing credentials). Only the entropy-requiring methods below
    // need it; they throw [SecondFactorRequiredException] when it is absent so
    // the caller can route the user through [loadWithSecondFactor] (tap a key).
    private val entropy: ByteArray?,
    private val passphrase: String,
    @Volatile var delegation: DelegationCert,
) {
    val holderDid: String get() = HolderDid.fromMasterPublicKey(masterPublicKey)

    /** True when this sandwich was opened without the root entropy because the
     *  second factor is on (a routine [load] while 2FA is enabled). Routine
     *  ephemeral-key operations work regardless; entropy-requiring ones throw
     *  [SecondFactorRequiredException]. Use [loadWithSecondFactor] to get a
     *  sandwich with the entropy present. */
    val needsSecondFactor: Boolean get() = entropy == null

    /** The root entropy, or throw a clear "tap your security key" error when it
     *  is absent because the second factor is on. */
    private fun requireEntropy(): ByteArray =
        entropy ?: throw SecondFactorRequiredException()

    private val words: List<String> get() = Bip39.mnemonicFromEntropy(requireEntropy())

    /** Fast path: sign with the ephemeral key (no master reconstruction,
     *  no biometric, no second factor). Used for presentation challenges. */
    fun signChallenge(message: ByteArray): ByteArray = mldsa65Sign(ephemeralSeed, message)

    /** Slow path: reconstruct the master and sign. Gate this behind
     *  BiometricPrompt at the UI for sensitive operations (P1). Requires the
     *  root entropy: throws [SecondFactorRequiredException] when 2FA is on and
     *  this sandwich came from a routine [load] (recover via
     *  [loadWithSecondFactor] first). */
    fun signWithMaster(message: ByteArray): ByteArray =
        MasterKey.sign(words, passphrase, message)

    /** The recovery material (24 words). Gate reveal behind BiometricPrompt at
     *  the UI. Requires the root entropy: throws [SecondFactorRequiredException]
     *  when 2FA is on and this sandwich came from a routine [load]. */
    fun recoveryWords(): List<String> = words
    fun hasPassphrase(): Boolean = passphrase.isNotEmpty()

    /** Produce a v3 encrypted backup of this identity (iOS byte-compatible).
     *  The blob is encrypted + signed by the master derived from the same
     *  entropy + passphrase; restore needs the passphrase. */
    fun exportEncryptedBackup(extra: JSONObject? = null): ByteArray {
        val e = requireEntropy()
        val root = JSONObject()
            .put("v", 4)
            .put("entropyHex", e.toHex())
        // Merge the app-built v4 sections (settings, lightningAccounts,
        // credentials, idDocuments, walletState, createdAt) so the full
        // cross-platform payload is written. Identity-only when extra is null.
        extra?.let { ex -> ex.keys().forEach { k -> root.put(k, ex.get(k)) } }
        return EncryptedBackup.encrypt(root.toString().toByteArray(Charsets.UTF_8), passphrase, e)
    }

    /** Re-sign the (same) ephemeral key with a fresh 24h window and persist. */
    fun renewDelegation(nowSec: Long, store: IdentityStore) {
        delegation = Delegation.sign(ephemeralPublicKey, words, passphrase, nowSec)
        store.saveDelegation(delegation)
    }

    fun delegationNeedsRenewal(nowSec: Long): Boolean = Delegation.needsRenewal(delegation, nowSec)

    /** ADR-0032: the raw 32-byte root secret (BIP39 entropy). Exposed so the
     *  enroll path can seal it under a CEK and so the second-factor unlock can
     *  rebuild a sandwich from it. Treat as sensitive: callers gate this behind
     *  biometric + the hardware factor; never log or persist it in the clear. */
    fun rootEntropy(): ByteArray = requireEntropy().copyOf()

    companion object {
        /** Brand-new identity: fresh 32-byte entropy + the given passphrase.
         *  Persists the sealed material/seed + public key + cert, and returns
         *  the entropy so the caller can show the 24-word backup. */
        fun generateFresh(
            passphrase: String,
            nowSec: Long,
            store: IdentityStore,
        ): Pair<IdentitySandwich, ByteArray> {
            val entropy = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sandwich = build(entropy, passphrase, nowSec, store)
            return sandwich to entropy
        }

        /** Recover from a 24-word mnemonic + passphrase (same master/DID). */
        fun restoreFromMnemonic(
            words: List<String>,
            passphrase: String,
            nowSec: Long,
            store: IdentityStore,
        ): IdentitySandwich {
            val entropy = Bip39.entropyFromMnemonic(words)
            return build(entropy, passphrase, nowSec, store)
        }

        /** Restore an identity from a v3 encrypted backup blob + passphrase
         *  (rebuilds the same master/DID), then persist it. */
        fun restoreFromEncryptedBackup(
            blobBytes: ByteArray,
            passphrase: String,
            nowSec: Long,
            store: IdentityStore,
        ): IdentitySandwich {
            val plaintext = EncryptedBackup.decrypt(blobBytes, passphrase)
            val entropyHex = JSONObject(String(plaintext, Charsets.UTF_8)).getString("entropyHex")
            val words = Bip39.mnemonicFromEntropy(hexToBytes(entropyHex))
            return restoreFromMnemonic(words, passphrase, nowSec, store)
        }

        /** Reload a persisted sandwich, or null if there is no identity.
         *
         *  ADR-0032 FIX 1: when the second factor is ON the plain entropy seal
         *  is gone, so the entropy can only be recovered by tapping an enrolled
         *  hardware key. This DOES NOT return null in that state: it returns a
         *  usable sandwich with the entropy ABSENT (see [needsSecondFactor]).
         *  That keeps every routine flow working with 2FA on, presentation
         *  signing (ephemeral key), holderDid, showing credentials, the
         *  onboarding gate. Only the entropy-requiring methods
         *  (recoveryWords / signWithMaster / exportEncryptedBackup /
         *  rootEntropy) throw [SecondFactorRequiredException] until the caller
         *  recovers the entropy via [loadWithSecondFactor] (a key tap).
         *
         *  Returns null only when there is genuinely no identity (no passphrase
         *  seal, ephemeral seed, master pk, or delegation cert). */
        fun load(store: IdentityStore): IdentitySandwich? {
            val ephemeralSeed = store.loadEphemeralSeed() ?: return null
            val masterPk = store.loadMasterPublicKey() ?: return null
            val cert = store.loadDelegation() ?: return null
            val ephemeralPk = mldsa65PublicKey(ephemeralSeed)
            if (store.secondFactorEnabled()) {
                // 2FA on: entropy is sealed under the hardware-wrapped CEK and
                // not available here. The passphrase seal still resolves (it is
                // kept alongside the sealed entropy) and is needed only by the
                // entropy-requiring paths, which throw until a key tap. Routine
                // ops never touch it.
                val passphrase = store.loadPassphrase() ?: return null
                return IdentitySandwich(masterPk, ephemeralPk, ephemeralSeed, null, passphrase, cert)
            }
            val (entropy, passphrase) = store.loadMaterial() ?: return null
            return IdentitySandwich(masterPk, ephemeralPk, ephemeralSeed, entropy, passphrase, cert)
                .also { renewDelegationIfNeeded(it, store) }
        }

        /** Auto-renew the 24h delegation cert when it is within the renewal lead
         *  of expiring (or already expired), as long as the entropy is present
         *  (2FA off, or a second-factor unlock). Without this the cert minted at
         *  enrollment expires after 24h and every presentation's challengeSig
         *  fails its validity window. Mirrors iOS signChallenge auto-renew.
         *  Best-effort: a renewal failure must never break load(). */
        private fun renewDelegationIfNeeded(sandwich: IdentitySandwich, store: IdentityStore) {
            if (sandwich.needsSecondFactor) return // no entropy here; renews on a 2FA unlock
            val now = System.currentTimeMillis() / 1000L
            if (sandwich.delegationNeedsRenewal(now)) {
                runCatching { sandwich.renewDelegation(now, store) }
            }
        }

        /**
         * ADR-0032 second-factor unlock. Recovers the root entropy through the
         * hardware-wrapped CEK: [recoverCek] taps an enrolled key, recomputes
         * its hmac-secret, and returns the 32-byte CEK (see
         * [SecondFactorWrap.unwrapCek]); we then open sealedEntropyUnderCEK and
         * rebuild the same master/DID. Biometric is applied by the UI on top.
         *
         * Returns null if there is no identity, the second factor is not
         * actually on, or the public items are missing. Propagates any throw
         * from [recoverCek] (wrong / foreign key -> caller surfaces "try
         * another key" or fails closed).
         */
        suspend fun loadWithSecondFactor(
            store: IdentityStore,
            recoverCek: suspend () -> ByteArray,
        ): IdentitySandwich? {
            if (!store.secondFactorEnabled()) return null
            val sealedEntropyHex = store.loadSealedEntropyUnderCek() ?: return null
            val passphrase = store.loadPassphrase() ?: return null
            val ephemeralSeed = store.loadEphemeralSeed() ?: return null
            val masterPk = store.loadMasterPublicKey() ?: return null
            val cert = store.loadDelegation() ?: return null
            val cek = recoverCek()
            val entropy = SecondFactorWrap.openEntropy(sealedEntropyHex, cek)
            val ephemeralPk = mldsa65PublicKey(ephemeralSeed)
            // Entropy is present on a second-factor unlock, so this is the moment
            // to refresh an expiring delegation for a 2FA identity.
            return IdentitySandwich(masterPk, ephemeralPk, ephemeralSeed, entropy, passphrase, cert)
                .also { renewDelegationIfNeeded(it, store) }
        }

        /**
         * First-device enroll seal (ADR-0032). Given the just-unlocked
         * sandwich and the freshly derived hmac-secret + salt for the device
         * being enrolled, this:
         *   1. generates the CEK (first device) or reuses the one recovered
         *      from an already-enrolled device,
         *   2. seals the entropy under the CEK (once) and flips the store to
         *      "2FA on" (removing the plain entropy seal),
         *   3. returns the per-device wrappedCEK (hex) + deviceSalt (hex) for
         *      the caller to persist on this device's IdentityPromotion.
         *
         * @param existingCek pass the CEK recovered from another enrolled key
         *   to ADD a device without rotating the seal; null to start fresh
         *   (first enroll) generating a new CEK.
         */
        fun sealForSecondFactorEnroll(
            sandwich: IdentitySandwich,
            store: IdentityStore,
            hmacSecret: ByteArray,
            deviceSalt: ByteArray,
            existingCek: ByteArray? = null,
        ): SecondFactorEnrollSeal {
            val cek = existingCek ?: SecondFactorWrap.newCek()
            val entropy = sandwich.rootEntropy()
            // Seal the entropy under the CEK and flip 2FA on. When adding a
            // second device with the same CEK this rewrites the identical
            // sealedEntropy, which is harmless (and keeps the store consistent).
            val sealedEntropyHex = SecondFactorWrap.sealEntropy(entropy, cek)
            store.enableSecondFactor(sealedEntropyHex, sandwich.passphrase)
            val wrappedCekHex = SecondFactorWrap.wrapCek(cek, hmacSecret, deviceSalt)
            return SecondFactorEnrollSeal(
                wrappedCekHex = wrappedCekHex,
                cek = cek,
            )
        }

        private fun build(
            entropy: ByteArray,
            passphrase: String,
            nowSec: Long,
            store: IdentityStore,
        ): IdentitySandwich {
            val words = Bip39.mnemonicFromEntropy(entropy)
            val masterPk = MasterKey.publicKey(words, passphrase)
            val ephemeralSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val ephemeralPk = mldsa65PublicKey(ephemeralSeed)
            val cert = Delegation.sign(ephemeralPk, words, passphrase, nowSec)

            store.saveMaterial(entropy, passphrase)
            store.saveEphemeralSeed(ephemeralSeed)
            store.saveMasterPublicKey(masterPk)
            store.saveDelegation(cert)

            return IdentitySandwich(masterPk, ephemeralPk, ephemeralSeed, entropy, passphrase, cert)
        }
    }
}

/** Result of sealing the wrap for one enrolled device (ADR-0032). The caller
 *  persists [wrappedCekHex] (with the deviceSalt it passed in) on that device's
 *  IdentityPromotion. [cek] is returned transiently so a multi-device add can
 *  reuse it for the next device without another tap; it is never persisted in
 *  the clear and the caller should drop it after use. */
data class SecondFactorEnrollSeal(
    val wrappedCekHex: String,
    val cek: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SecondFactorEnrollSeal &&
            wrappedCekHex == other.wrappedCekHex &&
            cek.contentEquals(other.cek)

    override fun hashCode(): Int = 31 * wrappedCekHex.hashCode() + cek.contentHashCode()
}

/** Thrown when an entropy-requiring operation (reveal recovery words, sign with
 *  the master key, export an encrypted backup, derive a software-wallet key) is
 *  attempted on a sandwich opened via a routine [IdentitySandwich.load] while
 *  the second factor is ON (ADR-0032). The root entropy is absent until the
 *  user taps an enrolled security key; recover it with
 *  [IdentitySandwich.loadWithSecondFactor], then retry. User-facing message
 *  says "second factor" / "security key", never the internal sandwich name. */
class SecondFactorRequiredException(
    message: String = "This action needs your second factor. Tap an enrolled security key to continue.",
) : Exception(message)
