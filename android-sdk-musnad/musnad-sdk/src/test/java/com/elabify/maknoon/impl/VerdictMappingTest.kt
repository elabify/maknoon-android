// Locks the internal-to-facade mappings in MusnadCredentialsImpl.
//
// Every defect found in this facade so far has been a mapping: a timestamp in
// the wrong unit, an error taxonomy that hid the common case, a policy default
// narrower than the app's. None was a type error, so none was caught by the
// compiler or by :embed-tests, which only proves the API is reachable.
//
// The polarity test below is the one that matters most. `toRevoked` INVERTS its
// input: OnChainTier.Pass means "the revocation check passed", so
// `revoked = false`. A future edit that made these read alike would report a
// revoked credential as valid, in a wallet whose whole purpose is to say
// whether a credential is still good. That is a safety property, not a style
// preference, so it gets an explicit test rather than a comment.

package com.elabify.maknoon.impl

import com.elabify.maknoon.ClaimValue
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.OnChainTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictMappingTest {

    // ---- tier polarity -----------------------------------------------------

    @Test
    fun `pass fail unknown map to true false null`() {
        assertEquals(true, OnChainTier.Pass.toBool())
        assertEquals(false, OnChainTier.Fail("nope").toBool())
        assertNull(OnChainTier.Unknown("no rpc").toBool())
    }

    @Test
    fun `revoked is the INVERSE of the tier, not a copy of it`() {
        // Pass means the revocation check passed, i.e. NOT revoked.
        assertEquals(false, OnChainTier.Pass.toRevoked())
        assertEquals(true, OnChainTier.Fail("revoked on-chain").toRevoked())
        assertNull(OnChainTier.Unknown("unreachable").toRevoked())
    }

    @Test
    fun `toBool and toRevoked disagree on every decided tier`() {
        // The guard against someone "simplifying" these into one function.
        for (tier in listOf(OnChainTier.Pass, OnChainTier.Fail("x"))) {
            assertTrue(
                "toRevoked must not equal toBool for $tier",
                tier.toBool() != tier.toRevoked(),
            )
        }
    }

    @Test
    fun `unknown never becomes a decision`() {
        // Reporting "not revoked" for an unreachable chain would be the
        // dangerous direction of the fail-open/fail-closed choice: absence of
        // evidence must stay absent, so the UI can say "could not check".
        assertNull(OnChainTier.Unknown("rpc down").toBool())
        assertNull(OnChainTier.Unknown("rpc down").toRevoked())
    }

    // ---- claim values ------------------------------------------------------

    @Test
    fun `every JsonValue variant maps to a ClaimValue`() {
        assertEquals(ClaimValue.Text("hi"), JsonValue.Str("hi").toClaimValue())
        assertEquals(ClaimValue.Bool(true), JsonValue.Bool(true).toClaimValue())
        assertEquals(ClaimValue.Null, JsonValue.Null.toClaimValue())
        // Both numeric shapes collapse to one facade type, so an integer claim
        // must not arrive as a string or lose its value.
        assertEquals(ClaimValue.Number(7.0), JsonValue.IntVal(7L).toClaimValue())
        assertEquals(ClaimValue.Number(1.5), JsonValue.DoubleVal(1.5).toClaimValue())
    }

    @Test
    fun `nesting is preserved to the leaves`() {
        // A passport credential nests: claims -> object -> array -> scalars.
        // Flattening or truncating here would silently drop disclosed data.
        val src = JsonValue.Obj(
            linkedMapOf(
                "name" to JsonValue.Str("ELI"),
                "codes" to JsonValue.Arr(listOf(JsonValue.IntVal(1L), JsonValue.Str("GBR"))),
            ),
        )
        val out = src.toClaimValue()
        assertTrue(out is ClaimValue.Nested)
        val nested = (out as ClaimValue.Nested).value
        assertEquals(ClaimValue.Text("ELI"), nested["name"])
        val items = (nested["codes"] as ClaimValue.Items).value
        assertEquals(listOf(ClaimValue.Number(1.0), ClaimValue.Text("GBR")), items)
    }

    @Test
    fun `an empty container stays an empty container`() {
        // Not null, and not dropped: an empty array is a real disclosed value.
        assertEquals(ClaimValue.Items(emptyList()), JsonValue.Arr(emptyList()).toClaimValue())
        assertEquals(ClaimValue.Nested(emptyMap()), JsonValue.Obj(linkedMapOf()).toClaimValue())
    }
}
