// Remote-backed verified token directory for Ethereum + EVM sidechains. 1:1
// port of EthereumTokenRegistry.swift. Fetched at runtime from a TokenList URL
// (Uniswap default, user-overridable), cached locally, refreshed weekly. Only
// verified contracts auto-install; everything else surfaces as "unknown".
//
// Network fetching goes through MaknoonHttp (OkHttp). Persists the raw catalog
// JSON to `networks.ethereum.tokens.catalog.v1` and the fetch time to
// `networks.ethereum.tokens.catalogFetch.v1`.

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.net.MaknoonHttp

class EthereumTokenRegistry(
    private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory(),
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    data class Entry(
        val contract: String, // lowercased 0x...
        val symbol: String,
        val name: String,
        val decimals: Int,
        val logoURI: String?,
    )

    var lastFetched: Long? = null
        private set
    /** Entries grouped by (network, lowercased contract). */
    val entries = HashMap<EthereumNetwork, HashMap<String, Entry>>()
    var refreshing: Boolean = false
        private set
    var lastError: String? = null
        private set

    init { load() }

    fun reload() {
        lastFetched = null
        entries.clear()
        lastError = null
        load()
    }

    val totalEntries: Int get() = entries.values.sumOf { it.size }

    /** Case-insensitive contract match within the network's catalog slice. */
    fun find(network: EthereumNetwork, contract: String): Entry? =
        entries[network]?.get(contract.lowercase())

    /** Refresh only if older than [STALE_AFTER_MS]. */
    fun refreshIfStale(catalogURL: String) {
        val last = lastFetched
        if (last != null && System.currentTimeMillis() - last < STALE_AFTER_MS) return
        refresh(catalogURL)
    }

    fun refresh(catalogURL: String) {
        refreshing = true
        try {
            val json = http.getJson(catalogURL)
            ingest(json)
            lastFetched = System.currentTimeMillis()
            lastError = null
            persist(json)
        } catch (e: Exception) {
            lastError = "Refresh failed: ${e.message}"
        } finally {
            refreshing = false
        }
    }

    fun clear() {
        entries.clear()
        lastFetched = null
        lastError = null
        kv.remove(CACHE_KEY)
        kv.remove(LAST_FETCH_KEY)
    }

    // ---- helpers ----

    private fun ingest(json: String) {
        val decoded = EthereumTokenListParser.parse(json) { it.lowercase() }
        entries.clear()
        for (token in decoded) {
            val cid = token.chainId ?: continue
            val net = EthereumNetwork.fromChainId(cid.toLong()) ?: continue
            entries.getOrPut(net) { HashMap() }[token.address] = Entry(
                contract = token.address,
                symbol = token.symbol,
                name = token.name,
                decimals = token.decimals,
                logoURI = token.logoURI,
            )
        }
    }

    private fun persist(json: String) {
        kv.putString(CACHE_KEY, json)
        lastFetched?.let { kv.putString(LAST_FETCH_KEY, it.toString()) }
    }

    private fun load() {
        kv.getString(CACHE_KEY)?.let { runCatching { ingest(it) } }
        lastFetched = kv.getString(LAST_FETCH_KEY)?.toLongOrNull()
    }

    companion object {
        const val DEFAULT_CATALOG_URL = "https://tokens.uniswap.org"
        const val STALE_AFTER_MS: Long = 7L * 24 * 60 * 60 * 1000
        private const val CACHE_KEY = "networks.ethereum.tokens.catalog.v1"
        private const val LAST_FETCH_KEY = "networks.ethereum.tokens.catalogFetch.v1"
    }
}
