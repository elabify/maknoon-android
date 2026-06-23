// Solana per-network settings: RPC endpoint override, explorer URL
// override, selected network, token-catalog URL, and logo base URL.
// Ported 1:1 from iOS SolanaSettings.swift. Persists to the KV store
// under `networks.solana.settings.v1` (the iOS UserDefaults key).

package com.elabify.musnad.wallet.solana

import org.json.JSONObject

class SolanaSettings(private val kv: SolanaKeyValueStore = SolanaKeyValueStore.InMemory()) {

    /** Per-network overrides keyed by network rawValue. Absent = the
     *  network's built-in default URL. */
    var rpcOverridesByNetwork: MutableMap<String, String> = mutableMapOf()
        private set
    var explorerOverridesByNetwork: MutableMap<String, String> = mutableMapOf()
        private set
    /** Network the dashboard + send/receive default to. */
    var selectedNetwork: SolanaNetwork = SolanaNetwork.MAINNET
    /** Verified-token catalog URL. Defaults to CoinGecko's Solana list. */
    var tokenCatalogURL: String = SolanaTokenCatalog.DEFAULT_CATALOG_URL
        private set
    /** Base URL for token logos: `{logoBaseURL}/{mint}/logo.png`. */
    var logoBaseURL: String = DEFAULT_LOGO_BASE_URL
        private set

    init { load() }

    fun rpcURL(network: SolanaNetwork): String =
        rpcOverridesByNetwork[network.rawValue] ?: network.defaultRpcURL

    fun explorerURL(network: SolanaNetwork): String =
        explorerOverridesByNetwork[network.rawValue] ?: network.defaultExplorerURL

    fun setRPCOverride(url: String?, network: SolanaNetwork) {
        val trimmed = url?.trim()
        if (!trimmed.isNullOrEmpty()) rpcOverridesByNetwork[network.rawValue] = trimmed
        else rpcOverridesByNetwork.remove(network.rawValue)
        persist()
    }

    fun setExplorerOverride(url: String?, network: SolanaNetwork) {
        val trimmed = url?.trim()
        if (!trimmed.isNullOrEmpty()) explorerOverridesByNetwork[network.rawValue] = trimmed
        else explorerOverridesByNetwork.remove(network.rawValue)
        persist()
    }

    fun setTokenCatalogURL(url: String) {
        val trimmed = url.trim()
        tokenCatalogURL = if (trimmed.isEmpty()) SolanaTokenCatalog.DEFAULT_CATALOG_URL else trimmed
        persist()
    }

    fun setLogoBaseURL(url: String) {
        val trimmed = url.trim()
        logoBaseURL = if (trimmed.isEmpty()) DEFAULT_LOGO_BASE_URL else trimmed
        persist()
    }

    /** Per-token logo URL. Returns null when the base or mint is empty. */
    fun tokenLogoURL(mint: String): String? {
        val base = logoBaseURL.trim().trim('/', ' ')
        val trimmed = mint.trim()
        if (base.isEmpty() || trimmed.isEmpty()) return null
        return "$base/$trimmed/logo.png"
    }

    // MARK: -- persistence

    private fun persist() {
        val rpc = JSONObject()
        rpcOverridesByNetwork.forEach { (k, v) -> rpc.put(k, v) }
        val exp = JSONObject()
        explorerOverridesByNetwork.forEach { (k, v) -> exp.put(k, v) }
        val p = JSONObject()
            .put("rpcOverrides", rpc)
            .put("explorerOverrides", exp)
            .put("selectedNetwork", selectedNetwork.rawValue)
            .put("tokenCatalogURL", tokenCatalogURL)
            .put("logoBaseURL", logoBaseURL)
        kv.putString(KEY, p.toString())
    }

    fun reload() {
        rpcOverridesByNetwork = mutableMapOf()
        explorerOverridesByNetwork = mutableMapOf()
        selectedNetwork = SolanaNetwork.MAINNET
        tokenCatalogURL = SolanaTokenCatalog.DEFAULT_CATALOG_URL
        logoBaseURL = DEFAULT_LOGO_BASE_URL
        load()
    }

    private fun load() {
        val raw = kv.getString(KEY) ?: return
        val p = runCatching { JSONObject(raw) }.getOrNull() ?: return
        p.optJSONObject("rpcOverrides")?.let { o ->
            o.keys().forEach { k -> rpcOverridesByNetwork[k] = o.getString(k) }
        }
        p.optJSONObject("explorerOverrides")?.let { o ->
            o.keys().forEach { k -> explorerOverridesByNetwork[k] = o.getString(k) }
        }
        SolanaNetwork.fromRawValue(p.optString("selectedNetwork"))?.let { selectedNetwork = it }
        p.optString("tokenCatalogURL").takeIf { it.isNotEmpty() }?.let { tokenCatalogURL = it }
        p.optString("logoBaseURL").takeIf { it.isNotEmpty() }?.let { logoBaseURL = it }
    }

    companion object {
        const val DEFAULT_LOGO_BASE_URL =
            "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/solana/assets"
        private const val KEY = "networks.solana.settings.v1"
    }
}
