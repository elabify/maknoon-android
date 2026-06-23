// SharedPreferences-backed implementations of the per-chain key/value
// persistence seams the engine layer depends on (Bitcoin/Ethereum/Solana use
// an interface so the engines stay pure-Kotlin + unit-testable; the app wires
// these concrete stores). Tron + Lightning already take SharedPreferences /
// Context directly. Keys mirror the iOS UserDefaults namespaces verbatim.

package com.elabify.musnad.wallet

import android.content.Context
import android.content.SharedPreferences
import com.elabify.musnad.wallet.bitcoin.BitcoinKeyValueStore
import com.elabify.musnad.wallet.ethereum.EthereumKeyValueStore
import com.elabify.musnad.wallet.solana.SolanaKeyValueStore

private const val WALLET_PREFS = "maknoon.wallets.v1"

/** Shared prefs file all chains' key/value stores live in. */
fun walletPrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(WALLET_PREFS, Context.MODE_PRIVATE)

class PrefsBitcoinStore(private val prefs: SharedPreferences) : BitcoinKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

class PrefsEthereumStore(private val prefs: SharedPreferences) : EthereumKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun contains(key: String): Boolean = prefs.contains(key)
}

class PrefsSolanaStore(private val prefs: SharedPreferences) : SolanaKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
