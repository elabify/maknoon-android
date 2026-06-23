// User's installed ERC-20 tokens, per network. Seeded with the curated catalog
// on first run; persists added/removed entries across launches. 1:1 port of
// EthereumTokenStore.swift. Persists to `networks.ethereum.tokens.v1`.

package com.elabify.musnad.wallet.ethereum

import org.json.JSONArray
import org.json.JSONObject

class EthereumTokenStore(private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory()) {

    val tokensByNetwork = HashMap<EthereumNetwork, MutableList<EthereumToken>>()
    private val seededNetworks = HashSet<EthereumNetwork>()

    init { load() }

    fun tokens(network: EthereumNetwork): List<EthereumToken> =
        tokensByNetwork[network]?.toList() ?: emptyList()

    fun tokens(resolved: ResolvedNetwork): List<EthereumToken> =
        when (val id = resolved.networkID) {
            is EthereumNetworkID.Builtin -> tokens(id.network)
            is EthereumNetworkID.Custom -> emptyList()
        }

    fun add(token: EthereumToken) {
        val current = tokensByNetwork.getOrPut(token.network) { mutableListOf() }
        val idx = current.indexOfFirst { it.contractAddress == token.contractAddress }
        if (idx >= 0) current[idx] = token else current.add(token)
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
        // Seed networks added after the app was first launched.
        for (n in EthereumNetwork.entries) {
            if (!seededNetworks.contains(n)) {
                EthereumTokenCatalog.defaults(n).forEach { add(it) }
                seededNetworks.add(n)
            }
        }
        persist()
    }

    private fun seedFirstRun() {
        for (n in EthereumNetwork.entries) {
            tokensByNetwork[n] = EthereumTokenCatalog.defaults(n).toMutableList()
            seededNetworks.add(n)
        }
        persist()
    }

    fun persist() {
        val tokensArr = JSONArray()
        tokensByNetwork.values.flatten().forEach { tokensArr.put(tokenToJson(it)) }
        val seededArr = JSONArray()
        seededNetworks.forEach { seededArr.put(it.rawValue) }
        val o = JSONObject().put("tokens", tokensArr).put("seededNetworks", seededArr)
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
