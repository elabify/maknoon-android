// Minimal ABI encoders/decoders for the ERC-20 reads + transfer we support:
//   balanceOf(address) -> uint256
//   symbol() / name()  -> string
//   decimals()         -> uint8
//   transfer(address, uint256) -> bool
//
// The iOS counterpart (EthereumABI.swift) builds these via Trust Wallet Core's
// `EthereumAbiFunction`. That class is NOT present in the WalletCore 0.12.8
// Maven AAR, so we hand-encode: 4-byte keccak256 selector || 32-byte padded
// args. The wire output is byte-identical to TWC's encoder. Response parsing
// mirrors EthereumABI.parse* exactly, including the legacy bytes32-symbol
// fallback for MKR/OMG-style tokens.

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import wallet.core.jni.Hash

object EthereumABI {

    /** call data for balanceOf(address). */
    fun balanceOfData(holderAddress: String): ByteArray? {
        val holder = address20(holderAddress) ?: return null
        return selector("balanceOf(address)") + pad32Left(holder)
    }

    /** call data for symbol(). */
    fun symbolData(): ByteArray = selector("symbol()")

    /** call data for decimals(). */
    fun decimalsData(): ByteArray = selector("decimals()")

    /** call data for name(). */
    fun nameData(): ByteArray = selector("name()")

    /** call data for transfer(address,uint256). */
    fun transferData(recipient: String, amount: EthereumWeiValue): ByteArray? {
        val r = address20(recipient) ?: return null
        return selector("transfer(address,uint256)") + pad32Left(r) + pad32Left(amount.bigEndianBytes)
    }

    /** Parse a 32-byte big-endian uint256 (balanceOf result). */
    fun parseUint256(hex: String): EthereumWeiValue? {
        var s = hex
        if (s.startsWith("0x")) s = s.substring(2)
        if (s.isEmpty() || s.all { it == '0' }) return EthereumWeiValue.ZERO
        return try { EthereumWeiValue.fromHex(s) } catch (_: Exception) { null }
    }

    /**
     * Parse the dynamic-string return of symbol()/name(). ABI dynamic strings
     * are: 32-byte offset + 32-byte length + N bytes string + zero pad. Falls
     * back to bytes32 decode for legacy tokens that declare symbol as bytes32.
     */
    fun parseSymbol(hex: String): String? {
        var s = hex
        if (s.startsWith("0x")) s = s.substring(2)
        val bytes = hexBytes(s) ?: return null
        if (bytes.size >= 64) {
            var len = 0L
            for (i in 32 until 64) len = (len shl 8) or (bytes[i].toLong() and 0xff)
            if (len in 0..256 && bytes.size >= 64 + len) {
                val strBytes = bytes.copyOfRange(64, 64 + len.toInt())
                return String(strBytes, Charsets.UTF_8)
            }
            return parseBytes32String(bytes)
        }
        return parseBytes32String(bytes)
    }

    private fun parseBytes32String(bytes: ByteArray): String? {
        val first32 = bytes.copyOfRange(0, minOf(32, bytes.size))
        var end = first32.size
        while (end > 0 && first32[end - 1].toInt() == 0) end--
        return String(first32.copyOfRange(0, end), Charsets.UTF_8)
    }

    /** Parse the uint8 return of decimals(): 32-byte big-endian, last byte. */
    fun parseDecimals(hex: String): Int? {
        var s = hex
        if (s.startsWith("0x")) s = s.substring(2)
        val bytes = hexBytes(s) ?: return null
        val last = bytes.lastOrNull() ?: return null
        return last.toInt() and 0xff
    }

    // ---- helpers ----

    /** 4-byte function selector = first 4 bytes of keccak256(signature). */
    private fun selector(signature: String): ByteArray =
        Hash.keccak256(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    /** Left-pad bytes to a 32-byte ABI word. */
    private fun pad32Left(value: ByteArray): ByteArray {
        require(value.size <= 32) { "ABI word overflow" }
        val out = ByteArray(32)
        System.arraycopy(value, 0, out, 32 - value.size, value.size)
        return out
    }

    private fun address20(addr: String): ByteArray? {
        var s = addr
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
        if (s.length != 40) return null
        return try { hexToBytes(s) } catch (_: Exception) { null }
    }

    private fun hexBytes(s: String): ByteArray? {
        val hex = if (s.length % 2 == 1) "0$s" else s
        return try { hexToBytes(hex) } catch (_: Exception) { null }
    }

    /** 0x-prefixed lowercase hex of calldata, for eth_call / eth_estimateGas. */
    fun toHexData(data: ByteArray): String = "0x" + data.toHex()
}
