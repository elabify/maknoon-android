// App-wide display preferences (theme, auto-lock, language), the Android analog
// of the iOS DisplayPreferences that MaknoonApp applies at the app root via
// preferredColorScheme / environment(\.locale) / AutoLockManager. Exposes
// observable Compose state so a change applies live: MainActivity reads `theme`
// and recomposes MaknoonTheme; AutoLockManager reads `autoLock`; the locale
// wrapper reads `language`. Backed by the same "UserDefaults" prefs file + keys
// the Settings screen already used, so prior selections survive.

package com.elabify.app.maknoon.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.elabify.app.maknoon.R

enum class AppTheme(val key: String, @StringRes val labelRes: Int) {
    AUTOMATIC("automatic", R.string.display_theme_automatic),
    LIGHT("light", R.string.display_theme_light),
    DARK("dark", R.string.display_theme_dark);

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

enum class AutoLockTimeout(val key: String, @StringRes val labelRes: Int, val seconds: Long?) {
    // A fixed ladder, not a plural: each value is its own string so a translator
    // can get Arabic's dual right for "2 minutes" rather than being handed a
    // count to pluralize.
    SEC30("30s", R.string.display_autolock_30s, 30),
    MIN1("1m", R.string.display_autolock_1m, 60),
    MIN2("2m", R.string.display_autolock_2m, 120),
    MIN3("3m", R.string.display_autolock_3m, 180),
    MIN4("4m", R.string.display_autolock_4m, 240),
    MIN5("5m", R.string.display_autolock_5m, 300),
    NEVER("never", R.string.display_autolock_never, null);

    companion object {
        fun fromKey(k: String?) = entries.firstOrNull { it.key == k } ?: MIN2
    }
}

/**
 * Language override. `key` is what lands in the `display.language` preference
 * and in the encrypted backup (ADR-0065), so existing "", "en", "ar" and
 * "zh-Hans" values keep loading unchanged.
 *
 * A value class over [AppLanguageCatalog] rather than 31 enum entries. Self-names
 * come from the catalog and are never translated: someone hunting for 简体中文
 * cannot find "Chinese (Simplified)" in a language they do not yet read.
 */
@JvmInline
value class AppLanguage(val key: String) {

    val entry: AppLanguageCatalog.Entry? get() = AppLanguageCatalog.entry(key)

    /** Shown as the primary label. Empty key means "follow the phone". */
    val selfName: String? get() = entry?.selfName

    /** Secondary label, for a user who half-reads English. */
    val englishName: String? get() = entry?.englishName

    val isRTL: Boolean get() = entry?.isRTL == true

    companion object {
        val USE_PHONE = AppLanguage("")

        /** Follow-the-phone first, then every shipped locale by English name. */
        val all: List<AppLanguage> =
            listOf(USE_PHONE) + AppLanguageCatalog.all.map { AppLanguage(it.code) }

        /**
         * Unknown key falls back to follow-the-phone rather than throwing: a
         * backup restored from a newer build can name a locale this build lacks.
         */
        fun fromKey(k: String?): AppLanguage {
            val v = k.orEmpty()
            return if (v.isEmpty() || AppLanguageCatalog.entry(v) != null) AppLanguage(v)
                   else USE_PHONE
        }
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
