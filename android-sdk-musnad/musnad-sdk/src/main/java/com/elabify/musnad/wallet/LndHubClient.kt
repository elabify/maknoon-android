// Lightning via LNDHub (custodial), mirroring the iOS LNDHubClient. One
// account per LNDHub credential pair; no on-device key (the custodian holds
// it), so this is a pure-Kotlin HTTP client over the SDK's networking. Auth
// exchanges login/password for an access token; balance + invoices use it.

package com.elabify.musnad.wallet

import com.elabify.musnad.net.MaknoonHttp
import org.json.JSONObject

class LndHubClient(
    private val baseUrl: String, // e.g. https://lndhub.example.com
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    data class Tokens(val accessToken: String, val refreshToken: String)

    /** POST /auth { login, password } -> tokens. */
    fun auth(login: String, password: String): Tokens {
        val body = JSONObject().put("login", login).put("password", password).toString()
        val o = JSONObject(http.postJson("$baseUrl/auth?type=auth", body))
        return Tokens(o.getString("access_token"), o.optString("refresh_token"))
    }

    /** GET /balance (Bearer) -> spendable balance in sats (BTC wallet). */
    fun balanceSats(accessToken: String): Long {
        val o = JSONObject(http.getJsonAuthed("$baseUrl/balance", accessToken))
        return o.getJSONObject("BTC").getLong("AvailableBalance")
    }

    /** Parse an `lndhub://login:password@host` connection string. */
    companion object {
        fun parseConnection(uri: String): Triple<String, String, String>? {
            val m = Regex("^lndhub://([^:]+):([^@]+)@(.+)$").find(uri.trim()) ?: return null
            val (login, password, host) = m.destructured
            val base = if (host.startsWith("http")) host else "https://$host"
            return Triple(login, password, base.trimEnd('/'))
        }
    }
}
