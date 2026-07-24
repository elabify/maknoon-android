// JSON-RPC client for EVM chains. 1:1 port of EthereumRPCClient.swift:
// read methods (balance, chainId, blockNumber, transactionCount, gasPrice,
// maxPriorityFeePerGas, feeHistory base fee, estimateGas, eth_call) + the send
// surface (sendRawTransaction). Transport is MaknoonHttp (OkHttp) + org.json
// instead of URLSession + Codable.
//
// EthereumRPCFailover wraps a primary + fallback URL list and retries the next
// endpoint on transport/-32603 errors, mirroring the iOS "try a different RPC"
// behaviour without changing the per-call API shape.

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import org.json.JSONArray
import org.json.JSONObject

class EthereumRPCException(val code: Int, override val message: String) : Exception(message) {
    companion object {
        const val MALFORMED = -100000
        const val HTTP = -100001
    }

    /** User-facing message, with the special-cased -32603 rate-limit hint. */
    fun describe(): String = when (code) {
        -32603 -> "RPC provider returned an internal error. This is usually a rate-limit or transient outage on the public endpoint. Try a different RPC URL under Settings, Networks, Ethereum."
        MALFORMED -> "Malformed RPC response: $message"
        HTTP -> message
        else -> "RPC error $code: $message"
    }
}

class EthereumRPCClient(
    val urlString: String,
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    init {
        require(urlString.isNotBlank()) { "RPC URL is blank" }
    }

    companion object {
        /** Returns null when the URL string is unusable (mirrors the Swift init?). */
        fun orNull(urlString: String?, http: MaknoonHttp = MaknoonHttp()): EthereumRPCClient? {
            if (urlString.isNullOrBlank()) return null
            return try { EthereumRPCClient(urlString, http) } catch (_: Exception) { null }
        }
    }

    // ---- read methods ----

    fun getBalance(address: String): EthereumWeiValue {
        val hex = callString("eth_getBalance", JSONArray().put(address).put("latest"))
        return EthereumWeiValue.fromHex(hex)
    }

    fun chainId(): Long = parseUInt64(callString("eth_chainId", JSONArray()))

    fun blockNumber(): Long = parseUInt64(callString("eth_blockNumber", JSONArray()))

    /** Tx count (nonce). `pending` includes mempool-only txs. */
    fun transactionCount(address: String, block: String = "pending"): Long =
        parseUInt64(callString("eth_getTransactionCount", JSONArray().put(address).put(block)))

    fun gasPrice(): EthereumWeiValue = EthereumWeiValue.fromHex(callString("eth_gasPrice", JSONArray()))

    fun maxPriorityFeePerGas(): EthereumWeiValue =
        EthereumWeiValue.fromHex(callString("eth_maxPriorityFeePerGas", JSONArray()))

    /** Next-block base fee from eth_feeHistory's last baseFeePerGas entry. */
    fun nextBlockBaseFee(): EthereumWeiValue {
        val params = JSONArray().put("0x1").put("latest").put(JSONArray())
        val result = callObject("eth_feeHistory", params)
        val arr = result.optJSONArray("baseFeePerGas")
            ?: throw EthereumRPCException(EthereumRPCException.MALFORMED, "empty baseFeePerGas in eth_feeHistory")
        if (arr.length() == 0) {
            throw EthereumRPCException(EthereumRPCException.MALFORMED, "empty baseFeePerGas in eth_feeHistory")
        }
        return EthereumWeiValue.fromHex(arr.getString(arr.length() - 1))
    }

    /** Estimate gas units for a transaction. */
    fun estimateGas(from: String, to: String, value: EthereumWeiValue, data: ByteArray?): Long {
        val call = JSONObject()
            .put("from", from)
            .put("to", to)
            .put("value", "0x" + value.hex)
        if (data != null) call.put("data", EthereumABI.toHexData(data))
        val hex = callString("eth_estimateGas", JSONArray().put(call).put("latest"))
        return parseUInt64(hex)
    }

    /** Read-only contract call (ERC-20 balanceOf / symbol / decimals / name).
     *  `from` is caller-dependent reads: e.g. a Uniswap v4 Quoter simulates the
     *  swap so the pool's beforeSwap hook checks isAllowed(tx.origin); without it
     *  the simulation sees address(0) and reverts. */
    fun ethCall(to: String, data: ByteArray, from: String? = null, block: String = "latest"): String {
        val call = JSONObject()
            .put("to", to)
            .put("data", EthereumABI.toHexData(data))
        if (from != null) call.put("from", from)
        return callString("eth_call", JSONArray().put(call).put(block))
    }

    /** Deployed bytecode at [address]. An EOA (regular wallet) returns "0x" or
     *  "0x0"; a contract returns its non-empty bytecode. Used to guard against
     *  sending tokens to a contract address (see EthereumWallet.isContract). */
    fun getCode(address: String, block: String = "latest"): String =
        callString("eth_getCode", JSONArray().put(address).put(block))

    /** Broadcast a fully signed transaction; returns the tx hash. */
    fun sendRawTransaction(rawHex: String): String {
        val hex = if (rawHex.startsWith("0x")) rawHex else "0x$rawHex"
        return callString("eth_sendRawTransaction", JSONArray().put(hex))
    }

    /** Receipt of a mined transaction, including its event logs. Throws if the tx
     *  is unknown/pending (null result) — callers treat that as "unreadable". */
    fun getTransactionReceipt(txHash: String): JSONObject {
        val hex = if (txHash.startsWith("0x")) txHash else "0x$txHash"
        return callObject("eth_getTransactionReceipt", JSONArray().put(hex))
    }

    // ---- transport ----

    private fun callString(method: String, params: JSONArray): String {
        val result = invoke(method, params)
        return result as? String
            ?: (result as? JSONObject)?.toString()
            ?: throw EthereumRPCException(EthereumRPCException.MALFORMED, "$method: expected string result")
    }

    private fun callObject(method: String, params: JSONArray): JSONObject {
        val result = invoke(method, params)
        return result as? JSONObject
            ?: throw EthereumRPCException(EthereumRPCException.MALFORMED, "$method: expected object result")
    }

    /** Returns the raw `result` value (String / JSONObject / JSONArray). */
    private fun invoke(method: String, params: JSONArray): Any {
        val body = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
            .toString()
        val responseText = try {
            http.postJson(urlString, body)
        } catch (e: NetworkException) {
            throw EthereumRPCException(EthereumRPCException.HTTP, "HTTP ${e.status}: ${e.body.take(200)}")
        }
        val env = JSONObject(responseText)
        if (env.has("error") && !env.isNull("error")) {
            val err = env.getJSONObject("error")
            throw EthereumRPCException(err.optInt("code", 0), err.optString("message", "RPC error"))
        }
        if (env.isNull("result")) {
            throw EthereumRPCException(EthereumRPCException.MALFORMED, "Neither result nor error in RPC envelope")
        }
        return env.get("result")
    }

    private fun parseUInt64(hex: String): Long {
        var s = hex
        if (s.startsWith("0x")) s = s.substring(2)
        return try {
            java.lang.Long.parseUnsignedLong(s, 16)
        } catch (_: NumberFormatException) {
            throw EthereumRPCException(EthereumRPCException.MALFORMED, "Bad hex integer '$hex'")
        }
    }
}

/**
 * RPC failover: try the primary endpoint first, fall back to the next URL on a
 * transport error or a -32603 internal error. Mirrors the iOS guidance to
 * "try a different RPC URL" but automates it across a candidate list.
 */
class EthereumRPCFailover(
    private val urls: List<String>,
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    init {
        require(urls.any { it.isNotBlank() }) { "no usable RPC URLs" }
    }

    /** Run [block] against each endpoint in order, retrying recoverable errors. */
    fun <T> withClient(block: (EthereumRPCClient) -> T): T {
        var last: Exception? = null
        for (url in urls) {
            val client = EthereumRPCClient.orNull(url, http) ?: continue
            try {
                return block(client)
            } catch (e: EthereumRPCException) {
                last = e
                if (e.code == -32603 || e.code == EthereumRPCException.HTTP) continue
                throw e
            } catch (e: Exception) {
                last = e
                continue
            }
        }
        throw last ?: EthereumRPCException(EthereumRPCException.MALFORMED, "all RPC endpoints failed")
    }
}
