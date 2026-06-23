// Host-owned, per-app key-value settings for mini apps. Android port of the
// iOS MiniAppSettingsStore.swift.
//
// Mini apps reach this through window.maknoon.storage (the storage namespace
// handler). The WebView's own localStorage is deliberately ephemeral (the
// host clears it per session and per app), so this is the ONLY durable
// storage a mini app has. The whole map is serialized to one JSON document,
// sealed with AndroidSecureStore (a StrongBox/TEE-wrapped AES-256-GCM key),
// and stored base64 in a private SharedPreferences file. If the hardware
// keystore refuses a key (no StrongBox/TEE, e.g. an emulator without a
// secure keyguard) we degrade to storing the plain JSON base64 in the same
// prefs file, matching the IDDocumentStore fallback, so the feature still
// works in dev; production devices always seal.
//
// Settings are namespaced by the installed-app id ("<storeId>::<appId>"), so
// one mini app can never read or write another's bucket. Quotas keep a single
// app from bloating storage.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.elabify.musnad.crypto.AndroidSecureStore
import org.json.JSONObject

/** Typed quota failure with a user-facing message (mirrors iOS). */
class MiniAppSettingsException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { VALUE_TOO_LARGE, TOO_MANY_KEYS, APP_QUOTA_EXCEEDED }

    companion object {
        fun valueTooLarge(maxBytes: Int) =
            MiniAppSettingsException(Kind.VALUE_TOO_LARGE, "Value exceeds the $maxBytes-byte per-value limit.")
        fun tooManyKeys(max: Int) =
            MiniAppSettingsException(Kind.TOO_MANY_KEYS, "This app already has the maximum of $max settings.")
        fun appQuotaExceeded(maxBytes: Int) =
            MiniAppSettingsException(Kind.APP_QUOTA_EXCEEDED, "This app's settings exceed the $maxBytes-byte limit.")
    }
}

/**
 * Per-mini-app durable settings, sealed at rest. Also the persistence point
 * for granted capabilities (see [grantedCapabilities] / [setGrantedCapabilities]).
 */
class MiniAppSettingsStore(
    context: Context,
    private val secureStore: AndroidSecureStore = AndroidSecureStore(WRAP_ALIAS),
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** installedAppId -> (key -> value). */
    private var byApp: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /** installedAppId -> set of granted capability tokens. */
    private var grants: MutableMap<String, MutableSet<String>> = mutableMapOf()

    init {
        load()
    }

    // ---- per-app key/value access (window.maknoon.storage) ----

    fun value(appId: String, key: String): String? = byApp[appId]?.get(key)

    fun all(appId: String): Map<String, String> = byApp[appId]?.toMap() ?: emptyMap()

    fun keys(appId: String): List<String> = byApp[appId]?.keys?.sorted() ?: emptyList()

    @Throws(MiniAppSettingsException::class)
    fun set(appId: String, key: String, value: String) {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
            throw MiniAppSettingsException.valueTooLarge(MAX_VALUE_BYTES)
        }
        val bucket = byApp[appId]?.toMutableMap() ?: mutableMapOf()
        if (!bucket.containsKey(key) && bucket.size >= MAX_KEYS_PER_APP) {
            throw MiniAppSettingsException.tooManyKeys(MAX_KEYS_PER_APP)
        }
        bucket[key] = value
        if (byteSize(bucket) > MAX_APP_BYTES) {
            throw MiniAppSettingsException.appQuotaExceeded(MAX_APP_BYTES)
        }
        byApp[appId] = bucket
        persist()
    }

    fun remove(appId: String, key: String) {
        val bucket = byApp[appId] ?: return
        bucket.remove(key)
        if (bucket.isEmpty()) byApp.remove(appId)
        persist()
    }

    // ---- granted capabilities (install-time consent + per-use base grant) ----

    /** Tokens this install has been granted (declared and accepted). */
    fun grantedCapabilities(appId: String): Set<String> = grants[appId]?.toSet() ?: emptySet()

    /** True when the install holds [token] (case-insensitive). */
    fun hasCapability(appId: String, token: String): Boolean =
        grants[appId]?.any { it.equals(token, ignoreCase = true) } == true

    /** Replace the granted set for an install (e.g. from the install sheet). */
    fun setGrantedCapabilities(appId: String, tokens: Set<String>) {
        if (tokens.isEmpty()) grants.remove(appId) else grants[appId] = tokens.toMutableSet()
        persist()
    }

    /** Grant a single token (idempotent). */
    fun grant(appId: String, token: String) {
        val set = grants[appId] ?: mutableSetOf()
        set.add(token)
        grants[appId] = set
        persist()
    }

    /** Revoke a single token (idempotent). */
    fun revoke(appId: String, token: String) {
        val set = grants[appId] ?: return
        set.removeAll { it.equals(token, ignoreCase = true) }
        if (set.isEmpty()) grants.remove(appId) else grants[appId] = set
        persist()
    }

    // ---- lifecycle ----

    /** Remove every setting and grant for an app (called on uninstall). */
    fun evict(appId: String) {
        var changed = false
        if (byApp.remove(appId) != null) changed = true
        if (grants.remove(appId) != null) changed = true
        if (changed) persist()
    }

    /** Wipe everything and drop the wrap key (wallet-wide reset). */
    fun reset() {
        byApp = mutableMapOf()
        grants = mutableMapOf()
        runCatching { secureStore.deleteKey() }
        prefs.edit().clear().apply()
    }

    /** Drop the in-memory cache and re-read storage (post-restore sync). */
    fun reload() = load()

    // ---- persistence ----

    private fun persist() {
        val root = JSONObject()
        val settingsObj = JSONObject()
        for ((app, bucket) in byApp) {
            val b = JSONObject()
            for ((k, v) in bucket) b.put(k, v)
            settingsObj.put(app, b)
        }
        root.put("settings", settingsObj)
        val grantsObj = JSONObject()
        for ((app, set) in grants) {
            val arr = org.json.JSONArray()
            for (t in set) arr.put(t)
            grantsObj.put(app, arr)
        }
        root.put("grants", grantsObj)

        val json = root.toString().toByteArray(Charsets.UTF_8)
        val sealed = runCatching { secureStore.seal(json) }.getOrNull()
        if (sealed != null) {
            prefs.edit()
                .putString(BLOB_KEY, Base64.encodeToString(sealed, Base64.NO_WRAP))
                .putBoolean(SEALED_KEY, true)
                .apply()
        } else {
            prefs.edit()
                .putString(BLOB_KEY, Base64.encodeToString(json, Base64.NO_WRAP))
                .putBoolean(SEALED_KEY, false)
                .apply()
        }
    }

    private fun load() {
        byApp = mutableMapOf()
        grants = mutableMapOf()
        val stored = prefs.getString(BLOB_KEY, null) ?: return
        val wasSealed = prefs.getBoolean(SEALED_KEY, true)
        val raw = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return
        val json = if (wasSealed) {
            runCatching { secureStore.open(raw) }.getOrNull() ?: return
        } else {
            raw
        }
        runCatching {
            val root = JSONObject(String(json, Charsets.UTF_8))
            val settingsObj = root.optJSONObject("settings")
            if (settingsObj != null) {
                for (app in settingsObj.keys()) {
                    val b = settingsObj.getJSONObject(app)
                    val bucket = mutableMapOf<String, String>()
                    for (k in b.keys()) bucket[k] = b.getString(k)
                    byApp[app] = bucket
                }
            }
            val grantsObj = root.optJSONObject("grants")
            if (grantsObj != null) {
                for (app in grantsObj.keys()) {
                    val arr = grantsObj.getJSONArray(app)
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    grants[app] = set
                }
            }
        }
    }

    private fun byteSize(bucket: Map<String, String>): Int =
        bucket.entries.sumOf {
            it.key.toByteArray(Charsets.UTF_8).size + it.value.toByteArray(Charsets.UTF_8).size
        }

    companion object {
        // Quotas (per installed app). Generous for config, tight enough that
        // a mini app cannot turn durable storage into a data dump.
        const val MAX_KEYS_PER_APP = 64
        const val MAX_VALUE_BYTES = 8 * 1024
        const val MAX_APP_BYTES = 64 * 1024

        private const val PREFS = "miniapp.settings.v1"
        private const val BLOB_KEY = "miniapp.settings.sealed.v1"
        private const val SEALED_KEY = "miniapp.settings.sealed.flag.v1"
        private const val WRAP_ALIAS = "miniapp.settings.vault.wrap"
    }
}
