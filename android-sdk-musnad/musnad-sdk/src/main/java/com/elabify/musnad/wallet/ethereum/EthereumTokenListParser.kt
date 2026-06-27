// Parser for the standard "TokenList" JSON schema (Uniswap / CoinGecko / Trust
// Wallet / Jupiter). 1:1 port of TokenListParser.swift, scoped to the ethereum
// package to stay conflict-free with other chains' parsers.
//
//   { "tokens": [ { "chainId": 1, "address": "0x..", "name": "..",
//                   "symbol": "..", "decimals": 6, "logoURI": ".." } ] }
//
// The caller supplies a `normalize` lambda to canonicalise the address
// (lowercase for EVM). Malformed rows are skipped so one bad entry can't reject
// the whole catalog.

package com.elabify.musnad.wallet.ethereum
import com.elabify.musnad.util.optStringOrNull

import org.json.JSONObject

data class TokenListEntry(
    val chainId: Int?,
    val address: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val logoURI: String?,
)

class TokenListParseException(message: String) : Exception(message)

object EthereumTokenListParser {

    /** Decode a TokenList JSON payload. Throws when the `tokens` array is absent. */
    fun parse(json: String, normalize: (String) -> String): List<TokenListEntry> {
        val env = JSONObject(json)
        val rows = env.optJSONArray("tokens")
            ?: throw TokenListParseException("Catalog JSON did not include a `tokens` array.")
        val out = ArrayList<TokenListEntry>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val addr = row.optString("address", "").trim()
            val sym = row.optString("symbol", "")
            val nm = row.optString("name", "")
            if (addr.isEmpty() || sym.isEmpty() || nm.isEmpty()) continue
            val decimals = optInt(row, "decimals") ?: continue
            out.add(
                TokenListEntry(
                    chainId = optInt(row, "chainId"),
                    address = normalize(addr),
                    symbol = sym,
                    name = nm,
                    decimals = decimals.coerceIn(0, 255),
                    logoURI = if (row.isNull("logoURI")) null else row.optStringOrNull("logoURI"),
                )
            )
        }
        return out
    }

    /** Tolerant int decode: accepts an Int or a numeric String. */
    private fun optInt(obj: JSONObject, key: String): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val v = obj.get(key)) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }
}
