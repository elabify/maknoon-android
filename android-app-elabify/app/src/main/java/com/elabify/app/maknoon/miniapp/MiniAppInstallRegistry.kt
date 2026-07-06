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
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
 * The offline fallback catalog. Used only when the runtime fetch
 * (MiniAppCatalogFetcher) fails; when online, the published elabify/maknoon-dapps
 * catalog is authoritative. One curated entry: the "Point of Sale" Verify & Pay
 * demo. Manifest URL + pinned SHA-256 are the published values.
 */
val SEED_CATALOG: List<MiniAppCatalogEntry> = listOf(
    // Point of Sale, 0.1.6 (Maknoon >= 0.6.3): the host re-scoped the receive
    // flows (commerce/payment/addressBook) from "payment" to "wallet" (ADR-0036),
    // so this declares only identity + wallet. Served from the single apps/pos
    // bundle. Beta channel.
    MiniAppCatalogEntry(
        appId = "pos",
        title = "Point of Sale",
        summary = "Verify a customer and accept payments.",
        details = "A merchant point-of-sale terminal. Enter an amount in cryptocurrency " +
            "or equivalent fiat currency and select which customer credentials are " +
            "required. Customers make payments on the network you choose to your wallet " +
            "along with sending the required credentials to verify.",
        curatedBy = "Elabify",
        manifestUrl = "https://elabify.github.io/maknoon-dapps/apps/pos/manifest.json",
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
 * Runtime catalog fetch (parity with iOS AppStoreRegistry.refresh). Downloads
 * the published catalog.json and parses BOTH the flat (v1) shape and the
 * ADR-0052 `catalogFormat: 2` nested-channel shape into flat entries that share
 * an appId (so the browse list groups them into one tile). GMS-free: plain
 * HttpURLConnection, no play-services. Soft-fails to null so the caller keeps
 * the offline SEED_CATALOG.
 */
object MiniAppCatalogFetcher {
    const val DEFAULT_CATALOG_URL = "https://elabify.github.io/maknoon-dapps/catalog.json"

    suspend fun fetch(url: String = DEFAULT_CATALOG_URL): List<MiniAppCatalogEntry>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    requestMethod = "GET"
                    useCaches = false // always pull LIVE; pins must match the published bundle
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.let { body ->
                runCatching { parseCatalog(JSONObject(body)) }.getOrNull()
            }
        }

    /** Parse a catalog JSON object into flat entries (v1 flat or v2 nested). */
    fun parseCatalog(o: JSONObject): List<MiniAppCatalogEntry> {
        val apps = o.optJSONArray("apps") ?: JSONArray()
        val v2 = o.optInt("catalogFormat", 1) >= 2
        val out = ArrayList<MiniAppCatalogEntry>()
        for (i in 0 until apps.length()) {
            val a = apps.optJSONObject(i) ?: continue
            if (v2) out.addAll(expandV2(a)) else parseFlat(a)?.let { out.add(it) }
        }
        return out
    }

    private fun parseFlat(a: JSONObject): MiniAppCatalogEntry? = runCatching {
        MiniAppCatalogEntry(
            appId = a.getString("id"),
            title = a.getString("title"),
            summary = a.optString("summary", ""),
            details = a.optString("details", ""),
            curatedBy = a.optString("curatedBy", SEED_CATALOG_CURATOR),
            manifestUrl = a.getString("manifestURL"),
            manifestSha256 = a.getString("manifestSha256"),
            permissions = permissionsOf(a),
            channel = if (a.isNull("channel")) null else a.optString("channel", "stable"),
            version = a.optString("version").ifEmpty { null },
            iconToken = iconTokenOf(a.optString("iconName")),
            requiresMaknoonVersion = a.optString("requiresMaknoonVersion").ifEmpty { null },
            supersededAtMaknoonVersion = a.optString("supersededAtMaknoonVersion").ifEmpty { null },
        )
    }.getOrNull()

    /** Expand an ADR-0052 app (one id, optional stable/beta channels). */
    private fun expandV2(a: JSONObject): List<MiniAppCatalogEntry> {
        val out = ArrayList<MiniAppCatalogEntry>()
        val id = a.optString("id").ifEmpty { return out }
        for (channel in listOf("stable", "beta")) {
            val ch = a.optJSONObject(channel) ?: continue
            out.add(
                MiniAppCatalogEntry(
                    appId = id,
                    title = a.optString("title", id),
                    summary = a.optString("summary", ""),
                    details = a.optString("details", ""),
                    curatedBy = a.optString("curatedBy", SEED_CATALOG_CURATOR),
                    manifestUrl = ch.getString("manifestURL"),
                    manifestSha256 = ch.getString("manifestSha256"),
                    permissions = permissionsOf(ch),
                    channel = channel,
                    version = ch.optString("version").ifEmpty { null },
                    iconToken = iconTokenOf(a.optString("iconName")),
                    requiresMaknoonVersion = ch.optString("requiresMaknoonVersion").ifEmpty { null },
                    supersededAtMaknoonVersion = ch.optString("supersededAtMaknoonVersion").ifEmpty { null },
                ),
            )
        }
        return out
    }

    /** Permission tokens from `capabilities[].name` (preferred) or `permissions`. */
    private fun permissionsOf(o: JSONObject): Set<String> {
        o.optJSONArray("capabilities")?.let { caps ->
            val s = (0 until caps.length()).mapNotNull { caps.optJSONObject(it)?.optString("name") }
                .filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
            if (s.isNotEmpty()) return s
        }
        val perms = o.optJSONArray("permissions") ?: return emptySet()
        return (0 until perms.length()).map { perms.getString(it).lowercase() }.toSet()
    }

    /** Map an iOS SF-symbol icon name to an Android icon token. */
    private fun iconTokenOf(iconName: String): String =
        if (iconName.startsWith("creditcard")) "creditCard" else "apps"
}

/**
 * Group catalog entries for the browse list (ADR-0052): one representative per
 * appId. The Show-beta-apps flag is the channel selector: default to stable;
 * when beta is on prefer beta and fall back to stable. A beta-only app is hidden
 * while beta is off. Within the chosen channel, prefer a host-compatible variant
 * (via [compatible]) then the highest version. Mirrors the iOS representative().
 */
fun groupCatalogForBrowse(
    entries: List<MiniAppCatalogEntry>,
    showBeta: Boolean,
    compatible: (MiniAppCatalogEntry) -> Boolean,
): List<MiniAppCatalogEntry> {
    val out = ArrayList<MiniAppCatalogEntry>()
    val seen = HashSet<String>()
    for (e in entries) {
        if (!seen.add(e.appId)) continue
        val variants = entries.filter { it.appId == e.appId }
        val stable = variants.filterNot { it.isBeta }
        val beta = variants.filter { it.isBeta }
        // Tile defaults to stable; a beta-only app appears only when beta is on.
        // Choosing beta for an app that also has stable is done in the install
        // sheet's channel picker, not here.
        val pool = if (stable.isNotEmpty()) stable else if (showBeta) beta else emptyList()
        if (pool.isEmpty()) continue
        val compat = pool.filter(compatible)
        val candidates = if (compat.isEmpty()) pool else compat
        candidates.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }?.let { out.add(it) }
    }
    return out
}

/** Element-wise numeric compare of semver-ish versions (missing = lowest). */
private fun compareVersions(a: String?, b: String?): Int {
    val x = (a ?: "").split(".").map { it.toIntOrNull() ?: 0 }
    val y = (b ?: "").split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

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
     * enforces it. Upsert: re-installing the same app id replaces its snapshot,
     * so choosing a different channel swaps the pinned manifest (a channel
     * switch) rather than being a no-op.
     */
    fun install(
        entry: MiniAppCatalogEntry,
        storeId: String = MiniAppCatalogEntry.DEFAULT_STORE_ID,
        granted: Set<String> = entry.permissions,
    ) {
        val installedAppId = entry.installedAppId(storeId)
        val current = installedApps().filterNot { it.installedAppId == installedAppId }.toMutableList()
        current.add(
            InstalledApp(
                installedAppId = installedAppId,
                storeId = storeId,
                entry = entry,
                installedAtIso = Instant.now().toString(),
            ),
        )
        persist(current)
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
    init {
        // Seed the process-wide reactive flag from persistence on first use so
        // the flow starts with the stored value.
        if (!seeded) {
            _showBetaApps.value = settings.value(BUCKET, BETA_KEY) == "true"
            seeded = true
        }
    }

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

    /** Reactive view of the flag so a screen recomposes the instant the toggle
     *  changes anywhere (mirrors iOS's @Observable AppStoreRegistry.showBetaApps,
     *  which had neither the toggle lag nor the stale-beta bug). */
    fun showBetaAppsFlow(): StateFlow<Boolean> = _showBetaApps.asStateFlow()

    fun setShowBetaApps(on: Boolean) {
        runCatching { settings.set(BUCKET, BETA_KEY, if (on) "true" else "false") }
        _showBetaApps.value = on
    }

    companion object {
        private const val BUCKET = "__settings::appstores"
        private const val STORE_PREFIX = "store."
        private const val BETA_KEY = "showBetaApps"
        // Process-wide so the Apps browse screen and the Settings toggle share
        // one reactive source. Compose-free (kotlinx StateFlow).
        private val _showBetaApps = MutableStateFlow(false)
        @Volatile private var seeded = false
    }
}
