// Per-INSTALLATION merchant verifier identity (ML-DSA-65), separate from the
// holder's consumer Identity Sandwich. Android port of MerchantIdentityStore.swift.
//
// Each installed merchant dApp (the POS) gets its own stable verifier key + DID,
// keyed by its installedAppId, so a merchant's settings are self-contained to
// that installation (uninstall wipes it; reinstall starts fresh + re-registers).
// The dApp signs its VerifierRequests / CommerceRequests with this key so the
// customer's wallet resolves a consistent verifierDid -> pubkey against the
// curated registry and shows "Verified: <Merchant>" once registered; until then
// requests are self-signed (pubkey inlined) with this same stable key.
//
// Device-only: the 32-byte ML-DSA seed is sealed with AndroidSecureStore (the
// StrongBox/TEE-wrapped AES-GCM key) and stored base64 in a private prefs file,
// the Android analog of the iOS ThisDeviceOnly, non-biometric Keychain item
// (generating a request QR stays silent). It never travels in the encrypted
// backup. Curated registration is manual (sales@elabify.com); no self-service.
//
// DID derivation mirrors iOS exactly: rpo256Tagged(0x03, pubkey), first 20 bytes
// hex, did:elabify:sepolia:verifier:0x<hex>.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import android.util.Base64
import com.elabify.core.rpo256Tagged
import com.elabify.musnad.crypto.AndroidSecureStore
import com.elabify.musnad.crypto.toHex
import java.security.SecureRandom
import uniffi.pq_crypto_core.mldsa65PublicKey
import uniffi.pq_crypto_core.mldsa65Sign

class MerchantIdentityStore(
    context: Context,
    private val secure: AndroidSecureStore = AndroidSecureStore(WRAP_ALIAS, requireUnlockedDevice = false),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 0x-prefixed public key hex (for the registry entry + inline self-signed QRs). */
    fun publicKeyHex(installedAppId: String): String? =
        publicKey(installedAppId)?.let { "0x" + it.toHex() }

    /**
     * Stable did:elabify verifier DID derived from the pubkey (RPO-256 tagged),
     * mirroring the holder DID scheme. null until provisioned.
     */
    fun did(installedAppId: String): String? {
        val pk = publicKey(installedAppId) ?: return null
        val user = rpo256Tagged(0x03, pk)
        val hex = user.copyOfRange(0, 20).toHex()
        return "did:elabify:sepolia:verifier:0x$hex"
    }

    /** Ensure a key exists for this install, generating + persisting on first use. */
    fun ensureProvisioned(installedAppId: String): String {
        did(installedAppId)?.let { return it }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        saveSeed(installedAppId, seed)
        return did(installedAppId)
            ?: throw IllegalStateException("merchant DID unavailable after provisioning")
    }

    /** Sign [message] with this install's merchant key (provisions on first use). */
    fun sign(installedAppId: String, message: ByteArray): ByteArray {
        ensureProvisioned(installedAppId)
        val seed = loadSeed(installedAppId)
            ?: throw IllegalStateException("merchant seed missing after provisioning")
        return mldsa65Sign(seed, message)
    }

    /** Wipe this install's identity (called on uninstall). */
    fun evict(installedAppId: String) {
        prefs.edit().remove(seedKey(installedAppId)).apply()
    }

    // ---- internals ----

    private fun publicKey(installedAppId: String): ByteArray? {
        val seed = loadSeed(installedAppId) ?: return null
        return try { mldsa65PublicKey(seed) } catch (_: Exception) { null }
    }

    private fun saveSeed(installedAppId: String, seed: ByteArray) {
        val sealed = secure.seal(seed)
        prefs.edit().putString(seedKey(installedAppId), Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun loadSeed(installedAppId: String): ByteArray? {
        val stored = prefs.getString(seedKey(installedAppId), null) ?: return null
        return try { secure.open(Base64.decode(stored, Base64.NO_WRAP)) } catch (_: Exception) { null }
    }

    private fun seedKey(installedAppId: String) = "merchant.mldsaSeed.v1.$installedAppId"

    companion object {
        private const val PREFS = "maknoon.miniapp.merchant.v1"
        private const val WRAP_ALIAS = "miniapp.merchant.vault.wrap"
    }
}
