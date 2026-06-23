// Tiny key/value persistence seam for the Ethereum stores. iOS persists every
// store to UserDefaults under the `networks.ethereum.*` namespace. Android has
// no UserDefaults; the app backs this with SharedPreferences. Stores depend on
// this interface (not android.content.Context) so the engine stays pure-Kotlin
// and unit-testable, and the UI phase picks the concrete backing.
//
// String keys used by the stores are byte-identical to the iOS UserDefaults
// keys so a future cross-device restore reads the same schema.

package com.elabify.musnad.wallet.ethereum

interface EthereumKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun contains(key: String): Boolean

    /** In-memory backing for unit tests / pre-UI use. */
    class InMemory : EthereumKeyValueStore {
        private val map = HashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun contains(key: String): Boolean = map.containsKey(key)
    }
}
