// On-device end-to-end for the Identity Sandwich on the Pixel 9: generate ->
// persist (StrongBox-sealed) -> reload, plus mnemonic restore, with the master
// + ephemeral ML-DSA-65 keys and the delegation cert all exercised on real
// hardware. Uses a test wrap key (requireUnlockedDevice=false) + a throwaway
// prefs namespace.

package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elabify.core.Bip39
import com.elabify.musnad.crypto.AndroidSecureStore
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.identity.Delegation
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IdentitySandwichDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val secure = AndroidSecureStore("maknoon.test.sandwich.wrap", requireUnlockedDevice = false)
    private val prefs = "maknoon.test.sandwich.prefs"
    private fun store() = IdentityStore(ctx, secure, prefs)
    private val now = 1_800_000_000L

    @After
    fun cleanup() {
        store().wipe()
    }

    @Test
    fun generatePersistReloadAndSign() {
        val (sandwich, entropy) = IdentitySandwich.generateFresh("test-pass", now, store())

        // Identity shape.
        assertEquals(1952, sandwich.masterPublicKey.size)
        assertEquals(1952, sandwich.ephemeralPublicKey.size)
        assertTrue(sandwich.holderDid.startsWith("did:elabify:sepolia:holder:0x"))

        // Delegation verifies under the master, on-device.
        assertTrue("delegation verifies", Delegation.verify(sandwich.masterPublicKey, sandwich.delegation))
        assertEquals("0x" + hex(sandwich.ephemeralPublicKey), sandwich.delegation.ephemeralPk)

        // Ephemeral fast-path + master slow-path signatures verify.
        val msg = "challenge-42".toByteArray()
        assertTrue(MasterKey.verify(sandwich.ephemeralPublicKey, sandwich.signChallenge(msg), msg))
        assertTrue(MasterKey.verify(sandwich.masterPublicKey, sandwich.signWithMaster(msg), msg))

        // Reload from sealed storage -> identical identity.
        val reloaded = IdentitySandwich.load(store())
        assertNotNull(reloaded)
        assertArrayEquals(sandwich.masterPublicKey, reloaded!!.masterPublicKey)
        assertEquals(sandwich.holderDid, reloaded.holderDid)
        assertTrue(Delegation.verify(reloaded.masterPublicKey, reloaded.delegation))
        assertTrue(MasterKey.verify(reloaded.ephemeralPublicKey, reloaded.signChallenge(msg), msg))

        // The 24 words round-trip the entropy.
        assertEquals(Bip39.mnemonicFromEntropy(entropy), reloaded.recoveryWords())
    }

    @Test
    fun restoreFromMnemonicYieldsSameMasterAndDid() {
        val (original, entropy) = IdentitySandwich.generateFresh("p@ss", now, store())
        val words = Bip39.mnemonicFromEntropy(entropy)
        store().wipe()

        val restored = IdentitySandwich.restoreFromMnemonic(words, "p@ss", now, store())
        assertArrayEquals(original.masterPublicKey, restored.masterPublicKey)
        assertEquals(original.holderDid, restored.holderDid)
        assertTrue(Delegation.verify(restored.masterPublicKey, restored.delegation))
    }

    @Test
    fun exportThenRestoreEncryptedBackupRebuildsIdentity() = runBlocking {
        val (original, _) = IdentitySandwich.generateFresh("backup-pass", now, store())
        val blob = original.exportEncryptedBackup()
        store().wipe() // simulate a fresh device

        val restored = IdentitySandwich.restoreFromEncryptedBackup(blob, "backup-pass", now, store())
        assertArrayEquals(original.masterPublicKey, restored.masterPublicKey)
        assertEquals(original.holderDid, restored.holderDid)
        assertTrue(Delegation.verify(restored.masterPublicKey, restored.delegation))
    }

    @Test
    fun renewDelegationKeepsEphemeralExtendsWindow() {
        val (sandwich, _) = IdentitySandwich.generateFresh("x", now, store())
        val before = sandwich.delegation
        sandwich.renewDelegation(now + 1, store())
        assertEquals(before.ephemeralPk, sandwich.delegation.ephemeralPk) // same ephemeral
        assertTrue(sandwich.delegation.validUntil > before.validUntil)
        assertTrue(Delegation.verify(sandwich.masterPublicKey, sandwich.delegation))
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
