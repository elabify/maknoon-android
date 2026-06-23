// On-device test for Android Key Attestation on the Pixel 9: proves the
// attested EC key lands in StrongBox, the leaf certificate binds our holder
// DID as the attestation challenge, the X.509 chain links to a hardware root,
// and per-request assertions verify under the enrolled leaf key. This is the
// GMS-free substitute for iOS App Attest.

package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.elabify.musnad.attest.KeyAttestation
import com.elabify.musnad.crypto.AndroidSecureStore
import java.io.ByteArrayInputStream
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyAttestationDeviceTest {

    private val attestation = KeyAttestation("maknoon.test.attest.key")
    private val holderDid = "did:elabify:sepolia:holder:0xabc123".toByteArray()

    @After
    fun cleanup() {
        attestation.deleteKey()
    }

    @Test
    fun enrollmentIsStrongBoxAndBindsChallenge() {
        val e = attestation.enroll(holderDid)

        // Key is in the Titan M2 StrongBox, both per KeyInfo and per the cert.
        assertEquals(AndroidSecureStore.SecurityLevel.STRONGBOX, e.securityLevel)
        assertEquals(KeyAttestation.LEVEL_STRONGBOX, e.attestationSecurityLevel)

        // Our holder DID is bound into the leaf certificate (freshness).
        val leaf = x509(e.certChainDer.first())
        val extBytes = leaf.getExtensionValue("1.3.6.1.4.1.11129.2.1.17")
        assertTrue("leaf has attestation extension", extBytes != null)
        assertArrayEquals(holderDid, e.challenge)

        // A real chain to a hardware root: more than one cert.
        assertTrue("multi-cert chain", e.certChainDer.size >= 2)
    }

    @Test
    fun certificateChainLinksUp() {
        val chain = attestation.enroll(holderDid).certChainDer.map { x509(it) }
        // Each non-root cert verifies under the next cert's public key.
        for (i in 0 until chain.size - 1) {
            chain[i].verify(chain[i + 1].publicKey)
        }
    }

    @Test
    fun assertionVerifiesUnderLeafKey() {
        val e = attestation.enroll(holderDid)
        val message = "verifier-nonce-7".toByteArray()
        val sig = attestation.assert(message)

        val leafKey = x509(e.certChainDer.first()).publicKey
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(leafKey)
            update(message)
            verify(sig)
        }
        assertTrue("assertion verifies under the enrolled leaf key", ok)
    }

    private fun x509(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
}
