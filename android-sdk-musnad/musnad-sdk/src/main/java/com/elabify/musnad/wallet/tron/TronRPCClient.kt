// Thin TronGrid HTTP client, ported 1:1 from iOS TronRPCClient.swift.
// Tron's API isn't JSON-RPC; instead each method is its own POST
// endpoint under `/wallet/...` taking a JSON body and returning JSON.
// The dashboard-driver subset:
//
//   - getBalance               sun balance for an address
//   - getNowBlock              latest block header (needed by the tx builder)
//   - broadcastTransaction
//   - getTransactionsByAddress recent activity (v1 GET endpoint)
//   - triggerConstantContract  TRC-20 read calls (balanceOf, decimals...)
//   - createNativeTransaction  server-built unsigned native TRX transfer
//   - createTRC20Transaction   server-built unsigned TRC-20 transfer
//   - broadcastWithSignature   splice an R||S||V signature + broadcast
//
// All transport goes through MaknoonHttp (OkHttp) to match the rest of
// the SDK. One client per (network, endpoint URL); cheap to recreate.

package com.elabify.musnad.wallet.tron
import com.elabify.musnad.util.optStringOrNull

import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

class TronRPCException(message: String) : Exception(message)

class TronRPCClient(
    /** Base URL with no trailing slash, e.g. "https://api.trongrid.io". */
    val base: String,
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    private val baseTrimmed: String = base.trimEnd('/')

    // MARK: -- account + balance

    /** Sun balance for an address. Returns 0 for accounts that don't
     *  exist on chain yet (Tron treats unfunded addresses as implicitly
     *  zero). 1 TRX = 1_000_000 sun. */
    fun getBalance(addressBase58: String): Long {
        val body = JSONObject()
            .put("address", addressBase58)
            .put("visible", true)
        val o = post("/wallet/getaccount", body)
        return o.optLong("balance", 0L)
    }

    // MARK: -- block reference

    /** Latest block header. The Tron tx builder folds these fields into
     *  the transaction so the network accepts it. */
    data class NowBlock(
        val number: Long,
        val timestamp: Long,
        val parentHashHex: String,
        val txTrieRootHex: String,
        val witnessAddressHex: String,
        val version: Int,
    )

    fun getNowBlock(): NowBlock {
        // TronGrid mixes snake_case and camelCase: `block_header` and
        // `raw_data` are snake_case; most fields inside raw_data are
        // camelCase EXCEPT `witness_address`. We pin every key by hand
        // and tolerate genesis/empty-block omissions.
        val block = post("/wallet/getnowblock", JSONObject())
        val rawData = block.getJSONObject("block_header").getJSONObject("raw_data")
        return NowBlock(
            number = rawData.optLong("number", 0L),
            timestamp = rawData.optLong("timestamp", 0L),
            parentHashHex = rawData.optString("parentHash", ""),
            txTrieRootHex = rawData.optString("txTrieRoot", ""),
            witnessAddressHex = rawData.optString("witness_address", ""),
            version = rawData.optInt("version", 0),
        )
    }

    // MARK: -- broadcast

    /** Broadcast a signed transaction JSON. Returns the txid (hex) on
     *  success; throws a server error with TronGrid's message/code if
     *  the network rejected it. */
    fun broadcastTransaction(signedJSON: String): String {
        val body = try {
            JSONObject(signedJSON)
        } catch (e: Exception) {
            throw TronRPCException("signedJSON not parseable: ${signedJSON.take(80)}")
        }
        val r = post("/wallet/broadcasttransaction", body)
        val result = if (r.has("result")) r.opt("result") else null
        val resultBool = result as? Boolean
        val message = if (r.isNull("message")) null else r.optStringOrNull("message")
        if (message != null && resultBool != true) {
            // Tron returns `message` as hex when `code` is set; decode
            // best-effort so the user sees the human-readable reason.
            val decoded = hexDecodeString(message) ?: message
            val code = if (r.isNull("code")) "?" else r.optString("code", "?")
            throw TronRPCException("[$code] $decoded")
        }
        val txid = (if (r.isNull("txid")) null else r.optStringOrNull("txid"))
            ?: throw TronRPCException("broadcastTransaction returned no txid")
        return txid
    }

    // MARK: -- recent activity

    /** One transaction record from the TronGrid v1 account index. */
    data class TxRecord(
        val txID: String,
        val blockTimestamp: Long?,
        val contractType: String?,
        val contractStatus: String?,
        val ownerAddress: String?,
        val toAddress: String?,
        val contractAddress: String?,
        val amount: Long?,
        /** ABI-encoded TRC-20 call payload (selector + amount). */
        val data: String?,
    ) {
        val id: String get() = txID

        /** Native TRX amount in sun. Null when this isn't a
         *  TransferContract. */
        val nativeSunAmount: Long?
            get() = if (contractType == "TransferContract") amount else null
    }

    /** Recent transactions for an address via TronGrid's v1 endpoint. */
    fun getTransactionsByAddress(addressBase58: String, limit: Int = 20): List<TxRecord> {
        val url = "$baseTrimmed/v1/accounts/$addressBase58/transactions" +
            "?limit=$limit&only_confirmed=true"
        val body = try {
            http.getJson(url)
        } catch (e: NetworkException) {
            throw TronRPCException("Tron RPC transport error: HTTP ${e.status}")
        } catch (e: Exception) {
            throw TronRPCException("Tron RPC transport error: ${e.message}")
        }
        val env = try { JSONObject(body) } catch (e: Exception) {
            throw TronRPCException("GET transactions: decode error: ${e.message}")
        }
        val arr = env.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<TxRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rawData = o.optJSONObject("raw_data")
            val firstContract = rawData?.optJSONArray("contract")?.optJSONObject(0)
            val value = firstContract?.optJSONObject("parameter")?.optJSONObject("value")
            val ret = o.optJSONArray("ret")?.optJSONObject(0)
            out.add(
                TxRecord(
                    txID = o.optString("txID"),
                    blockTimestamp = if (o.isNull("block_timestamp")) null else o.optLong("block_timestamp"),
                    contractType = firstContract?.optStringOrNull("type"),
                    contractStatus = ret?.optStringOrNull("contractRet"),
                    ownerAddress = value?.optStringOrNull("owner_address"),
                    toAddress = value?.optStringOrNull("to_address"),
                    contractAddress = value?.optStringOrNull("contract_address"),
                    amount = value?.let { if (it.isNull("amount")) null else it.optLong("amount") },
                    data = value?.optStringOrNull("data"),
                )
            )
        }
        return out
    }

    // MARK: -- TRC-20 reads

    /** Read-only contract call (no on-chain state change). Used by the
     *  TRC-20 balance probe. Returns the raw hex-encoded return value;
     *  caller ABI-decodes. */
    fun triggerConstantContract(
        ownerAddressBase58: String,
        contractAddressBase58: String,
        functionSelector: String,
        parameterHex: String,
    ): String {
        val body = JSONObject()
            .put("owner_address", ownerAddressBase58)
            .put("contract_address", contractAddressBase58)
            .put("function_selector", functionSelector)
            .put("parameter", parameterHex)
            .put("visible", true)
        val r = post("/wallet/triggerconstantcontract", body)
        val result = r.optJSONObject("result")
        if (result != null && result.has("result") && !result.optBoolean("result", true)) {
            val msg = if (result.isNull("message")) null else result.optStringOrNull("message")
            throw TronRPCException(msg?.let { hexDecodeString(it) ?: it } ?: "constant contract error")
        }
        val arr = r.optJSONArray("constant_result")
        return if (arr != null && arr.length() > 0) arr.optString(0, "") else ""
    }

    // MARK: -- hardware/software sign helpers
    //
    // The Android WalletCore (0.12.8) Tron SigningOutput exposes only
    // {id, signature, refBlockBytes, refBlockHash}, NOT the broadcast
    // JSON the iOS build's `output.json` provides. So even software
    // signing takes the server-built-unsigned + splice-signature route:
    // ask TronGrid to build the canonical transaction and hand back the
    // raw_data bytes (signed locally / by the Ledger) plus the JSON
    // envelope (broadcast after splicing the signature in).

    /** Server-built unsigned transaction. */
    data class UnsignedTransaction(
        /** Raw protobuf bytes of `Transaction.raw_data`. */
        val rawData: ByteArray,
        /** Hex of [rawData]. */
        val rawDataHex: String,
        /** JSON envelope to splice the signature into for broadcast. */
        val envelopeJSON: String,
        /** The transaction id (hex of SHA256(raw_data)), echoed by the
         *  server. Surfaced for pending-tx tracking. */
        val txID: String?,
    )

    /** Build a server-side unsigned native TRX transfer via TronGrid's
     *  `/wallet/createtransaction`. The server picks the block ref +
     *  expiration; the caller decides actor + recipient + amount +
     *  (optional) fee limit. */
    fun createNativeTransaction(
        senderBase58: String,
        recipientBase58: String,
        sunAmount: Long,
        feeLimitSun: Long?,
    ): UnsignedTransaction {
        val body = JSONObject()
            .put("owner_address", senderBase58)
            .put("to_address", recipientBase58)
            .put("amount", sunAmount)
            .put("visible", true)
        if (feeLimitSun != null) body.put("fee_limit", feeLimitSun)
        val envelope = post("/wallet/createtransaction", body)
        envelope.optString("Error", "").takeIf { it.isNotEmpty() }?.let {
            throw TronRPCException(it)
        }
        val rawHex = envelope.optString("raw_data_hex", "")
        if (rawHex.isEmpty()) {
            throw TronRPCException("createtransaction: missing raw_data_hex")
        }
        val rawBytes = decodeHex(rawHex)
        if (rawBytes.isEmpty()) throw TronRPCException("createtransaction: raw_data_hex decoded to 0 bytes")
        return UnsignedTransaction(
            rawData = rawBytes,
            rawDataHex = rawHex,
            envelopeJSON = envelope.toString(),
            txID = if (envelope.isNull("txID")) null else envelope.optStringOrNull("txID"),
        )
    }

    /** Build a server-side unsigned TRC-20 token transfer via
     *  `/wallet/triggersmartcontract`. The server returns a
     *  `transaction.raw_data_hex` we ship to the signer unchanged.
     *  `rawAmount` is the integer base-units value as a base-10 string
     *  (TRC-20 amounts can exceed 64 bits). */
    fun createTRC20Transaction(
        senderBase58: String,
        contractAddressBase58: String,
        recipientBase58: String,
        rawAmount: String,
        feeLimitSun: Long,
    ): UnsignedTransaction {
        val toHex = encodeAddressParameter(recipientBase58)
        val amountHex = encodeUint256Decimal(rawAmount)
        val body = JSONObject()
            .put("owner_address", senderBase58)
            .put("contract_address", contractAddressBase58)
            .put("function_selector", "transfer(address,uint256)")
            .put("parameter", toHex + amountHex)
            .put("fee_limit", feeLimitSun)
            .put("call_value", 0)
            .put("visible", true)
        val envelope = post("/wallet/triggersmartcontract", body)
        val result = envelope.optJSONObject("result")
        if (result != null && result.has("result") && !result.optBoolean("result", true)) {
            val msg = if (result.isNull("message")) null else result.optStringOrNull("message")
            throw TronRPCException(msg?.let { hexDecodeString(it) ?: it } ?: "triggersmartcontract error")
        }
        val txObj = envelope.optJSONObject("transaction")
            ?: throw TronRPCException("triggersmartcontract: missing transaction")
        val rawHex = txObj.optString("raw_data_hex", "")
        if (rawHex.isEmpty()) throw TronRPCException("triggersmartcontract: missing transaction.raw_data_hex")
        val rawBytes = decodeHex(rawHex)
        if (rawBytes.isEmpty()) throw TronRPCException("triggersmartcontract: raw_data_hex decoded to 0 bytes")
        return UnsignedTransaction(
            rawData = rawBytes,
            rawDataHex = rawHex,
            envelopeJSON = txObj.toString(),
            txID = if (txObj.isNull("txID")) null else txObj.optStringOrNull("txID"),
        )
    }

    /** Splice an externally-produced 65-byte (R || S || V) signature
     *  into the JSON envelope returned by create*Transaction and
     *  broadcast it. Returns the txid on success. */
    fun broadcastWithSignature(envelopeJSON: String, signatureRSV: ByteArray): String {
        if (signatureRSV.size != 65) {
            throw TronRPCException("signature must be 65 bytes (R||S||V), got ${signatureRSV.size}")
        }
        val signed = try {
            JSONObject(envelopeJSON)
        } catch (e: Exception) {
            throw TronRPCException("envelopeJSON could not be re-parsed")
        }
        signed.put("signature", JSONArray().put(signatureRSV.toHex()))
        return broadcastTransaction(signed.toString())
    }

    // MARK: -- ABI helpers

    /** ABI-encode an address parameter for the Tron VM. Strips the 0x41
     *  prefix and left-pads the remaining 20 bytes to 32. Hex, no 0x. */
    private fun encodeAddressParameter(base58: String): String {
        val raw = TronAddressCodec.base58CheckDecode(base58)
            ?: throw TronRPCException("encodeAddressParameter: bad base58check for $base58")
        if (raw.size != 21 || raw[0] != 0x41.toByte()) {
            throw TronRPCException("encodeAddressParameter: bad base58check for $base58")
        }
        val hash = raw.copyOfRange(1, 21)
        val padded = ByteArray(12) + hash
        return padded.toHex()
    }

    /** Encode a decimal-string uint256 value as 32-byte big-endian hex. */
    private fun encodeUint256Decimal(decString: String): String {
        val value = try { BigInteger(decString) } catch (e: Exception) {
            return "0".repeat(64)
        }
        if (value.signum() < 0) return "0".repeat(64)
        var bytes = value.toByteArray()
        // BigInteger.toByteArray() may prepend a sign byte; drop leading
        // zero bytes beyond 32.
        if (bytes.size > 32) bytes = bytes.copyOfRange(bytes.size - 32, bytes.size)
        val padded = ByteArray(32 - bytes.size) + bytes
        return padded.toHex()
    }

    // MARK: -- envelope

    private fun post(path: String, body: JSONObject): JSONObject {
        val url = baseTrimmed + path
        val resp = try {
            http.postJson(url, body.toString())
        } catch (e: NetworkException) {
            throw TronRPCException("Tron RPC transport error: HTTP ${e.status}")
        } catch (e: Exception) {
            throw TronRPCException("Tron RPC transport error: ${e.message}")
        }
        return try {
            JSONObject(resp)
        } catch (e: Exception) {
            throw TronRPCException("$path: decode error: ${e.message}. Raw: ${resp.take(200)}")
        }
    }

    /** Hex string -> bytes, tolerating `0x` prefix. Returns empty on any
     *  non-hex character so the caller can diagnose "0 bytes". */
    private fun decodeHex(hex: String): ByteArray = try {
        hexToBytes(hex)
    } catch (e: Exception) {
        ByteArray(0)
    }

    private fun hexDecodeString(hex: String): String? = try {
        val bytes = hexToBytes(hex)
        String(bytes, Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
