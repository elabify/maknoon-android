// Parser for the standard "TokenList" JSON schema (CoinGecko, Uniswap,
// Trust Wallet, Jupiter, et al.), ported from iOS TokenListParser.swift.
// Scoped to the solana subpackage to stay conflict-free with other
// chains' ports of the same shared util.
//
//   { "tokens": [ { "chainId": 101, "address": "<base58 mint>",
//                   "name": "...", "symbol": "...", "decimals": 6,
//                   "logoURI": "..." } ] }
//
// `chainId` / `decimals` are tolerated as either Int or String (CoinGecko
// and TronScan both vary). Malformed rows are silently skipped so a
// single bad entry doesn't reject the whole catalog.

package com.elabify.musnad.wallet.solana

import org.json.JSONObject

data class SolanaTokenListEntry(
    val chainId: Int?,
    val address: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    val logoURI: String?,
)

class SolanaTokenListParseException(message: String) : Exception(message)

object SolanaTokenListParser {

    /** Decode a TokenList JSON payload. `normalize` canonicalises the
     *  address (identity for Solana base58 mints). */
    fun parse(jsonText: String, normalize: (String) -> String = { it }): List<SolanaTokenListEntry> {
        val root = JSONObject(jsonText)
        val rows = root.optJSONArray("tokens")
            ?: throw SolanaTokenListParseException("Catalog JSON did not include a `tokens` array.")
        val out = ArrayList<SolanaTokenListEntry>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val addr = row.optString("address").trim()
            val sym = if (row.has("symbol")) row.optString("symbol") else null
            val nm = if (row.has("name")) row.optString("name") else null
            val dec = anyInt(row, "decimals")
            if (addr.isEmpty() || sym.isNullOrEmpty() || nm.isNullOrEmpty() || dec == null) continue
            out.add(
                SolanaTokenListEntry(
                    chainId = anyInt(row, "chainId"),
                    address = normalize(addr),
                    symbol = sym,
                    name = nm,
                    decimals = dec.coerceIn(0, 255),
                    logoURI = if (row.has("logoURI") && !row.isNull("logoURI")) row.optString("logoURI") else null,
                )
            )
        }
        return out
    }

    /** Tolerant int read: accepts Int or numeric String. */
    private fun anyInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        o.optInt(key, Int.MIN_VALUE).let { if (it != Int.MIN_VALUE) return it }
        return o.optString(key).toIntOrNull()
    }
}
