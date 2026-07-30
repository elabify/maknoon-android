package com.elabify.app.maknoon.ui.wallet.ethereum

import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumToken
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the classification a scanned token QR gets against one wallet's token
 * list. The motivating case is bridged USDC.e (0xff97…5cc8) requested while the
 * wallet holds native USDC (0xaf88…5831): both report symbol() == "USDC" on
 * chain, so this must surface the held token as a CANDIDATE the user picks,
 * never as an automatic substitution. Mirrors iOS EthereumScannedTokenTests.
 */
class EthereumScannedTokenTest {

    private val nativeUsdc = EthereumToken.create(
        EthereumNetwork.ARBITRUM, "0xaf88d065e77c8cc2239327c5edb3a432268e5831",
        "USDC", "USD Coin", 6, false,
    )
    private val bridgedUsdc = EthereumToken.create(
        EthereumNetwork.ARBITRUM, "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8",
        "USDC", "USD Coin (Arb1)", 6, false,
    )
    private val arb = EthereumToken.create(
        EthereumNetwork.ARBITRUM, "0x912ce59144191c1204e64559fe8253a0e49e6548",
        "ARB", "Arbitrum", 18, false,
    )

    /** The exact contract wins even though a same-symbol token is also present. */
    @Test
    fun exactContractBeatsSameSymbol() {
        val match = EthereumScannedToken.resolve(
            "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", "USDC", listOf(nativeUsdc, bridgedUsdc),
        )
        assertEquals(EthereumScannedTokenMatch.AlreadyAdded(bridgedUsdc), match)
    }

    /** The QR's target is checksummed, the store's is lowercase. */
    @Test
    fun contractCompareIsCaseInsensitive() {
        val match = EthereumScannedToken.resolve(
            "0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8", "USDC", listOf(bridgedUsdc),
        )
        assertEquals(EthereumScannedTokenMatch.AlreadyAdded(bridgedUsdc), match)
    }

    /** The real-world case: bridged requested, only native held. */
    @Test
    fun absentContractSurfacesSameSymbolHolding() {
        val match = EthereumScannedToken.resolve(
            "0xFF970A61A04b1cA14834A43f5dE4533eBDDB5CC8", "USDC", listOf(nativeUsdc, arb),
        )
        assertEquals(EthereumScannedTokenMatch.SameSymbolCandidates(listOf(nativeUsdc)), match)
    }

    /**
     * Every same-symbol holding is offered, and the requested contract itself is
     * never listed as its own alternative.
     */
    @Test
    fun allSameSymbolCandidatesReturnedExcludingTheRequestedOne() {
        val otherUsdc = EthereumToken.create(
            EthereumNetwork.ARBITRUM, "0x1111111111111111111111111111111111111111",
            "usdc", "Some other USDC", 6, false,
        )
        val match = EthereumScannedToken.resolve(
            "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", "USDC", listOf(nativeUsdc, otherUsdc, arb),
        )
        assertEquals(EthereumScannedTokenMatch.SameSymbolCandidates(listOf(nativeUsdc, otherUsdc)), match)
    }

    /** No holdings at all, or nothing with a matching symbol: only the add path. */
    @Test
    fun unknownWhenNothingMatches() {
        assertEquals(
            EthereumScannedTokenMatch.Unknown,
            EthereumScannedToken.resolve("0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", "USDC", emptyList()),
        )
        assertEquals(
            EthereumScannedTokenMatch.Unknown,
            EthereumScannedToken.resolve("0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", "USDC", listOf(arb)),
        )
    }

    /**
     * A failed probe leaves no symbol to match on, so a same-symbol holding must
     * NOT be guessed at.
     */
    @Test
    fun noProbedSymbolCannotProduceCandidates() {
        assertEquals(
            EthereumScannedTokenMatch.Unknown,
            EthereumScannedToken.resolve("0xff970a61a04b1ca14834a43f5de4533ebddb5cc8", null, listOf(nativeUsdc)),
        )
    }

    @Test
    fun emptyContractIsUnknown() {
        assertEquals(
            EthereumScannedTokenMatch.Unknown,
            EthereumScannedToken.resolve("  ", "USDC", listOf(nativeUsdc)),
        )
    }
}
