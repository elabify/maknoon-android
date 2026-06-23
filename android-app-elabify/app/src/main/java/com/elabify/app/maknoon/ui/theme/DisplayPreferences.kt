// App-wide display preferences (theme, auto-lock, language), the Android analog
// of the iOS DisplayPreferences that MaknoonApp applies at the app root via
// preferredColorScheme / environment(\.locale) / AutoLockManager. Exposes
// observable Compose state so a change applies live: MainActivity reads `theme`
// and recomposes MaknoonTheme; AutoLockManager reads `autoLock`; the locale
// wrapper reads `language`. Backed by the same "UserDefaults" prefs file + keys
// the Settings screen already used, so prior selections survive.

package com.elabify.app.maknoon.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppTheme(val key: String, val label: String) {
    AUTOMATIC("automatic", "Automatic"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    /** Resolve to dark? Automatic follows the system, matching iOS resolvedColorScheme. */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        AUTOMATIC -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        // Treat the legacy "" (use-phone) value as Automatic.
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: AUTOMATIC
    }
}

enum class AutoLockTimeout(val key: String, val label: String, val seconds: Long?) {
    SEC30("30s", "30 seconds", 30),
    MIN1("1m", "1 minute", 60),
    MIN2("2m", "2 minutes", 120),
    MIN3("3m", "3 minutes", 180),
    MIN4("4m", "4 minutes", 240),
    MIN5("5m", "5 minutes", 300),
    NEVER("never", "Never", null);

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: MIN2
    }
}

enum class AppLanguage(val key: String, val label: String) {
    USE_PHONE("", "Use Phone Setting"),
    ENGLISH("en", "English"),
    ARABIC("ar", "العربية"),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文");

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: USE_PHONE
    }
}

/** Process-wide, observable display preferences. Call [init] once at app start. */
object DisplayPreferences {
    private lateinit var prefs: SharedPreferences

    // Backing Compose snapshot state: reading the public vals in a composable
    // subscribes it, so a set() recomposes (e.g. MainActivity's theme).
    private val themeState = mutableStateOf(AppTheme.AUTOMATIC)
    private val autoLockState = mutableStateOf(AutoLockTimeout.MIN2)
    private val languageState = mutableStateOf(AppLanguage.USE_PHONE)

    var theme: AppTheme
        get() = themeState.value
        set(v) { themeState.value = v; persist(THEME_KEY, v.key) }

    var autoLock: AutoLockTimeout
        get() = autoLockState.value
        set(v) { autoLockState.value = v; persist(AUTOLOCK_KEY, v.key) }

    var language: AppLanguage
        get() = languageState.value
        set(v) { languageState.value = v; persist(LANGUAGE_KEY, v.key) }

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
        themeState.value = AppTheme.fromKey(prefs.getString(THEME_KEY, null))
        autoLockState.value = AutoLockTimeout.fromKey(prefs.getString(AUTOLOCK_KEY, null))
        languageState.value = AppLanguage.fromKey(prefs.getString(LANGUAGE_KEY, null))
    }

    private fun persist(key: String, value: String) {
        if (::prefs.isInitialized) prefs.edit().putString(key, value).apply()
    }

    private const val THEME_KEY = "display.theme"
    private const val AUTOLOCK_KEY = "display.autoLock"
    private const val LANGUAGE_KEY = "display.language"
}
