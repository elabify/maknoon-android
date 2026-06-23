// User's installed SPL tokens per cluster plus the auto-discover
// pipeline, ported 1:1 from iOS SolanaSPLTokenStore.swift.
//
// Lifecycle:
//   1. On dashboard refresh, the WalletView passes the mints the wallet
//      currently holds (the `mint` field from getTokenAccountsByOwner).
//   2. Each mint is matched against SolanaTokenCatalog.
//   3. Verified hits auto-install under tokens(on:). Unverified mints
//      surface in unknownMints(on:) so the dashboard can offer
//      "Add as custom?" without spamming the token list.

package com.elabify.musnad.wallet.solana

import org.json.JSONArray
import org.json.JSONObject

class SolanaSPLTokenStore(private val kv: SolanaKeyValueStore = SolanaKeyValueStore.InMemory()) {

    /** Persisted: tokens the user (or auto-discover) has installed. */
    var allTokens: List<SolanaSPLToken> = emptyList()
        private set
    /** Transient (not persisted): mints held in the last refresh that
     *  didn't match the catalog. Reset every refresh. */
    var unknownMintsByNetwork: MutableMap<SolanaNetwork, List<String>> = mutableMapOf()
        private set
    /** Persisted: mints the user explicitly Ignored. */
    var ignoredMintsByNetwork: MutableMap<SolanaNetwork, MutableSet<String>> = mutableMapOf()
        private set

    init { load() }

    fun tokens(network: SolanaNetwork): List<SolanaSPLToken> =
        allTokens.filter { it.network == network }

    fun unknownMints(network: SolanaNetwork): List<String> =
        unknownMintsByNetwork[network] ?: emptyList()

    /** Auto-discover pass. Verified mints auto-install; unverified land
     *  in unknownMintsByNetwork for separate UI treatment. */
    fun reconcile(heldMints: List<String>, network: SolanaNetwork, catalog: SolanaTokenCatalog) {
        val unknown = ArrayList<String>()
        var changed = false
        val list = allTokens.toMutableList()
        for (mint in heldMints) {
            if (list.any { it.network == network && it.mint == mint }) continue
            val entry = catalog.find(mint)
            if (entry != null) {
                list.add(
                    SolanaSPLToken(
                        network = network,
                        mint = mint,
                        symbol = entry.symbol,
                        name = entry.name,
                        decimals = entry.decimals,
                        logoURI = entry.logoURI,
                        source = SolanaTokenSource.JUPITER,
                    )
                )
                changed = true
            } else if (ignoredMintsByNetwork[network]?.contains(mint) != true) {
                unknown.add(mint)
            }
        }
        unknownMintsByNetwork[network] = unknown
        if (changed) { allTokens = list; persist() }
    }

    /** Manually add a token. Idempotent against the (network, mint) id. */
    fun add(token: SolanaSPLToken) {
        if (allTokens.any { it.id == token.id }) return
        allTokens = allTokens + token
        unknownMintsByNetwork[token.network]?.let { unk ->
            unknownMintsByNetwork[token.network] = unk.filterNot { it == token.mint }
        }
        persist()
    }

    /** Remove an installed token (hides the row; doesn't touch chain). */
    fun remove(token: SolanaSPLToken) {
        allTokens = allTokens.filterNot { it.id == token.id }
        persist()
    }

    /** Drop an unknown mint from the dashboard banner without installing
     *  it; remembers the choice (persisted). */
    fun dismissUnknown(mint: String, network: SolanaNetwork) {
        unknownMintsByNetwork[network]?.let { unk ->
            unknownMintsByNetwork[network] = unk.filterNot { it == mint }
        }
        val ignored = ignoredMintsByNetwork.getOrPut(network) { mutableSetOf() }
        ignored.add(mint)
        persistIgnored()
    }

    // MARK: -- persistence

    private fun persist() {
        val arr = JSONArray()
        allTokens.forEach { arr.put(it.toJson()) }
        kv.putString(KEY, arr.toString())
    }

    private fun persistIgnored() {
        val o = JSONObject()
        ignoredMintsByNetwork.forEach { (net, mints) ->
            o.put(net.rawValue, JSONArray().apply { mints.forEach { put(it) } })
        }
        kv.putString(IGNORED_KEY, o.toString())
    }

    fun reload() {
        allTokens = emptyList()
        unknownMintsByNetwork = mutableMapOf()
        ignoredMintsByNetwork = mutableMapOf()
        load()
    }

    private fun load() {
        kv.getString(KEY)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { arr ->
                val out = ArrayList<SolanaSPLToken>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { SolanaSPLToken.fromJson(it)?.let(out::add) }
                }
                allTokens = out
            }
        }
        kv.getString(IGNORED_KEY)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { o ->
                val out = HashMap<SolanaNetwork, MutableSet<String>>()
                o.keys().forEach { key ->
                    SolanaNetwork.fromRawValue(key)?.let { net ->
                        val mints = o.getJSONArray(key)
                        val set = HashSet<String>(mints.length())
                        for (i in 0 until mints.length()) set.add(mints.getString(i))
                        out[net] = set
                    }
                }
                ignoredMintsByNetwork = out
            }
        }
    }

    companion object {
        private const val KEY = "networks.solana.tokens.installed.v1"
        private const val IGNORED_KEY = "networks.solana.tokens.ignored.v1"
    }
}
