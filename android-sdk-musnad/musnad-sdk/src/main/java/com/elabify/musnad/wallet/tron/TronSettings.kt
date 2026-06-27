// Tron per-network backend settings: TronGrid endpoint override,
// block-explorer override, the TRC-20 catalog URL, and the logo base
// URL. Ported 1:1 from iOS TronSettings.swift. Backed by
// SharedPreferences under the same key the iOS build uses for its
// UserDefaults blob.

package com.elabify.musnad.wallet.tron
import com.elabify.musnad.util.optStringOrNull

import android.content.SharedPreferences
import org.json.JSONObject

class TronSettings(private val prefs: SharedPreferences) {

    var rpcOverridesByNetwork: MutableMap<String, String> = mutableMapOf()
        private set
    var explorerOverridesByNetwork: MutableMap<String, String> = mutableMapOf()
        private set
    var selectedNetwork: TronNetwork = TronNetwork.MAINNET

    /** TRC-20 verified-token catalog URL. Defaults to CoinGecko's Tron
     *  TokenList. User-overridable for a self-hosted mirror or a frozen
     *  snapshot. */
    var tokenCatalogURL: String = TronTokenCatalog.DEFAULT_CATALOG_URL
        private set

    /** Base URL used to render token logos. Full URL is
     *  `{logoBaseURL}/{contract}/logo.png`. Default is Trust Wallet's
     *  assets repo. */
    var logoBaseURL: String = DEFAULT_LOGO_BASE_URL
        private set

    init {
        load()
    }

    fun rpcURL(network: TronNetwork): String =
        rpcOverridesByNetwork[network.rawValue] ?: network.defaultRpcURL

    fun explorerURL(network: TronNetwork): String =
        explorerOverridesByNetwork[network.rawValue] ?: network.defaultExplorerURL

    fun setRPCOverride(url: String?, network: TronNetwork) {
        val trimmed = url?.trim()
        if (!trimmed.isNullOrEmpty()) rpcOverridesByNetwork[network.rawValue] = trimmed
        else rpcOverridesByNetwork.remove(network.rawValue)
        persist()
    }

    fun setExplorerOverride(url: String?, network: TronNetwork) {
        val trimmed = url?.trim()
        if (!trimmed.isNullOrEmpty()) explorerOverridesByNetwork[network.rawValue] = trimmed
        else explorerOverridesByNetwork.remove(network.rawValue)
        persist()
    }

    fun setTokenCatalogURL(url: String) {
        val trimmed = url.trim()
        tokenCatalogURL = if (trimmed.isEmpty()) TronTokenCatalog.DEFAULT_CATALOG_URL else trimmed
        persist()
    }

    fun setLogoBaseURL(url: String) {
        val trimmed = url.trim()
        logoBaseURL = if (trimmed.isEmpty()) DEFAULT_LOGO_BASE_URL else trimmed
        persist()
    }

    /** Per-token logo URL. Returns null when the base or contract is
     *  empty; the row renders the monogram fallback in that case. */
    fun tokenLogoURL(contract: String): String? {
        val base = logoBaseURL.trim().trim('/')
        val trimmed = contract.trim()
        if (base.isEmpty() || trimmed.isEmpty()) return null
        return "$base/$trimmed/logo.png"
    }

    // MARK: -- persistence

    private fun persist() {
        val o = JSONObject()
        o.put("rpcOverrides", JSONObject(rpcOverridesByNetwork as Map<*, *>))
        o.put("explorerOverrides", JSONObject(explorerOverridesByNetwork as Map<*, *>))
        o.put("selectedNetwork", selectedNetwork.rawValue)
        o.put("tokenCatalogURL", tokenCatalogURL)
        o.put("logoBaseURL", logoBaseURL)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    fun reload() {
        rpcOverridesByNetwork = mutableMapOf()
        explorerOverridesByNetwork = mutableMapOf()
        selectedNetwork = TronNetwork.MAINNET
        tokenCatalogURL = TronTokenCatalog.DEFAULT_CATALOG_URL
        logoBaseURL = DEFAULT_LOGO_BASE_URL
        load()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        val o = try { JSONObject(raw) } catch (e: Exception) { return }
        o.optJSONObject("rpcOverrides")?.let { rpcOverridesByNetwork = it.toStringMap() }
        o.optJSONObject("explorerOverrides")?.let { explorerOverridesByNetwork = it.toStringMap() }
        o.optStringOrNull("selectedNetwork")?.let { sel ->
            TronNetwork.fromRawValue(sel)?.let { selectedNetwork = it }
        }
        o.optString("tokenCatalogURL", "").takeIf { it.isNotEmpty() }?.let { tokenCatalogURL = it }
        o.optString("logoBaseURL", "").takeIf { it.isNotEmpty() }?.let { logoBaseURL = it }
    }

    private fun JSONObject.toStringMap(): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        for (k in keys()) out[k] = getString(k)
        return out
    }

    companion object {
        const val DEFAULT_LOGO_BASE_URL =
            "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/tron/assets"
        private const val KEY = "networks.tron.settings.v1"
    }
}
