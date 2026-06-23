// At-rest persistence for the Identity Sandwich. Secrets (master material =
// entropy+passphrase, and the ephemeral seed) are sealed by the StrongBox
// AES key before they touch SharedPreferences; the master public key and the
// delegation cert are public and stored in the clear. Mirrors the iOS
// KeyStore split (biometric/wrapped secrets vs non-biometric public items).

package com.elabify.musnad.identity

import android.content.Context
import android.util.Base64
import com.elabify.musnad.crypto.AndroidSecureStore

class IdentityStore(
    context: Context,
    private val secure: AndroidSecureStore = AndroidSecureStore(),
    prefsName: String = PREFS,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun saveMaterial(entropy: ByteArray, passphrase: String) {
        require(entropy.size == 32) { "entropy must be 32 bytes" }
        val blob = entropy + passphrase.toByteArray(Charsets.UTF_8)
        prefs.edit().putString(K_MATERIAL, enc(secure.seal(blob))).apply()
    }

    /** Returns (entropy, passphrase) or null if absent. Null also when the
     *  second factor is ON: the plain entropy seal is gone (only the
     *  passphrase remains, via [loadPassphrase]), so the entropy must be
     *  recovered through the hardware-wrapped CEK. */
    fun loadMaterial(): Pair<ByteArray, String>? {
        val s = prefs.getString(K_MATERIAL, null) ?: return null
        val blob = secure.open(dec(s))
        // When 2FA is on the seal holds ONLY the passphrase (no 32-byte
        // entropy prefix), so the plain entropy path correctly reports
        // "locked": there is no entropy here without a hardware unlock.
        if (blob.size < 32 || secondFactorEnabled()) return null
        val entropy = blob.copyOfRange(0, 32)
        val passphrase = String(blob.copyOfRange(32, blob.size), Charsets.UTF_8)
        return entropy to passphrase
    }

    // -- Second-factor (ADR-0032) at-rest state --
    //
    // When the second factor is ON, the entropy is no longer in the plain
    // material seal. Instead:
    //   - the passphrase alone is sealed (still needed for the master key +
    //     the passphrase-based encrypted backup, both independent of the
    //     second factor), and
    //   - sealedEntropyUnderCEK holds AES-256-GCM(CEK, entropy); the CEK is
    //     only recoverable by tapping an enrolled device.
    // The plain material seal (K_MATERIAL) is repurposed to hold just the
    // passphrase bytes so hasIdentity()/wipe()/the no-2FA path are unchanged.

    fun secondFactorEnabled(): Boolean = prefs.getBoolean(K_2FA_ON, false)

    /** The passphrase alone (no entropy). Available whether or not 2FA is on;
     *  null if no identity. Used by the hardware-unlock rebuild and by backup
     *  export when the entropy has just been recovered via the CEK. */
    fun loadPassphrase(): String? {
        val s = prefs.getString(K_MATERIAL, null) ?: return null
        val blob = secure.open(dec(s))
        // 2FA-on seal = passphrase only; 2FA-off seal = entropy(32) || passphrase.
        return if (secondFactorEnabled() || blob.size < 32) {
            String(blob, Charsets.UTF_8)
        } else {
            String(blob.copyOfRange(32, blob.size), Charsets.UTF_8)
        }
    }

    /** The CEK-sealed entropy blob (hex) while 2FA is on; null otherwise. */
    fun loadSealedEntropyUnderCek(): String? = prefs.getString(K_SEALED_ENTROPY, null)

    /**
     * Flip the store to "second factor on": store the CEK-sealed entropy,
     * remove the plain entropy seal (keep only the passphrase), and set the
     * flag. Idempotent-safe: re-running with the same passphrase just rewrites.
     * The 24-word phrase and the encrypted backup remain the escape hatch, so
     * this is reversible via [disableSecondFactor].
     */
    fun enableSecondFactor(sealedEntropyUnderCekHex: String, passphrase: String) {
        prefs.edit()
            .putString(K_SEALED_ENTROPY, sealedEntropyUnderCekHex)
            // material seal now holds the passphrase ONLY (entropy removed).
            .putString(K_MATERIAL, enc(secure.seal(passphrase.toByteArray(Charsets.UTF_8))))
            .putBoolean(K_2FA_ON, true)
            .apply()
    }

    /**
     * Turn the second factor off: re-seal the (now hardware-recovered) entropy
     * under the plain key-store seal and clear the CEK envelope. Caller must
     * have recovered the entropy first (tap a key) and pass it here.
     */
    fun disableSecondFactor(entropy: ByteArray, passphrase: String) {
        require(entropy.size == 32) { "entropy must be 32 bytes" }
        saveMaterial(entropy, passphrase)
        prefs.edit()
            .remove(K_SEALED_ENTROPY)
            .putBoolean(K_2FA_ON, false)
            .apply()
    }

    fun saveEphemeralSeed(seed: ByteArray) {
        prefs.edit().putString(K_EPH_SEED, enc(secure.seal(seed))).apply()
    }

    fun loadEphemeralSeed(): ByteArray? =
        prefs.getString(K_EPH_SEED, null)?.let { secure.open(dec(it)) }

    fun saveMasterPublicKey(pk: ByteArray) {
        prefs.edit().putString(K_MASTER_PK, enc(pk)).apply()
    }

    fun loadMasterPublicKey(): ByteArray? = prefs.getString(K_MASTER_PK, null)?.let { dec(it) }

    fun saveDelegation(cert: DelegationCert) {
        prefs.edit()
            .putString(K_DLG_EPH, cert.ephemeralPk)
            .putLong(K_DLG_FROM, cert.validFrom)
            .putLong(K_DLG_UNTIL, cert.validUntil)
            .putString(K_DLG_SCOPE, cert.scope.joinToString(","))
            .putString(K_DLG_SIG, cert.delegationSig)
            .apply()
    }

    fun loadDelegation(): DelegationCert? {
        val eph = prefs.getString(K_DLG_EPH, null) ?: return null
        val sig = prefs.getString(K_DLG_SIG, null) ?: return null
        val scope = prefs.getString(K_DLG_SCOPE, "")!!.split(",").filter { it.isNotEmpty() }
        return DelegationCert(
            ephemeralPk = eph,
            validFrom = prefs.getLong(K_DLG_FROM, 0),
            validUntil = prefs.getLong(K_DLG_UNTIL, 0),
            scope = scope,
            delegationSig = sig,
        )
    }

    fun hasIdentity(): Boolean = prefs.contains(K_MATERIAL)

    /**
     * Cheap, allocation-light fingerprint of the persisted identity material,
     * using only plain (non-Keystore, non-crypto) prefs reads. Changes whenever
     * the identity is created / reset, the delegation (ephemeral key) rotates,
     * or the second factor is toggled, so an in-memory [IdentitySandwich] cache
     * keyed on it (see [IdentitySession]) invalidates automatically. NOT a
     * security boundary, just a change detector.
     */
    fun materialFingerprint(): String = buildString {
        append(prefs.getString(K_MASTER_PK, "")); append('|')
        append(prefs.getString(K_DLG_EPH, "")); append('|')
        append(prefs.getLong(K_DLG_UNTIL, 0L)); append('|')
        append(prefs.getString(K_DLG_SIG, "")); append('|')
        append(prefs.getBoolean(K_2FA_ON, false))
    }

    /** Wipe the persisted identity and drop the wrap key (wallet reset). */
    fun wipe() {
        prefs.edit().clear().apply()
        secure.deleteKey()
        IdentitySession.clear()
    }

    private fun enc(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun dec(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val PREFS = "maknoon.identity.v1"
        private const val K_MATERIAL = "master.material"
        private const val K_EPH_SEED = "ephemeral.seed"
        private const val K_MASTER_PK = "master.publicKey"
        private const val K_DLG_EPH = "delegation.ephemeralPk"
        private const val K_DLG_FROM = "delegation.validFrom"
        private const val K_DLG_UNTIL = "delegation.validUntil"
        private const val K_DLG_SCOPE = "delegation.scope"
        private const val K_DLG_SIG = "delegation.sig"
        private const val K_2FA_ON = "secondFactor.enabled"
        private const val K_SEALED_ENTROPY = "secondFactor.sealedEntropyUnderCek"
    }
}
