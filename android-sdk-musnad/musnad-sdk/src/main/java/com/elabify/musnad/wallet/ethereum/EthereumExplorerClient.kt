// Etherscan-family transaction-history client. 1:1 port of
// EthereumExplorerClient.swift. Every chain's explorer (Etherscan, Arbiscan,
// Blockscout, ...) exposes the same module=account&action=txlist shape, so this
// client works uniformly. Per-network base URL + optional API key come from
// EthereumSettings. NO baked Etherscan key: users override per network.
//
// Transport is MaknoonHttp (OkHttp) + org.json. The validated-fetch step maps
// flaky-explorer responses (HTML 404/502, gateway pages) to a clean
// EthereumExplorerException instead of leaking a raw decode error. Per-row
// decoding is lossy so one malformed tx can't empty the whole list.

package com.elabify.musnad.wallet.ethereum
import com.elabify.musnad.util.optStringOrNull

import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import org.json.JSONObject

data class EthereumTx(
    val hash: String,
    val blockNumber: String,
    val timeStamp: String,
    val from: String,
    val to: String?, // null for contract-creation
    val value: String, // wei decimal string
    val gas: String?,
    val gasPrice: String?,
    val gasUsed: String?,
    val isError: String?,
    val txreceiptStatus: String?,
    val input: String?,
) {
    val id: String get() = hash
    val timestampSeconds: Double get() = timeStamp.toDoubleOrNull() ?: 0.0
}

data class EthereumTokenTransfer(
    val hash: String,
    val blockNumber: String,
    val timeStamp: String,
    val from: String,
    val to: String,
    val value: String, // raw token units decimal string
    val contractAddress: String,
    val tokenName: String?,
    val tokenSymbol: String?,
    val tokenDecimal: String?, // sic: Etherscan field is singular
) {
    val timestampSeconds: Double get() = timeStamp.toDoubleOrNull() ?: 0.0
}

/** Unified history item rendered in the wallet's tx list. */
sealed interface EthereumTxItem {
    data class Native(val tx: EthereumTx) : EthereumTxItem
    data class Token(val transfer: EthereumTokenTransfer) : EthereumTxItem

    val id: String
        get() = when (this) {
            is Native -> "n:${tx.hash}"
            is Token -> "t:${transfer.hash}:${transfer.contractAddress}"
        }

    val timestampSeconds: Double
        get() = when (this) {
            is Native -> tx.timestampSeconds
            is Token -> transfer.timestampSeconds
        }

    val hash: String
        get() = when (this) {
            is Native -> tx.hash
            is Token -> transfer.hash
        }
}

class EthereumExplorerException(val host: String, val status: Int?) : Exception() {
    override val message: String
        get() = if (status != null) {
            "Transaction history is temporarily unavailable ($host returned HTTP $status)."
        } else {
            "Transaction history is temporarily unavailable from $host."
        }
}

class EthereumExplorerClient private constructor(
    private val apiURL: String,
    private val apiKey: String?,
    private val chainId: Long,
    private val http: MaknoonHttp,
) {
    private val host: String = runCatching { java.net.URI(apiURL).host }.getOrNull() ?: "explorer"

    companion object {
        /** Returns null when the API URL is unusable (mirrors the Swift init?). */
        fun orNull(
            apiURL: String?,
            apiKey: String?,
            chainId: Long,
            http: MaknoonHttp = MaknoonHttp(),
        ): EthereumExplorerClient? {
            if (apiURL.isNullOrEmpty()) return null
            return EthereumExplorerClient(apiURL, apiKey, chainId, http)
        }
    }

    /** Recent ERC-20 transfers involving [address]. */
    fun recentTokenTransfers(address: String, page: Int = 1, perPage: Int = 100): List<EthereumTokenTransfer> {
        val url = buildURL("tokentx", address, page, perPage)
        val data = fetchValidated(url, "tokentx")
        val env = parseEnvelope(data, "tokentx")
        if (env.status != "1") {
            if (env.message?.contains("No transactions") == true) return emptyList()
            throw Exception(env.resultMessage ?: env.message ?: "Unknown explorer error")
        }
        return env.array.mapNotNull { o -> runCatching { tokenTransferFromJson(o) }.getOrNull() }
    }

    /** Recent transactions for [address], newest first. */
    fun recentTransactions(address: String, page: Int = 1, perPage: Int = 25): List<EthereumTx> {
        val url = buildURL("txlist", address, page, perPage)
        val data = fetchValidated(url, "txlist")
        val env = parseEnvelope(data, "txlist")
        if (env.status != "1") {
            if (env.message?.contains("No transactions") == true) return emptyList()
            throw Exception(env.resultMessage ?: env.message ?: "Unknown explorer error")
        }
        return env.array.mapNotNull { o -> runCatching { txFromJson(o) }.getOrNull() }
    }

    // ---- internals ----

    private fun buildURL(action: String, address: String, page: Int, perPage: Int): String {
        val sep = if (apiURL.contains("?")) "&" else "?"
        val sb = StringBuilder(apiURL).append(sep)
            .append("module=account")
            .append("&action=").append(action)
            .append("&address=").append(enc(address))
            .append("&startblock=0")
            .append("&endblock=99999999")
            .append("&page=").append(page)
            .append("&offset=").append(perPage)
            .append("&sort=desc")
            .append("&chainid=").append(chainId)
        if (apiKey != null) sb.append("&apikey=").append(enc(apiKey))
        return sb.toString()
    }

    private fun fetchValidated(url: String, label: String): String {
        val text = try {
            http.getJson(url)
        } catch (e: NetworkException) {
            throw EthereumExplorerException(host, e.status)
        } catch (e: Exception) {
            throw EthereumExplorerException(host, null)
        }
        val firstByte = text.firstOrNull { it != ' ' && it != '\n' && it != '\r' && it != '\t' }
        if (firstByte != '{' && firstByte != '[') {
            throw EthereumExplorerException(host, null)
        }
        return text
    }

    private data class Envelope(
        val status: String?,
        val message: String?,
        val array: List<JSONObject>,
        val resultMessage: String?,
    )

    private fun parseEnvelope(text: String, label: String): Envelope {
        val o = runCatching { JSONObject(text) }.getOrNull()
            ?: throw EthereumExplorerException(host, null)
        val status = if (o.isNull("status")) null else o.optString("status")
        val message = if (o.isNull("message")) null else o.optString("message")
        // result is bimorphic: an array (status=1) or a string reason (status=0).
        val resultArr = o.optJSONArray("result")
        if (resultArr != null) {
            val list = ArrayList<JSONObject>(resultArr.length())
            for (i in 0 until resultArr.length()) {
                resultArr.optJSONObject(i)?.let { list.add(it) }
            }
            return Envelope(status, message, list, null)
        }
        val resultStr = if (o.isNull("result")) null else o.optStringOrNull("result")
        return Envelope(status, message, emptyList(), resultStr)
    }

    private fun txFromJson(o: JSONObject): EthereumTx = EthereumTx(
        hash = o.getString("hash"),
        blockNumber = o.optString("blockNumber"),
        timeStamp = o.optString("timeStamp"),
        from = o.optString("from"),
        to = if (o.isNull("to")) null else o.optStringOrNull("to"),
        value = o.optString("value", "0"),
        gas = o.optStringOrNull("gas"),
        gasPrice = o.optStringOrNull("gasPrice"),
        gasUsed = o.optStringOrNull("gasUsed"),
        isError = o.optStringOrNull("isError"),
        txreceiptStatus = o.optStringOrNull("txreceipt_status"),
        input = o.optStringOrNull("input"),
    )

    private fun tokenTransferFromJson(o: JSONObject): EthereumTokenTransfer = EthereumTokenTransfer(
        hash = o.getString("hash"),
        blockNumber = o.optString("blockNumber"),
        timeStamp = o.optString("timeStamp"),
        from = o.optString("from"),
        to = o.optString("to"),
        value = o.optString("value", "0"),
        contractAddress = o.optString("contractAddress"),
        tokenName = o.optStringOrNull("tokenName"),
        tokenSymbol = o.optStringOrNull("tokenSymbol"),
        tokenDecimal = o.optStringOrNull("tokenDecimal"),
    )

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
