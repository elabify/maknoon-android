// Applies the Settings > Display > Language choice app-wide, the Android analog
// of the iOS environment(\.locale, displayPrefs.language.locale). The activity
// wraps its base context with the selected locale in attachBaseContext, so the
// app's Configuration (and Compose LayoutDirection, e.g. RTL for Arabic) follows
// the preference. "Use Phone Setting" leaves the system locale untouched.

package com.elabify.app.maknoon.ui.theme

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleSupport {
    /** Wrap [base] with the saved display language, or return it unchanged for
     *  "Use Phone Setting". Read straight from prefs because this runs in
     *  attachBaseContext, before DisplayPreferences.init. */
    fun wrap(base: Context): Context {
        val prefs = base.getSharedPreferences("UserDefaults", Context.MODE_PRIVATE)
        val tag = prefs.getString("display.language", "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
