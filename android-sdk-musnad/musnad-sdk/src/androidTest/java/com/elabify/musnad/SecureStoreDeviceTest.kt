// On-device test for AndroidSecureStore: proves the wrap key lands in the
// Pixel 9's StrongBox (Titan M2) and seal/open round-trips on real hardware.
// Uses throwaway aliases so it never touches a real identity's wrap key.
//
// Crypto round-trips use requireUnlockedDevice=false: a headless instrumented
// test can't satisfy a PIN keyguard, and the production (unlocked-required)
// gate is verified separately by observing the DEVICE_LOCKED rejection.

package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elabify.musnad.crypto.AndroidSecureStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureStoreDeviceTest {

    private val lockedAlias = "maknoon.test.wrap.locked"
    private val cryptoAlias = "maknoon.test.wrap.crypto"

    private fun cryptoStore() = AndroidSecureStore(cryptoAlias, requireUnlockedDevice = false)

    @After
    fun cleanup() {
        AndroidSecureStore(lockedAlias).deleteKey()
        AndroidSecureStore(cryptoAlias).deleteKey()
    }

    @Test
    fun wrapKeyIsHardwareBackedStrongBoxOnPixel9() {
        // Production config (unlocked-device-required) still lands in StrongBox.
        assertEquals(
            AndroidSecureStore.SecurityLevel.STRONGBOX,
            AndroidSecureStore(lockedAlias).securityLevel,
        )
    }

    @Test
    fun cryptoStoreIsAlsoStrongBox() {
        assertEquals(AndroidSecureStore.SecurityLevel.STRONGBOX, cryptoStore().securityLevel)
    }

    @Test
    fun sealOpenRoundTrips() {
        val store = cryptoStore()
        val secret = "entropy+passphrase master material".toByteArray()
        val blob = store.seal(secret)
        assertFalse("ciphertext must not equal plaintext", blob.contentEquals(secret))
        assertArrayEquals(secret, store.open(blob))
    }

    @Test
    fun sealsAreNonDeterministic() {
        val store = cryptoStore()
        val p = ByteArray(64) { it.toByte() }
        assertFalse("random IV -> distinct ciphertexts", store.seal(p).contentEquals(store.seal(p)))
    }

    @Test
    fun keyPersistsAcrossInstances() {
        val secret = "persist me".toByteArray()
        val blob = cryptoStore().seal(secret)
        assertTrue(cryptoStore().keyExists())
        assertArrayEquals(secret, cryptoStore().open(blob))
    }
}
