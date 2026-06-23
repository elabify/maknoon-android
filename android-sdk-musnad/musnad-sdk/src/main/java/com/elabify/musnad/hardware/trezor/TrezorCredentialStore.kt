// Persists the THP host static key (one per app install) and the
// most-recent Trezor reconnection credential. Ported 1:1 from iOS
// Maknoon/HardwareWallet/TrezorCredentialStore.swift.
//
// With these, identity and signing operations reconnect to a paired
// Trezor WITHOUT making the user re-enter the 6-digit code each time:
// the handshake replays the credential and reaches the encrypted
// session directly.
//
// The two secrets are sealed with a dedicated AndroidKeyStore AES-GCM
// key (the Android analog of the iOS biometric Keychain) and the sealed
// blobs are stored in a private SharedPreferences file. Single-device
// for now (one stored credential); per-device keying for users with
// multiple Trezors is a follow-up, exactly as on iOS. The credential is
// bound to the host static key, which is why that key is persistent.

package com.elabify.musnad.hardware.trezor

import android.content.Context
import android.util.Base64
import com.elabify.musnad.crypto.AndroidSecureStore
import com.elabify.musnad.hardware.HardwareWalletException
import java.security.SecureRandom

class TrezorCredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // A dedicated wrap key so Trezor secrets never share the wallet
    // master alias. Bound to the device being unlocked, mirroring the
    // iOS WhenUnlockedThisDeviceOnly tier.
    private val secureStore = AndroidSecureStore(alias = WRAP_ALIAS)

    /**
     * The app's persistent THP host X25519 secret (32 bytes), created on
     * first use. The reconnection credential is bound to this key, so it
     * must stay stable across connects.
     */
    @Throws(HardwareWalletException::class)
    fun hostStaticKey(): ByteArray {
        loadSealed(KEY_HOST)?.let { if (it.size == 32) return it }
        val bytes = ByteArray(32)
        try {
            SecureRandom().nextBytes(bytes)
        } catch (t: Throwable) {
            throw HardwareWalletException.Transport("could not generate a Trezor host key")
        }
        saveSealed(KEY_HOST, bytes)
        return bytes
    }

    fun saveCredential(credential: ByteArray) {
        saveSealed(KEY_CREDENTIAL, credential)
    }

    fun loadCredential(): ByteArray? = loadSealed(KEY_CREDENTIAL)

    /**
     * Forget the stored reconnection credential. Called when a Trezor is
     * removed, so a later re-register does a fresh CodeEntry pairing instead of
     * resuming a stale credential (which the device rejects on a clean reconnect).
     */
    fun clearCredential() {
        prefs.edit().remove(KEY_CREDENTIAL).apply()
    }

    // ---- internals ----

    private fun saveSealed(key: String, plaintext: ByteArray) {
        val sealed = secureStore.seal(plaintext)
        prefs.edit()
            .putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP))
            .apply()
    }

    private fun loadSealed(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            secureStore.open(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (t: Throwable) {
            // A sealed blob we can no longer open (key dropped / corrupt)
            // is treated as absent so the caller re-pairs cleanly.
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "trezor.thp.store.v1"
        private const val WRAP_ALIAS = "maknoon.trezor.thp.wrap"
        private const val KEY_HOST = "trezor.thp.hostkey.v1"
        private const val KEY_CREDENTIAL = "trezor.thp.credential.v1"
    }
}
