// User's installed ERC-20 tokens, per network. Seeded with the curated catalog
// on first run; persists added/removed entries across launches. 1:1 port of
// EthereumTokenStore.swift. Persists to `networks.ethereum.tokens.v1`.

package com.elabify.musnad.wallet.ethereum

import org.json.JSONArray
import org.json.JSONObject

class EthereumTokenStore(private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory()) {

    val tokensByNetwork = HashMap<EthereumNetwork, MutableList<EthereumToken>>()
    private val seededNetworks = HashSet<EthereumNetwork>()
    // Every curated catalog default ever seeded, keyed "network:contract". Lets
    // us union in tokens ADDED to the catalog after a network was first seeded
    // (e.g. Arbitrum USDC) exactly once, without resurrecting a token the user
    // later removed on every launch.
    private val seededContracts = HashSet<String>()

    init { load() }

    private fun seedKey(network: EthereumNetwork, contract: String): String =
        "${network.rawValue}:${contract.lowercase()}"

    /** Insert-or-replace a token without persisting (used during load reconcile). */
    private fun addInMemory(token: EthereumToken) {
        val current = tokensByNetwork.getOrPut(token.network) { mutableListOf() }
        val idx = current.indexOfFirst { it.contractAddress == token.contractAddress }
        if (idx >= 0) current[idx] = token else current.add(token)
    }

    fun tokens(network: EthereumNetwork): List<EthereumToken> =
        tokensByNetwork[network]?.toList() ?: emptyList()

    fun tokens(resolved: ResolvedNetwork): List<EthereumToken> =
        when (val id = resolved.networkID) {
            is EthereumNetworkID.Builtin -> tokens(id.network)
            is EthereumNetworkID.Custom -> emptyList()
        }

    fun add(token: EthereumToken) {
        addInMemory(token)
        persist()
    }

    fun remove(token: EthereumToken) {
        tokensByNetwork[token.network]?.removeAll { it.contractAddress == token.contractAddress }
        persist()
    }

    fun find(network: EthereumNetwork, contract: String): EthereumToken? {
        val needle = contract.lowercase()
        return tokensByNetwork[network]?.firstOrNull { it.contractAddress == needle }
    }

    fun reload() {
        tokensByNetwork.clear()
        seededNetworks.clear()
        seededContracts.clear()
        load()
    }

    // ---- persistence ----

    private fun load() {
        val raw = kv.getString(STORE_KEY)
        if (raw == null) {
            seedFirstRun()
            return
        }
        val o = runCatching { JSONObject(raw) }.getOrNull()
        if (o == null) {
            seedFirstRun()
            return
        }
        val arr = o.optJSONArray("tokens") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val tok = tokenFromJson(arr.optJSONObject(i) ?: continue) ?: continue
            tokensByNetwork.getOrPut(tok.network) { mutableListOf() }.add(tok)
        }
        val seeded = o.optJSONArray("seededNetworks") ?: JSONArray()
        for (i in 0 until seeded.length()) {
            EthereumNetwork.fromRawValue(seeded.optString(i))?.let { seededNetworks.add(it) }
        }
        val seededC = o.optJSONArray("seededContracts") ?: JSONArray()
        for (i in 0 until seededC.length()) seededContracts.add(seededC.optString(i))
        // Seed networks added after the app was first launched.
        for (n in EthereumNetwork.entries) {
            if (!seededNetworks.contains(n)) {
                EthereumTokenCatalog.defaults(n).forEach { addInMemory(it) }
                seededNetworks.add(n)
            }
        }
        // Reconcile: ensure every curated catalog default has been seeded at
        // least once. Adds tokens introduced to the catalog AFTER a network was
        // first seeded (e.g. Arbitrum USDC on an install that predates it), which
        // the seededNetworks gate alone would miss. Tracked per-contract so a
        // token the user removes later is not resurrected on the next launch.
        for (n in EthereumNetwork.entries) {
            for (def in EthereumTokenCatalog.defaults(n)) {
                if (seededContracts.add(seedKey(n, def.contractAddress))) addInMemory(def)
            }
        }
        persist()
    }

    private fun seedFirstRun() {
        for (n in EthereumNetwork.entries) {
            tokensByNetwork[n] = EthereumTokenCatalog.defaults(n).toMutableList()
            seededNetworks.add(n)
            EthereumTokenCatalog.defaults(n).forEach { seededContracts.add(seedKey(n, it.contractAddress)) }
        }
        persist()
    }

    fun persist() {
        val tokensArr = JSONArray()
        tokensByNetwork.values.flatten().forEach { tokensArr.put(tokenToJson(it)) }
        val seededArr = JSONArray()
        seededNetworks.forEach { seededArr.put(it.rawValue) }
        val seededContractsArr = JSONArray()
        seededContracts.forEach { seededContractsArr.put(it) }
        val o = JSONObject()
            .put("tokens", tokensArr)
            .put("seededNetworks", seededArr)
            .put("seededContracts", seededContractsArr)
        kv.putString(STORE_KEY, o.toString())
    }

    private fun tokenToJson(t: EthereumToken): JSONObject = JSONObject()
        .put("network", t.network.rawValue)
        .put("contractAddress", t.contractAddress)
        .put("symbol", t.symbol)
        .put("name", t.name)
        .put("decimals", t.decimals)
        .put("curated", t.curated)

    private fun tokenFromJson(o: JSONObject): EthereumToken? {
        val net = EthereumNetwork.fromRawValue(o.optString("network")) ?: return null
        return EthereumToken.create(
            network = net,
            contractAddress = o.optString("contractAddress"),
            symbol = o.optString("symbol"),
            name = o.optString("name"),
            decimals = o.optInt("decimals"),
            curated = o.optBoolean("curated", false),
        )
    }

    companion object {
        private const val STORE_KEY = "networks.ethereum.tokens.v1"
    }
}
