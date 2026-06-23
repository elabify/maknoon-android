// Tiny key/value persistence seam for the Solana stores. iOS persists
// every store to UserDefaults under the `networks.solana.*` namespace.
// Android has no UserDefaults; the app backs this with SharedProferences
// (one line: SolanaKeyValueStore.sharedPreferences(context)). Keeping the
// stores depend on this interface (not android.content.Context directly)
// keeps the engine pure-Kotlin + unit-testable, and lets the UI phase
// pick the concrete backing (SharedPreferences / DataStore).
//
// The string keys used by the stores are byte-identical to the iOS
// UserDefaults keys so a future cross-device restore reads the same
// schema.

package com.elabify.musnad.wallet.solana

interface SolanaKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getLong(key: String): Long?
    fun putLong(key: String, value: Long)
    fun remove(key: String)

    /** In-memory backing for unit tests / pre-UI use. */
    class InMemory : SolanaKeyValueStore {
        private val map = HashMap<String, Any>()
        override fun getString(key: String): String? = map[key] as? String
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getLong(key: String): Long? = map[key] as? Long
        override fun putLong(key: String, value: Long) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }
}
