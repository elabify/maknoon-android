// Remote-backed verified TRC-20 catalog, ported from iOS
// TronTokenCatalog.swift: no first-run seed, fetch on first dashboard
// visit, cache locally, refresh weekly, surface fetch errors. Parses
// the standard "TokenList" JSON schema (CoinGecko / Uniswap / Trust
// Wallet shape). Backed by SharedPreferences.

package com.elabify.musnad.wallet.tron

import android.content.SharedPreferences
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import org.json.JSONObject

class TronTokenCatalog(
    private val prefs: SharedPreferences,
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    var lastFetchedEpochMs: Long? = null
        private set
    var entriesByContract: Map<String, Entry> = emptyMap()
        private set
    var refreshing: Boolean = false
        private set
    var lastError: String? = null
        private set

    data class Entry(
        val contract: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val logoURI: String?,
    )

    init {
        load()
    }

    fun find(contract: String): Entry? = entriesByContract[contract]

    fun refreshIfStale(catalogURL: String) {
        val last = lastFetchedEpochMs
        if (last != null && System.currentTimeMillis() - last < STALE_AFTER_MS) return
        refresh(catalogURL)
    }

    fun refresh(catalogURL: String) {
        refreshing = true
        try {
            val body = try {
                http.getJson(catalogURL)
            } catch (e: NetworkException) {
                lastError = "HTTP ${e.status} from catalog host."
                return
            } catch (e: Exception) {
                lastError = "Refresh failed: ${e.message}"
                return
            }
            val parsed = try {
                parseTokenList(body)
            } catch (e: Exception) {
                lastError = "Refresh failed: ${e.message}"
                return
            }
            val map = HashMap<String, Entry>(parsed.size)
            for (e in parsed) {
                map[e.address] = Entry(
                    contract = e.address,
                    symbol = e.symbol,
                    name = e.name,
                    decimals = e.decimals,
                    logoURI = e.logoURI,
                )
            }
            entriesByContract = map
            lastFetchedEpochMs = System.currentTimeMillis()
            lastError = null
            persist(body)
        } finally {
            refreshing = false
        }
    }

    fun clear() {
        entriesByContract = emptyMap()
        lastFetchedEpochMs = null
        lastError = null
        prefs.edit().remove(CACHE_KEY).remove(LAST_FETCH_KEY).apply()
    }

    // MARK: -- persistence

    private fun persist(rawBody: String) {
        val editor = prefs.edit().putString(CACHE_KEY, rawBody)
        lastFetchedEpochMs?.let { editor.putLong(LAST_FETCH_KEY, it) }
        editor.apply()
    }

    fun reload() {
        lastFetchedEpochMs = null
        entriesByContract = emptyMap()
        lastError = null
        load()
    }

    private fun load() {
        prefs.getString(CACHE_KEY, null)?.let { raw ->
            try {
                val parsed = parseTokenList(raw)
                val map = HashMap<String, Entry>(parsed.size)
                for (e in parsed) {
                    map[e.address] = Entry(e.address, e.symbol, e.name, e.decimals, e.logoURI)
                }
                entriesByContract = map
            } catch (_: Exception) {}
        }
        if (prefs.contains(LAST_FETCH_KEY)) lastFetchedEpochMs = prefs.getLong(LAST_FETCH_KEY, 0L)
    }

    // MARK: -- TokenList parsing

    /** One parsed entry from a TokenList JSON. Chain-agnostic; the
     *  caller filters by chainId / canonicalises the address. */
    data class TokenListEntry(
        val chainId: Int?,
        val address: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val logoURI: String?,
    )

    /** Decode a TokenList JSON payload. Malformed rows are silently
     *  skipped so a single bad entry doesn't reject the whole catalog.
     *  Mirror of iOS TokenListParser.parse with identity `normalize`
     *  (Tron addresses are base58check, no lowercasing). */
    private fun parseTokenList(body: String): List<TokenListEntry> {
        val tokens = JSONObject(body).optJSONArray("tokens")
            ?: throw IllegalStateException("Catalog JSON did not include a `tokens` array.")
        val out = ArrayList<TokenListEntry>(tokens.length())
        for (i in 0 until tokens.length()) {
            val row = tokens.optJSONObject(i) ?: continue
            val addr = row.optString("address", "").trim()
            val sym = row.optString("symbol", "")
            val nm = row.optString("name", "")
            if (addr.isEmpty() || sym.isEmpty() || nm.isEmpty() || !row.has("decimals")) continue
            out.add(
                TokenListEntry(
                    chainId = if (row.isNull("chainId")) null else optInt(row, "chainId"),
                    address = addr,
                    symbol = sym,
                    name = nm,
                    decimals = optInt(row, "decimals") ?: continue,
                    logoURI = if (row.has("logoURI") && !row.isNull("logoURI")) row.optString("logoURI") else null,
                )
            )
        }
        return out
    }

    /** Tolerant int read: CoinGecko + TronScan send Int or String. */
    private fun optInt(o: JSONObject, key: String): Int? {
        return when (val v = o.opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }

    companion object {
        /** CoinGecko's Tron TokenList. Standard schema, stable URL, no
         *  API key required. */
        const val DEFAULT_CATALOG_URL = "https://tokens.coingecko.com/tron/all.json"

        private const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        private const val CACHE_KEY = "networks.tron.tokens.catalog.v1"
        private const val LAST_FETCH_KEY = "networks.tron.tokens.catalogFetch.v1"
    }
}
