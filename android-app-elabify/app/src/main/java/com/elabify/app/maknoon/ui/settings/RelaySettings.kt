// App-wide override for the presentation relay / drop host (the verifier origin
// that sealed presentation payloads transit when shared over the network, and
// the registry host for commerce request validation). Default is the public
// elabify verifier; a self-hoster can point it at their own /v1/drop endpoint,
// and the privacy-conscious can turn network sharing off entirely (#61).
//
// The host is a per-presentation rendezvous: the holder uploads the sealed
// presentation and the resulting link/QR embeds THIS host, so the verifier
// fetches from wherever the holder uploaded -- overriding it works end-to-end
// with no server coordination. When disabled, the network drop is refused and
// only the rotating privacy QR (tiny payloads) remains.
//
// Mirrors the iOS RelaySettings; backed by the same "UserDefaults" prefs store
// the other app settings use. Observable so a Settings change applies live.

package com.elabify.app.maknoon.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

object RelaySettings {
    private lateinit var prefs: SharedPreferences

    private val hostState = mutableStateOf(DEFAULT_HOST)
    private val enabledState = mutableStateOf(true)

    /** The relay/verifier origin (no trailing slash), e.g.
     *  "https://musnad-verifier.elabify.com". Always returns the configured
     *  value; the [enabled] flag gates whether the network drop is used. */
    var host: String
        get() = hostState.value
        set(v) {
            hostState.value = v
            if (::prefs.isInitialized) prefs.edit().putString(HOST_KEY, v).apply()
        }

    /** When false, the app refuses to upload / fetch a presentation over the
     *  network relay (sharing falls back to the rotating privacy QR only). */
    var enabled: Boolean
        get() = enabledState.value
        set(v) {
            enabledState.value = v
            if (::prefs.isInitialized) prefs.edit().putBoolean(ENABLED_KEY, v).apply()
        }

    /** The effective host for sharing, or null when the relay is disabled.
     *  Call sites that upload / fetch a presentation use this and surface a
     *  clear "relay is off" message on null. */
    val sharingHost: String?
        get() = if (enabledState.value) hostState.value.trim().ifEmpty { DEFAULT_HOST } else null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("UserDefaults", Context.MODE_PRIVATE)
        hostState.value = prefs.getString(HOST_KEY, DEFAULT_HOST) ?: DEFAULT_HOST
        enabledState.value = if (prefs.contains(ENABLED_KEY)) prefs.getBoolean(ENABLED_KEY, true) else true
    }

    const val DEFAULT_HOST = "https://musnad-verifier.elabify.com"

    private const val HOST_KEY = "app.relayHost"
    private const val ENABLED_KEY = "app.relayEnabled"
}
