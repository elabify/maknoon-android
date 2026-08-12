// Encrypted persistence for the user's saved ID documents, the Android
// analog of the iOS IDDocumentStore.
//
// iOS split storage three ways: JSON metadata in UserDefaults, photos
// in one Documents subdirectory, and the raw chip blobs (SOD + each
// data group) in another, keyed by UUID filename. On Android we keep it
// simpler and stronger: the whole list (metadata + raw DG/SOD bytes +
// portrait, all base64 inside the JSON) is serialized to one JSON
// document, sealed with AndroidSecureStore (a StrongBox/TEE-wrapped
// AES-256-GCM key, the Android analog of the iOS biometric Keychain
// protection on the chip blobs), and the sealed blob is stored base64
// in a private SharedPreferences file. If the hardware keystore refuses
// a key (no StrongBox/TEE, e.g. an emulator without a secure keyguard),
// we degrade to storing the plain JSON base64 in the same prefs file so
// the feature still works in dev; production devices always seal.
//
// The observable surface mirrors iOS add/remove/list/observe: a
// StateFlow<List<IDDocument>> the Compose layer collects, plus suspend
// save/delete that update the flow and re-persist. Reset Wallet clears
// the key + the prefs (reset()).

package com.elabify.app.maknoon.iddocument

import com.elabify.app.maknoon.ui.common.LocalizedThrowable
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.MaknoonApplication
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.elabify.musnad.crypto.AndroidSecureStore
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Thrown by [IDDocumentStore.save] when the same document (type + issuer + number
 *  + DOB + expiry) is already saved. Carries a user-friendly message (ADR-0037). */
class DuplicateIDDocumentException :
    Exception(), LocalizedThrowable {
    // Resolved lazily against the application context: thrown from a store
    // with no Context in scope. Marked LocalizedThrowable so userMessage()
    // shows THIS rather than a generic fallback: "already saved" is the whole
    // point of the message, and it is the difference between a user retrying
    // forever and a user understanding they are done.
    override val message: String
        get() = MaknoonApplication.appContext.getString(R.string.iddoc_already_saved)
}

class IDDocumentStore(
    context: Context,
    /**
     * Factory for the sealing key. Defaults to a StrongBox/TEE
     * AndroidSecureStore aliased for the ID-document vault. Overridable
     * for tests (an in-memory or software-fallback store) so the persist
     * path can run without a secure keyguard.
     */
    private val secureStore: AndroidSecureStore = AndroidSecureStore(WRAP_ALIAS),
) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _documents = MutableStateFlow<List<IDDocument>>(emptyList())

    /** Observable list of saved documents. Collect this from Compose. */
    val documents: StateFlow<List<IDDocument>> = _documents.asStateFlow()

    init {
        _documents.value = load()
    }

    // ---- CRUD (mirrors the iOS add / remove / setNickname / set*Result surface) ----

    /**
     * Persist a freshly-read document. The portrait and any raw chip
     * blobs from the read result are folded into the stored model, then
     * the whole list is re-sealed. Returns the stored document (with the
     * blobs attached) so the caller can navigate straight to its detail
     * view, matching the iOS `add(...) -> IDDocument` return.
     */
    suspend fun save(result: IDDocumentReadResult): IDDocument {
        val raw = result.rawChipData
        val stored = result.document.copy(
            sod = raw["sod"] ?: result.document.sod,
            dg1 = raw["dg1"] ?: result.document.dg1,
            dg2 = raw["dg2"] ?: result.document.dg2,
            dg11 = raw["dg11"] ?: result.document.dg11,
            dg12 = raw["dg12"] ?: result.document.dg12,
            dg15 = raw["dg15"] ?: result.document.dg15,
            portraitJpeg = result.portraitJpeg ?: result.document.portraitJpeg,
        )
        requireNotDuplicate(stored)
        // Replace any existing entry with the same id (re-scan of the
        // same document), else append. Mirrors iOS append semantics while
        // staying idempotent under a re-read.
        val next = _documents.value.filterNot { it.id == stored.id } + stored
        update(next)
        return stored
    }

    /** Persist an already-assembled document (the blobs are already on it). */
    suspend fun save(document: IDDocument): IDDocument {
        requireNotDuplicate(document)
        val next = _documents.value.filterNot { it.id == document.id } + document
        update(next)
        return document
    }

    /** Reject a second copy of the same document (ADR-0037 de-dup): same
     *  type + issuer + number + DOB + expiry, but a different id. A re-scan of
     *  the SAME stored id is allowed (idempotent update). */
    private fun requireNotDuplicate(doc: IDDocument) {
        val clash = _documents.value.any { it.id != doc.id && it.dedupeKey == doc.dedupeKey }
        if (clash) throw DuplicateIDDocumentException()
    }

    /** Remove a saved document by id. No-op if absent. */
    suspend fun delete(id: UUID) {
        val next = _documents.value.filterNot { it.id == id }
        if (next.size != _documents.value.size) update(next)
    }

    /** Rename a document. A blank name clears the nickname. */
    suspend fun setNickname(name: String?, id: UUID) {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        mutate(id) { it.copy(nickname = trimmed) }
    }

    /** Store an OpenSanctions screening result for a document. */
    suspend fun setSanctionsResult(result: SanctionsScreenResult, id: UUID) {
        mutate(id) { it.copy(sanctionsResult = result) }
    }

    /** Store an on-device Passive Authentication result for a document. */
    suspend fun setPassiveAuthResult(result: PassiveAuthResult, id: UUID) {
        mutate(id) { it.copy(passiveAuthResult = result) }
    }

    /** Look up a saved document by id without collecting the flow. */
    fun document(id: UUID): IDDocument? = _documents.value.firstOrNull { it.id == id }

    /**
     * Wipe every saved document. Drops the wrap key (sealed blobs become
     * unreadable) and clears the prefs. The wallet-wide reset path calls
     * this. Idempotent.
     */
    suspend fun reset() {
        update(emptyList())
        runCatching { secureStore.deleteKey() }
        prefs.edit().clear().apply()
    }

    /**
     * Drop the in-memory cache and re-read from storage. Used by the
     * wallet-wide reset path so a wipe done elsewhere surfaces in the
     * live UI without a relaunch.
     */
    fun reload() {
        _documents.value = load()
    }

    // ---- mutation helpers ----

    private suspend fun mutate(id: UUID, transform: (IDDocument) -> IDDocument) {
        val current = _documents.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        val next = current.toMutableList().apply { this[idx] = transform(this[idx]) }
        update(next)
    }

    private fun update(next: List<IDDocument>) {
        _documents.value = next
        persist(next)
    }

    // ---- persistence ----

    private fun persist(docs: List<IDDocument>) {
        val arr = JSONArray()
        for (d in docs) arr.put(encode(d))
        val json = arr.toString().toByteArray(Charsets.UTF_8)
        // Seal when hardware allows; otherwise fall back to plain base64
        // so dev devices without a secure keyguard still persist. The
        // SEALED flag records which path was taken so load() decodes
        // correctly even after a key state change.
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

    private fun load(): List<IDDocument> {
        val stored = prefs.getString(BLOB_KEY, null) ?: return emptyList()
        val wasSealed = prefs.getBoolean(SEALED_KEY, true)
        val raw = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull()
            ?: return emptyList()
        val json = if (wasSealed) {
            // The wrap key can be gone (wallet reset on another path) or
            // unusable (device locked); treat unreadable as empty rather
            // than crashing the UI.
            runCatching { secureStore.open(raw) }.getOrNull() ?: return emptyList()
        } else {
            raw
        }
        return runCatching {
            val arr = JSONArray(String(json, Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { i ->
                runCatching { decode(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    // ---- JSON codec (kept hand-rolled so the model stays a plain data class) ----

    private fun encode(d: IDDocument): JSONObject = JSONObject().apply {
        put("id", d.id.toString())
        putOpt("nickname", d.nickname)
        put("surname", d.surname)
        put("givenNames", d.givenNames)
        put("documentNumber", d.documentNumber)
        put("nationality", d.nationality)
        put("issuingAuthority", d.issuingAuthority)
        putOpt("sex", d.sex)
        put("dateOfBirth", d.dateOfBirth)
        put("dateOfExpiry", d.dateOfExpiry)
        put("documentType", d.documentType)
        putOpt("latinSurname", d.latinSurname)
        putOpt("latinGivenNames", d.latinGivenNames)
        putOpt("nativeFullName", d.nativeFullName)
        putOpt("userDeclaredKind", d.userDeclaredKind?.rawValue)
        putOpt("personalNumber", d.personalNumber)
        putOpt("placeOfBirth", d.placeOfBirth)
        putOpt("sod", encodeBytes(d.sod))
        putOpt("dg1", encodeBytes(d.dg1))
        putOpt("dg2", encodeBytes(d.dg2))
        putOpt("dg11", encodeBytes(d.dg11))
        putOpt("dg12", encodeBytes(d.dg12))
        putOpt("dg15", encodeBytes(d.dg15))
        putOpt("portraitJpeg", encodeBytes(d.portraitJpeg))
        putOpt("activeAuthChallengeHex", d.activeAuthChallengeHex)
        putOpt("activeAuthSignatureHex", d.activeAuthSignatureHex)
        if (d.activeAuthVerifiedLocally != null) put("activeAuthVerifiedLocally", d.activeAuthVerifiedLocally)
        put("readAt", d.readAt)
        put("schemaVersion", d.schemaVersion)
        d.sanctionsResult?.let { put("sanctionsResult", encodeSanctions(it)) }
        d.passiveAuthResult?.let { put("passiveAuthResult", encodePassiveAuth(it)) }
    }

    private fun decode(o: JSONObject): IDDocument = IDDocument(
        id = UUID.fromString(o.getString("id")),
        nickname = o.optStringOrNull("nickname"),
        surname = o.getString("surname"),
        givenNames = o.getString("givenNames"),
        documentNumber = o.getString("documentNumber"),
        nationality = o.getString("nationality"),
        issuingAuthority = o.getString("issuingAuthority"),
        sex = o.optStringOrNull("sex"),
        dateOfBirth = o.getString("dateOfBirth"),
        dateOfExpiry = o.getString("dateOfExpiry"),
        documentType = o.getString("documentType"),
        latinSurname = o.optStringOrNull("latinSurname"),
        latinGivenNames = o.optStringOrNull("latinGivenNames"),
        nativeFullName = o.optStringOrNull("nativeFullName"),
        userDeclaredKind = IDDocumentKind.fromRaw(o.optStringOrNull("userDeclaredKind")),
        personalNumber = o.optStringOrNull("personalNumber"),
        placeOfBirth = o.optStringOrNull("placeOfBirth"),
        sod = decodeBytes(o.optStringOrNull("sod")),
        dg1 = decodeBytes(o.optStringOrNull("dg1")),
        dg2 = decodeBytes(o.optStringOrNull("dg2")),
        dg11 = decodeBytes(o.optStringOrNull("dg11")),
        dg12 = decodeBytes(o.optStringOrNull("dg12")),
        dg15 = decodeBytes(o.optStringOrNull("dg15")),
        portraitJpeg = decodeBytes(o.optStringOrNull("portraitJpeg")),
        activeAuthChallengeHex = o.optStringOrNull("activeAuthChallengeHex"),
        activeAuthSignatureHex = o.optStringOrNull("activeAuthSignatureHex"),
        activeAuthVerifiedLocally =
            if (o.has("activeAuthVerifiedLocally")) o.getBoolean("activeAuthVerifiedLocally") else null,
        readAt = o.optLong("readAt", System.currentTimeMillis()),
        sanctionsResult = o.optJSONObject("sanctionsResult")?.let { decodeSanctions(it) },
        passiveAuthResult = o.optJSONObject("passiveAuthResult")?.let { decodePassiveAuth(it) },
        schemaVersion = o.optString("schemaVersion", "1.0.0"),
    )

    private fun encodeSanctions(r: SanctionsScreenResult): JSONObject = JSONObject().apply {
        put("outcome", r.outcome.rawValue)
        put("screenedAt", r.screenedAt)
        put("datasetVersion", r.datasetVersion)
        val arr = JSONArray()
        for (m in r.matches) {
            arr.put(JSONObject().put("name", m.name).put("listName", m.listName))
        }
        put("matches", arr)
    }

    private fun decodeSanctions(o: JSONObject): SanctionsScreenResult {
        val matchesArr = o.optJSONArray("matches")
        val matches = if (matchesArr == null) emptyList() else
            (0 until matchesArr.length()).map { i ->
                val m = matchesArr.getJSONObject(i)
                SanctionsMatchDetail(m.getString("name"), m.getString("listName"))
            }
        return SanctionsScreenResult(
            outcome = SanctionsOutcome.fromRaw(o.optStringOrNull("outcome")) ?: SanctionsOutcome.ERROR,
            screenedAt = o.optLong("screenedAt", 0L),
            datasetVersion = o.optString("datasetVersion", ""),
            matches = matches,
        )
    }

    private fun encodePassiveAuth(r: PassiveAuthResult): JSONObject = JSONObject().apply {
        put("status", r.status.rawValue)
        put("reason", r.reason)
        putOpt("cscaCountry", r.cscaCountry)
        put("checkedAt", r.checkedAt)
        putOpt("bundleVersion", r.bundleVersion)
        putOpt("dscIssuer", r.dscIssuer)
        putOpt("dscFingerprint", r.dscFingerprint)
    }

    private fun decodePassiveAuth(o: JSONObject): PassiveAuthResult = PassiveAuthResult(
        status = PassiveAuthResult.Status.fromRaw(o.optStringOrNull("status"))
            ?: PassiveAuthResult.Status.UNAVAILABLE,
        reason = o.optString("reason", ""),
        cscaCountry = o.optStringOrNull("cscaCountry"),
        checkedAt = o.optLong("checkedAt", 0L),
        bundleVersion = o.optStringOrNull("bundleVersion"),
        dscIssuer = o.optStringOrNull("dscIssuer"),
        dscFingerprint = o.optStringOrNull("dscFingerprint"),
    )

    private fun encodeBytes(b: ByteArray?): String? =
        b?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private fun decodeBytes(s: String?): ByteArray? =
        s?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }

    private fun JSONObject.putOpt(key: String, value: String?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    companion object {
        private const val PREFS = "iddocuments.store.v1"
        private const val BLOB_KEY = "iddocuments.sealed.v1"
        private const val SEALED_KEY = "iddocuments.sealed.flag.v1"
        private const val WRAP_ALIAS = "iddocuments.vault.wrap"

        // Constructing the store decrypts + JSON-decodes the (image-bearing)
        // document blob in init {} (~1.2s). The Identity screen is disposed on
        // every tab switch, so a fresh `remember { IDDocumentStore(context) }`
        // re-ran that load each time. Share one process-wide instance: the load
        // happens once, and a single StateFlow keeps every screen consistent
        // (previously separate instances did not see each other's saves).
        @Volatile private var shared: IDDocumentStore? = null

        fun shared(context: Context): IDDocumentStore =
            shared ?: synchronized(this) {
                shared ?: IDDocumentStore(context).also { shared = it }
            }
    }
}
