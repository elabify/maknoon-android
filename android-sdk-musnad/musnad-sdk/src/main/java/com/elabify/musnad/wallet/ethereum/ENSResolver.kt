// ENS (Ethereum Name Service) -> 0x-address resolver. 1:1 port of
// ENSResolver.swift. ENS lives on Ethereum mainnet (chain id 1), so resolution
// always runs against the mainnet RPC even when sending on an L2.
//
// L1 resolution path: ENS Registry -> Resolver -> addr(node). No ENSIP-10
// wildcard / CCIP-read in this cut. Includes the in-place EIP-55 checksum
// helper (EIP55) that the iOS file defines and EthereumSettings reuses.

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import wallet.core.jni.Hash

class ENSException(val kind: Kind, val detail: String? = null) : Exception() {
    enum class Kind { MALFORMED_NAME, NO_RESOLVER, NO_ADDRESS, RPC_DOWN, BAD_RESPONSE, CONFIG_MISSING }

    override val message: String
        get() = when (kind) {
            Kind.MALFORMED_NAME -> "That doesn't look like a valid ENS name. Use the form name.eth (or any ENS-supported TLD)."
            Kind.NO_RESOLVER -> "ENS Registry has no resolver set for that name. The owner needs to set a public resolver before it can be used."
            Kind.NO_ADDRESS -> "That ENS name has no Ethereum address record. The owner needs to set the addr() record on the resolver."
            Kind.RPC_DOWN -> "Couldn't reach the ENS gateway: ${detail ?: ""}. Check Settings, Networks, Ethereum, ENS gateway."
            Kind.BAD_RESPONSE -> "Unexpected ENS response: ${detail ?: ""}"
            Kind.CONFIG_MISSING -> "No ENS gateway configured. Set one in Settings, Networks, Ethereum, ENS gateway."
        }
}

class ENSResolver(rpcURLString: String) {

    private val rpcURL: String = rpcURLString

    init { require(rpcURLString.isNotBlank()) { "ENS RPC URL is blank" } }

    fun resolve(name: String): String {
        // ENS namehash + EIP-55 checksum use WalletCore's keccak256 (JNI); make
        // sure the native lib is loaded or Hash.keccak256 has no implementation.
        MultiChainNative.ensure()
        val normalized = name.trim().lowercase()
        if (!looksLikeName(normalized)) throw ENSException(ENSException.Kind.MALFORMED_NAME)
        val node = namehash(normalized)
        val rpc = EthereumRPCClient.orNull(rpcURL) ?: throw ENSException(ENSException.Kind.CONFIG_MISSING)

        // resolver(bytes32 node) - selector 0x0178b8bf
        val resolverCallData = callData("0178b8bf", node.toHex())
        val resolverResp = try {
            rpc.ethCall(REGISTRY_ADDRESS, resolverCallData)
        } catch (e: Exception) {
            throw ENSException(ENSException.Kind.RPC_DOWN, e.message)
        }
        val resolverAddr = addressFromCallResult(resolverResp)
            ?: throw ENSException(ENSException.Kind.BAD_RESPONSE, resolverResp)
        if (resolverAddr == "0x0000000000000000000000000000000000000000") {
            throw ENSException(ENSException.Kind.NO_RESOLVER)
        }

        // addr(bytes32 node) - selector 0x3b3b57de
        val addrCallData = callData("3b3b57de", node.toHex())
        val addrResp = try {
            rpc.ethCall(resolverAddr, addrCallData)
        } catch (e: Exception) {
            throw ENSException(ENSException.Kind.RPC_DOWN, e.message)
        }
        val address = addressFromCallResult(addrResp)
            ?: throw ENSException(ENSException.Kind.BAD_RESPONSE, addrResp)
        if (address == "0x0000000000000000000000000000000000000000") {
            throw ENSException(ENSException.Kind.NO_ADDRESS)
        }
        return EIP55.checksum(address)
    }

    companion object {
        /** Canonical ENS Registry on Ethereum mainnet. */
        const val REGISTRY_ADDRESS = "0x00000000000C2E074eC69A0dFb2997BA6C7d2e1e"

        /** Heuristic: looks like a name we should try ENS for? */
        fun looksLikeName(s: String): Boolean {
            val trimmed = s.trim().lowercase()
            if (trimmed.isEmpty()) return false
            if (trimmed.startsWith("0x")) return false
            if (trimmed.startsWith("http") || trimmed.contains("/") || trimmed.contains(":")) return false
            if (!trimmed.contains(".") || trimmed.contains(" ")) return false
            val labels = trimmed.split(".")
            return labels.size >= 2 && (labels.lastOrNull()?.length ?: 0) >= 2
        }

        /** ENS namehash per EIP-137. */
        fun namehash(name: String): ByteArray {
            var node = ByteArray(32)
            if (name.isEmpty()) return node
            val labels = name.split(".")
            for (label in labels.reversed()) {
                val labelHash = Hash.keccak256(label.toByteArray(Charsets.UTF_8))
                node = Hash.keccak256(node + labelHash)
            }
            return node
        }

        private fun callData(selectorHex: String, argHex32: String): ByteArray {
            var hex = selectorHex
            val arg = argHex32.replace("0x", "")
            hex += if (arg.length < 64) "0".repeat(64 - arg.length) + arg else arg.takeLast(64)
            return runCatching { hexToBytes(hex) }.getOrDefault(ByteArray(0))
        }

        private fun addressFromCallResult(hex: String): String? {
            val s = if (hex.startsWith("0x")) hex.substring(2) else hex
            if (s.length < 64) return null
            return "0x" + s.takeLast(40)
        }
    }
}

/** EIP-55 address checksum. Mirrors the iOS EIP55 enum. */
object EIP55 {
    fun checksum(address: String): String {
        // keccak256 is a WalletCore JNI call; ensure the native lib is loaded.
        MultiChainNative.ensure()
        val s = if (address.startsWith("0x")) address.substring(2) else address
        val lower = s.lowercase()
        val hashHex = Hash.keccak256(lower.toByteArray(Charsets.UTF_8)).toHex()
        val out = StringBuilder("0x")
        for (i in lower.indices) {
            val ch = lower[i]
            val n = Character.digit(hashHex[i], 16)
            if (ch.isLetter() && n >= 8) out.append(ch.uppercaseChar()) else out.append(ch)
        }
        return out.toString()
    }

    /**
     * EIP-55 validation of a user-supplied address. All-lowercase or
     * all-uppercase carries no checksum and is accepted (cannot be validated).
     * A MIXED-case address must match the checksum exactly, so a mistyped
     * (wrong-case) character is caught instead of being sent to a different,
     * valid-looking address. Non-hex / wrong-length input returns false.
     * Mirrors iOS EIP55.passesChecksum.
     */
    fun passesChecksum(address: String): Boolean {
        val body = if (address.startsWith("0x") || address.startsWith("0X")) address.substring(2) else address
        if (body.length != 40 || !body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return false
        val letters = body.filter { it.isLetter() }
        val allLower = letters.all { it.isLowerCase() }
        val allUpper = letters.all { it.isUpperCase() }
        if (allLower || allUpper) return true
        return checksum("0x$body") == "0x$body"
    }
}
