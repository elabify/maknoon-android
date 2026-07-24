package com.elabify.app.maknoon.ui.wallet.ethereum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the token-send safety guard: a token send whose recipient is a contract
 * (its own contract, another installed token's contract, or any address with
 * bytecode) must be blocked. Native ETH sends to a contract are allowed.
 */
class EthereumSendGuardTest {
    private val token = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"       // a token contract
    private val otherToken = "0xff970a61a04b1ca14834a43f5de4533ebddb5cc8"  // a different token contract
    private val eoa = "0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f"

    @Test fun blocksSendingTokenToItsOwnContract() {
        assertTrue(tokenSendBlocksContract(token, true, token, listOf(token), false))
    }

    @Test fun blocksSendingToAnotherKnownTokenContract() {
        assertTrue(tokenSendBlocksContract(otherToken, true, token, listOf(token, otherToken), false))
    }

    @Test fun blocksAnyContractViaGetCodeEvenIfUnknownToken() {
        assertTrue(tokenSendBlocksContract(otherToken, true, token, listOf(token), true))
    }

    @Test fun allowsSendingTokenToAnEoa() {
        assertFalse(tokenSendBlocksContract(eoa, true, token, listOf(token), false))
    }

    @Test fun allowsNativeEthToAContract() {
        assertFalse(tokenSendBlocksContract(token, false, null, listOf(token), true))
    }

    @Test fun matchIsCaseInsensitive() {
        assertTrue(tokenSendBlocksContract(token.uppercase(), true, token, emptyList(), false))
    }
}
