package com.elabify.app.maknoon

import android.content.Context
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.miniapp.MiniAppBundleStore
import com.elabify.app.maknoon.ui.MaknoonRoot
import com.elabify.app.maknoon.ui.theme.DisplayPreferences
import com.elabify.app.maknoon.ui.theme.LocaleSupport
import com.elabify.app.maknoon.ui.theme.MaknoonTheme

// Single-activity host. FragmentActivity (a ComponentActivity subclass) so
// androidx BiometricPrompt can attach. Deep-link routing is added later.
class MainActivity : FragmentActivity() {
    // Apply the saved display language (locale + layout direction) to the whole
    // activity. Settings recreate() the activity when the language changes.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleSupport.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Process-wide mini-app bundle cache; the Apps tab host reads it via
        // MiniAppBundleStore.shared (idempotent init).
        MiniAppBundleStore.init(applicationContext)
        DisplayPreferences.init(applicationContext)
        com.elabify.app.maknoon.ui.settings.FiatPreferences.init(applicationContext)
        com.elabify.app.maknoon.ui.settings.RelaySettings.init(applicationContext)
        // Shared multi-asset price cache (persists its snapshot to "UserDefaults").
        com.elabify.musnad.wallet.pricing.AssetPriceCache.init(applicationContext)
        setContent {
            // Read the observable theme so a change in Settings > Display
            // recomposes here and applies live. Automatic follows the system.
            val dark = DisplayPreferences.theme.resolveDark(isSystemInDarkTheme())
            MaknoonTheme(darkTheme = dark) {
                MaknoonRoot()
            }
        }
    }
}
