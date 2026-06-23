// On-device verification (Pixel 9 / GrapheneOS, arm64): proves the native
// pq-crypto-core .so, loaded via JNA on real hardware, reproduces Apple
// CryptoKit's ML-DSA-65 bytes. Vectors are the pq-crypto-rs CryptoKit oracle
// corpus (mldsa65.kat.json), wired in as an androidTest asset.
//
// Run:  ./gradlew :musnad-sdk:connectedDebugAndroidTest

package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.identity.HolderDid
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.pq_crypto_core.mldsa65PublicKey
import uniffi.pq_crypto_core.mldsa65Sign
import uniffi.pq_crypto_core.mldsa65VerifySignature

@RunWith(AndroidJUnit4::class)
class MlDsaDeviceTest {

    private fun corpus(): JSONObject {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val text = ctx.assets.open("mldsa65.kat.json").bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    @Test
    fun nativeMlDsaMatchesCryptoKitOnDevice() {
        val vectors = corpus().getJSONArray("vectors")
        assertTrue("corpus not empty", vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val seed = hexToBytes(v.getString("seedHex"))
            val expectedPk = v.getString("publicKeyHex")
            val message = hexToBytes(v.getString("messageHex"))
            val cryptoKitSig = hexToBytes(v.getString("signatureHex"))

            // 1. seed -> pubkey byte-parity with CryptoKit, on real arm64.
            val pk = mldsa65PublicKey(seed)
            assertEquals("pubkey[$i]", expectedPk, pk.toHex())

            // 2. The device verifies CryptoKit's (hedged) signature.
            assertTrue("verify CryptoKit sig[$i]", mldsa65VerifySignature(pk, cryptoKitSig, message))

            // 3. A device-produced deterministic signature verifies under the pubkey.
            val sig = mldsa65Sign(seed, message)
            assertEquals("sig length[$i]", 3309, sig.size)
            assertTrue("verify device sig[$i]", mldsa65VerifySignature(pk, sig, message))
        }
    }

    @Test
    fun holderDidIsDeterministicOnDevice() {
        val seed = hexToBytes("07".repeat(32))
        val pk = mldsa65PublicKey(seed)
        val did1 = HolderDid.fromMasterPublicKey(pk)
        val did2 = HolderDid.fromMasterPublicKey(pk)
        assertEquals(did1, did2)
        assertTrue(did1.startsWith("did:elabify:sepolia:holder:0x"))
        assertEquals(40, did1.removePrefix(HolderDid.PREFIX).length)
    }
}
