// Holder-independent on-chain verification (ADR-0054, item 7). Android port of
// the iOS OnChainVerifier: the holder confirms the three chain-gated checks the
// offline verifier can only mark UNVERIFIED, by talking DIRECTLY to a public EVM
// RPC + the registry contracts. No issuer or verifier server is in the loop.
//
//   * issuerRegistered  -> IdentityRegistry.isActive(string did)
//   * notRevoked        -> RevocationRegistry.isRevoked(string did, bytes32 cid)
//   * rootCurrent       -> RevocationRegistry.isRootRecent(string,bytes32,uint256)
//   * headerSigValid    -> IdentityRegistry.getIssuerPubkey(string) + ML-DSA verify
//   * cscaProvenance    -> CscaRegistry.isValidAt(bytes32 certHash, uint64 ts)
//
// Trust notes (ADR-0054): a malicious RPC could lie (mitigation: user-configurable
// RPC); isRevoked(did, cid) discloses the credential id to the RPC provider (a
// privacy tradeoff; a self-hosted RPC avoids it).

package com.elabify.musnad.present

import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.wallet.ethereum.MultiChainNative
import com.elabify.musnad.wallet.ethereum.EthereumRPCClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wallet.core.jni.Hash

/** Registry addresses + RPC endpoint. Bundled Sepolia defaults; override via the
 *  issuer well-known doc / verifier info / Settings (bundle + discover). */
data class RegistryConfig(
    val rpcURL: String,
    val identityRegistry: String,
    val revocationRegistry: String,
    val cscaRegistry: String? = null,
) {
    companion object {
        /** Committed Sepolia deployment (smart-contracts/deployments/11155111.json).
         *  The RPC is NOT hardcoded: the registries live on Sepolia, so the caller
         *  passes the app's effective Sepolia RPC (EthereumSettings.rpcURL, which
         *  honors the user's per-network override and otherwise falls back to
         *  EthereumNetwork.SEPOLIA.defaultRPCURL). Keeps the on-chain verifier on
         *  the same endpoint the wallet already uses. */
        /** ADR-0072 (2026-07-30): the hardened redeploy replaced both registries.
         *  The retired originals (identity 0x8ca4260A…, revocation 0x56CCaCEf…)
         *  are still deployed and still answer, so a stale build keeps working,
         *  but they are frozen: they no longer receive issuer rotations,
         *  deactivations or new revocations. Reading them would report a stale
         *  issuer as active. Keep these in step with
         *  smart-contracts/deployments/11155111.json; RegistryConfigTest asserts
         *  they match. */
        fun sepolia(rpcURL: String) = RegistryConfig(
            rpcURL = rpcURL,
            identityRegistry = "0xA228871E831F5AB6d220ce953c6A60D4F9a90d88",
            revocationRegistry = "0x2dC725379FCEb2611a646ED0b163c1E8E28E3579",
        )
    }
}

sealed class OnChainTier {
    object Pass : OnChainTier()
    data class Fail(val reason: String) : OnChainTier()
    data class Unknown(val reason: String) : OnChainTier()
}

data class OnChainVerdict(
    val reachedChain: Boolean,
    val issuerRegistered: OnChainTier,
    val notRevoked: OnChainTier,
    val rootCurrent: OnChainTier,
    val headerSigValid: OnChainTier,
    val cscaProvenance: OnChainTier?,
) {
    /** True when the three chain gates + on-chain header signature all pass. */
    val fullyVerified: Boolean
        get() = issuerRegistered is OnChainTier.Pass && notRevoked is OnChainTier.Pass &&
            rootCurrent is OnChainTier.Pass && headerSigValid is OnChainTier.Pass
}

object OnChainVerifier {
    /** Generous root-freshness window for a holder-side sanity check. */
    private const val ROOT_WINDOW_SEC = 90L * 24 * 3600

    /** Reference-pass result: the verdict (headerSigValid left "unknown", since a
     *  badge carries no header to verify) + the on-chain issuer pubkey (so the
     *  caller can bind HAVID via the on-chain key rather than a credential sig). */
    data class ReferenceResult(val verdict: OnChainVerdict, val issuerPubkey: ByteArray?)

    suspend fun verify(
        config: RegistryConfig,
        header: CredentialHeader,
        headerSig: String,
        cscaCertIdHex: String?,
        anchorBatchRoot: String?,
        anchorRPCURL: String? = null,
        anchorRevocationRegistry: String? = null,
        anchorBatchTxHash: String? = null,
    ): OnChainVerdict = withContext(Dispatchers.IO) {
        // Reuse the reference pass, then layer headerSigValid on with the full header.
        val ref = verifyReference(
            config, header.iss, header.cid, header.iat, cscaCertIdHex,
            anchorBatchRoot, anchorRPCURL, anchorRevocationRegistry, anchorBatchTxHash,
        )
        val pk = ref.issuerPubkey
        val headerSigValid: OnChainTier = when {
            pk != null && pk.isNotEmpty() ->
                if (MasterKey.verify(pk, hexToBytes(headerSig), header.canonicalBytes())) OnChainTier.Pass
                else OnChainTier.Fail("Header signature does not verify against the on-chain issuer key")
            ref.verdict.reachedChain -> OnChainTier.Unknown("Issuer key not published on-chain")
            else -> ref.verdict.headerSigValid
        }
        ref.verdict.copy(headerSigValid = headerSigValid)
    }

    /**
     * On-chain checks needing only a credential REFERENCE (did + cid + iat +
     * anchor): issuerRegistered, notRevoked, rootCurrent, cscaProvenance, plus the
     * issuer's on-chain pubkey. Identity checks run on `config` (Sepolia);
     * revocation + root run on the anchor's chain (anchorRPCURL + the anchor's
     * RevocationRegistry). Used directly by the badge flow.
     */
    suspend fun verifyReference(
        config: RegistryConfig,
        did: String,
        cid: String,
        iat: Long,
        cscaCertIdHex: String?,
        anchorBatchRoot: String?,
        anchorRPCURL: String? = null,
        anchorRevocationRegistry: String? = null,
        anchorBatchTxHash: String? = null,
    ): ReferenceResult = withContext(Dispatchers.IO) {
        MultiChainNative.ensure() // keccak256 (selector) needs the native lib
        val rpc = EthereumRPCClient.orNull(config.rpcURL)
            ?: return@withContext ReferenceResult(unreachable("No RPC configured"), null)
        val anchorRpc = anchorRPCURL?.let { EthereumRPCClient.orNull(it) }
        val revRegistry = anchorRevocationRegistry ?: config.revocationRegistry
        var reached = false

        var issuerRegistered: OnChainTier = OnChainTier.Unknown("RPC unreachable")
        runCatching {
            val data = selector("isActive(string)") + word(0x20L) + stringTail(did)
            decodeBool(rpc.ethCall(config.identityRegistry, data))
        }.getOrNull()?.let {
            reached = true
            issuerRegistered = if (it) OnChainTier.Pass
            else OnChainTier.Fail("Issuer is not registered / not active on-chain")
        }

        var notRevoked: OnChainTier = OnChainTier.Unknown("No reachable anchor chain")
        var rootCurrent: OnChainTier =
            OnChainTier.Unknown("Carries no on-chain anchor for a supported network")
        if (anchorRpc != null) {
            runCatching {
                val data = selector("isRevoked(string,bytes32)") + word(0x40L) +
                    bytes32(cid) + stringTail(did)
                decodeBool(anchorRpc.ethCall(revRegistry, data))
            }.getOrNull()?.let {
                reached = true
                notRevoked = if (it) OnChainTier.Fail("Credential has been revoked on-chain") else OnChainTier.Pass
            } ?: run { notRevoked = OnChainTier.Unknown("Could not read the revocation registry") }

            if (!anchorBatchRoot.isNullOrEmpty() && !anchorBatchTxHash.isNullOrEmpty()) {
                // ADR-0022 amendment: a v2 batch root is valid if it was genuinely
                // anchored by the issuer (not merely "recent"). Confirm the anchor
                // tx emitted RevocationRegistry RootUpdated(did, root) from the
                // expected registry. Matches the server's wasRootAnchored.
                runCatching { anchorRpc.getTransactionReceipt(anchorBatchTxHash) }
                    .getOrNull()?.let { receipt ->
                        reached = true
                        rootCurrent = if (rootWasAnchored(receipt, revRegistry, did, anchorBatchRoot)) OnChainTier.Pass
                        else OnChainTier.Fail("Credential anchor root was not published in the anchor transaction on-chain")
                    } ?: run { rootCurrent = OnChainTier.Unknown("Could not read the anchor transaction on-chain") }
            }
        }

        var issuerPubkey: ByteArray? = null
        runCatching {
            val data = selector("getIssuerPubkey(string)") + word(0x20L) + stringTail(did)
            decodeFirstBytes(rpc.ethCall(config.identityRegistry, data))
        }.getOrNull()?.let { pubkey ->
            reached = true
            issuerPubkey = pubkey
        }

        var cscaProvenance: OnChainTier? = null
        val cscaRegistry = config.cscaRegistry
        if (cscaCertIdHex != null && cscaRegistry != null) {
            runCatching {
                val ts = maxOf(0L, iat)
                val data = selector("isValidAt(bytes32,uint64)") + bytes32(cscaCertIdHex) + word(ts)
                decodeBool(rpc.ethCall(cscaRegistry, data))
            }.getOrNull()?.let {
                reached = true
                cscaProvenance = if (it) OnChainTier.Pass
                else OnChainTier.Fail("Passport CSCA certificate was not anchored/valid at issuance")
            } ?: run { cscaProvenance = OnChainTier.Unknown("Could not read CSCA registry") }
        }

        ReferenceResult(
            OnChainVerdict(
                reached, issuerRegistered, notRevoked, rootCurrent,
                OnChainTier.Unknown("Needs the full credential (a badge is a reference)"),
                cscaProvenance,
            ),
            issuerPubkey,
        )
    }

    private fun unreachable(why: String) = OnChainVerdict(
        reachedChain = false,
        issuerRegistered = OnChainTier.Unknown(why),
        notRevoked = OnChainTier.Unknown(why),
        rootCurrent = OnChainTier.Unknown(why),
        headerSigValid = OnChainTier.Unknown(why),
        cscaProvenance = null,
    )

    // ---- minimal ABI encode/decode (selectors from the exact Solidity sig) ----

    private fun selector(signature: String): ByteArray =
        Hash.keccak256(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    /** Full keccak256 of a UTF-8 string as 0x-hex (event-signature topic0 + an
     *  indexed dynamic-string topic, which is keccak of the value bytes). */
    private fun keccakHex(s: String): String =
        "0x" + Hash.keccak256(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Normalize a hex value to a 32-byte (0x + 64 lowercase) topic form. */
    private fun topic32(hex: String): String {
        var h = hex.removePrefix("0x").removePrefix("0X").lowercase()
        if (h.length > 64) h = h.takeLast(64)
        if (h.length < 64) h = "0".repeat(64 - h.length) + h
        return "0x$h"
    }

    /** True iff `receipt` has a RevocationRegistry RootUpdated log from `registry`
     *  binding this issuer `did` + `batchRoot`. Topics only: topic0 = keccak256 of
     *  the event signature, topic1 = keccak256(utf8(did)) (indexed string),
     *  topic2 = the indexed bytes32 root. */
    private fun rootWasAnchored(receipt: JSONObject, registry: String, did: String, batchRoot: String): Boolean {
        val topic0 = keccakHex("RootUpdated(string,string,bytes32,uint256,uint256)").lowercase()
        val topic1 = keccakHex(did).lowercase()
        val wantRoot = topic32(batchRoot)
        val reg = registry.lowercase()
        val logs = receipt.optJSONArray("logs") ?: return false
        for (i in 0 until logs.length()) {
            val log = logs.optJSONObject(i) ?: continue
            if (log.optString("address").lowercase() != reg) continue
            val topics = log.optJSONArray("topics") ?: continue
            if (topics.length() < 3) continue
            if (topics.getString(0).lowercase() == topic0 &&
                topics.getString(1).lowercase() == topic1 &&
                topic32(topics.getString(2)) == wantRoot
            ) {
                return true
            }
        }
        return false
    }

    /** 32-byte big-endian word for a uint / offset. */
    private fun word(value: Long): ByteArray {
        val out = ByteArray(32)
        var v = value
        for (i in 0 until 8) { out[31 - i] = (v and 0xFF).toByte(); v = v ushr 8 }
        return out
    }

    /** bytes32 from hex: exactly 32 bytes, right-padded if short. */
    private fun bytes32(hex: String): ByteArray {
        val d = hexToBytes(hex)
        return when {
            d.size == 32 -> d
            d.size > 32 -> d.copyOfRange(0, 32)
            else -> d + ByteArray(32 - d.size)
        }
    }

    /** Dynamic string tail: length word + UTF-8 bytes + zero pad to 32. */
    private fun stringTail(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val pad = (32 - bytes.size % 32) % 32
        return word(bytes.size.toLong()) + bytes + ByteArray(pad)
    }

    private fun decodeBool(hexResult: String): Boolean? {
        val d = hexToBytes(hexResult)
        if (d.size < 32) return null
        return d.copyOfRange(d.size - 32, d.size).any { it.toInt() != 0 }
    }

    /** First `bytes` of a (bytes,bytes) return (the ML-DSA pubkey). */
    private fun decodeFirstBytes(hexResult: String): ByteArray? {
        val d = hexToBytes(hexResult)
        if (d.size < 32) return null
        val off = beInt(d, 0)
        if (d.size < off + 32) return null
        val len = beInt(d, off)
        val start = off + 32
        if (d.size < start + len) return null
        return d.copyOfRange(start, start + len)
    }

    /** Read the low 4 bytes of the 32-byte word at [wordOffset] as an Int. */
    private fun beInt(d: ByteArray, wordOffset: Int): Int {
        var v = 0L
        for (i in (wordOffset + 24) until (wordOffset + 32)) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return v.toInt()
    }

    private fun hexToBytes(hex: String): ByteArray {
        var s = if (hex.startsWith("0x") || hex.startsWith("0X")) hex.substring(2) else hex
        if (s.length % 2 != 0) s = "0$s"
        return ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }
    }
}
