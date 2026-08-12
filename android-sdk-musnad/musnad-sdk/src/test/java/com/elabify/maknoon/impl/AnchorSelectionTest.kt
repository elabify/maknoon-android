// Guards the facade's anchor selection, and specifically that the anchor
// TRANSACTION HASH survives the trip to OnChainVerifier.
//
// Why this test exists rather than a broader one: after ADR-0022's amendment
// OnChainVerifier gates the whole `rootCurrent` tier on `anchorBatchTxHash`
// being non-empty, and the parameter defaults to null. The facade passed only
// `anchorBatchRoot`. It compiled, ran, reached the chain, and reported
// `rootCurrent = Unknown("Carries no on-chain anchor for a supported network")`
// on credentials that plainly carried an anchor. A verification tier that
// silently stops verifying is worse than one that fails, because nothing
// anywhere goes red.
//
// The end-to-end assertion (root + txHash yields a real Pass/Fail) needs a live
// RPC and an anchored credential, so it belongs on-device. What is checked here
// is the pure part that actually broke: that selection hands back the tx hash
// and the anchor's own registry instead of dropping them.

package com.elabify.maknoon.impl

import com.elabify.musnad.present.AnchorDescriptor
import com.elabify.musnad.present.AnchorEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnchorSelectionTest {

    private fun entry(
        chain: String,
        registry: String = "0x2dC72537000000000000000000000000000000001",
        root: String = "0xr00t",
        tx: String = "0x7x",
    ) = AnchorEntry(
        chain = chain,
        registry = registry,
        batchRoot = root,
        batchTxHash = tx,
        anchoredAt = 1_700_000_000L,
        batchProof = emptyList(),
    )

    @Test
    fun `tx hash reaches the caller`() {
        // The regression itself. If this returns null the rootCurrent tier is off.
        val d = AnchorDescriptor(v = 2, anchors = listOf(entry("eip155:11155111", tx = "0xabc")))
        assertEquals("0xabc", d.selected().txHashOrNull())
    }

    @Test
    fun `the anchor's own registry is used, not the identity chain's`() {
        // Revocation and root are read on the ANCHOR's chain, which need not be
        // the identity chain, so the anchor names the contract that holds them.
        val d = AnchorDescriptor(v = 2, anchors = listOf(entry("eip155:84532", registry = "0xBASE")))
        assertEquals("0xBASE", d.selected().registryOrNull())
    }

    @Test
    fun `sepolia wins when a credential is anchored on several chains`() {
        val d = AnchorDescriptor(
            v = 2,
            anchors = listOf(entry("eip155:84532", tx = "0xbase"), entry("eip155:11155111", tx = "0xsep")),
        )
        assertEquals("0xsep", d.selected().txHashOrNull())
    }

    @Test
    fun `falls back to the only anchor when sepolia is absent`() {
        val d = AnchorDescriptor(v = 2, anchors = listOf(entry("eip155:84532", tx = "0xbase")))
        assertEquals("0xbase", d.selected().txHashOrNull())
    }

    @Test
    fun `no anchor yields nulls rather than throwing`() {
        // An unanchored credential is ordinary, not an error: rootCurrent is
        // simply not assessable. Verifier treats null as "tier does not run".
        assertNull(null.selected())
        assertNull(AnchorDescriptor(v = 2, anchors = emptyList()).selected())
        assertNull(null.selected().txHashOrNull())
        assertNull(null.selected().rootOrNull())
        assertNull(null.selected().registryOrNull())
    }

    @Test
    fun `blank strings are treated as absent`() {
        // A blank tx hash must not be handed to the verifier: isNullOrEmpty is
        // what gates the tier, and " " would pass that check while being useless.
        val d = AnchorDescriptor(v = 2, anchors = listOf(entry("eip155:11155111", registry = "", root = "", tx = "")))
        assertNull(d.selected().txHashOrNull())
        assertNull(d.selected().rootOrNull())
        assertNull(d.selected().registryOrNull())
    }
}
