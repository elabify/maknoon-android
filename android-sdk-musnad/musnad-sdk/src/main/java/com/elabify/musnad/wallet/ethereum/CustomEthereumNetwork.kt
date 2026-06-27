// User-defined EVM network + the EthereumNetworkID discriminator + the flat
// ResolvedNetwork value. 1:1 port of CustomEthereumNetwork.swift. Held
// separately from the EthereumNetwork enum so custom networks don't touch
// built-in code paths.

package com.elabify.musnad.wallet.ethereum
import com.elabify.musnad.util.optStringOrNull

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class CustomEthereumNetwork(
    val id: UUID = UUID.randomUUID(),
    var name: String,
    var chainId: Long,
    var ticker: String,
    var rpcURL: String,
    var explorerURL: String,
    var explorerAPIURL: String? = null,
    var explorerAPIKey: String? = null,
    var isTestnet: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id.toString())
        .put("name", name)
        .put("chainId", chainId)
        .put("ticker", ticker)
        .put("rpcURL", rpcURL)
        .put("explorerURL", explorerURL)
        .put("explorerAPIURL", explorerAPIURL ?: JSONObject.NULL)
        .put("explorerAPIKey", explorerAPIKey ?: JSONObject.NULL)
        .put("isTestnet", isTestnet)

    companion object {
        fun fromJson(o: JSONObject): CustomEthereumNetwork = CustomEthereumNetwork(
            id = UUID.fromString(o.getString("id")),
            name = o.getString("name"),
            chainId = o.getLong("chainId"),
            ticker = o.getString("ticker"),
            rpcURL = o.getString("rpcURL"),
            explorerURL = o.getString("explorerURL"),
            explorerAPIURL = if (o.isNull("explorerAPIURL")) null else o.optStringOrNull("explorerAPIURL"),
            explorerAPIKey = if (o.isNull("explorerAPIKey")) null else o.optStringOrNull("explorerAPIKey"),
            isTestnet = o.optBoolean("isTestnet", false),
        )
    }
}

/** Identifier pointing at either a built-in network case or a custom UUID. */
sealed interface EthereumNetworkID {
    data class Builtin(val network: EthereumNetwork) : EthereumNetworkID
    data class Custom(val id: UUID) : EthereumNetworkID

    /** Stable id: "builtin:<rawValue>" or "custom:<uuid>". */
    val stableId: String
        get() = when (this) {
            is Builtin -> "builtin:${network.rawValue}"
            is Custom -> "custom:$id"
        }

    companion object {
        fun decode(s: String): EthereumNetworkID? {
            if (s.startsWith("builtin:")) {
                val raw = s.removePrefix("builtin:")
                return EthereumNetwork.fromRawValue(raw)?.let { Builtin(it) }
            }
            if (s.startsWith("custom:")) {
                return try { Custom(UUID.fromString(s.removePrefix("custom:"))) } catch (_: Exception) { null }
            }
            return null
        }
    }
}

/** Pre-resolved, flat network properties consumed by RPC / explorer / signing. */
data class ResolvedNetwork(
    val networkID: EthereumNetworkID,
    val chainId: Long,
    val displayName: String,
    val ticker: String,
    val isTestnet: Boolean,
    val rpcURL: String,
    val explorerURL: String,
    val explorerAPIURL: String?,
    val explorerAPIKey: String?,
) {
    val isBuiltin: Boolean get() = networkID is EthereumNetworkID.Builtin

    val coinGeckoAssetId: String?
        get() {
            if (isTestnet) return null
            return (networkID as? EthereumNetworkID.Builtin)?.network?.coinGeckoAssetId
        }
}

/** User-defined custom EVM networks. Persists to `networks.ethereum.custom.v1`. */
class CustomNetworkStore(private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory()) {

    private val _networks = mutableListOf<CustomEthereumNetwork>()
    val networks: List<CustomEthereumNetwork> get() = _networks.toList()

    init { load() }

    fun add(network: CustomEthereumNetwork) {
        _networks.add(network)
        persist()
    }

    fun update(network: CustomEthereumNetwork) {
        val idx = _networks.indexOfFirst { it.id == network.id }
        if (idx < 0) return
        _networks[idx] = network
        persist()
    }

    fun remove(id: UUID) {
        _networks.removeAll { it.id == id }
        persist()
    }

    fun find(id: UUID): CustomEthereumNetwork? = _networks.firstOrNull { it.id == id }

    fun reload() {
        _networks.clear()
        load()
    }

    private fun load() {
        val raw = kv.getString(STORE_KEY) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            runCatching { CustomEthereumNetwork.fromJson(arr.getJSONObject(i)) }.getOrNull()
                ?.let { _networks.add(it) }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        _networks.forEach { arr.put(it.toJson()) }
        kv.putString(STORE_KEY, arr.toString())
    }

    companion object {
        private const val STORE_KEY = "networks.ethereum.custom.v1"
    }
}
