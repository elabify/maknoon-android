// HTTP client for LNDHub-compatible Lightning custodial servers. Ported 1:1
// from the iOS LNDHubClient.swift. Same API surface BlueWallet defined and Zeus
// speaks: POST /auth for tokens, GET /balance, POST /addinvoice,
// POST /payinvoice, GET /gettxs, GET /getuserinvoices.
//
// TLS: the configured account flag `allowInsecureTLS` swaps in an OkHttp client
// that trusts any server certificate (self-signed hubs behind a private CA).
// Off by default; users opt in from the Networks settings page.
//
// This supersedes the earlier starter at wallet/LndHubClient.kt: it covers the
// full iOS surface (invoices, pay, history, insecure TLS) and lives in the
// lightning subpackage alongside the rest of the engine.

package com.elabify.musnad.wallet.lightning

import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Errors surfaced by the LNDHub client, mirroring the iOS LNDHubError cases. */
sealed class LNDHubError(message: String) : Exception(message) {
    object BadServerURL : LNDHubError("Server URL is not a valid HTTPS URL.")
    class Http(val status: Int, val body: String) :
        LNDHubError("HTTP $status: ${body.take(200)}")
    class Server(val reason: String) : LNDHubError("Hub error: $reason")
    class Decode(val detail: String) : LNDHubError("Could not decode response: $detail")
}

/** Result of paying a BOLT11 invoice. */
data class PaymentResult(
    val preimage: String,
    val amountSat: Long?,
    val feeSat: Long?,
)

/**
 * One LNDHub account's client. Holds an access + refresh token after the first
 * authenticated call. Not thread-safe across concurrent callers; the iOS type
 * is an actor, callers here should confine a client to a single coroutine /
 * dispatcher (e.g. Dispatchers.IO) the way the iOS app awaits one actor.
 */
class LndHubClient(
    val account: LightningAccount,
    private val password: String,
) {
    private var accessToken: String? = null
    private var refreshToken: String? = null

    private val client: OkHttpClient =
        if (account.allowInsecureTLS) insecureClient() else strictClient()

    // ---- auth ----

    private fun authenticate() {
        val body = JSONObject()
            .put("login", account.username)
            .put("password", password)
            .toString()
        val o = postJson("/auth?type=auth", body, authenticated = false)
        val t = o.optStringOrNull("access_token")
        val r = o.optStringOrNull("refresh_token")
        if (t == null || r == null) {
            throw LNDHubError.Server(
                o.optStringOrNull("error") ?: o.optStringOrNull("message")
                    ?: "auth response missing tokens",
            )
        }
        accessToken = t
        refreshToken = r
    }

    private fun ensureAuth() {
        if (accessToken == null) authenticate()
    }

    // ---- balance ----

    /** Total available balance in satoshis. */
    fun balanceSat(): Long {
        ensureAuth()
        val o = JSONObject(getRaw("/balance"))
        (o.optStringOrNull("error") ?: o.optStringOrNull("message"))?.let { throw LNDHubError.Server(it) }
        val btc = o.optJSONObject("BTC") ?: return 0L
        return btc.optLong("AvailableBalance", 0L)
    }

    // ---- invoices ----

    /** Create a receive invoice. `amountSat = 0` makes an amountless invoice
     *  (the payer specifies the amount). Returns the BOLT11 string. */
    fun addInvoice(amountSat: Long, memo: String): String {
        ensureAuth()
        val body = JSONObject()
            .put("amt", "$amountSat")
            .put("memo", memo)
            .toString()
        val o = postJson("/addinvoice", body, authenticated = true)
        val pr = o.optStringOrNull("payment_request")
        if (pr == null) {
            (o.optStringOrNull("error") ?: o.optStringOrNull("message"))?.let { throw LNDHubError.Server(it) }
            throw LNDHubError.Decode("payment_request missing")
        }
        return pr
    }

    // ---- pay ----

    /** Pay a BOLT11 invoice. `amountSat` is required only when the invoice has
     *  no embedded amount; pass null to use the invoice's amount. */
    fun payInvoice(bolt11: String, amountSat: Long? = null): PaymentResult {
        ensureAuth()
        val bodyObj = JSONObject().put("invoice", bolt11)
        if (amountSat != null) bodyObj.put("amount", amountSat)
        // /payinvoice blocks on the server until the payment ROUTES (or fails),
        // which routinely takes longer than the default 30s read timeout for a
        // multi-hop route -> the call would surface as a bogus "timeout" even
        // though the hub is healthy. Give it a generous idle timeout (matches /
        // exceeds iOS's effective 60s URLSession default). readTimeout is an
        // IDLE timeout, so fast calls are unaffected.
        val o = postJson(
            "/payinvoice",
            bodyObj.toString(),
            authenticated = true,
            readTimeoutSeconds = PAYINVOICE_READ_TIMEOUT_SEC,
        )
        val preimage = o.optStringOrNull("payment_preimage")
        if (preimage == null) {
            (o.optStringOrNull("error") ?: o.optStringOrNull("message"))?.let { throw LNDHubError.Server(it) }
            throw LNDHubError.Decode("payment_preimage missing")
        }
        val route = o.optJSONObject("payment_route")
        return PaymentResult(
            preimage = preimage,
            amountSat = route?.int64Flex("total_amt"),
            feeSat = route?.int64Flex("total_fees"),
        )
    }

    // ---- history ----

    /**
     * Combined history: outgoing payments (`/gettxs`) + settled incoming
     * invoices (`/getuserinvoices` filtered by isPaid). Sorted newest first;
     * deduped by payment_hash. Mirrors the iOS `history(limit:)`.
     */
    fun history(limit: Int = 100): List<LightningTx> {
        val out = runCatching { transactions(limit) }.getOrDefault(emptyList())
        val inv = runCatching { userInvoices(limit) }.getOrDefault(emptyList())
        val merged = out + inv.filter { it.isPaid != false }
        val seen = HashSet<String>()
        val deduped = ArrayList<LightningTx>()
        for (tx in merged) {
            val key = tx.paymentHash ?: tx.id
            if (seen.add(key)) deduped.add(tx)
        }
        return deduped.sortedByDescending { it.timestamp ?: 0L }
    }

    /** Outgoing payments from `/gettxs`. Accepts both a bare array and a
     *  `{txs:[...]}` envelope; throws a friendly decode error otherwise. */
    fun transactions(limit: Int = 100): List<LightningTx> {
        ensureAuth()
        val data = getRaw("/gettxs?limit=$limit")
        val rows = parseTxArray(data, "txs")
            ?: throw LNDHubError.Decode(
                "history payload didn't match any known LNDHub shape -- share the diagnostic log so we can extend the decoder.",
            )
        return rows.map { LightningTx.fromJson(it) }
    }

    /** Incoming invoices from `/getuserinvoices`. LNDHub splits these out from
     *  `/gettxs`. Returns them as LightningTx; callers filter by isPaid. Empty
     *  list on a shape mismatch (does not throw, matching iOS). */
    fun userInvoices(limit: Int = 100): List<LightningTx> {
        ensureAuth()
        val data = getRaw("/getuserinvoices?limit=$limit")
        val rows = parseTxArray(data, "invoices") ?: return emptyList()
        return rows.map { o ->
            val tx = LightningTx.fromJson(o)
            // user_invoice rows don't carry a `type` field in some forks; tag
            // them post-decode so isOutgoing returns false.
            if ((tx.type ?: "").isEmpty()) tx.copy(type = "user_invoice") else tx
        }
    }

    // ---- transport ----

    private fun url(path: String): String {
        val base = account.serverURL.trim().trimEnd('/')
        if (base.isEmpty()) throw LNDHubError.BadServerURL
        return base + path
    }

    private fun getRaw(path: String): String {
        val builder = Request.Builder().url(url(path)).header("Accept", "application/json").get()
        accessToken?.let { builder.header("Authorization", "Bearer $it") }
        return execute(builder.build())
    }

    private fun postJson(
        path: String,
        jsonBody: String,
        authenticated: Boolean,
        readTimeoutSeconds: Long? = null,
    ): JSONObject {
        val builder = Request.Builder()
            .url(url(path))
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(JSON))
        if (authenticated) accessToken?.let { builder.header("Authorization", "Bearer $it") }
        val raw = execute(builder.build(), readTimeoutSeconds)
        return runCatching { JSONObject(raw) }.getOrElse {
            throw LNDHubError.Decode(it.message ?: "non-JSON response")
        }
    }

    private fun execute(req: Request, readTimeoutSeconds: Long? = null): String {
        // A per-call client derived from the shared one (reuses its connection
        // pool + dispatcher) so a slow path like /payinvoice can opt into a
        // longer idle timeout without affecting the rest.
        val call = if (readTimeoutSeconds != null) {
            client.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
        } else {
            client
        }
        call.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LNDHubError.Http(resp.code, body)
            return body
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Idle-timeout for /payinvoice: routing a multi-hop Lightning payment
         *  can take well over the default 30s. 120s gives slow routes room
         *  without hanging forever on a dead hub (connectTimeout still guards
         *  TCP reachability). */
        private const val PAYINVOICE_READ_TIMEOUT_SEC = 120L

        private fun strictClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** OkHttp client that trusts any server cert, for self-signed hubs the
         *  account opted into. Android analog of the iOS InsecureTLSDelegate. */
        private fun insecureClient(): OkHttpClient {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustAll), SecureRandom())
            }
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}

/** optString that returns null (not "") for a missing/null key. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key, null)?.takeIf { it.isNotEmpty() } else null
