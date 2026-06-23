// JVM unit tests for the platform-independent identity core (no Android
// APIs, no native ML-DSA). The native sign/verify + StrongBox keystore are
// exercised in instrumented tests on the Pixel 9 (P7).

package com.elabify.musnad.identity

import com.elabify.core.rpo256Tagged
import com.elabify.musnad.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityCoreTest {

    // Any 1952-byte array stands in for an ML-DSA-65 public key; the DID
    // derivation only hashes bytes (rpo256Tagged is already KAT-verified).
    private val fakePk = ByteArray(1952) { (it and 0xff).toByte() }

    @Test
    fun holderDidFormatAndDerivation() {
        val did = HolderDid.fromMasterPublicKey(fakePk)
        assertTrue(did.startsWith("did:elabify:sepolia:holder:0x"), "prefix")
        val suffix = did.removePrefix(HolderDid.PREFIX)
        assertEquals(40, suffix.length, "20-byte fingerprint = 40 hex chars")
        assertTrue(suffix.all { it in "0123456789abcdef" }, "lowercase hex")
        // Self-consistency with the documented formula.
        val expected = rpo256Tagged(0x03, fakePk).copyOfRange(0, 20).toHex()
        assertEquals(expected, suffix)
        // Deterministic.
        assertEquals(did, HolderDid.fromMasterPublicKey(fakePk))
    }

    @Test
    fun delegationCanonicalMessageIsSortedKeyJson() {
        val msg = Delegation.canonicalMessage(
            ephemeralPkHex = "0xdeadbeef",
            validFrom = 1_800_000_000L,
            validUntil = 1_800_000_000L + Delegation.VALIDITY_WINDOW_SEC,
        )
        // Canonical JSON: sorted keys, integer literals, no whitespace.
        val expected =
            """{"ephemeralPk":"0xdeadbeef","scope":["verify"],"validFrom":1800000000,"validUntil":1800086400}"""
        assertEquals(expected, String(msg, Charsets.UTF_8))
    }

    @Test
    fun renewalWindow() {
        val cert = DelegationCert(
            ephemeralPk = "0x00",
            validFrom = 1_000L,
            validUntil = 1_000L + Delegation.VALIDITY_WINDOW_SEC,
            scope = Delegation.SCOPE,
            delegationSig = "0x00",
        )
        assertTrue(!Delegation.needsRenewal(cert, nowSec = 1_000L))
        assertTrue(Delegation.needsRenewal(cert, nowSec = cert.validUntil - 10L))
    }
}
