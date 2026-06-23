// Remote-backed verified-token directory for Solana, ported 1:1 from iOS
// SolanaTokenCatalog.swift. Maknoon does NOT ship a hardcoded SPL token
// list: it pulls a verified-tokens registry at runtime (CoinGecko's
// Solana TokenList by default, user-overridable), caches the JSON in the
// KV store, and refreshes weekly. The catalog is the trust anchor for
// the auto-discover path in SolanaSPLTokenStore.

package com.elabify.musnad.wallet.solana

class SolanaTokenCatalog(private val kv: SolanaKeyValueStore = SolanaKeyValueStore.InMemory()) {

    var lastFetchedEpochMs: Long? = null
        private set
    var entriesByMint: Map<String, Entry> = emptyMap()
        private set
    var refreshing: Boolean = false
        private set
    /** Last refresh's error message, or null if the most recent attempt
     *  succeeded (or hasn't happened yet). */
    var lastError: String? = null
        private set

    data class Entry(
        val address: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val logoURI: String?,
    )

    init { load() }

    fun find(mint: String): Entry? = entriesByMint[mint]

    /** Refresh only if the cache is older than [STALE_AFTER_MS]. */
    fun refreshIfStale(catalogURL: String, http: com.elabify.musnad.net.MaknoonHttp = com.elabify.musnad.net.MaknoonHttp()) {
        lastFetchedEpochMs?.let {
            if (System.currentTimeMillis() - it < STALE_AFTER_MS) return
        }
        refresh(catalogURL, http)
    }

    /** Fetch the catalog over HTTP, parse it, cache it. Blocking; call on
     *  a background dispatcher. */
    fun refresh(catalogURL: String, http: com.elabify.musnad.net.MaknoonHttp = com.elabify.musnad.net.MaknoonHttp()) {
        refreshing = true
        try {
            val body = http.getJson(catalogURL)
            val decoded = SolanaTokenListParser.parse(body) { it }
            val map = HashMap<String, Entry>(decoded.size)
            for (e in decoded) {
                map[e.address] = Entry(e.address, e.symbol, e.name, e.decimals, e.logoURI)
            }
            entriesByMint = map
            lastFetchedEpochMs = System.currentTimeMillis()
            lastError = null
            persist(body)
        } catch (e: Exception) {
            lastError = "Refresh failed: ${e.message}"
        } finally {
            refreshing = false
        }
    }

    fun clear() {
        entriesByMint = emptyMap()
        lastFetchedEpochMs = null
        lastError = null
        kv.remove(CACHE_KEY)
        kv.remove(LAST_FETCH_KEY)
    }

    // MARK: -- persistence

    private fun persist(body: String) {
        kv.putString(CACHE_KEY, body)
        lastFetchedEpochMs?.let { kv.putLong(LAST_FETCH_KEY, it) }
    }

    fun reload() {
        lastFetchedEpochMs = null
        entriesByMint = emptyMap()
        lastError = null
        load()
    }

    private fun load() {
        kv.getString(CACHE_KEY)?.let { body ->
            runCatching { SolanaTokenListParser.parse(body) { it } }.getOrNull()?.let { decoded ->
                val map = HashMap<String, Entry>(decoded.size)
                for (e in decoded) map[e.address] = Entry(e.address, e.symbol, e.name, e.decimals, e.logoURI)
                entriesByMint = map
            }
        }
        lastFetchedEpochMs = kv.getLong(LAST_FETCH_KEY)
    }

    companion object {
        /** CoinGecko's Solana TokenList. Standard schema, no API key.
         *  User-overridable in SolanaSettings (the prompt's "Jupiter
         *  strict list" intent is preserved as an alternative the user
         *  can point at). */
        const val DEFAULT_CATALOG_URL = "https://tokens.coingecko.com/solana/all.json"
        const val STALE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000
        private const val CACHE_KEY = "networks.solana.tokens.catalog.v1"
        private const val LAST_FETCH_KEY = "networks.solana.tokens.catalogFetch.v1"
    }
}
