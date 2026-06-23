// TRC-20 token transfer helpers, ported from iOS
// TronTRC20TransferBuilder.swift. Two jobs:
//
//   - `balance(...)`: read a holder's TRC-20 balance via
//     `balanceOf(address)` through triggerConstantContract, returning
//     the raw on-chain integer as a base-10 string,
//   - the ABI address-parameter encoder shared with the balance probe.
//
// The signing path itself lives in TronWallet/TronDescriptors: unlike
// iOS (which uses WalletCore's TronTransferTRC20Contract + output.json),
// the Android WalletCore binding ships no TRC-20 contract message and no
// SigningOutput JSON, so the TRC-20 send goes through
// `/wallet/triggersmartcontract` (server builds the canonical tx) + a
// local secp256k1 signature over SHA256(raw_data). See TronDescriptors.

package com.elabify.musnad.wallet.tron

import com.elabify.musnad.crypto.toHex
import java.math.BigInteger

object TronTRC20TransferBuilder {

    /** Big-endian bytes for an arbitrary-precision decimal string.
     *  Returns null if the value won't fit in [maxBytes] (32 for
     *  TRC-20's uint256) or isn't a non-negative integer. Mirror of iOS
     *  `bigEndianBytes(decimalString:maxBytes:)`. */
    fun bigEndianBytes(decimalString: String, maxBytes: Int): ByteArray? {
        val trimmed = decimalString.trim()
        if (trimmed.isEmpty() || !trimmed.all { it.isDigit() }) return null
        val value = try { BigInteger(trimmed) } catch (e: Exception) { return null }
        if (value.signum() < 0) return null
        if (value.signum() == 0) return byteArrayOf(0)
        var bytes = value.toByteArray()
        // strip a possible leading sign byte
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        if (bytes.size > maxBytes) return null
        return bytes
    }

    /** ABI-encode a Tron address as a 32-byte zero-padded EVM-style
     *  address: the 20-byte hash, left-padded to 32 bytes (the 0x41
     *  network byte dropped). Returns null on malformed input. Mirror of
     *  iOS `encodeAddressParameter(base58:)`. */
    fun encodeAddressParameter(base58: String): String? {
        val raw = TronAddressCodec.base58CheckDecode(base58) ?: return null
        if (raw.size != 21 || raw[0] != 0x41.toByte()) return null
        val twenty = raw.copyOfRange(1, 21)
        val padded = ByteArray(32 - twenty.size) + twenty
        return padded.toHex()
    }

    /** Read a holder's TRC-20 balance via `balanceOf(address)`, returning
     *  the raw on-chain integer as a base-10 string (the shape
     *  `TronTRC20Token.format(rawAmountDecimal)` expects). Returns "0" on
     *  any RPC / decode failure so callers can render a balance line
     *  without special-casing errors. Mirror of iOS `balance(...)`. */
    fun balance(
        holderBase58: String,
        contractBase58: String,
        rpcURL: String,
    ): String {
        val rpc = TronRPCClient(rpcURL)
        val parameter = encodeAddressParameter(holderBase58) ?: return "0"
        val hex = try {
            rpc.triggerConstantContract(
                ownerAddressBase58 = holderBase58,
                contractAddressBase58 = contractBase58,
                functionSelector = "balanceOf(address)",
                parameterHex = parameter,
            )
        } catch (e: Exception) {
            return "0"
        }
        val cleaned = hex.removePrefix("0x").lowercase()
        if (cleaned.isEmpty()) return "0"
        return try {
            BigInteger(cleaned, 16).toString()
        } catch (e: Exception) {
            "0"
        }
    }
}
