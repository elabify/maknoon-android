package com.elabify.musnad.wallet.ethereum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the blind-sign guard: an ERC-20 transfer/transferFrom whose token
 * recipient is the call target (the token contract itself) must be flagged so a
 * blind-signed eth_sendTransaction (mini-app / WalletConnect) is refused rather
 * than sending tokens to the contract. Mirrors iOS EthereumCallDataGuardTests.
 */
class EthereumCallDataDecoderTest {
    private val contract = "0xaf88d065e77c8cc2239327c5edb3a432268e5831"
    private val eoa = "0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f"

    private fun sel(vararg b: Int) = b.map { it.toByte() }.toByteArray()
    private fun addr32(a: String): ByteArray {
        val hex = a.removePrefix("0x")
        val b = ByteArray(20) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return ByteArray(12) + b
    }
    private fun word(v: Long): ByteArray {
        val b = ByteArray(32); var x = v; var i = 31
        while (x > 0) { b[i] = (x and 0xff).toByte(); x = x shr 8; i-- }
        return b
    }
    private fun transfer(to: String, amt: Long) = sel(0xa9, 0x05, 0x9c, 0xbb) + addr32(to) + word(amt)
    private fun transferFrom(from: String, to: String, amt: Long) = sel(0x23, 0xb8, 0x72, 0xdd) + addr32(from) + addr32(to) + word(amt)
    private fun approve(sp: String, amt: Long) = sel(0x09, 0x5e, 0xa7, 0xb3) + addr32(sp) + word(amt)

    @Test fun transferToCalleeIsBlocked() =
        assertTrue(EthereumCallDataDecoder.transferTargetsCallee(contract, transfer(contract, 100)))

    @Test fun transferToEoaIsAllowed() =
        assertFalse(EthereumCallDataDecoder.transferTargetsCallee(contract, transfer(eoa, 100)))

    @Test fun transferFromToCalleeIsBlocked() =
        assertTrue(EthereumCallDataDecoder.transferTargetsCallee(contract, transferFrom(eoa, contract, 100)))

    @Test fun approveToSelfIsNotBlocked() =
        assertFalse(EthereumCallDataDecoder.transferTargetsCallee(contract, approve(contract, 100)))

    @Test fun emptyOrShortDataIsNotBlocked() {
        assertFalse(EthereumCallDataDecoder.transferTargetsCallee(contract, ByteArray(0)))
        assertFalse(EthereumCallDataDecoder.transferTargetsCallee(contract, sel(0xa9, 0x05, 0x9c, 0xbb)))
    }
}
