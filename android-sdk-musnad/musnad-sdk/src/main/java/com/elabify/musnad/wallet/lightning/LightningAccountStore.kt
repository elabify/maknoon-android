// Persisted list of LNDHub-compatible accounts + the active selection, ported
// 1:1 from iOS LightningAccountStore.swift.
//
// Passwords live sealed in AndroidSecureStore (one StrongBox/TEE-wrapped entry
// per account id, the Android analog of the iOS per-account Keychain entry).
// The public account metadata (label, server URL, username, TLS flag) ships in
// SharedPreferences as plain JSON (iOS used UserDefaults). Storage keys match
// iOS: `lightning.accounts.v1`, `lightning.active.v1`, `lightning.password.<id>`.
//
// Two add paths: manual entry through `add(account, password)`, or
// `parseImportURL` on a Zeus-style `lndhub://user:pass@server[:port][/path]`
// URL (paste or QR scan). Both end up calling `add(account, password)` after
// the caller has resolved a final label.

package com.elabify.musnad.wallet.lightning

import android.content.Context
import android.util.Base64
import com.elabify.musnad.crypto.AndroidSecureStore
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class LightningAccountStore(
    private val context: Context,
    /** Factory for the per-account sealing key. Defaults to a StrongBox/TEE
     *  AndroidSecureStore aliased per account, mirroring the iOS per-account
     *  Keychain entry. Overridable for tests. */
    private val secureStoreFor: (UUID) -> AndroidSecureStore = { id ->
        AndroidSecureStore(passwordWrapAlias(id))
    },
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _accounts = ArrayList<LightningAccount>()
    val accounts: List<LightningAccount> get() = _accounts.toList()

    var activeAccountId: UUID? = null
        private set

    init { load() }

    val activeAccount: LightningAccount?
        get() {
            val id = activeAccountId ?: return _accounts.firstOrNull()
            return _accounts.firstOrNull { it.id == id } ?: _accounts.firstOrNull()
        }

    // ---- mutate ----

    fun add(account: LightningAccount, password: String, makeActive: Boolean = true): LightningAccount {
        _accounts.add(account)
        savePassword(password, account.id)
        if (makeActive) activeAccountId = account.id
        persist()
        return account
    }

    fun update(account: LightningAccount, newPassword: String? = null) {
        val idx = _accounts.indexOfFirst { it.id == account.id }
        if (idx < 0) return
        _accounts[idx] = account
        if (newPassword != null) savePassword(newPassword, account.id)
        persist()
    }

    fun remove(id: UUID) {
        _accounts.removeAll { it.id == id }
        runCatching { secureStoreFor(id).deleteKey() }
        prefs.edit().remove(passwordSealedKey(id)).apply()
        if (activeAccountId == id) activeAccountId = _accounts.firstOrNull()?.id
        persist()
    }

    fun setActive(id: UUID) {
        if (_accounts.none { it.id == id }) return
        activeAccountId = id
        prefs.edit().putString(ACTIVE_KEY, id.toString()).apply()
    }

    // ---- credentials ----

    /** The sealed password for an account, or null if no entry / wrap key gone. */
    fun password(id: UUID): String? {
        val sealed = prefs.getString(passwordSealedKey(id), null) ?: return null
        return runCatching {
            val plain = secureStoreFor(id).open(Base64.decode(sealed, Base64.NO_WRAP))
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun savePassword(password: String, id: UUID) {
        val sealed = secureStoreFor(id).seal(password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(passwordSealedKey(id), Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    // ---- encrypted-backup export / import ----

    /** Snapshot every account + its sealed password for the encrypted backup.
     *  Accounts whose sealed entry has gone missing are skipped silently. */
    fun exportForEncryptedBackup(): List<LightningAccountWithSecret> {
        val out = ArrayList<LightningAccountWithSecret>()
        for (account in _accounts) {
            val pw = password(account.id) ?: continue
            out.add(LightningAccountWithSecret(account, pw))
        }
        return out
    }

    /** Restore Lightning accounts from a decrypted backup, reusing add() so the
     *  sealed-password write is the same code path as normal creation. */
    fun importFromEncryptedBackup(items: List<LightningAccountWithSecret>) {
        for (item in items) {
            // remove first so add() doesn't surface dup-id state for users who
            // restore on top of an already-configured device.
            remove(item.account.id)
            add(item.account, item.password, makeActive = false)
        }
        if (activeAccountId == null) _accounts.firstOrNull()?.let { setActive(it.id) }
    }

    // ---- persistence ----

    /** Drop the in-memory cache and re-read from storage. Used by the
     *  wallet-wide reset path so a wipe surfaces immediately. */
    fun reload() {
        _accounts.clear()
        activeAccountId = null
        load()
    }

    private fun load() {
        prefs.getString(ACCOUNTS_KEY, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    _accounts.add(LightningAccount.fromJson(arr.getJSONObject(i)))
                }
            }
        }
        prefs.getString(ACTIVE_KEY, null)?.let { s ->
            runCatching { activeAccountId = UUID.fromString(s) }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        for (a in _accounts) arr.put(a.toJson())
        val editor = prefs.edit().putString(ACCOUNTS_KEY, arr.toString())
        if (activeAccountId != null) editor.putString(ACTIVE_KEY, activeAccountId.toString())
        else editor.remove(ACTIVE_KEY)
        editor.apply()
    }

    companion object {
        private const val PREFS = "lightning.store.v1"
        private const val ACCOUNTS_KEY = "lightning.accounts.v1"
        private const val ACTIVE_KEY = "lightning.active.v1"

        private fun passwordSealedKey(id: UUID) = "lightning.password.$id"
        private fun passwordWrapAlias(id: UUID) = "lightning.password.wrap.$id"

        /**
         * Parse an `lndhub://login:password@<server>` import URL. The `lndhub://`
         * scheme is a de-facto BlueWallet standard, but the `<server>` part comes
         * in TWO shapes in the wild and we accept both:
         *
         *   1. Zeus / bare host:  `lndhub://login:password@host[:port][/path]`
         *      (https is implied; java.net.URI parses it cleanly).
         *   2. BlueWallet / LNbits: `lndhub://login:password@https://host[/path]`
         *      (the full scheme is embedded after the `@`). This is what the
         *      LNbits LNDHub extension QR encodes, and java.net.URI cannot parse
         *      it (the inner `//` breaks authority parsing), so we hand-split.
         *
         * Returns null on malformed input. A trailing `?tls=false` query
         * (BlueWallet writes this for self-signed hubs) surfaces through
         * `allowInsecureTLS`. Caller decides the final label and persists via
         * `add(account, password)`.
         */
        fun parseImportURL(raw: String, defaultLabel: String? = null): Pair<LightningAccount, String>? {
            var s = raw.trim()
            if (!s.lowercase().startsWith("lndhub://")) return null
            s = s.substring("lndhub://".length)

            // Split a trailing query (?tls=false) off the server, if present.
            var query = ""
            val qIdx = s.indexOf('?')
            if (qIdx >= 0) {
                query = s.substring(qIdx + 1)
                s = s.substring(0, qIdx)
            }

            // login:password @ server. The server part never contains '@' in
            // either shape, and LNDHub login/password are opaque tokens (no '@'),
            // so the first '@' is the userInfo separator.
            val atIdx = s.indexOf('@')
            if (atIdx <= 0 || atIdx >= s.length - 1) return null
            val userInfo = s.substring(0, atIdx)
            val serverPart = s.substring(atIdx + 1).trimEnd('/')
            if (serverPart.isEmpty()) return null

            val sepIdx = userInfo.indexOf(':')
            if (sepIdx <= 0 || sepIdx >= userInfo.length - 1) return null
            val user = userInfo.substring(0, sepIdx)
            val pass = userInfo.substring(sepIdx + 1)

            // Shape 2 already carries http(s)://; shape 1 implies https.
            val lower = serverPart.lowercase()
            val server = if (lower.startsWith("https://") || lower.startsWith("http://")) {
                serverPart
            } else {
                "https://$serverPart"
            }

            // BlueWallet-style `?tls=false` opts into self-signed certs.
            var allowInsecureTLS = false
            query.split("&").forEach { item ->
                val kv = item.split("=", limit = 2)
                if (kv.size == 2 && kv[0].lowercase() == "tls" && kv[1].lowercase() == "false") {
                    allowInsecureTLS = true
                }
            }

            val host = runCatching { URI(server).host }.getOrNull()
                ?: server.substringAfter("://").substringBefore('/').substringBefore(':')
            val label = if (!defaultLabel.isNullOrBlank()) defaultLabel else host
            val account = LightningAccount(
                label = label,
                serverURL = server,
                username = user,
                allowInsecureTLS = allowInsecureTLS,
            )
            return account to pass
        }
    }
}
