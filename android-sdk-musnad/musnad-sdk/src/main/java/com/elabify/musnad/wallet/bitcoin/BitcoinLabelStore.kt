// Sparrow-style label store: a user-editable label per address and per
// transaction output (txid:vout). Backed by a BitcoinKeyValueStore (iOS:
// UserDefaults). Ported 1:1 from iOS BitcoinLabelStore.swift.

package com.elabify.musnad.wallet.bitcoin

import org.json.JSONObject

class BitcoinLabelStore(private val store: BitcoinKeyValueStore = InMemoryKeyValueStore()) {

    private val addressLabels = HashMap<String, String>()
    private val outputLabels = HashMap<String, String>() // key = "txid:vout"

    init {
        load()
    }

    private fun load() {
        addressLabels.clear()
        outputLabels.clear()
        store.getString(ADDRESS_KEY)?.let { raw ->
            val o = JSONObject(raw)
            for (k in o.keys()) addressLabels[k] = o.getString(k)
        }
        store.getString(OUTPUT_KEY)?.let { raw ->
            val o = JSONObject(raw)
            for (k in o.keys()) outputLabels[k] = o.getString(k)
        }
    }

    /** Re-read from storage after a backup restore. */
    fun reload() = load()

    fun labelForAddress(addr: String): String? = addressLabels[addr]

    fun labelForOutput(txid: String, vout: Long): String? = outputLabels["$txid:$vout"]

    fun setLabelForAddress(label: String, addr: String) {
        addressLabels[addr] = label
        store.putString(ADDRESS_KEY, encode(addressLabels))
    }

    fun setLabelForOutput(label: String, txid: String, vout: Long) {
        outputLabels["$txid:$vout"] = label
        store.putString(OUTPUT_KEY, encode(outputLabels))
    }

    private fun encode(map: Map<String, String>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    companion object {
        private const val ADDRESS_KEY = "networks.bitcoin.labels.address.v1"
        private const val OUTPUT_KEY = "networks.bitcoin.labels.output.v1"
    }
}
