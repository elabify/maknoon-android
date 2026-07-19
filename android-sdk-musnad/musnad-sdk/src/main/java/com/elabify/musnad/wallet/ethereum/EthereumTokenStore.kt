// User's ERC-20 tokens. Two tiers (ADR-0060), 1:1 port of EthereumTokenStore.swift:
//
//   * Curated catalog defaults (USDC/USDT/...) are seeded per network and shared
//     across every wallet on that chain. Well-known assets are the same for
//     everyone, so a chain-wide list is correct (and matches MetaMask). These
//     live in `tokensByNetwork`, keyed by network only. Persists to
//     `networks.ethereum.tokens.v1`.
//
//   * Tokens a user ADDS ("Add custom token") or a wallet DISCOVERS (auto-
//     discovery from its own transfer history) belong to a single wallet on a
//     single chain, NOT every wallet on the network. These live in `userTokens`,
//     keyed by (wallet UUID, network). Persists to `networks.ethereum.userTokens.v2`.
//
// Earlier builds stored everything network-wide, so a custom token added to one
// wallet leaked into all wallets on that chain. Existing entries stay in
// `tokensByNetwork` (still visible everywhere, so no data is lost on upgrade);
// only NEW adds/discoveries are wallet-scoped. See ADR-0060.

package com.elabify.musnad.wallet.ethereum

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class EthereumTokenStore(private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory()) {

    /** Curated catalog defaults, chain-wide (shared across wallets). */
    val tokensByNetwork = HashMap<EthereumNetwork, MutableList<EthereumToken>>()

    /**
     * Runtime-added tokens (custom + auto-discovered), scoped per (wallet, chain).
     * Keyed "<walletUUID>:<network.rawValue>".
     */
    private val userTokens = HashMap<String, MutableList<EthereumToken>>()

    private val seededNetworks = HashSet<EthereumNetwork>()
    // Every curated catalog default ever seeded, keyed "network:contract". Lets
    // us union in tokens ADDED to the catalog after a network was first seeded
    // (e.g. Arbitrum USDC) exactly once, without resurrecting a token the user
    // later removed on every launch.
    private val seededContracts = HashSet<String>()

    init { load() }

    private fun seedKey(network: EthereumNetwork, contract: String): String =
        "${network.rawValue}:${contract.lowercase()}"

    private fun walletKey(walletId: UUID, network: EthereumNetwork): String =
        "$walletId:${network.rawValue}"

    /** Insert-or-replace a token without persisting (used during load reconcile). */
    private fun addInMemory(token: EthereumToken) {
        val current = tokensByNetwork.getOrPut(token.network) { mutableListOf() }
        val idx = current.indexOfFirst { it.contractAddress == token.contractAddress }
        if (idx >= 0) current[idx] = token else current.add(token)
    }

    /**
     * Chain-wide curated/seeded defaults for a network (no wallet scope). Used
     * for generic asset catalogs (e.g. the mini-app asset lister), which list
     * well-known tokens rather than one wallet's holdings.
     */
    fun tokens(network: EthereumNetwork): List<EthereumToken> =
        tokensByNetwork[network]?.toList() ?: emptyList()

    /** Chain-wide overload accepting a resolved network. */
    fun tokens(resolved: ResolvedNetwork): List<EthereumToken> =
        when (val id = resolved.networkID) {
            is EthereumNetworkID.Builtin -> tokens(id.network)
            is EthereumNetworkID.Custom -> emptyList()
        }

    /**
     * Tokens visible to ONE wallet on a chain: the curated chain-wide defaults
     * plus that wallet's own added/discovered tokens, deduped by contract.
     */
    fun tokens(network: EthereumNetwork, walletId: UUID): List<EthereumToken> {
        val curated = tokensByNetwork[network] ?: emptyList()
        val extra = userTokens[walletKey(walletId, network)] ?: emptyList()
        val seen = HashSet(curated.map { it.contractAddress })
        val out = ArrayList(curated)
        for (t in extra) if (seen.add(t.contractAddress)) out.add(t)
        return out
    }

    /** Wallet-scoped overload accepting a resolved network. */
    fun tokens(resolved: ResolvedNetwork, walletId: UUID): List<EthereumToken> =
        when (val id = resolved.networkID) {
            is EthereumNetworkID.Builtin -> tokens(id.network, walletId)
            is EthereumNetworkID.Custom -> emptyList()
        }

    /** Insert-or-replace a curated default chain-wide (seed/reconcile only). */
    fun add(token: EthereumToken) {
        addInMemory(token)
        persist()
    }

    /**
     * Add or update a token for a specific wallet on its chain. Used by the "Add
     * custom token" flow and auto-discovery; the token is scoped to this wallet
     * and does not appear in the user's other wallets (ADR-0060).
     */
    fun add(token: EthereumToken, walletId: UUID) {
        val key = walletKey(walletId, token.network)
        val current = userTokens.getOrPut(key) { mutableListOf() }
        val idx = current.indexOfFirst { it.contractAddress == token.contractAddress }
        if (idx >= 0) current[idx] = token else current.add(token)
        persistUserTokens()
    }

    /** Remove a token chain-wide (curated/legacy). */
    fun remove(token: EthereumToken) {
        tokensByNetwork[token.network]?.removeAll { it.contractAddress == token.contractAddress }
        persist()
    }

    /**
     * Remove a token from a wallet. A wallet-scoped (custom/discovered) token is
     * removed from just that wallet; a curated/legacy chain-wide token is removed
     * chain-wide (its prior behavior), so old leaked entries can still be cleared
     * in one action.
     */
    fun remove(token: EthereumToken, walletId: UUID) {
        val key = walletKey(walletId, token.network)
        val scoped = userTokens[key]
        if (scoped != null && scoped.any { it.contractAddress == token.contractAddress }) {
            scoped.removeAll { it.contractAddress == token.contractAddress }
            persistUserTokens()
            return
        }
        tokensByNetwork[token.network]?.removeAll { it.contractAddress == token.contractAddress }
        persist()
    }

    /** Chain-wide lookup by network + contract (case-insensitive). */
    fun find(network: EthereumNetwork, contract: String): EthereumToken? {
        val needle = contract.lowercase()
        return tokensByNetwork[network]?.firstOrNull { it.contractAddress == needle }
    }

    /**
     * Lookup by network + contract for one wallet (case-insensitive): the
     * wallet's own tokens first, then the chain-wide curated defaults. Lets
     * auto-discovery skip a contract this wallet already has.
     */
    fun find(network: EthereumNetwork, contract: String, walletId: UUID): EthereumToken? {
        val needle = contract.lowercase()
        userTokens[walletKey(walletId, network)]?.firstOrNull { it.contractAddress == needle }?.let { return it }
        return tokensByNetwork[network]?.firstOrNull { it.contractAddress == needle }
    }

    fun reload() {
        tokensByNetwork.clear()
        userTokens.clear()
        seededNetworks.clear()
        seededContracts.clear()
        load()
    }

    // ---- persistence ----

    private fun load() {
        loadUserTokens()
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

    private fun loadUserTokens() {
        val raw = kv.getString(USER_STORE_KEY) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        for (key in o.keys()) {
            val arr = o.optJSONArray(key) ?: continue
            val list = mutableListOf<EthereumToken>()
            for (i in 0 until arr.length()) {
                tokenFromJson(arr.optJSONObject(i) ?: continue)?.let { list.add(it) }
            }
            if (list.isNotEmpty()) userTokens[key] = list
        }
    }

    private fun persistUserTokens() {
        val o = JSONObject()
        for ((key, list) in userTokens) {
            val arr = JSONArray()
            list.forEach { arr.put(tokenToJson(it)) }
            o.put(key, arr)
        }
        kv.putString(USER_STORE_KEY, o.toString())
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
        private const val USER_STORE_KEY = "networks.ethereum.userTokens.v2"
    }
}
