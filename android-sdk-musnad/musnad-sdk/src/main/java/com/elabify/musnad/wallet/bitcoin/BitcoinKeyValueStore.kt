// Minimal key/value persistence seam for the Bitcoin stores, standing in
// for iOS `UserDefaults`. The engine layer must not depend on an Android
// `Context`, so stores take a [BitcoinKeyValueStore]; the app/UI phase
// supplies a SharedPreferences-backed implementation, and tests use the
// in-memory default.
//
// Keys mirror the iOS UserDefaults keys exactly (networks.bitcoin.*) so a
// future cross-platform restore reads the same JSON shapes.

package com.elabify.musnad.wallet.bitcoin

/** String key/value store. Values are JSON strings (the iOS stores hold
 *  JSON-encoded `Data`; here we hold the equivalent JSON text). */
interface BitcoinKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

/** In-memory default. Non-persistent across process restarts; the UI
 *  phase replaces it with a SharedPreferences-backed store. */
class InMemoryKeyValueStore : BitcoinKeyValueStore {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
