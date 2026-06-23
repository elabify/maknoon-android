// Background-polling store for credentials the issuer has minted but the
// holder has not yet picked up + imported, the Android analog of the iOS
// PendingPickupsStore.swift.
//
// Lifecycle:
//   - The passport-issuance flow appends a [PendingPickup] here (the ack's
//     pickupUrl + a human label) and returns the user to the Identity tab
//     immediately.
//   - The Identity tab drives a poll sweep on load and then every ~10s while
//     entries remain (see IdentityScreen's pending-pickup LaunchedEffect),
//     calling [pollOnce]. Each entry's pickup URL is polled via the SDK
//     com.elabify.musnad.net.IssuerClient.pickup; a Ready credential is parsed
//     and upserted into the encrypted credentials DAO (the exact import logic
//     the Receive flow uses), then the entry is removed.
//   - Entries can be cancelled by the user; that just removes the row locally.
//     The server-side credential is untouched and can be re-fetched later.
//   - Persisted to SharedPreferences so a pending pickup survives an app
//     restart; the Identity tab resumes polling on next load.
//
// iOS specifics vs Android:
//   - @Observable + UserDefaults  -> StateFlow + SharedPreferences.
//   - the store-owned polling Task -> a Compose LaunchedEffect in IdentityScreen
//     (lifecycle-bound to the tab), so this store stays a plain data + IO holder.
//   - JSONEncoder/Decoder([PendingPickup]) -> org.json array of objects.

package com.elabify.app.maknoon.ui.identity

import android.content.Context
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.net.IssuerClient
import com.elabify.musnad.net.PickupOutcome
import com.elabify.musnad.present.ParsedCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One credential the issuer has minted that we have not imported yet.
 *
 * @param credentialId issuer-side credential id; the stable identity of the
 *   pending row (deduped on this).
 * @param pickupUrl fully-qualified pickup URL the issuer handed back.
 * @param humanLabel label shown on the pending row (e.g. "Verified Identity").
 * @param schemaUri credential schema URI when known (forward-compat; not
 *   required for the import, which reads the schema from the parsed header).
 * @param startedAt epoch millis the pickup was queued; drives the row caption.
 */
data class PendingPickup(
    val credentialId: String,
    val pickupUrl: String,
    val humanLabel: String,
    val schemaUri: String?,
    val startedAt: Long,
)

/**
 * On-disk store of pending issuance pickups, with a single-sweep poller.
 *
 * Construct once per Identity tab (remember { ... }); the polling cadence is
 * owned by the caller (a LaunchedEffect), mirroring the iOS 10s loop.
 */
class PendingPickupsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _pending = MutableStateFlow<List<PendingPickup>>(emptyList())

    /** Observable list of pending pickups. Collect this from Compose. */
    val pending: StateFlow<List<PendingPickup>> = _pending.asStateFlow()

    init {
        _pending.value = load()
    }

    /** Queue a pickup. Deduped on credentialId; no-op if already present. */
    fun add(pickup: PendingPickup) {
        if (_pending.value.any { it.credentialId == pickup.credentialId }) return
        update(_pending.value + pickup)
    }

    /** Remove a pickup the user cancelled (server-side credential untouched). */
    fun cancel(credentialId: String) {
        update(_pending.value.filterNot { it.credentialId == credentialId })
    }

    /**
     * Poll every pending entry once. A Ready credential is parsed + imported
     * into the credentials DAO (the same logic the Receive flow uses) and its
     * entry removed. Pending entries are left in place. Transient poll errors
     * are swallowed so the entry survives to the next sweep.
     *
     * @return true if at least one credential was imported (the caller can
     *   bump its reload key to surface the new card).
     */
    suspend fun pollOnce(): Boolean = withContext(Dispatchers.IO) {
        var importedAny = false
        // Snapshot so a cancel during the sweep does not surprise us.
        for (entry in _pending.value) {
            val origin = originOf(entry.pickupUrl)
            val outcome = runCatching {
                IssuerClient(origin).pickup(entry.pickupUrl)
            }.getOrNull() ?: continue // transient: retry next sweep
            if (outcome is PickupOutcome.Ready) {
                val imported = runCatching { importCredential(outcome.credentialJson) }
                    .getOrDefault(false)
                if (imported) {
                    cancel(entry.credentialId)
                    importedAny = true
                }
            }
        }
        importedAny
    }

    // ---- import (mirrors the Receive route's pickup -> parse -> upsert) ----

    private suspend fun importCredential(credentialJson: String): Boolean {
        val parsed = ParsedCredential.parse(credentialJson)
        val entity = CredentialEntity(
            cid = parsed.header.cid,
            issuerDid = parsed.header.iss,
            subjectDid = parsed.header.sub,
            schema = parsed.header.schema,
            credentialJson = credentialJson,
            nickname = null,
            createdAt = System.currentTimeMillis(),
        )
        MaknoonStore.open(appContext).credentials().upsert(entity)
        return true
    }

    /** Derive the issuer origin (scheme://host[:port]) for the IssuerClient. */
    private fun originOf(pickupUrl: String): String = runCatching {
        val u = java.net.URI(pickupUrl)
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    }.getOrDefault(pickupUrl)

    // ---- persistence ----

    private fun update(next: List<PendingPickup>) {
        _pending.value = next
        persist(next)
    }

    private fun persist(items: List<PendingPickup>) {
        val arr = JSONArray()
        for (p in items) {
            arr.put(
                JSONObject()
                    .put("credentialId", p.credentialId)
                    .put("pickupUrl", p.pickupUrl)
                    .put("humanLabel", p.humanLabel)
                    .apply { p.schemaUri?.let { put("schemaUri", it) } }
                    .put("startedAt", p.startedAt),
            )
        }
        prefs.edit().putString(STORE_KEY, arr.toString()).apply()
    }

    private fun load(): List<PendingPickup> {
        val raw = prefs.getString(STORE_KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val cid = o.optString("credentialId").ifEmpty { return@mapNotNull null }
                val url = o.optString("pickupUrl").ifEmpty { return@mapNotNull null }
                PendingPickup(
                    credentialId = cid,
                    pickupUrl = url,
                    humanLabel = o.optString("humanLabel", "Verified credential"),
                    schemaUri = if (o.has("schemaUri") && !o.isNull("schemaUri")) {
                        o.getString("schemaUri")
                    } else {
                        null
                    },
                    startedAt = o.optLong("startedAt", System.currentTimeMillis()),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS = "pendingPickups.v1"
        private const val STORE_KEY = "pendingPickups.v1"

        /** Poll cadence, matching iOS PendingPickupsStore.pollIntervalSeconds. */
        const val POLL_INTERVAL_MS = 10_000L

        /** The passport credential label shown on the pending row. */
        const val PASSPORT_LABEL = "Verified Identity"
    }
}
