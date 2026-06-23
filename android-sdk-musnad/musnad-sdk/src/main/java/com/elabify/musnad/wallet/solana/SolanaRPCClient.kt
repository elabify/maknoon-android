// Thin Solana JSON-RPC client, ported 1:1 from iOS SolanaRPCClient.swift.
// Surface:
//   - getBalance              (lamports for an address)
//   - getLatestBlockhash      (needed by the tx builder)
//   - sendTransaction         (broadcast a base64 signed tx)
//   - getSignatureStatuses    (poll a freshly-broadcast signature)
//   - getSignaturesForAddress (cheap pagination of recent activity)
//   - getTransactionDelta     (per-signature SOL delta for the tx-list)
//   - getTokenAccountsByOwner (SPL holdings)
//   - getTokenAccountBalance  (single token-account balance)
//   - accountExists           (ATA existence probe)
//   - getParsedMint           (decimals/supply for add-token)
//
// No third-party library: we hand-roll the JSON envelopes via org.json.
// Networking goes through MaknoonHttp (OkHttp) to match the rest of the
// SDK. One client per (network, endpoint URL); cheap to recreate.

package com.elabify.musnad.wallet.solana

import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import org.json.JSONArray
import org.json.JSONObject

class SolanaRPCException(message: String) : Exception(message)

class SolanaRPCClient(
    val endpoint: String,
    private val http: MaknoonHttp = MaknoonHttp(),
) {

    // MARK: -- read

    /** Returns lamports. 1 SOL = 10^9 lamports. */
    fun getBalance(address: String): Long {
        val value = callValue("getBalance", JSONArray().put(address))
        return (value as Number).toLong()
    }

    data class LatestBlockhash(val blockhash: String, val lastValidBlockHeight: Long)

    /** Most recent blockhash (base58) plus the slot it's valid until.
     *  Transactions older than ~150 slots get rejected. */
    fun getLatestBlockhash(): LatestBlockhash {
        val value = callValue("getLatestBlockhash", JSONArray()) as JSONObject
        return LatestBlockhash(
            blockhash = value.getString("blockhash"),
            lastValidBlockHeight = value.getLong("lastValidBlockHeight"),
        )
    }

    /** Broadcast a signed transaction (`signedBase64` from the descriptor
     *  signer). Returns the base58 signature (the canonical tx id). */
    fun sendTransaction(signedBase64: String): String {
        val opts = JSONObject()
            .put("encoding", "base64")
            .put("skipPreflight", false)
            .put("preflightCommitment", "processed")
        val params = JSONArray().put(signedBase64).put(opts)
        val result = callRaw("sendTransaction", params)
        return result as String
    }

    data class SignatureStatus(
        val slot: Long,
        val confirmations: Long?,
        val confirmationStatus: String?,
        val err: Any?,
    )

    fun getSignatureStatuses(signatures: List<String>): List<SignatureStatus?> {
        val arr = JSONArray().apply { signatures.forEach { put(it) } }
        val value = callValue("getSignatureStatuses", JSONArray().put(arr)) as JSONArray
        val out = ArrayList<SignatureStatus?>(value.length())
        for (i in 0 until value.length()) {
            if (value.isNull(i)) { out.add(null); continue }
            val o = value.getJSONObject(i)
            out.add(
                SignatureStatus(
                    slot = o.optLong("slot"),
                    confirmations = if (o.isNull("confirmations")) null else o.optLong("confirmations"),
                    confirmationStatus = if (o.isNull("confirmationStatus")) null else o.optString("confirmationStatus"),
                    err = if (o.isNull("err")) null else o.get("err"),
                )
            )
        }
        return out
    }

    data class SignatureRecord(
        val signature: String,
        val slot: Long,
        val blockTime: Long?,
        val memo: String?,
        val err: Any?,
        val confirmationStatus: String?,
    )

    fun getSignaturesForAddress(
        address: String,
        before: String? = null,
        until: String? = null,
        limit: Int = 20,
    ): List<SignatureRecord> {
        val options = JSONObject().put("limit", limit)
        if (before != null) options.put("before", before)
        if (until != null) options.put("until", until)
        val params = JSONArray().put(address).put(options)
        val arr = callRaw("getSignaturesForAddress", params) as JSONArray
        val out = ArrayList<SignatureRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                SignatureRecord(
                    signature = o.getString("signature"),
                    slot = o.optLong("slot"),
                    blockTime = if (o.isNull("blockTime")) null else o.optLong("blockTime"),
                    memo = if (o.isNull("memo")) null else o.optString("memo"),
                    err = if (o.isNull("err")) null else o.get("err"),
                    confirmationStatus = if (o.isNull("confirmationStatus")) null else o.optString("confirmationStatus"),
                )
            )
        }
        return out
    }

    /** SOL delta for an owner inside a single transaction. Negative =
     *  sent, positive = received. Includes the fee on the outgoing leg. */
    data class TransactionDelta(
        val lamports: Long,
        val feeLamports: Long,
        val isError: Boolean,
    )

    /** Fetch a single transaction's SOL delta for `ownerAddress`.
     *  Returns null when the transaction is missing or hasn't propagated
     *  yet so the row can fall back to "—". */
    fun getTransactionDelta(signature: String, ownerAddress: String): TransactionDelta? {
        val cfg = JSONObject()
            .put("encoding", "json")
            .put("maxSupportedTransactionVersion", 0)
            .put("commitment", "confirmed")
        val root = try {
            callRaw("getTransaction", JSONArray().put(signature).put(cfg))
        } catch (e: SolanaRPCException) {
            return null
        }
        if (root == null || root == JSONObject.NULL || root !is JSONObject) return null
        val meta = root.optJSONObject("meta") ?: return null
        val accountKeys = root
            .optJSONObject("transaction")?.optJSONObject("message")?.optJSONArray("accountKeys")
            ?: return null
        var idx = -1
        for (i in 0 until accountKeys.length()) {
            if (accountKeys.getString(i) == ownerAddress) { idx = i; break }
        }
        if (idx < 0) return null
        val pre = meta.optJSONArray("preBalances") ?: return null
        val post = meta.optJSONArray("postBalances") ?: return null
        if (idx >= pre.length() || idx >= post.length()) return null
        val delta = post.getLong(idx) - pre.getLong(idx)
        val errVal = if (meta.isNull("err")) null else meta.opt("err")
        val isError = errVal != null && errVal != JSONObject.NULL
        return TransactionDelta(
            lamports = delta,
            feeLamports = meta.optLong("fee"),
            isError = isError,
        )
    }

    // MARK: -- SPL token methods

    data class TokenAccount(
        val mint: String,
        val amount: Long,
        val decimals: Int,
        val tokenAccountPubkey: String,
    )

    /** Walk every SPL token account the wallet owns. Filtered to the
     *  standard SPL Token Program; Token-2022 is excluded for v1. */
    fun getTokenAccountsByOwner(ownerAddress: String): List<TokenAccount> {
        val programId = TOKEN_PROGRAM_ID
        val filter = JSONObject().put("programId", programId)
        val cfg = JSONObject().put("encoding", "jsonParsed")
        val params = JSONArray().put(ownerAddress).put(filter).put(cfg)
        val value = callValue("getTokenAccountsByOwner", params) as JSONArray
        val out = ArrayList<TokenAccount>(value.length())
        for (i in 0 until value.length()) {
            val p = value.getJSONObject(i)
            val info = p.getJSONObject("account").getJSONObject("data")
                .getJSONObject("parsed").getJSONObject("info")
            val tokenAmount = info.getJSONObject("tokenAmount")
            val amount = tokenAmount.getString("amount").toLongOrNull() ?: continue
            out.add(
                TokenAccount(
                    mint = info.getString("mint"),
                    amount = amount,
                    decimals = tokenAmount.getInt("decimals"),
                    tokenAccountPubkey = p.getString("pubkey"),
                )
            )
        }
        return out
    }

    /** Balance of a specific SPL token account. */
    fun getTokenAccountBalance(tokenAccountPubkey: String): Pair<Long, Int> {
        val value = callValue("getTokenAccountBalance", JSONArray().put(tokenAccountPubkey)) as JSONObject
        val amount = value.getString("amount").toLongOrNull()
            ?: throw SolanaRPCException("getTokenAccountBalance: amount not an integer")
        return amount to value.getInt("decimals")
    }

    /** Whether an account exists on chain. Used by the SPL transfer
     *  builder to decide if a recipient already has an ATA for the mint. */
    fun accountExists(address: String): Boolean {
        val cfg = JSONObject().put("encoding", "base64")
        val value = callValue("getAccountInfo", JSONArray().put(address).put(cfg))
        return value != null && value != JSONObject.NULL
    }

    /** Raw base64 account data for [address], or null if the account does not
     *  exist on chain. Used by SNS (.sol) resolution to read the name registry. */
    fun getAccountDataBase64(address: String): String? {
        val cfg = JSONObject().put("encoding", "base64")
        val value = callValue("getAccountInfo", JSONArray().put(address).put(cfg))
        if (value == null || value == JSONObject.NULL) return null
        val obj = value as? JSONObject ?: return null
        val arr = obj.optJSONArray("data") ?: return null
        return arr.optString(0).takeIf { it.isNotEmpty() }
    }

    data class ParsedMint(val decimals: Int, val supplyRaw: String)

    /** SPL Mint metadata via getAccountInfo (jsonParsed). Returns null
     *  when the address does not exist or is not a spl-token mint. */
    fun getParsedMint(address: String): ParsedMint? {
        val cfg = JSONObject().put("encoding", "jsonParsed")
        return try {
            val value = callValue("getAccountInfo", JSONArray().put(address).put(cfg))
            if (value == null || value == JSONObject.NULL || value !is JSONObject) return null
            val data = value.getJSONObject("data")
            if (data.optString("program") != "spl-token") return null
            val parsed = data.getJSONObject("parsed")
            if (parsed.optString("type") != "mint") return null
            val info = parsed.getJSONObject("info")
            ParsedMint(decimals = info.getInt("decimals"), supplyRaw = info.getString("supply"))
        } catch (e: Exception) {
            null
        }
    }

    // MARK: -- envelope

    /** Solana JSON-RPC `result` for most methods is `{ context, value }`.
     *  Returns the unwrapped `value` (may be a primitive, JSONObject,
     *  JSONArray, or JSONObject.NULL). */
    private fun callValue(method: String, params: JSONArray): Any? {
        val result = callRaw(method, params)
        if (result is JSONObject && result.has("value")) {
            return if (result.isNull("value")) JSONObject.NULL else result.get("value")
        }
        return result
    }

    /** Raw call: returns the `result` field as-is (caller unwraps). */
    private fun callRaw(method: String, params: JSONArray): Any? {
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
        val body = try {
            http.postJson(endpoint, envelope.toString())
        } catch (e: NetworkException) {
            throw SolanaRPCException("Solana RPC transport error: HTTP ${e.status}")
        } catch (e: Exception) {
            throw SolanaRPCException("Solana RPC transport error: ${e.message}")
        }
        val parsed = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw SolanaRPCException("$method: decode error: ${e.message}")
        }
        if (parsed.has("error") && !parsed.isNull("error")) {
            val err = parsed.getJSONObject("error")
            throw SolanaRPCException("Solana RPC error ${err.optInt("code")}: ${err.optString("message")}")
        }
        if (!parsed.has("result") || parsed.isNull("result")) {
            // getAccountInfo etc. can legitimately have a result whose
            // value is null; only treat a wholly-missing result as empty.
            if (!parsed.has("result")) {
                throw SolanaRPCException("empty result for method $method")
            }
            return JSONObject.NULL
        }
        return parsed.get("result")
    }

    companion object {
        /** Standard SPL Token Program id. Token-2022 has its own program
         *  id and is intentionally excluded here. */
        const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    }
}
