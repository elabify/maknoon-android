// Full v4 encrypted-backup payload (write + restore) for Android, matching the
// iOS reference wire contract in ADR-0035 field-for-field. iOS is the frozen
// reference; this conforms to it.
//
// The SDK IdentitySandwich.exportEncryptedBackup(extra) writes {v:4, entropyHex}
// and merges the `extra` sections this builder produces; restore decrypts the
// blob, rebuilds the identity, then applies the sections back onto the app
// stores. Crypto envelope + identity are unchanged (already cross-compatible);
// this fills in the rich payload (settings, lightning + passwords, credentials,
// passports, walletState).
//
// Cross-platform encoding rules (ADR-0035):
//   - createdAt (top-level): Int64 UNIX SECONDS (not ISO, not millis).
//   - every other date: ISO-8601 string at SECOND precision ("2026-06-20T13:53:53Z"),
//     no fractional seconds (Swift's .iso8601 decoder rejects fractional).
//   - binaries: standard base64 (padded, single line).
//   - walletState value: base64(UTF-8(JSON)). Blob keys carry their JSON string
//     verbatim; scalar keys carry a JSON fragment ("usd" / true / "dark").
//
// Documented divergences (carried best-effort, not cross-platform):
//   - appstore.* / miniapp.settings.v1 / yubikey.enrollments.v1 walletState keys:
//     Android stores these in different shapes / a sealed blob, so they are NOT
//     in the cross-platform walletState. Apps install state, mini-app settings,
//     and YubiKey enrollments do not migrate across platforms (yet).
//   - SettingsBackup.devices: Android has no registeredAt / promotions storage,
//     so those iOS fields are emitted with defaults (now / omitted) for iOS to
//     consume and ignored on Android import. Hardware-wallet linkage rides in the
//     wallet-store JSON (walletState) regardless.
//   - SettingsBackup.bitcoin / ethereum: omitted; the per-network URL/fiat
//     overrides round-trip via walletState's networks.<chain>.settings.v1 blob.

package com.elabify.app.maknoon.backup

import android.content.Context
import android.util.Base64
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.IDDocumentKind
import com.elabify.app.maknoon.iddocument.IDDocumentStore
import com.elabify.app.maknoon.iddocument.PassiveAuthResult
import com.elabify.app.maknoon.iddocument.SanctionsMatchDetail
import com.elabify.app.maknoon.iddocument.SanctionsOutcome
import com.elabify.app.maknoon.iddocument.SanctionsScreenResult
import com.elabify.app.maknoon.ui.settings.AddressBookEntry
import com.elabify.app.maknoon.ui.settings.AddressBookNetwork
import com.elabify.app.maknoon.ui.settings.AddressBookStore
import com.elabify.app.maknoon.ui.settings.FiatPreferences
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.DeviceEntity
import com.elabify.musnad.data.IssuerEntity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.backup.EncryptedBackup
import com.elabify.musnad.wallet.lightning.LightningAccount
import com.elabify.musnad.wallet.lightning.LightningAccountStore
import com.elabify.musnad.wallet.lightning.LightningAccountWithSecret
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object MaknoonBackupV4 {

    /**
     * Suggested export filename, IDENTICAL to the iOS spec
     * (`EncryptedBackup.defaultFilename`): `maknoon-backup-yyyyMMdd-HHmm.json` in
     * the device's local time, Gregorian calendar, fixed (US) digits. So a backup
     * made on either platform has the same name shape.
     */
    fun defaultBackupFilename(): String {
        val fmt = java.text.SimpleDateFormat("'maknoon-backup-'yyyyMMdd-HHmm'.json'", java.util.Locale.US)
        fmt.calendar = java.util.Calendar.getInstance()
        return fmt.format(java.util.Date())
    }

    // ---- date helpers (ISO-8601, second precision; iOS .iso8601 compatible) ----

    private fun isoFromEpochMs(ms: Long): String =
        Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun epochMsFromIso(iso: String): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    private fun b64Utf8(s: String): String = b64(s.toByteArray(Charsets.UTF_8))
    private fun unb64Utf8(s: String): String = String(unb64(s), Charsets.UTF_8)

    // ---- walletState key routing ----

    private const val WALLET_PREFS = "maknoon.wallets.v1"
    private const val USER_DEFAULTS = "UserDefaults"

    /** Blob keys: JSON documents in maknoon.wallets.v1 (exact iOS key parity). */
    private val WALLET_BLOB_KEYS = listOf(
        "networks.bitcoin.wallets.v1", "networks.bitcoin.active.v1", "networks.bitcoin.settings.v1",
        "networks.bitcoin.labels.address.v1", "networks.bitcoin.labels.output.v1",
        "networks.ethereum.wallets.v2", "networks.ethereum.active.v1", "networks.ethereum.currentNetwork.v3",
        "networks.ethereum.tokens.v1", "networks.ethereum.tokens.catalog.v1", "networks.ethereum.tokens.catalogFetch.v1",
        "networks.ethereum.custom.v1", "networks.ethereum.settings.v1",
        "networks.solana.wallets.v1", "networks.solana.wallets.v2", "networks.solana.active.v1",
        "networks.solana.currentNetwork.v1", "networks.solana.settings.v1", "networks.solana.tokens.installed.v1",
        "networks.solana.tokens.catalog.v1", "networks.solana.tokens.catalogFetch.v1",
        "networks.tron.wallets.v1", "networks.tron.wallets.v2", "networks.tron.active.v1",
        "networks.tron.currentNetwork.v1", "networks.tron.settings.v1", "networks.tron.tokens.installed.v1",
        "networks.tron.tokens.catalog.v1", "networks.tron.tokens.catalogFetch.v1",
        "networks.ethereum.currentNetwork.chainwide.v1", "networks.solana.currentNetwork.chainwide.v1",
        "networks.tron.currentNetwork.chainwide.v1", "networks.solana.tokens.ignored.v1",
    )

    /** Scalar keys: stored in the "UserDefaults" prefs file; carried as JSON fragments. */
    private val SCALAR_KEYS = listOf(
        "app.fiatCurrencyCode", "app.fiatReferenceEnabled",
        "display.theme", "display.autoLock", "display.language",
    )

    private fun captureWalletState(context: Context): JSONObject {
        val out = JSONObject()
        captureFrom(context.getSharedPreferences(WALLET_PREFS, Context.MODE_PRIVATE), WALLET_BLOB_KEYS, out)
        captureFrom(context.getSharedPreferences(USER_DEFAULTS, Context.MODE_PRIVATE), SCALAR_KEYS, out)
        return out
    }

    /**
     * Capture each key's value as `base64(UTF-8(JSON-text))`, handling its ACTUAL
     * SharedPreferences type (a key listed as a "blob" may still hold a primitive,
     * e.g. the `tokens.catalogFetch.v1` last-fetch timestamps are Longs). A stored
     * JSON document is carried verbatim; everything else becomes a JSON fragment.
     */
    private fun captureFrom(prefs: android.content.SharedPreferences, keys: List<String>, out: JSONObject) {
        val all = prefs.all
        for (key in keys) {
            val v = all[key] ?: continue
            val text: String = when (v) {
                is String -> if (looksLikeJsonDoc(v)) v else JSONObject.quote(v)
                is Boolean -> v.toString()
                is Int -> v.toString()
                is Long -> v.toString()
                is Float -> v.toString()
                is Double -> v.toString()
                is Set<*> -> JSONArray(v.map { it.toString() }).toString()
                else -> JSONObject.quote(v.toString())
            }
            out.put(key, b64Utf8(text))
        }
    }

    private fun looksLikeJsonDoc(s: String): Boolean {
        val t = s.trimStart()
        return t.startsWith("{") || t.startsWith("[")
    }

    private fun applyWalletState(context: Context, ws: JSONObject) {
        val wallet = context.getSharedPreferences(WALLET_PREFS, Context.MODE_PRIVATE).edit()
        val ud = context.getSharedPreferences(USER_DEFAULTS, Context.MODE_PRIVATE).edit()
        val it = ws.keys()
        while (it.hasNext()) {
            val key = it.next()
            val text = runCatching { unb64Utf8(ws.getString(key)) }.getOrNull() ?: continue
            val editor = when {
                WALLET_BLOB_KEYS.contains(key) -> wallet
                SCALAR_KEYS.contains(key) -> ud
                else -> continue
            }
            // Discriminate by the parsed JSON type: a document goes back as the
            // raw JSON string; a fragment goes back as its typed primitive.
            val parsed = runCatching { JSONArray("[$text]").get(0) }.getOrNull()
            when (parsed) {
                is JSONObject, is JSONArray -> editor.putString(key, text)
                is String -> editor.putString(key, parsed)
                is Boolean -> editor.putBoolean(key, parsed)
                is Int -> editor.putLong(key, parsed.toLong())
                is Long -> editor.putLong(key, parsed)
                is Double -> editor.putLong(key, parsed.toLong())
                else -> editor.putString(key, text)
            }
        }
        // commit (synchronous) so the wallet stores, created lazily on first
        // Wallet-tab access AFTER restore, read the restored values, and the
        // FiatPreferences / DisplayPreferences reloads below see them.
        wallet.commit()
        ud.commit()
    }

    // ---- ID documents (inline base64 + ISO dates) ----

    private fun encodeIdDocument(d: IDDocument): JSONObject = JSONObject().apply {
        put("id", d.id.toString())
        d.nickname?.let { put("nickname", it) }
        put("surname", d.surname)
        put("givenNames", d.givenNames)
        put("documentNumber", d.documentNumber)
        put("nationality", d.nationality)
        put("issuingAuthority", d.issuingAuthority)
        d.sex?.let { put("sex", it) }
        put("dateOfBirth", d.dateOfBirth)
        put("dateOfExpiry", d.dateOfExpiry)
        put("documentType", d.documentType)
        d.latinSurname?.let { put("latinSurname", it) }
        d.latinGivenNames?.let { put("latinGivenNames", it) }
        d.nativeFullName?.let { put("nativeFullName", it) }
        d.userDeclaredKind?.let { put("userDeclaredKind", it.rawValue) }
        d.personalNumber?.let { put("personalNumber", it) }
        d.placeOfBirth?.let { put("placeOfBirth", it) }
        d.sod?.let { put("sod", b64(it)) }
        d.dg1?.let { put("dg1", b64(it)) }
        d.dg2?.let { put("dg2", b64(it)) }
        d.dg11?.let { put("dg11", b64(it)) }
        d.dg12?.let { put("dg12", b64(it)) }
        d.dg15?.let { put("dg15", b64(it)) }
        d.portraitJpeg?.let { put("portraitJpeg", b64(it)) }
        d.activeAuthChallengeHex?.let { put("activeAuthChallengeHex", it) }
        d.activeAuthSignatureHex?.let { put("activeAuthSignatureHex", it) }
        d.activeAuthVerifiedLocally?.let { put("activeAuthVerifiedLocally", it) }
        put("readAt", isoFromEpochMs(d.readAt))
        put("schemaVersion", d.schemaVersion)
        d.sanctionsResult?.let { put("sanctionsResult", encodeSanctions(it)) }
        d.passiveAuthResult?.let { put("passiveAuthResult", encodePassiveAuth(it)) }
    }

    private fun encodeSanctions(r: SanctionsScreenResult): JSONObject = JSONObject().apply {
        put("outcome", r.outcome.rawValue)
        put("screenedAt", isoFromEpochMs(r.screenedAt))
        put("datasetVersion", r.datasetVersion)
        val arr = JSONArray()
        for (m in r.matches) arr.put(JSONObject().put("name", m.name).put("listName", m.listName))
        put("matches", arr)
    }

    private fun encodePassiveAuth(r: PassiveAuthResult): JSONObject = JSONObject().apply {
        put("status", r.status.rawValue)
        put("reason", r.reason)
        r.cscaCountry?.let { put("cscaCountry", it) }
        put("checkedAt", isoFromEpochMs(r.checkedAt))
        r.bundleVersion?.let { put("bundleVersion", it) }
        r.dscIssuer?.let { put("dscIssuer", it) }
        r.dscFingerprint?.let { put("dscFingerprint", it) }
    }

    private fun decodeIdDocument(o: JSONObject): IDDocument {
        fun bytes(k: String) = if (o.isNull(k)) null else runCatching { unb64(o.getString(k)) }.getOrNull()
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        return IDDocument(
            id = runCatching { UUID.fromString(o.getString("id")) }.getOrDefault(UUID.randomUUID()),
            nickname = str("nickname"),
            surname = o.optString("surname", ""),
            givenNames = o.optString("givenNames", ""),
            documentNumber = o.optString("documentNumber", ""),
            nationality = o.optString("nationality", ""),
            issuingAuthority = o.optString("issuingAuthority", ""),
            sex = str("sex"),
            dateOfBirth = o.optString("dateOfBirth", ""),
            dateOfExpiry = o.optString("dateOfExpiry", ""),
            documentType = o.optString("documentType", ""),
            latinSurname = str("latinSurname"),
            latinGivenNames = str("latinGivenNames"),
            nativeFullName = str("nativeFullName"),
            userDeclaredKind = str("userDeclaredKind")?.let { raw -> IDDocumentKind.entries.firstOrNull { it.rawValue == raw } },
            personalNumber = str("personalNumber"),
            placeOfBirth = str("placeOfBirth"),
            sod = bytes("sod"), dg1 = bytes("dg1"), dg2 = bytes("dg2"),
            dg11 = bytes("dg11"), dg12 = bytes("dg12"), dg15 = bytes("dg15"),
            portraitJpeg = bytes("portraitJpeg"),
            activeAuthChallengeHex = str("activeAuthChallengeHex"),
            activeAuthSignatureHex = str("activeAuthSignatureHex"),
            activeAuthVerifiedLocally = if (o.has("activeAuthVerifiedLocally") && !o.isNull("activeAuthVerifiedLocally")) o.getBoolean("activeAuthVerifiedLocally") else null,
            readAt = str("readAt")?.let { epochMsFromIso(it) } ?: System.currentTimeMillis(),
            sanctionsResult = o.optJSONObject("sanctionsResult")?.let { decodeSanctions(it) },
            passiveAuthResult = o.optJSONObject("passiveAuthResult")?.let { decodePassiveAuth(it) },
            schemaVersion = o.optString("schemaVersion", "1.0.0"),
        )
    }

    private fun decodeSanctions(o: JSONObject): SanctionsScreenResult {
        val matchesArr = o.optJSONArray("matches")
        val matches = if (matchesArr == null) emptyList() else
            (0 until matchesArr.length()).map { i ->
                val m = matchesArr.getJSONObject(i)
                SanctionsMatchDetail(m.optString("name"), m.optString("listName"))
            }
        return SanctionsScreenResult(
            outcome = SanctionsOutcome.fromRaw(o.optString("outcome")) ?: SanctionsOutcome.ERROR,
            screenedAt = epochMsFromIso(o.optString("screenedAt")),
            datasetVersion = o.optString("datasetVersion", ""),
            matches = matches,
        )
    }

    private fun decodePassiveAuth(o: JSONObject): PassiveAuthResult = PassiveAuthResult(
        status = PassiveAuthResult.Status.fromRaw(o.optString("status")) ?: PassiveAuthResult.Status.UNAVAILABLE,
        reason = o.optString("reason", ""),
        cscaCountry = if (o.has("cscaCountry") && !o.isNull("cscaCountry")) o.getString("cscaCountry") else null,
        checkedAt = epochMsFromIso(o.optString("checkedAt")),
        bundleVersion = if (o.has("bundleVersion") && !o.isNull("bundleVersion")) o.getString("bundleVersion") else null,
        dscIssuer = if (o.has("dscIssuer") && !o.isNull("dscIssuer")) o.getString("dscIssuer") else null,
        dscFingerprint = if (o.has("dscFingerprint") && !o.isNull("dscFingerprint")) o.getString("dscFingerprint") else null,
    )

    // ---- build the v4 extra sections (export) ----

    /** What a backup contains, as a human list, shown to the user at EXPORT
     *  time so it can be eyeballed against the import confirmation, and so a new
     *  config that silently isn't being backed up is easy to spot. */
    data class ExportSummary(val items: List<String>)

    /** Per-chain wallet count lines from a walletState object. Shared by the
     *  EXPORT manifest and the IMPORT confirmation so the two read identically. */
    fun walletLines(ws: JSONObject?): List<String> {
        if (ws == null) return emptyList()
        val out = mutableListOf<String>()
        val walletKeys = linkedMapOf(
            "Bitcoin" to "networks.bitcoin.wallets.v1",
            "Ethereum" to "networks.ethereum.wallets.v2",
            "Solana" to "networks.solana.wallets.v2",
            "Tron" to "networks.tron.wallets.v2",
        )
        for ((label, key) in walletKeys) {
            val b64 = ws.optString(key, "")
            if (b64.isEmpty()) continue
            val n = runCatching { JSONArray(unb64Utf8(b64)).length() }.getOrDefault(0)
            if (n > 0) out.add("$label wallets ($n)")
        }
        return out
    }

    /** Build the export summary by counting what [buildExtra] produced. */
    fun summarize(context: Context, extra: JSONObject): ExportSummary {
        val items = mutableListOf("Identity & recovery phrase")
        val ws = extra.optJSONObject("walletState")
        items.addAll(walletLines(ws))
        if (ws != null) items.add("Networks, RPC/explorer overrides, tokens, currency & display")
        extra.optJSONObject("settings")?.let { s ->
            s.optJSONArray("knownIssuers")?.takeIf { it.length() > 0 }?.let { items.add("Trusted issuers (${it.length()})") }
            s.optJSONArray("devices")?.takeIf { it.length() > 0 }?.let { items.add("Hardware devices (${it.length()})") }
            s.optJSONArray("addressBook")?.takeIf { it.length() > 0 }?.let { items.add("Address book (${it.length()})") }
        }
        extra.optJSONArray("lightningAccounts")?.takeIf { it.length() > 0 }?.let { items.add("Lightning accounts (${it.length()})") }
        extra.optJSONObject("credentials")?.optJSONArray("credentials")?.takeIf { it.length() > 0 }
            ?.let { items.add("Credentials (${it.length()})") }
        extra.optJSONObject("idDocuments")?.optJSONArray("documents")?.takeIf { it.length() > 0 }
            ?.let { items.add("ID documents / passports (${it.length()})") }
        return ExportSummary(items)
    }

    suspend fun buildExtra(context: Context): JSONObject {
        val ctx = context.applicationContext
        val extra = JSONObject()
        extra.put("createdAt", System.currentTimeMillis() / 1000L) // Int64 unix seconds

        // settings: knownIssuers + devices + addressBook (bitcoin/ethereum omitted;
        // their URL/fiat overrides ride in walletState's networks.*.settings.v1).
        val db = MaknoonStore.open(ctx)
        val settings = JSONObject().apply {
            put("v", 2)
            put("exportedAt", isoFromEpochMs(System.currentTimeMillis()))
            runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull()?.let { put("appVersion", it) }
            val issuers = JSONArray()
            runCatching { db.issuers().all() }.getOrDefault(emptyList())
                .filter { it.trusted }.forEach { issuers.put(it.host) }
            put("knownIssuers", issuers)
            // Devices live in DeviceRegistry (SharedPreferences), NOT the Room DB.
            // Emit the iOS SettingsBackup.DeviceSection shape (id, kind rawValue,
            // serial, label, registeredAt ISO). Promotions are omitted from the
            // cross-platform backup: the device RECORD + UUID is what the wallet's
            // hardware binding resolves against, and the promotion shapes / date
            // formats diverge per platform (a mismatch there would fail the whole
            // settings decode).
            val devices = JSONArray()
            runCatching { com.elabify.musnad.devices.DeviceRegistry(ctx).devices }
                .getOrDefault(emptyList()).forEach { d ->
                    devices.put(
                        JSONObject()
                            .put("id", d.id.toString())
                            .put("kind", d.kind.rawValue)
                            .put("serial", d.serial)
                            .put("label", d.label)
                            .put("registeredAt", isoFromEpochMs(d.registeredAtEpochMs)),
                    )
                }
            put("devices", devices)
            val book = JSONArray()
            AddressBookStore(ctx).all().forEach { e ->
                book.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("address", e.address)
                        .put("network", e.network.key)
                        .put("createdAt", isoFromEpochMs(System.currentTimeMillis())),
                )
            }
            put("addressBook", book)
        }
        extra.put("settings", settings)

        // lightningAccounts (+passwords)
        val ln = JSONArray()
        runCatching { LightningAccountStore(ctx).exportForEncryptedBackup() }
            .getOrDefault(emptyList()).forEach { item ->
                val acc = JSONObject()
                    .put("id", item.account.id.toString())
                    .put("label", item.account.label)
                    .put("serverURL", item.account.serverURL)
                    .put("username", item.account.username)
                    .put("allowInsecureTLS", item.account.allowInsecureTLS)
                    .put("createdAt", isoFromEpochMs(item.account.createdAt))
                ln.put(JSONObject().put("account", acc).put("password", item.password))
            }
        extra.put("lightningAccounts", ln)

        // credentials: { credentials: [<issuer Credential JSON>], nicknames: {cid:nickname} }
        val creds = JSONArray()
        val nicknames = JSONObject()
        runCatching { db.credentials().all() }.getOrDefault(emptyList()).forEach { c ->
            runCatching { JSONObject(c.credentialJson) }.getOrNull()?.let { creds.put(it) }
            if (!c.nickname.isNullOrEmpty()) nicknames.put(c.cid, c.nickname)
        }
        extra.put("credentials", JSONObject().put("credentials", creds).put("nicknames", nicknames))

        // idDocuments (inline)
        val docs = JSONArray()
        IDDocumentStore.shared(ctx).documents.value.forEach { docs.put(encodeIdDocument(it)) }
        extra.put("idDocuments", JSONObject().put("documents", docs))

        // walletState
        extra.put("walletState", captureWalletState(ctx))
        return extra
    }

    // ---- restore (decrypt + identity + apply sections) ----

    /**
     * Outcome of a restore: what came back (per section) and anything that
     * could not be imported. Shown to the user on a confirmation screen before
     * continuing, so a partial restore is never silently dropped.
     */
    data class RestoreReport(
        val restored: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    ) {
        val hadWarnings: Boolean get() = warnings.isNotEmpty()
    }

    suspend fun restore(
        context: Context,
        blob: ByteArray,
        passphrase: String,
        nowSec: Long,
        store: IdentityStore,
    ): RestoreReport {
        val ctx = context.applicationContext
        // Decrypt + identity FIRST. A wrong passphrase / tampered blob throws
        // here; the caller surfaces it as the error case (restore not done).
        val plaintext = EncryptedBackup.decrypt(blob, passphrase)
        val json = JSONObject(String(plaintext, Charsets.UTF_8))
        IdentitySandwich.restoreFromEncryptedBackup(blob, passphrase, nowSec, store)

        val restored = mutableListOf("Identity & recovery phrase")
        val warnings = mutableListOf<String>()

        // walletState (on-chain wallets + network/URL overrides + fiat/display prefs)
        json.optJSONObject("walletState")?.let { ws ->
            runCatching { applyWalletState(ctx, ws) }
                .onSuccess {
                    // Same per-chain lines the export manifest shows, so the two
                    // can be compared 1:1.
                    restored.addAll(walletLines(ws))
                    restored.add("Networks, RPC/explorer overrides, tokens, currency & display")
                }
                .onFailure { warnings.add("Wallet state: ${it.message ?: "could not import"}") }
        }

        json.optJSONObject("settings")?.let { settings ->
            val db = MaknoonStore.open(ctx)
            settings.optJSONArray("knownIssuers")?.let { arr ->
                var n = 0
                for (i in 0 until arr.length()) {
                    val host = arr.optString(i)
                    if (host.isNotEmpty()) {
                        runCatching { db.issuers().upsert(IssuerEntity(host, true, null)) }
                            .onSuccess { n++ }
                            .onFailure { warnings.add("Trusted issuer '$host': ${it.message ?: "failed"}") }
                    }
                }
                if (n > 0) restored.add("Trusted issuers ($n)")
            }
            // Devices live in DeviceRegistry (SharedPreferences), NOT the Room DB.
            // replaceAll preserves the original UUIDs + promotions so a restored
            // wallet's hardware binding still resolves to the device record.
            settings.optJSONArray("devices")?.let { arr ->
                val devices = (0 until arr.length()).mapNotNull { i ->
                    runCatching {
                        com.elabify.musnad.devices.RegisteredDevice.fromJson(arr.getJSONObject(i))
                    }.getOrNull()
                }
                if (arr.length() > devices.size) {
                    warnings.add("Hardware devices: ${arr.length() - devices.size} of ${arr.length()} could not be read")
                }
                if (devices.isNotEmpty()) {
                    runCatching { com.elabify.musnad.devices.DeviceRegistry(ctx).replaceAll(devices) }
                        .onSuccess { restored.add("Hardware devices (${devices.size})") }
                        .onFailure { warnings.add("Hardware devices: ${it.message ?: "failed"}") }
                }
            }
            settings.optJSONArray("addressBook")?.let { arr ->
                val ab = AddressBookStore(ctx)
                var n = 0
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    runCatching {
                        ab.upsert(
                            AddressBookEntry(
                                id = e.optString("id"),
                                name = e.optString("name"),
                                address = e.optString("address"),
                                network = AddressBookNetwork.fromKey(e.optString("network")),
                            ),
                        )
                    }.onSuccess { n++ }.onFailure { warnings.add("Address book entry: ${it.message ?: "failed"}") }
                }
                if (n > 0) restored.add("Address book ($n)")
            }
        }

        // lightningAccounts (+passwords)
        json.optJSONArray("lightningAccounts")?.let { arr ->
            val items = mutableListOf<LightningAccountWithSecret>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val item = arr.getJSONObject(i)
                    val a = item.getJSONObject("account")
                    items.add(
                        LightningAccountWithSecret(
                            account = LightningAccount(
                                id = runCatching { UUID.fromString(a.getString("id")) }.getOrDefault(UUID.randomUUID()),
                                label = a.optString("label"),
                                serverURL = a.optString("serverURL"),
                                username = a.optString("username"),
                                allowInsecureTLS = a.optBoolean("allowInsecureTLS", false),
                                createdAt = a.optString("createdAt").takeIf { it.isNotEmpty() }?.let { epochMsFromIso(it) }
                                    ?: System.currentTimeMillis(),
                            ),
                            password = item.optString("password"),
                        ),
                    )
                }.onFailure { warnings.add("Lightning account: ${it.message ?: "failed"}") }
            }
            if (items.isNotEmpty()) {
                runCatching { LightningAccountStore(ctx).importFromEncryptedBackup(items) }
                    .onSuccess { restored.add("Lightning accounts (${items.size})") }
                    .onFailure { warnings.add("Lightning accounts: ${it.message ?: "failed"}") }
            }
        }

        // credentials
        json.optJSONObject("credentials")?.optJSONArray("credentials")?.let { arr ->
            val db = MaknoonStore.open(ctx)
            val nicknames = json.optJSONObject("credentials")?.optJSONObject("nicknames")
            var n = 0
            for (i in 0 until arr.length()) {
                val cred = arr.getJSONObject(i)
                runCatching {
                    val header = cred.getJSONObject("header")
                    val cid = header.getString("cid")
                    db.credentials().upsert(
                        CredentialEntity(
                            cid = cid,
                            issuerDid = header.optString("iss"),
                            subjectDid = header.optString("sub"),
                            schema = header.optString("schema"),
                            credentialJson = cred.toString(),
                            nickname = nicknames?.optString(cid)?.takeIf { it.isNotEmpty() },
                            createdAt = header.optLong("iat", System.currentTimeMillis() / 1000L) * 1000L,
                        ),
                    )
                }.onSuccess { n++ }.onFailure { warnings.add("Credential: ${it.message ?: "failed"}") }
            }
            if (n > 0) restored.add("Credentials ($n)")
        }

        // idDocuments
        json.optJSONObject("idDocuments")?.optJSONArray("documents")?.let { arr ->
            val docStore = IDDocumentStore.shared(ctx)
            var n = 0
            for (i in 0 until arr.length()) {
                runCatching { docStore.save(decodeIdDocument(arr.getJSONObject(i))) }
                    .onSuccess { n++ }.onFailure { warnings.add("ID document: ${it.message ?: "failed"}") }
            }
            if (n > 0) restored.add("ID documents / passports ($n)")
        }

        // Post-restore reloads so the live UI reflects the restored state. The
        // wallet stores are created lazily on first Wallet-tab access (never
        // during onboarding), so they pick up the committed prefs on their own;
        // FiatPreferences / DisplayPreferences were already initialized at app
        // start, so they must be explicitly re-read.
        runCatching { FiatPreferences.reload() }
        runCatching { com.elabify.app.maknoon.ui.theme.DisplayPreferences.reload() }
        runCatching { LightningAccountStore(ctx).reload() }
        runCatching { IDDocumentStore.shared(ctx).reload() }
        // Refresh the passport CSCA trust list (not part of the backup; it's a
        // downloadable trust store) so passport passive-auth works after a restore.
        runCatching { com.elabify.app.maknoon.iddocument.CSCATrustStore(ctx).refresh(force = true) }

        return RestoreReport(restored = restored, warnings = warnings)
    }
}
