// Per-network RPC + explorer overrides for Ethereum-family chains. Defaults
// live on EthereumNetwork itself; this store only holds user overrides + the
// global fiat code, ENS RPC URL, token-catalog URL, and logo template. 1:1 port
// of EthereumSettings.swift. Persists to `networks.ethereum.settings.v1`.

package com.elabify.musnad.wallet.ethereum

import org.json.JSONObject

class EthereumSettings(private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory()) {

    val rpcURLByNetwork = HashMap<EthereumNetwork, String>()
    val explorerURLByNetwork = HashMap<EthereumNetwork, String>()
    val explorerAPIURLByNetwork = HashMap<EthereumNetwork, String>()
    val explorerAPIKeyByNetwork = HashMap<EthereumNetwork, String>()
    var fiatCode: String = "usd"
    /** JSON-RPC URL for ENS lookups; empty means "use the mainnet RPC". */
    var ensRPCURL: String = ""
    /** Verified-token catalog URL (Uniswap multi-chain default). */
    var tokenCatalogURL: String = EthereumTokenRegistry.DEFAULT_CATALOG_URL
        private set
    /** Token-logo URL template with {chain} + {address} placeholders. */
    var logoTemplate: String = DEFAULT_LOGO_TEMPLATE
        private set
    /**
     * Optional self-hosted WalletConnect relay host (EVM-only today, but a
     * single relay across all networks). Empty uses the built-in default relay.
     * Host only (e.g. relay.example.com); takes effect on next app launch.
     */
    var walletConnectRelayHost: String = ""
        private set

    init { load() }

    fun rpcURL(network: EthereumNetwork): String {
        val override = rpcURLByNetwork[network]
        if (override != null && isValidRPC(override)) return override
        return network.defaultRPCURL
    }

    fun explorerURL(network: EthereumNetwork): String {
        val override = explorerURLByNetwork[network]
        if (!override.isNullOrEmpty()) return override
        return network.defaultExplorerURL
    }

    fun explorerAPIURL(network: EthereumNetwork): String? {
        val override = explorerAPIURLByNetwork[network]
        if (!override.isNullOrEmpty()) return override
        return network.defaultExplorerAPIURL
    }

    fun explorerAPIKey(network: EthereumNetwork): String? {
        val key = explorerAPIKeyByNetwork[network] ?: ""
        return key.ifEmpty { null }
    }

    fun explorerAddressURL(address: String, network: EthereumNetwork): String {
        val base = explorerURL(network).trim('/')
        return "$base/address/$address"
    }

    fun explorerTxURL(txHash: String, network: EthereumNetwork): String {
        val base = explorerURL(network).trim('/')
        return "$base/tx/$txHash"
    }

    fun setRPC(url: String, network: EthereumNetwork) {
        val trimmed = url.trim()
        rpcURLByNetwork[network] = if (isValidRPC(trimmed)) trimmed else ""
        persist()
    }

    fun setExplorer(url: String, network: EthereumNetwork) {
        explorerURLByNetwork[network] = url
        persist()
    }

    fun setExplorerAPI(url: String, key: String, network: EthereumNetwork) {
        explorerAPIURLByNetwork[network] = url
        explorerAPIKeyByNetwork[network] = key
        persist()
    }

    fun setTokenCatalogURL(url: String) {
        val trimmed = url.trim()
        tokenCatalogURL = if (trimmed.isEmpty()) EthereumTokenRegistry.DEFAULT_CATALOG_URL else trimmed
        persist()
    }

    fun setLogoTemplate(url: String) {
        val trimmed = url.trim()
        logoTemplate = if (trimmed.isEmpty()) DEFAULT_LOGO_TEMPLATE else trimmed
        persist()
    }

    /** Host only: strips any scheme (wss://, https://) and trailing slashes. */
    fun setWalletConnectRelayHost(value: String) {
        var h = value.trim()
        val idx = h.indexOf("://")
        if (idx >= 0) h = h.substring(idx + 3)
        h = h.trim('/')
        walletConnectRelayHost = h
        persist()
    }

    fun resetToDefaults() {
        rpcURLByNetwork.clear()
        explorerURLByNetwork.clear()
        explorerAPIURLByNetwork.clear()
        explorerAPIKeyByNetwork.clear()
        fiatCode = "usd"
        ensRPCURL = ""
        tokenCatalogURL = EthereumTokenRegistry.DEFAULT_CATALOG_URL
        walletConnectRelayHost = ""
        persist()
    }

    /** Resolved URL the ENS resolver should hit (ENS is mainnet-only). */
    fun effectiveENSRPCURL(): String {
        val override = ensRPCURL.trim()
        if (override.isNotEmpty()) return override
        return rpcURL(EthereumNetwork.MAINNET)
    }

    /** Per-token logo URL; null when the network has no Trust Wallet slug. */
    fun tokenLogoURL(network: EthereumNetwork, contract: String): String? {
        val slug = network.trustWalletSlug ?: return null
        val checksummed = EIP55.checksum(contract)
        return logoTemplate
            .replace("{chain}", slug)
            .replace("{address}", checksummed)
    }

    fun reload() {
        rpcURLByNetwork.clear()
        explorerURLByNetwork.clear()
        explorerAPIURLByNetwork.clear()
        explorerAPIKeyByNetwork.clear()
        fiatCode = "usd"
        ensRPCURL = ""
        tokenCatalogURL = EthereumTokenRegistry.DEFAULT_CATALOG_URL
        logoTemplate = DEFAULT_LOGO_TEMPLATE
        walletConnectRelayHost = ""
        load()
    }

    // ---- persistence ----

    private fun load() {
        val raw = kv.getString(STORE_KEY) ?: return
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return
        readMap(o, "rpcURLByNetwork", rpcURLByNetwork)
        readMap(o, "explorerURLByNetwork", explorerURLByNetwork)
        readMap(o, "explorerAPIURLByNetwork", explorerAPIURLByNetwork)
        readMap(o, "explorerAPIKeyByNetwork", explorerAPIKeyByNetwork)
        fiatCode = o.optString("fiatCode", "usd").ifEmpty { "usd" }
        ensRPCURL = o.optString("ensRPCURL", "")
        o.optString("tokenCatalogURL", "").takeIf { it.isNotEmpty() }?.let { tokenCatalogURL = it }
        o.optString("logoTemplate", "").takeIf { it.isNotEmpty() }?.let { logoTemplate = it }
        walletConnectRelayHost = o.optString("walletConnectRelayHost", "")

        // Retire stale Etherscan v1/v2 hosts so the Blockscout defaults take over.
        var changed = false
        val toRemove = explorerAPIURLByNetwork.filter { (_, url) ->
            val host = runCatching { java.net.URI(url).host }.getOrNull()
            host != null && STALE_HOSTS.contains(host)
        }.keys.toList()
        for (net in toRemove) {
            explorerAPIURLByNetwork.remove(net)
            changed = true
        }
        if (changed) persist()
    }

    fun persist() {
        val o = JSONObject()
            .put("rpcURLByNetwork", writeMap(rpcURLByNetwork))
            .put("explorerURLByNetwork", writeMap(explorerURLByNetwork))
            .put("explorerAPIURLByNetwork", writeMap(explorerAPIURLByNetwork))
            .put("explorerAPIKeyByNetwork", writeMap(explorerAPIKeyByNetwork))
            .put("fiatCode", fiatCode)
            .put("ensRPCURL", ensRPCURL)
            .put("tokenCatalogURL", tokenCatalogURL)
            .put("logoTemplate", logoTemplate)
            .put("walletConnectRelayHost", walletConnectRelayHost)
        kv.putString(STORE_KEY, o.toString())
    }

    private fun readMap(o: JSONObject, key: String, into: HashMap<EthereumNetwork, String>) {
        val m = o.optJSONObject(key) ?: return
        m.keys().forEach { k ->
            EthereumNetwork.fromRawValue(k)?.let { into[it] = m.getString(k) }
        }
    }

    private fun writeMap(map: Map<EthereumNetwork, String>): JSONObject {
        val o = JSONObject()
        map.forEach { (net, v) -> o.put(net.rawValue, v) }
        return o
    }

    companion object {
        const val DEFAULT_LOGO_TEMPLATE =
            "https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/{chain}/assets/{address}/logo.png"
        private const val STORE_KEY = "networks.ethereum.settings.v1"

        private val STALE_HOSTS = setOf(
            "api.etherscan.io", "api-sepolia.etherscan.io",
            "api.arbiscan.io", "api-sepolia.arbiscan.io",
            "api-optimistic.etherscan.io", "api-sepolia-optimism.etherscan.io",
            "api.basescan.org", "api-sepolia.basescan.org",
            "api.polygonscan.com", "api-zkevm.polygonscan.com",
            "api.bscscan.com",
            "api.scrollscan.com", "api.lineascan.build",
            "api.mantlescan.xyz",
        )

        /** Mirrors EthereumSettings.isValidRPC: absolute http(s) URL with host. */
        fun isValidRPC(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            return !uri.host.isNullOrEmpty()
        }
    }
}
