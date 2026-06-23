// On-device cross-platform test for the encrypted backup on the Pixel 9:
// opens authentic iOS v3 blobs (backup.kat.json, produced by compiling the
// real iOS PBKDF2.swift + BIP39.swift + CryptoKit) and round-trips Kotlin
// encrypt/decrypt -- proving an iPhone backup restores on Android (and, by
// the identical format + round-trip, the reverse).

package com.elabify.musnad

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elabify.musnad.backup.BackupException
import com.elabify.musnad.backup.EncryptedBackup
import com.elabify.musnad.crypto.hexToBytes
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedBackupDeviceTest {

    private fun corpus(): JSONObject {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open("backup.kat.json").bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    @Test
    fun opensAuthenticIosBlobs() {
        val vectors = corpus().getJSONArray("vectors")
        assertTrue("corpus not empty", vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val passphrase = v.getString("passphrase")
            val expectedPlain = hexToBytes(v.getString("plaintextHex"))
            val entropy = hexToBytes(v.getString("entropyHex"))
            val blobBytes = v.getJSONObject("blob").toString().toByteArray(Charsets.UTF_8)

            assertArrayEquals(
                "iOS blob[$i] decrypts to the original plaintext",
                expectedPlain,
                EncryptedBackup.decrypt(blobBytes, passphrase),
            )
            assertTrue(
                "embedded master pubkey matches entropy+passphrase[$i]",
                EncryptedBackup.verifyMasterBinding(blobBytes, passphrase, entropy),
            )
        }
    }

    @Test
    fun kotlinEncryptRoundTrips() {
        val entropy = ByteArray(32) { 0x07 }
        val passphrase = "round-trip-pass"
        val plaintext = "android-made backup payload".toByteArray()

        val blob = EncryptedBackup.encrypt(plaintext, passphrase, entropy)
        val parsed = JSONObject(String(blob, Charsets.UTF_8))
        assertEquals(3, parsed.getInt("v"))
        assertEquals("ML-DSA-65", parsed.getString("sigAlg"))

        assertArrayEquals(plaintext, EncryptedBackup.decrypt(blob, passphrase))
        assertTrue(EncryptedBackup.verifyMasterBinding(blob, passphrase, entropy))
    }

    @Test
    fun wrongPassphraseFails() {
        val entropy = ByteArray(32) { 0x07 }
        val blob = EncryptedBackup.encrypt("secret".toByteArray(), "right", entropy)
        assertThrows(BackupException::class.java) { EncryptedBackup.decrypt(blob, "wrong") }
    }

    @Test
    fun tamperedCiphertextFailsSignature() {
        val entropy = ByteArray(32) { 0x07 }
        val blob = EncryptedBackup.encrypt("secret".toByteArray(), "p", entropy)
        // Flip a byte inside the ciphertext -> v3 sig verification fails.
        val obj = JSONObject(String(blob, Charsets.UTF_8))
        val combined = Base64.decode(obj.getString("combined"), Base64.NO_WRAP)
        combined[combined.size / 2] = (combined[combined.size / 2].toInt() xor 0x01).toByte()
        obj.put("combined", Base64.encodeToString(combined, Base64.NO_WRAP))
        val bad = obj.toString().toByteArray(Charsets.UTF_8)
        assertThrows(BackupException::class.java) { EncryptedBackup.decrypt(bad, "p") }
    }
}
