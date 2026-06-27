// The Apps catalog + install model. Android port of the iOS AppStoreRegistry +
// AppStoreCatalog surface (Apps/AppStoreRegistry.swift, Apps/AppStoreCatalog.swift).
//
// Three things live here, all in the model layer (no Compose) so both the Apps
// tab (ui/miniapp/AppsScreen.kt) and Settings > Apps (ui/settings/
// AppsSettingsScreen.kt) share one source of truth:
//
//   * MiniAppCatalogEntry  - a catalog row, the runnable subset of the iOS
//     AppStoreEntry (id, title, summary, details, the pinned manifest, declared
//     capability tokens, release channel + version).
//   * SEED_CATALOG         - the built-in "Maknoon dApps" catalog. iOS fetches
//     this from elabify/maknoon-dapps via Pages into an AppStoreRegistry; the
//     Android SDK has no runtime catalog fetch yet, so this is the same curated
//     entry the published catalog ships.
//   * MiniAppInstallRegistry - the installed-apps store. iOS persists installed
//     apps (snapshot + granted capabilities) in UserDefaults; we persist the
//     same snapshot as JSON in a private SharedPreferences file. install() adds,
//     uninstall() removes AND evicts the app's durable settings, merchant
//     identity, and cached bundle so nothing is orphaned (mirrors the iOS
//     InstalledAppDetailSheet uninstall path).
//   * MiniAppCatalogSettings - user-added catalogs + the Show-beta-apps flag,
//     persisted through MiniAppSettingsStore's reserved host bucket (the same
//     adapter Settings > Apps already used, promoted here so the browse-list
//     beta filter and the settings toggle agree).
//
// GMS-free.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * A runnable catalog entry. The runnable subset of the iOS AppStoreEntry: id,
 * title, the pinned manifest, declared capability tokens, and the release
 * channel + version that drive the status pill and the install sheet.
 */
data class MiniAppCatalogEntry(
    val appId: String,
    val title: String,
    val summary: String,
    val details: String,
    val curatedBy: String,
    val manifestUrl: String,
    val manifestSha256: String,
    /** Capability tokens this entry declares, lowercased. */
    val permissions: Set<String>,
    /** Release channel: "beta" or "stable" (default stable). Drives the badge. */
    val channel: String? = "stable",
    /** Semantic version of the dApp, shown at install. */
    val version: String? = null,
    /** Stable icon token resolved to a Material icon in the UI. */
    val iconToken: String = "apps",
    /**
     * Minimum Maknoon (app) version this entry targets, e.g. "0.4.1". Drives the
     * compatibility badge + install gate. Null means "unknown support" (still
     * installable), matching iOS.
     */
    val requiresMaknoonVersion: String? = null,
    /**
     * Upper bound: the Maknoon (app) version at/above which this dApp version is
     * superseded (no longer supported). Null means no upper bound. Compatible iff
     * requiresMaknoonVersion <= host < supersededAtMaknoonVersion.
     */
    val supersededAtMaknoonVersion: String? = null,
) {
    /** Stable per-install id "<storeId>::<appId>", matching the bundle store. */
    fun installedAppId(storeId: String = DEFAULT_STORE_ID): String = "$storeId::$appId"

    /** Badge text for the release chip ("Beta"/"Stable"). */
    val channelLabel: String
        get() = channel?.takeIf { it.isNotEmpty() }
            ?.let { it.first().uppercaseChar() + it.drop(1).lowercase() }
            ?: "Stable"

    /** True when this entry's channel is exactly "beta" (case-insensitive). */
    val isBeta: Boolean get() = (channel ?: "").equals("beta", ignoreCase = true)

    companion object {
        const val DEFAULT_STORE_ID = "elabify.maknoon-dapps"
    }
}

/**
 * The built-in catalog. The published elabify/maknoon-dapps catalog ships a
 * single curated entry: the "Point of Sale" Verify & Pay demo (which doubles as
 * the Merchant POS demo). Manifest URL + pinned SHA-256 are the published
 * values. Point of Sale ships on the stable channel (GA): it is the reference
 * cross-platform commerce demo, no longer beta.
 */
val SEED_CATALOG: List<MiniAppCatalogEntry> = listOf(
    // Point of Sale, 0.1.6 entry: this binary is Maknoon >= 0.6.3, where the host
    // re-scoped the receive flows (commerce/payment/addressBook) from "payment" to
    // "wallet" (ADR-0036), so the offline seed declares only identity + wallet. The
    // remote catalog also carries the legacy 0.1.5 entry (supersededAtMaknoonVersion
    // 0.6.3, still "payment") for Maknoon <= 0.6.2; the seed omits it since this
    // binary never runs there. Beta channel.
    MiniAppCatalogEntry(
        appId = "pos",
        title = "Point of Sale",
        summary = "Verify a customer and accept payments.",
        details = "A merchant point-of-sale terminal. Enter an amount in cryptocurrency " +
            "or equivalent fiat currency and select which customer credentials are " +
            "required. Customers make payments on the network you choose to your wallet " +
            "along with sending the required credentials to verify.",
        curatedBy = "Elabify",
        manifestUrl = "https://elabify.github.io/maknoon-dapps/apps/pos-0.1.6/manifest.json",
        manifestSha256 = "fd612d461626298d62d506a9ff8d95f44059cb6d984ddbe5b1edea0936c13af0",
        permissions = setOf("identity", "wallet"),
        channel = "beta",
        version = "0.1.6",
        iconToken = "creditCard",
        requiresMaknoonVersion = "0.6.3",
    ),
)

/** The seed presented as a single catalog (the "Maknoon Apps" store). */
const val SEED_CATALOG_NAME = "Maknoon Apps"
const val SEED_CATALOG_CURATOR = "Elabify"

/**
 * The installed-apps store. Mirrors the iOS AppStoreRegistry.installedApps:
 * each install snapshots the entry (so the Apps tab renders without the catalog
 * online) plus the capability tokens the user accepted. Granted tokens are the
 * authoritative enforcement set and live in [MiniAppSettingsStore] (the same
 * store the bridge checks); the snapshot here is for rendering + launching.
 */
class MiniAppInstallRegistry(
    context: Context,
    private val settings: MiniAppSettingsStore,
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A snapshot of an installed app (the iOS InstalledApp.entry + metadata). */
    data class InstalledApp(
        val installedAppId: String,
        val storeId: String,
        val entry: MiniAppCatalogEntry,
        val installedAtIso: String,
    )

    fun installedApps(): List<InstalledApp> {
        val raw = prefs.getString(INSTALLED_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> decode(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    fun isInstalled(storeId: String, appId: String): Boolean =
        installedApps().any { it.storeId == storeId && it.entry.appId == appId }

    /**
     * Install [entry], granting [granted] capability tokens (default: everything
     * the entry declares). The granted set is the consent the install sheet
     * collected; it is also written to [MiniAppSettingsStore] so the bridge
     * enforces it. Idempotent.
     */
    fun install(
        entry: MiniAppCatalogEntry,
        storeId: String = MiniAppCatalogEntry.DEFAULT_STORE_ID,
        granted: Set<String> = entry.permissions,
    ) {
        val installedAppId = entry.installedAppId(storeId)
        val current = installedApps().toMutableList()
        if (current.none { it.installedAppId == installedAppId }) {
            current.add(
                InstalledApp(
                    installedAppId = installedAppId,
                    storeId = storeId,
                    entry = entry,
                    installedAtIso = Instant.now().toString(),
                ),
            )
            persist(current)
        }
        settings.setGrantedCapabilities(installedAppId, granted.map { it.lowercase() }.toSet())
    }

    /** Replace the granted capability set for an install (review/revoke UI). */
    fun setGrantedCapabilities(installedAppId: String, tokens: Set<String>) {
        settings.setGrantedCapabilities(installedAppId, tokens.map { it.lowercase() }.toSet())
    }

    /** Effective granted set for an install (from the settings store). */
    fun grantedCapabilities(installedAppId: String): Set<String> =
        settings.grantedCapabilities(installedAppId)

    /**
     * Uninstall: drop the install record AND evict everything the app owns so
     * nothing is orphaned. Mirrors the iOS uninstall path (registry.uninstall +
     * miniAppSettings.evict + merchantIdentity.evict + bundle.evict).
     */
    fun uninstall(installedAppId: String) {
        val current = installedApps().filterNot { it.installedAppId == installedAppId }
        persist(current)
        settings.evict(installedAppId)
        runCatching { MerchantIdentityStore(appContext).evict(installedAppId) }
        runCatching { MiniAppBundleStore.shared.evict(installedAppId) }
    }

    // ---- persistence ----

    private fun persist(apps: List<InstalledApp>) {
        val arr = JSONArray()
        apps.forEach { arr.put(encode(it)) }
        prefs.edit().putString(INSTALLED_KEY, arr.toString()).apply()
    }

    private fun encode(app: InstalledApp): JSONObject {
        val e = app.entry
        val perms = JSONArray().apply { e.permissions.forEach { put(it) } }
        return JSONObject().apply {
            put("installedAppId", app.installedAppId)
            put("storeId", app.storeId)
            put("installedAt", app.installedAtIso)
            put("appId", e.appId)
            put("title", e.title)
            put("summary", e.summary)
            put("details", e.details)
            put("curatedBy", e.curatedBy)
            put("manifestUrl", e.manifestUrl)
            put("manifestSha256", e.manifestSha256)
            put("permissions", perms)
            put("channel", e.channel)
            e.version?.let { put("version", it) }
            put("iconToken", e.iconToken)
            e.requiresMaknoonVersion?.let { put("requiresMaknoonVersion", it) }
            e.supersededAtMaknoonVersion?.let { put("supersededAtMaknoonVersion", it) }
        }
    }

    private fun decode(o: JSONObject): InstalledApp? = runCatching {
        val permsArr = o.optJSONArray("permissions") ?: JSONArray()
        val perms = (0 until permsArr.length()).map { permsArr.getString(it) }.toSet()
        val entry = MiniAppCatalogEntry(
            appId = o.getString("appId"),
            title = o.getString("title"),
            summary = o.optString("summary", ""),
            details = o.optString("details", ""),
            curatedBy = o.optString("curatedBy", SEED_CATALOG_CURATOR),
            manifestUrl = o.getString("manifestUrl"),
            manifestSha256 = o.getString("manifestSha256"),
            permissions = perms,
            channel = if (o.isNull("channel")) null else o.optString("channel", "stable"),
            version = if (o.has("version")) o.optString("version") else null,
            iconToken = o.optString("iconToken", "apps"),
            requiresMaknoonVersion = if (o.has("requiresMaknoonVersion")) o.optString("requiresMaknoonVersion") else null,
            supersededAtMaknoonVersion = if (o.has("supersededAtMaknoonVersion")) o.optString("supersededAtMaknoonVersion") else null,
        )
        InstalledApp(
            installedAppId = o.getString("installedAppId"),
            storeId = o.optString("storeId", MiniAppCatalogEntry.DEFAULT_STORE_ID),
            entry = entry,
            installedAtIso = o.optString("installedAt", ""),
        )
    }.getOrNull()

    private companion object {
        const val PREFS = "miniapp.installed.v1"
        const val INSTALLED_KEY = "miniapp.installed.apps.v1"
    }
}

/**
 * User-added dApps catalogs + the Show-beta-apps flag, persisted through the
 * existing [MiniAppSettingsStore] reserved host bucket. iOS keeps these in its
 * AppStoreRegistry (UserDefaults); the Android SDK has no such registry, so the
 * contract's named store is reused rather than introducing a new persistence
 * type. Each catalog is one key/value entry whose value is the JSON {name, url};
 * the flag is a single boolean key. Shared by Settings > Apps (the toggle + the
 * add/remove UI) and the Apps tab (the browse-list beta filter), so they agree.
 */
class MiniAppCatalogSettings(private val settings: MiniAppSettingsStore) {
    data class Catalog(val id: String, val name: String, val url: String)

    fun userStores(): List<Catalog> =
        settings.keys(BUCKET)
            .filter { it.startsWith(STORE_PREFIX) }
            .mapNotNull { key ->
                val raw = settings.value(BUCKET, key) ?: return@mapNotNull null
                runCatching {
                    val o = JSONObject(raw)
                    Catalog(id = key.removePrefix(STORE_PREFIX), name = o.getString("name"), url = o.getString("url"))
                }.getOrNull()
            }

    fun addStore(name: String, url: String) {
        val id = Instant.now().toEpochMilli().toString()
        val json = JSONObject().put("name", name).put("url", url).toString()
        runCatching { settings.set(BUCKET, STORE_PREFIX + id, json) }
    }

    fun removeStore(id: String) {
        settings.remove(BUCKET, STORE_PREFIX + id)
    }

    fun showBetaApps(): Boolean = settings.value(BUCKET, BETA_KEY) == "true"

    fun setShowBetaApps(on: Boolean) {
        runCatching { settings.set(BUCKET, BETA_KEY, if (on) "true" else "false") }
    }

    private companion object {
        const val BUCKET = "__settings::appstores"
        const val STORE_PREFIX = "store."
        const val BETA_KEY = "showBetaApps"
    }
}
