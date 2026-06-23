// Per-presentation history. Every successful Share records an entry keyed by
// the recipient verifier's DID (or "did:elabify:open" when the holder shared
// without a scanned verifier request, the "anyone with the QR" case). The Apps
// tab "Connected verifiers" section reads from here.
//
// Ported from iOS VerifierHistory.swift. iOS uses a UserDefaults JSON blob; on
// Android we persist through Room (VerifierHistoryDao) so a Reset Wallet that
// clears the encrypted DB wipes history alongside credentials. The public shape
// (entry fields, newest-first ordering, group-by-verifier) matches iOS.
//
// GMS-free.

package com.elabify.musnad.present

import com.elabify.musnad.data.MaknoonDatabase
import com.elabify.musnad.data.VerifierHistoryEntity
import org.json.JSONArray

/**
 * One recorded share. Mirrors iOS VerifierHistoryEntry.
 *
 * [id] is a stable string built from the verifier DID, credential id, and
 * timestamp, matching the iOS Identifiable id so list diffing behaves the same.
 */
data class VerifierHistoryEntry(
    val verifierDid: String,
    val verifierName: String?,
    /** Display nickname for "did:elabify:open" (the open-share path). */
    val label: String,
    val credentialId: String,
    val credentialSchema: String,
    val disclosedKeys: List<String>,
    val lastUsedAt: Long,
) {
    val id: String get() = "$verifierDid/$credentialId/$lastUsedAt"
}

/** A verifier and the (newest-first) entries shared with it. Mirrors the iOS tuple. */
data class VerifierHistoryGroup(
    val verifierDid: String,
    val verifierName: String?,
    val label: String,
    val entries: List<VerifierHistoryEntry>,
)

/**
 * Room-backed per-presentation history. Construct with the DAO obtained from
 * [MaknoonStore.open]/[MaknoonDatabase.verifierHistory]; all methods are
 * suspending because the underlying Room DAO is.
 */
class VerifierHistory(private val db: MaknoonDatabase) {

    private val dao = db.verifierHistory()

    /** Every entry, newest first. */
    suspend fun all(): List<VerifierHistoryEntry> =
        dao.all().map { it.toEntry() }

    /**
     * Group by verifier DID, each group's entries newest-first, groups sorted
     * by their most-recent entry. Matches iOS groupedByVerifier().
     */
    suspend fun groupedByVerifier(): List<VerifierHistoryGroup> {
        val buckets = LinkedHashMap<String, MutableList<VerifierHistoryEntry>>()
        for (e in all()) {
            buckets.getOrPut(e.verifierDid) { mutableListOf() }.add(e)
        }
        return buckets.map { (did, list) ->
            // all() is already newest-first, so each bucket preserves that order;
            // sort defensively in case the source order ever changes.
            val sorted = list.sortedByDescending { it.lastUsedAt }
            val first = sorted.first()
            VerifierHistoryGroup(
                verifierDid = did,
                verifierName = first.verifierName,
                label = first.label,
                entries = sorted,
            )
        }.sortedByDescending { it.entries.firstOrNull()?.lastUsedAt ?: 0L }
    }

    /**
     * Insert a new entry (newest-first) and trim to the latest [MAX_ENTRIES].
     * Matches iOS record(...): inserts at the front then prefixes 200.
     */
    suspend fun record(
        verifierDid: String,
        verifierName: String?,
        label: String,
        credentialId: String,
        credentialSchema: String,
        disclosedKeys: List<String>,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ) {
        dao.insert(
            VerifierHistoryEntity(
                verifierDid = verifierDid,
                verifierName = verifierName,
                label = label,
                credentialId = credentialId,
                credentialSchema = credentialSchema,
                disclosedKeysJson = encodeKeys(disclosedKeys),
                lastUsedAt = nowSec,
            ),
        )
        dao.trim(MAX_ENTRIES)
    }

    /** Wipe all history. Called from the SDK's Reset Wallet path. */
    suspend fun reset() = dao.clear()

    private fun VerifierHistoryEntity.toEntry() = VerifierHistoryEntry(
        verifierDid = verifierDid,
        verifierName = verifierName,
        label = label,
        credentialId = credentialId,
        credentialSchema = credentialSchema,
        disclosedKeys = decodeKeys(disclosedKeysJson),
        lastUsedAt = lastUsedAt,
    )

    companion object {
        /** Keep the latest 200 entries (matches iOS). */
        const val MAX_ENTRIES = 200

        /** DID used when the holder shares without a scanned verifier request. */
        const val OPEN_VERIFIER_DID = "did:elabify:open"

        private fun encodeKeys(keys: List<String>): String {
            val arr = JSONArray()
            keys.forEach { arr.put(it) }
            return arr.toString()
        }

        private fun decodeKeys(json: String): List<String> {
            if (json.isEmpty()) return emptyList()
            val arr = JSONArray(json)
            return (0 until arr.length()).map { arr.getString(it) }
        }
    }
}
