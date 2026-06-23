// Per-user Bitcoin backend configuration. Persists JSON to a
// BitcoinKeyValueStore (iOS: UserDefaults). Drives ElectrumClient,
// BitcoinFeeEstimator, and BitcoinPriceCache. Ported 1:1 from iOS
// BitcoinSettings.swift, including the per-network Electrum / mempool /
// explorer overrides and the CoinGecko base + fiat code.

package com.elabify.musnad.wallet.bitcoin

import org.json.JSONObject

class BitcoinSettings(private val store: BitcoinKeyValueStore = InMemoryKeyValueStore()) {

    /** Per-network Electrum server config. Default is the public
     *  Blockstream endpoint defined in `BitcoinNetwork.defaultElectrumURL`. */
    data class ElectrumConfig(val url: String, val pinnedCertSHA256: String) {
        companion object {
            val empty = ElectrumConfig("", "")
        }
    }

    private val electrumByNetwork = HashMap<BitcoinNetwork, ElectrumConfig>()
    private val mempoolURLByNetwork = HashMap<BitcoinNetwork, String>()
    private val explorerURLByNetwork = HashMap<BitcoinNetwork, String>()
    var coinGeckoBaseURL: String = "https://api.coingecko.com/api/v3"
    var fiatCode: String = "usd"

    init {
        load()
    }

    /** Resolve the Electrum URL: user override if set, else public default. */
    fun electrumURL(network: BitcoinNetwork): String {
        val cfg = electrumByNetwork[network] ?: ElectrumConfig.empty
        return if (cfg.url.isEmpty()) network.defaultElectrumURL else cfg.url
    }

    fun mempoolURL(network: BitcoinNetwork): String =
        mempoolURLByNetwork[network] ?: network.defaultMempoolURL

    /** HTML explorer base URL. Falls back to the configured mempool URL
     *  (mempool.space serves both the JSON API and HTML at the same host),
     *  then the public default. */
    fun explorerURL(network: BitcoinNetwork): String {
        val override = explorerURLByNetwork[network]
        if (!override.isNullOrEmpty()) return override
        return mempoolURL(network)
    }

    /** `<base>/address/<bech32>` on the configured explorer. */
    fun explorerAddressURL(address: String, network: BitcoinNetwork): String {
        val base = explorerURL(network).trim('/')
        return "$base/address/$address"
    }

    /** `<base>/tx/<txid>` on the configured explorer. */
    fun explorerTxURL(txid: String, network: BitcoinNetwork): String {
        val base = explorerURL(network).trim('/')
        return "$base/tx/$txid"
    }

    fun setElectrum(cfg: ElectrumConfig, network: BitcoinNetwork) {
        electrumByNetwork[network] = cfg
        persist()
    }

    fun setMempool(url: String, network: BitcoinNetwork) {
        mempoolURLByNetwork[network] = url
        persist()
    }

    fun setExplorerURL(url: String, network: BitcoinNetwork) {
        explorerURLByNetwork[network] = url
        persist()
    }

    fun resetToDefaults() {
        electrumByNetwork.clear()
        mempoolURLByNetwork.clear()
        explorerURLByNetwork.clear()
        coinGeckoBaseURL = "https://api.coingecko.com/api/v3"
        fiatCode = "usd"
        persist()
    }

    /** Reset to defaults then re-read storage (post-restore refresh). */
    fun reload() {
        resetToDefaults()
        load()
    }

    // MARK: -- persistence

    private fun load() {
        val raw = store.getString(STORE_KEY) ?: return
        val snap = JSONObject(raw)

        snap.optJSONObject("electrumByNetwork")?.let { obj ->
            for (k in obj.keys()) {
                val net = BitcoinNetwork.fromRawValue(k) ?: continue
                val cfg = obj.getJSONObject(k)
                electrumByNetwork[net] = ElectrumConfig(
                    url = cfg.optString("url", ""),
                    pinnedCertSHA256 = cfg.optString("pinnedCertSHA256", ""),
                )
            }
        }
        snap.optJSONObject("mempoolURLByNetwork")?.let { obj ->
            for (k in obj.keys()) {
                val net = BitcoinNetwork.fromRawValue(k) ?: continue
                mempoolURLByNetwork[net] = obj.getString(k)
            }
        }
        snap.optJSONObject("explorerURLByNetwork")?.let { obj ->
            for (k in obj.keys()) {
                val net = BitcoinNetwork.fromRawValue(k) ?: continue
                explorerURLByNetwork[net] = obj.getString(k)
            }
        }
        coinGeckoBaseURL = snap.optString("coinGeckoBaseURL", coinGeckoBaseURL)
        fiatCode = snap.optString("fiatCode", fiatCode)
    }

    fun persist() {
        val electrum = JSONObject()
        electrumByNetwork.forEach { (net, cfg) ->
            electrum.put(
                net.rawValue,
                JSONObject().put("url", cfg.url).put("pinnedCertSHA256", cfg.pinnedCertSHA256),
            )
        }
        val mempool = JSONObject()
        mempoolURLByNetwork.forEach { (net, url) -> mempool.put(net.rawValue, url) }
        val explorer = JSONObject()
        explorerURLByNetwork.forEach { (net, url) -> explorer.put(net.rawValue, url) }

        val snap = JSONObject()
            .put("electrumByNetwork", electrum)
            .put("mempoolURLByNetwork", mempool)
            .put("explorerURLByNetwork", explorer)
            .put("coinGeckoBaseURL", coinGeckoBaseURL)
            .put("fiatCode", fiatCode)
        store.putString(STORE_KEY, snap.toString())
    }

    companion object {
        // Persistence root under "networks.bitcoin.*", matching iOS.
        private const val STORE_KEY = "networks.bitcoin.settings.v1"
    }
}
