// One LNDHub-backed Lightning custodial account, ported 1:1 from iOS
// LightningAccount.swift. The password is NOT stored on this object -- it lives
// sealed in AndroidSecureStore keyed by `lightning.password.<account.id>` (the
// Android analog of the iOS Keychain entry) so the account list can ship as
// plain SharedPreferences JSON without leaking credentials.
//
// Multiple accounts are supported (Zeus-style multi-wallet). Each account gets
// a unique thumbprint icon derived from the (serverURL, username) pair so the
// user can tell them apart at a glance.

package com.elabify.musnad.wallet.lightning

import java.net.URI
import java.util.UUID
import org.json.JSONObject

data class LightningAccount(
    val id: UUID = UUID.randomUUID(),
    var label: String,
    /** LNDHub server base URL (https://...). The client appends `/auth`,
     *  `/balance`, `/payinvoice`, etc. */
    var serverURL: String,
    var username: String,
    /** True = accept self-signed certs. Default false (strict TLS); users who
     *  run their own hub with a self-signed cert enable this knowingly. */
    var allowInsecureTLS: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Seed for the thumbprint icon. Same (url, username) pair always renders
     *  the same icon so duplicate imports are visually obvious. */
    val thumbprintSeed: String
        get() = "${serverURL.lowercase()}|${username.lowercase()}"

    /** `lndhub://login:password@host[:port][/path]` URL used by Zeus and most
     *  LNDHub front-ends for QR-encoded account export. Caller supplies the
     *  password (we don't keep it). Returns null on a malformed serverURL. */
    fun exportURL(password: String): String? {
        val uri = runCatching { URI(serverURL) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val sb = StringBuilder("lndhub://$username:$password@$host")
        if (uri.port != -1) sb.append(":").append(uri.port)
        val path = uri.path ?: ""
        if (path.isNotEmpty() && path != "/") sb.append(path)
        return sb.toString()
    }

    // ---- JSON (matches the iOS Codable shape persisted in UserDefaults) ----

    fun toJson(): JSONObject = JSONObject()
        .put("id", id.toString())
        .put("label", label)
        .put("serverURL", serverURL)
        .put("username", username)
        .put("allowInsecureTLS", allowInsecureTLS)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject): LightningAccount = LightningAccount(
            id = UUID.fromString(o.getString("id")),
            label = o.getString("label"),
            serverURL = o.getString("serverURL"),
            username = o.getString("username"),
            allowInsecureTLS = o.optBoolean("allowInsecureTLS", false),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/** An account bundled with its plaintext password, for the encrypted backup. */
data class LightningAccountWithSecret(
    val account: LightningAccount,
    val password: String,
)
