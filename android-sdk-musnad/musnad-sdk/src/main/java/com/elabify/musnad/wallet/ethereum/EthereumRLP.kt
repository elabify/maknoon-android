// Minimal RLP (Recursive Length Prefix) encoder. 1:1 port of
// EthereumRLP.swift. Used to materialise the EIP-1559 unsigned + signed
// transaction envelope (0x02 || rlp(payload[ || v || r || s])).
//
// On Android the WalletCore 0.12.8 Maven binding ships only the LEGACY flat
// Ethereum proto (no EIP-1559 fields, no txMode), so unlike iOS we cannot
// delegate EIP-1559 encoding to AnySigner. We therefore RLP-encode and sign the
// keccak256 digest ourselves (see EthereumDescriptors.signTransaction).
//
// Reference: https://ethereum.org/en/developers/docs/data-structures-and-encoding/rlp/

package com.elabify.musnad.wallet.ethereum

import java.math.BigInteger

object EthereumRLP {

    /** A single RLP item: either raw bytes or a list. */
    sealed interface Item {
        data class Bytes(val data: ByteArray) : Item
        data class RLPList(val items: List<Item>) : Item

        companion object {
            /** Integer as its minimal big-endian representation. Zero -> empty bytes. */
            fun uint(v: Long): Item {
                if (v == 0L) return Bytes(ByteArray(0))
                val bytes = ArrayList<Byte>()
                var x = v
                while (x > 0) {
                    bytes.add((x and 0xff).toByte())
                    x = x ushr 8
                }
                return Bytes(bytes.reversed().toByteArray())
            }

            /** Big-endian wei value, leading zeros stripped. Zero -> empty bytes. */
            fun wei(v: EthereumWeiValue): Item {
                val raw = v.bigEndianBytes
                if (raw.size == 1 && raw[0].toInt() == 0) return Bytes(ByteArray(0))
                return Bytes(raw)
            }

            /** Hex address (0x... or raw) -> 20 fixed bytes. Empty -> empty bytes. */
            fun address(hex: String): Item {
                var s = hex
                if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
                if (s.isEmpty()) return Bytes(ByteArray(0))
                val bytes = ByteArray(s.length / 2)
                var i = 0
                while (i < s.length - 1) {
                    bytes[i / 2] = (s.substring(i, i + 2).toIntOrNull(16) ?: 0).toByte()
                    i += 2
                }
                return Bytes(bytes)
            }
        }
    }

    fun encode(item: Item): ByteArray = when (item) {
        is Item.Bytes -> encodeBytes(item.data)
        is Item.RLPList -> {
            var inner = ByteArray(0)
            for (child in item.items) inner += encode(child)
            encodeListPrefix(inner.size) + inner
        }
    }

    private fun encodeBytes(data: ByteArray): ByteArray {
        if (data.size == 1 && (data[0].toInt() and 0xff) < 0x80) return data
        return encodeStringPrefix(data.size) + data
    }

    private fun encodeStringPrefix(payloadLen: Int): ByteArray {
        if (payloadLen < 56) return byteArrayOf((0x80 + payloadLen).toByte())
        val lenBytes = bigEndianLength(payloadLen)
        return byteArrayOf((0xB7 + lenBytes.size).toByte()) + lenBytes
    }

    private fun encodeListPrefix(payloadLen: Int): ByteArray {
        if (payloadLen < 56) return byteArrayOf((0xC0 + payloadLen).toByte())
        val lenBytes = bigEndianLength(payloadLen)
        return byteArrayOf((0xF7 + lenBytes.size).toByte()) + lenBytes
    }

    private fun bigEndianLength(n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val bytes = BigInteger.valueOf(n.toLong()).toByteArray()
        var start = 0
        while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
        return bytes.copyOfRange(start, bytes.size)
    }
}
