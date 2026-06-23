// Hardware-backed at-rest protection for the Identity Sandwich, the Android
// analog of the iOS biometric Keychain. A 256-bit AES-GCM key lives in the
// AndroidKeyStore, StrongBox-backed (the Pixel 9 Titan M2) when available
// and TEE otherwise; software-only is refused outside debug. The master
// material ({entropyHex, passphrase}) and the ephemeral seed are sealed with
// this key so the secret bytes never sit in app storage in the clear.
//
// `setUnlockedDeviceRequired(true)` ties use to the device being unlocked
// (the device-unlock tier, like iOS kSecAttrAccessibleWhenUnlockedThisDeviceOnly).
// Per-operation biometric confirmation (the iOS .userPresence tier for
// revealing the seed / signing with the master) is layered on top at the UI
// via BiometricPrompt in P1; it is not baked into this key so the at-rest
// crypto stays testable on-device without a fingerprint tap.

package com.elabify.musnad.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureStore(
    private val alias: String = DEFAULT_ALIAS,
    private val allowSoftwareFallback: Boolean = false,
    // Bind use to the device being unlocked (iOS WhenUnlockedThisDeviceOnly
    // analog). Production default. The keystore enforces this hard: a sealed
    // op throws KeyStoreException(DEVICE_LOCKED) while the keyguard is locked.
    // Headless instrumented tests can't satisfy a PIN keyguard, so they pass
    // false to exercise the StrongBox crypto path itself.
    private val requireUnlockedDevice: Boolean = true,
) {
    enum class SecurityLevel { STRONGBOX, TEE, SOFTWARE }

    /** The hardware-security level the wrap key actually landed in. */
    val securityLevel: SecurityLevel by lazy { levelOf(getOrCreateKey()) }

    /** AES-256-GCM seal. Output = iv(12) || ciphertext || tag(16). */
    fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv // GCM IV the keystore generated for us
        val ct = cipher.doFinal(plaintext)
        return iv + ct
    }

    /** Inverse of [seal]. */
    fun open(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "sealed blob too short" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    /** Drop the wrap key (e.g. wallet reset). Sealed blobs become unreadable. */
    fun deleteKey() {
        keystore().deleteEntry(alias)
    }

    fun keyExists(): Boolean = keystore().containsAlias(alias)

    // ---- internals ----

    private fun keystore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keystore()
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey(strongBox = true)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(requireUnlockedDevice)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return try {
            gen.init(spec)
            gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) generateKey(strongBox = false)
            else throw e
        }.also { key ->
            // Enforce the posture: refuse a software-only key unless explicitly
            // allowed (debug). StrongBox or TEE is required in production.
            if (!allowSoftwareFallback && levelOf(key) == SecurityLevel.SOFTWARE) {
                keystore().deleteEntry(alias)
                throw IllegalStateException(
                    "No hardware-backed keystore (StrongBox/TEE) available; refusing software-only.",
                )
            }
        }
    }

    private fun levelOf(key: SecretKey): SecurityLevel {
        val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        // KeyInfo.getSecurityLevel() is API 31+; minSdk is 33.
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE
            else -> SecurityLevel.SOFTWARE
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "maknoon.master.wrap"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
