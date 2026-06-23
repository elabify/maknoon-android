// User-created folders for the Identity tab's credential stack, ported from the
// iOS CredentialFolderStore. Folders are a purely local wallet concern: the
// credential records themselves are immutable on-chain objects and are never
// mutated, so membership lives in a separate cardId -> folderId map rather than
// on the credential row (this also matches the cross-platform backup contract).
//
// Two SharedPreferences values mirror the two iOS UserDefaults keys:
//   folders     -> JSON array of {id, name, createdAt}
//   membership  -> JSON object of cardId -> folderId
// Card ids are namespaced like iOS ("cred:<cid>"), so the membership map is
// byte-compatible with the iOS folderMembership map for backup round-trips.
//
// "All credentials" is implicit: a null folderId, never a stored record.

package com.elabify.app.maknoon.ui.identity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One folder. [id] is a stable UUID string preserved across renames + restores. */
data class CredentialFolder(
    val id: String,
    val name: String,
    val createdAtEpochSec: Long,
)

class CredentialFolderStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var foldersCache: MutableList<CredentialFolder> = mutableListOf()
    private var membershipCache: MutableMap<String, String> = mutableMapOf()

    init {
        load()
    }

    // MARK: -- queries

    /** Folder list in user order. */
    fun folders(): List<CredentialFolder> = foldersCache.toList()

    /** The card ids assigned to [folderId]. */
    fun cardIds(folderId: String): Set<String> =
        membershipCache.filterValues { it == folderId }.keys.toSet()

    /** The folder a card belongs to, or null for "All credentials". */
    fun folderId(cardId: String): String? = membershipCache[cardId]

    /** Raw count for a folder pill badge (may include stale entries; the UI
     *  filters against live cards). */
    fun count(folderId: String): Int = membershipCache.values.count { it == folderId }

    // MARK: -- mutations

    /** Create a folder; blank names fall back to "New folder". */
    fun add(name: String): CredentialFolder {
        val trimmed = name.trim()
        val folder = CredentialFolder(
            id = UUID.randomUUID().toString(),
            name = trimmed.ifEmpty { "New folder" },
            createdAtEpochSec = System.currentTimeMillis() / 1000L,
        )
        foldersCache.add(folder)
        persistFolders()
        return folder
    }

    /** Rename a folder; a blank new name keeps the old one. */
    fun rename(id: String, newName: String) {
        val idx = foldersCache.indexOfFirst { it.id == id }
        if (idx < 0) return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        foldersCache[idx] = foldersCache[idx].copy(name = trimmed)
        persistFolders()
    }

    /** Delete a folder; its member cards move back to "All credentials". */
    fun remove(id: String) {
        foldersCache.removeAll { it.id == id }
        val it = membershipCache.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value == id) it.remove()
        }
        persistFolders()
        persistMembership()
    }

    /** Assign a card to a folder, or pass null to move it back to root. Unknown
     *  folder ids are ignored (defensive against stale selections). */
    fun assign(cardId: String, folderId: String?) {
        if (folderId != null) {
            if (foldersCache.none { it.id == folderId }) return
            membershipCache[cardId] = folderId
        } else {
            membershipCache.remove(cardId)
        }
        persistMembership()
    }

    // MARK: -- persistence

    private fun load() {
        foldersCache = mutableListOf()
        membershipCache = mutableMapOf()
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_FOLDERS, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                foldersCache.add(
                    CredentialFolder(
                        id = id,
                        name = o.optString("name", "Folder"),
                        createdAtEpochSec = o.optLong("createdAt", 0L),
                    )
                )
            }
        }
        runCatching {
            val o = JSONObject(prefs.getString(KEY_MEMBERSHIP, "{}") ?: "{}")
            for (key in o.keys()) {
                val fid = o.optString(key, "")
                if (fid.isNotEmpty()) membershipCache[key] = fid
            }
        }
    }

    private fun persistFolders() {
        val arr = JSONArray()
        foldersCache.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("name", f.name)
                    .put("createdAt", f.createdAtEpochSec)
            )
        }
        prefs.edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }

    private fun persistMembership() {
        val o = JSONObject()
        membershipCache.forEach { (cardId, fid) -> o.put(cardId, fid) }
        prefs.edit().putString(KEY_MEMBERSHIP, o.toString()).apply()
    }

    companion object {
        private const val PREFS = "maknoon.credentialFolders.v1"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_MEMBERSHIP = "membership"

        /** Namespaced membership key for a credential, matching iOS. */
        fun cardKey(cid: String): String = "cred:$cid"

        /** Namespaced membership key for an ID document, matching iOS. A folder
         *  can hold both credentials and ID documents (ADR-0037). */
        fun docCardKey(id: String): String = "passport:$id"
    }
}
