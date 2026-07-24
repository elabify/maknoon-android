package com.elabify.musnad.present

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the on-chain credential verdict composition: fullyVerified is true only
 * when the three chain gates (issuerRegistered, notRevoked, rootCurrent) AND
 * the on-chain header signature all pass. Any Fail or Unknown (e.g. RPC
 * unreachable) drops it to false. Mirrors iOS OnChainVerdictTests.
 */
class OnChainVerdictTest {
    private fun verdict(
        reached: Boolean = true,
        issuer: OnChainTier = OnChainTier.Pass,
        notRevoked: OnChainTier = OnChainTier.Pass,
        root: OnChainTier = OnChainTier.Pass,
        header: OnChainTier = OnChainTier.Pass,
        csca: OnChainTier? = null,
    ) = OnChainVerdict(reached, issuer, notRevoked, root, header, csca)

    @Test fun allGatesPassIsFullyVerified() {
        assertTrue(verdict().fullyVerified)
        // cscaProvenance does not affect the core verdict.
        assertTrue(verdict(csca = OnChainTier.Fail("bad")).fullyVerified)
    }

    @Test fun anyFailBreaksVerification() {
        assertFalse(verdict(issuer = OnChainTier.Fail("not registered")).fullyVerified)
        assertFalse(verdict(notRevoked = OnChainTier.Fail("revoked")).fullyVerified)
        assertFalse(verdict(root = OnChainTier.Fail("stale root")).fullyVerified)
        assertFalse(verdict(header = OnChainTier.Fail("bad sig")).fullyVerified)
    }

    @Test fun anyUnknownBreaksVerification() {
        assertFalse(verdict(issuer = OnChainTier.Unknown("rpc")).fullyVerified)
        assertFalse(verdict(header = OnChainTier.Unknown("key not published")).fullyVerified)
    }

    @Test fun unreachableDegradesToUnverified() {
        // An all-Unknown verdict from an unreachable chain is not fully verified.
        val v = verdict(
            reached = false,
            issuer = OnChainTier.Unknown("RPC unreachable"),
            notRevoked = OnChainTier.Unknown("RPC unreachable"),
            root = OnChainTier.Unknown("RPC unreachable"),
            header = OnChainTier.Unknown("RPC unreachable"),
        )
        assertFalse(v.fullyVerified)
    }
}
