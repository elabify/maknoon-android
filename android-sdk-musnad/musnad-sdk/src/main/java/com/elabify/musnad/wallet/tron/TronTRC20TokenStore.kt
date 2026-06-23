// Per-network installed TRC-20 tokens + the auto-discover pipeline,
// ported from iOS TronTRC20TokenStore.swift. No first-run seed, lookup
// against the remote catalog; unverified contracts surface separately
// so the dashboard can offer "Add as custom?" without spamming the list
// with airdrop tokens. Backed by SharedPreferences.

package com.elabify.musnad.wallet.tron

import android.content.SharedPreferences
import org.json.JSONArray

class TronTRC20TokenStore(private val prefs: SharedPreferences) {

    var allTokens: List<TronTRC20Token> = emptyList()
        private set

    /** Transient (not persisted): contracts the wallet held in its last
     *  refresh that didn't match the catalog. Reset every refresh so
     *  stale unknowns don't linger. */
    var unknownContractsByNetwork: Map<TronNetwork, List<String>> = emptyMap()
        private set

    init {
        load()
    }

    fun tokens(network: TronNetwork): List<TronTRC20Token> =
        allTokens.filter { it.network == network }

    fun unknownContracts(network: TronNetwork): List<String> =
        unknownContractsByNetwork[network] ?: emptyList()

    /** Auto-discover pass. The dashboard's refresh hands the list of
     *  TRC-20 contracts the wallet currently holds; we install verified
     *  ones from the catalog and surface unverified ones in the banner. */
    fun reconcile(
        heldContracts: List<String>,
        network: TronNetwork,
        catalog: TronTokenCatalog,
    ) {
        val unknown = ArrayList<String>()
        var changed = false
        val mutable = allTokens.toMutableList()
        for (contract in heldContracts) {
            if (mutable.any { it.network == network && it.contract == contract }) continue
            val entry = catalog.find(contract)
            if (entry != null) {
                mutable.add(
                    TronTRC20Token(
                        network = network,
                        contract = contract,
                        symbol = entry.symbol,
                        name = entry.name,
                        decimals = entry.decimals,
                        logoURI = entry.logoURI,
                        source = TronTokenSource.TRONSCAN,
                    )
                )
                changed = true
            } else {
                unknown.add(contract)
            }
        }
        unknownContractsByNetwork = unknownContractsByNetwork + (network to unknown)
        if (changed) {
            allTokens = mutable
            persist()
        }
    }

    fun add(token: TronTRC20Token) {
        if (allTokens.any { it.id == token.id }) return
        allTokens = allTokens + token
        unknownContractsByNetwork[token.network]?.let { unk ->
            unknownContractsByNetwork =
                unknownContractsByNetwork + (token.network to unk.filterNot { it == token.contract })
        }
        persist()
    }

    fun remove(token: TronTRC20Token) {
        allTokens = allTokens.filterNot { it.id == token.id }
        persist()
    }

    fun dismissUnknown(contract: String, network: TronNetwork) {
        unknownContractsByNetwork[network]?.let { unk ->
            unknownContractsByNetwork =
                unknownContractsByNetwork + (network to unk.filterNot { it == contract })
        }
    }

    // MARK: -- persistence

    private fun persist() {
        val arr = JSONArray()
        allTokens.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun reload() {
        allTokens = emptyList()
        unknownContractsByNetwork = emptyMap()
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        val arr = try { JSONArray(raw) } catch (e: Exception) { return }
        val list = ArrayList<TronTRC20Token>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { TronTRC20Token.fromJson(it)?.let(list::add) }
        }
        allTokens = list
    }

    companion object {
        private const val KEY = "networks.tron.tokens.installed.v1"
    }
}
