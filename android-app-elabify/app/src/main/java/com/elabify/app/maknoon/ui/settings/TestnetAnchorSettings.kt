// Advanced, client-only display preference: whether to show testnet anchor
// badges (Sepolia, Base Sepolia) on the passport card. Off by default. The
// credential itself always shows; this only gates the testnet anchor chips, so
// a testnet pin never reads as production trust unless the holder opts in
// (Settings, Identity, Advanced, "Show testnet anchors"). Backed by the same
// "UserDefaults" prefs store the other app settings use; observable so a
// Settings change applies live. Mirrors the iOS @AppStorage flag.

package com.elabify.app.maknoon.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

object TestnetAnchorSettings {
    private lateinit var prefs: SharedPreferences
    private val enabledState = mutableStateOf(false)

    /** When true, testnet anchor badges (Sepolia, Base Sepolia) are shown. */
    var showTestnetAnchors: Boolean
        get() = enabledState.value
        set(v) {
            enabledState.value = v
            if (::prefs.isInitialized) prefs.edit().putBoolean(ENABLED_KEY, v).apply()
        }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("UserDefaults", Context.MODE_PRIVATE)
        enabledState.value = prefs.getBoolean(ENABLED_KEY, false)
    }

    private const val ENABLED_KEY = "app.showTestnetAnchors"
}
