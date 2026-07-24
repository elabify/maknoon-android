package com.elabify.musnad.wallet.solana

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the rent-exempt decision branches for a native SOL transfer, including
 * the deliberate fail-open behaviour (an RPC error defaults recipientExists to
 * true, so it never blocks). Mirrors iOS SolanaRentExemptTests.
 */
class RentExemptTest {
    private val min = SolanaWallet.RENT_EXEMPT_MINIMUM_LAMPORTS // 890_880

    @Test fun fundedAboveMinimumNeverBlocks() {
        assertFalse(rentExemptBlocksNativeTransfer(min, recipientExists = false))
        assertFalse(rentExemptBlocksNativeTransfer(min + 1, recipientExists = false))
        assertFalse(rentExemptBlocksNativeTransfer(2_000_000, recipientExists = false))
    }

    @Test fun newAccountBelowMinimumBlocks() {
        assertTrue(rentExemptBlocksNativeTransfer(1, recipientExists = false))
        assertTrue(rentExemptBlocksNativeTransfer(min - 1, recipientExists = false))
    }

    @Test fun existingAccountBelowMinimumDoesNotBlock() {
        assertFalse(rentExemptBlocksNativeTransfer(1, recipientExists = true))
    }

    @Test fun failOpenDoesNotBlock() {
        // An RPC probe error defaults recipientExists to true -> never blocks.
        assertFalse(rentExemptBlocksNativeTransfer(1, recipientExists = true))
    }
}
