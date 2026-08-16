package com.elabify.app.maknoon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.miniapp.MiniAppBundleStore
import com.elabify.app.maknoon.ui.MaknoonRoot
import com.elabify.app.maknoon.ui.SystemBarIconAppearance
import com.elabify.app.maknoon.ui.applyEdgeToEdgeWindow
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
        // Hand-rolled rather than enableEdgeToEdge(): see ui/EdgeToEdge.kt and
        // ADR-0080. androidx's version reaches a deprecated cutout-mode constant
        // that Play flags, and sets two more deprecated window colour setters.
        applyEdgeToEdgeWindow()
        // Process-wide mini-app bundle cache; the Apps tab host reads it via
        // MiniAppBundleStore.shared (idempotent init).
        MiniAppBundleStore.init(applicationContext)
        DisplayPreferences.init(applicationContext)
        com.elabify.app.maknoon.ui.settings.FiatPreferences.init(applicationContext)
        com.elabify.app.maknoon.ui.settings.RelaySettings.init(applicationContext)
        com.elabify.app.maknoon.ui.settings.TestnetAnchorSettings.init(applicationContext)
        // Shared multi-asset price cache (persists its snapshot to "UserDefaults").
        com.elabify.musnad.wallet.pricing.AssetPriceCache.init(applicationContext)
        // ADR-0064 hard switch: delete EVM software wallets abandoned by the
        // wallet-derivation passphrase-parity change (one-shot, pref-gated).
        com.elabify.app.maknoon.ui.wallet.ethereum.WalletDerivationMigration.runIfNeeded(applicationContext)
        setContent {
            // Read the observable theme so a change in Settings > Display
            // recomposes here and applies live. Automatic follows the system.
            val dark = DisplayPreferences.theme.resolveDark(isSystemInDarkTheme())
            MaknoonTheme(darkTheme = dark) {
                // Follows the theme live. A one-shot call in onCreate would leave
                // the bar icons stale after a Settings > Display theme change,
                // because that recomposes without recreating the activity.
                SystemBarIconAppearance(darkTheme = dark)
                MaknoonRoot()
            }
        }
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    // WalletConnect (ADR-0049): a dApp hands off a `wc:` pairing URI; route it to
    // the manager to pair. `maknoon://wc` is the return-redirect and needs no work.
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("wc", ignoreCase = true)) {
            com.elabify.app.maknoon.walletconnect.WalletConnectManager.pair(data.toString())
        }
    }
}
