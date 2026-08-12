// Locks the UNIT of the credential row's createdAt column.
//
// This exists because the facade shipped `System.currentTimeMillis() / 1000L`
// into a column every other writer fills with milliseconds, and which the UI
// divides by 1000 to display. The DAO orders by `createdAt DESC`, so a
// credential imported through the facade sorted as if it were issued in 1970
// and sat at the bottom of the holder's list permanently.
//
// Nothing failed. No type was wrong. Both values are a Long, both are
// plausible, and the only way to see it is to compare against what else writes
// the column. A unit is a contract that the type system cannot express, so it
// needs a test.

package com.elabify.maknoon.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialEntityUnitsTest {

    /** Minimal credential payload: what ParsedCredential.parse requires. */
    private fun credentialJson(cid: String = "0xcid") = """
        {
          "header": {
            "v": 2,
            "cid": "$cid",
            "iss": "did:elabify:sepolia:issuer:musnad",
            "sub": "did:elabify:holder:abc",
            "schema": "elabify://schema/global/passport/v1",
            "iat": 1750000000,
            "root": "0xroot"
          },
          "headerSig": "0xsig",
          "claims": { "givenName": "ELI" },
          "merkleTree": { "sortedKeys": ["givenName"], "root": "0xroot", "depth": 1 }
        }
    """.trimIndent()

    @Test
    fun `createdAt is stored in milliseconds, verbatim`() {
        val nowMs = 1_760_000_000_000L          // ~2025 in millis
        val e = credentialEntityOf(credentialJson(), nowMs)
        assertEquals(nowMs, e.createdAt)
    }

    @Test
    fun `createdAt is not silently converted to seconds`() {
        // The exact regression. A seconds value here is ~1.7e9, which as millis
        // is January 1970, and the list sorts by this column.
        val nowMs = 1_760_000_000_000L
        val e = credentialEntityOf(credentialJson(), nowMs)
        assertTrue(
            "createdAt looks like seconds (${e.createdAt}); the column is millis",
            e.createdAt > 1_000_000_000_000L,
        )
    }

    @Test
    fun `two imports a second apart order correctly`() {
        // What the bug actually broke: newest-first ordering in the holder's
        // credential list. In seconds these would have collided or inverted.
        val first = credentialEntityOf(credentialJson("0xa"), 1_760_000_000_000L)
        val second = credentialEntityOf(credentialJson("0xb"), 1_760_000_001_000L)
        assertTrue(second.createdAt > first.createdAt)
    }

    @Test
    fun `header fields are carried onto the row`() {
        // The rest of the mapping, so a future edit cannot quietly drop the
        // issuer or schema while keeping the timestamp right.
        val e = credentialEntityOf(credentialJson("0xdeadbeef"), 1L)
        assertEquals("0xdeadbeef", e.cid)
        assertEquals("did:elabify:sepolia:issuer:musnad", e.issuerDid)
        assertEquals("did:elabify:holder:abc", e.subjectDid)
        assertEquals("elabify://schema/global/passport/v1", e.schema)
    }

    @Test
    fun `the credential JSON is stored unmodified`() {
        // The stored bytes are what signature verification re-reads later, so
        // any reformatting here would break verification after a restart.
        val json = credentialJson()
        assertEquals(json, credentialEntityOf(json, 1L).credentialJson)
    }
}
