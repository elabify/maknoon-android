// App-wide fiat/reference-price preference, the Android analog of the iOS
// global store.fiatPreferences (one currency used everywhere, plus a
// show-reference-prices master switch). Observable Compose state so a change in
// Settings > Currency applies live across the wallets and the mini-app fiat
// bridge. Backed by the same "UserDefaults" keys the Currency screen used.

package com.elabify.app.maknoon.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

object FiatPreferences {
    private lateinit var prefs: SharedPreferences

    private val codeState = mutableStateOf("usd")
    private val showState = mutableStateOf(true)
    private val cgState = mutableStateOf(DEFAULT_COINGECKO)
    private val fxState = mutableStateOf(DEFAULT_FX)

    /** lowercase ISO 4217 code, e.g. "usd", "aed". */
    var code: String
        get() = codeState.value
        set(v) {
            codeState.value = v
            if (::prefs.isInitialized) prefs.edit().putString(CODE_KEY, v).apply()
        }

    /** Master switch: when false, no fiat captions are shown anywhere. */
    var showReferencePrices: Boolean
        get() = showState.value
        set(v) {
            showState.value = v
            if (::prefs.isInitialized) prefs.edit().putBoolean(ENABLED_KEY, v).apply()
        }

    /** Overridable price-data sources (the only third-party hosts the fiat feature
     *  contacts). Both are skipped entirely when [showReferencePrices] is false. */
    var coinGeckoBaseURL: String
        get() = cgState.value
        set(v) {
            cgState.value = v
            if (::prefs.isInitialized) prefs.edit().putString(CG_KEY, v).apply()
        }

    var fxBaseURL: String
        get() = fxState.value
        set(v) {
            fxState.value = v
            if (::prefs.isInitialized) prefs.edit().putString(FX_KEY, v).apply()
        }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("UserDefaults", Context.MODE_PRIVATE)
        reloadFromPrefs()
    }

    /** Re-read every value from prefs (post-restore refresh; init() is a no-op
     *  once initialized, so a backup restore that wrote these keys needs this). */
    fun reload() {
        if (::prefs.isInitialized) reloadFromPrefs()
    }

    private fun reloadFromPrefs() {
        codeState.value = prefs.getString(CODE_KEY, "usd") ?: "usd"
        showState.value = if (prefs.contains(ENABLED_KEY)) prefs.getBoolean(ENABLED_KEY, true) else true
        cgState.value = prefs.getString(CG_KEY, DEFAULT_COINGECKO) ?: DEFAULT_COINGECKO
        fxState.value = prefs.getString(FX_KEY, DEFAULT_FX) ?: DEFAULT_FX
    }

    const val DEFAULT_COINGECKO = "https://api.coingecko.com/api/v3"
    const val DEFAULT_FX = "https://open.er-api.com/v6/latest/USD"

    private const val CODE_KEY = "app.fiatCurrencyCode"
    private const val ENABLED_KEY = "app.fiatReferenceEnabled"
    private const val CG_KEY = "app.fiatCoinGeckoBaseURL"
    private const val FX_KEY = "app.fiatFxBaseURL"
}
