// LNURL handler, ported 1:1 from iOS LNURL.swift.
//
// LNURL-pay (LUD-06): two phases:
//   1. Decode a bech32 LNURL / lightning: prefix / bare URL / LUD-16 Lightning
//      Address into a URL.
//   2. GET the URL; server returns `tag=payRequest` with min/max amount and a
//      callback. Caller picks an amount, GETs the callback with the chosen
//      amount, gets back a BOLT11 invoice the LNDHub client then pays.
//
// LNURL-withdraw (LUD-03) is also wired: a payee (e.g. a POS) scans a withdraw
// voucher the customer presents, then submits its own BOLT11 invoice to the
// voucher's callback to PULL the funds. LNURL-auth is out of scope.

package com.elabify.musnad.wallet.lightning

import com.elabify.musnad.net.MaknoonHttp
import org.json.JSONArray
import org.json.JSONObject

object LNURL {

    sealed class LNURLError(message: String) : Exception(message) {
        class InvalidEncoding(val detail: String) : LNURLError("Invalid LNURL: $detail")
        class Http(val status: Int, val body: String) : LNURLError("LNURL HTTP $status: ${body.take(200)}")
        class WrongTag(val tag: String) : LNURLError("LNURL tag was '$tag', not what this flow expected.")
        class Decode(val detail: String) : LNURLError("LNURL decode failed: $detail")
        class AmountOutOfRange(val minMsat: Long, val maxMsat: Long) :
            LNURLError("Amount is outside the issuer's allowed range (${minMsat / 1000} - ${maxMsat / 1000} sat).")
    }

    /** LUD-06 payRequest. Amounts are millisatoshi. */
    data class PayRequest(
        val tag: String,
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val metadata: String,
        val commentAllowed: Int? = null,
    )

    /** LUD-03 withdrawRequest. Amounts are millisatoshi. */
    data class WithdrawRequest(
        val tag: String,
        val callback: String,
        val k1: String,
        val minWithdrawable: Long,
        val maxWithdrawable: Long,
        val defaultDescription: String? = null,
    )

    private val http = MaknoonHttp()

    /**
     * Pull the underlying URL out of any of:
     *   - bech32-encoded `lnurl1...` string
     *   - `lightning:LNURL1...` prefix
     *   - bare https/http URL (some endpoints distribute raw URLs)
     *   - LUD-16 Lightning Address (`user@domain.tld`)
     * Trims any `lightning:` scheme prefix.
     */
    fun decode(raw: String): String {
        var s = raw.trim()
        if (s.lowercase().startsWith("lightning:")) {
            s = s.substring("lightning:".length)
        }
        // Bare URL passthrough: some QRs encode the resolved URL directly.
        val lower = s.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return s
        }
        // LUD-16 Lightning Address: `user@domain.tld` ->
        // `https://domain.tld/.well-known/lnurlp/user`.
        if (s.contains("@") && !s.contains(" ")) {
            val parts = s.split("@", limit = 2)
            if (parts.size == 2 &&
                parts[0].isNotEmpty() &&
                parts[1].contains(".") &&
                !parts[1].startsWith(".")
            ) {
                val user = parts[0]
                val domain = parts[1].lowercase()
                return "https://$domain/.well-known/lnurlp/$user"
            }
        }
        // bech32 path.
        val decoded = Bech32.decode(s)
            ?: throw LNURLError.InvalidEncoding("bech32 checksum or alphabet failed")
        if (decoded.hrp != "lnurl") {
            throw LNURLError.InvalidEncoding("expected hrp 'lnurl', got '${decoded.hrp}'")
        }
        val bytes = Bech32.toBytes(decoded.data, 5, 8, pad = false)
            ?: throw LNURLError.InvalidEncoding("could not regroup bech32 bits")
        return String(bytes, Charsets.UTF_8)
    }

    /** Fetch the payRequest JSON from a decoded LNURL. */
    fun fetchPayRequest(url: String): PayRequest {
        val body = httpGet(url)
        val o = runCatching { JSONObject(body) }.getOrElse { throw LNURLError.Decode("$it") }
        val tag = o.optString("tag", "")
        if (tag != "payRequest") throw LNURLError.WrongTag(tag)
        return PayRequest(
            tag = tag,
            callback = o.optString("callback", ""),
            minSendable = o.optLong("minSendable", 0L),
            maxSendable = o.optLong("maxSendable", 0L),
            metadata = o.optString("metadata", ""),
            commentAllowed = if (o.has("commentAllowed")) o.optInt("commentAllowed") else null,
        )
    }

    /**
     * Hit the callback URL with the chosen amount (sat -> msat conversion here)
     * and get back a BOLT11 invoice.
     */
    fun fetchInvoice(payRequest: PayRequest, amountSat: Long, comment: String? = null): String {
        val amountMsat = amountSat * 1_000
        if (amountMsat < payRequest.minSendable || amountMsat > payRequest.maxSendable) {
            throw LNURLError.AmountOutOfRange(payRequest.minSendable, payRequest.maxSendable)
        }
        val params = LinkedHashMap<String, String>()
        params["amount"] = "$amountMsat"
        val max = payRequest.commentAllowed
        if (!comment.isNullOrEmpty() && max != null && max > 0) {
            params["comment"] = comment.take(max)
        }
        val url = appendQuery(payRequest.callback, params)
            ?: throw LNURLError.Decode("callback URL with amount param is invalid")
        val body = httpGet(url)
        val o = runCatching { JSONObject(body) }.getOrElse { throw LNURLError.Decode("$it") }
        if (o.optString("status", "").uppercase() == "ERROR") {
            throw LNURLError.Http(0, o.optString("reason", "callback returned ERROR status"))
        }
        val pr = o.optStringOrNull("pr") ?: throw LNURLError.Decode("payResponse missing `pr` field")
        return pr
    }

    // ---- LNURL-withdraw (LUD-03) ----

    /** Append `k1` + `pr` to the voucher callback (preserving existing query
     *  items). Pure, for testability. Returns null if the callback is bad. */
    fun withdrawCallbackURL(callback: String, k1: String, bolt11: String): String? =
        appendQuery(callback, linkedMapOf("k1" to k1, "pr" to bolt11))

    /** Fetch a withdraw voucher's parameters from a decoded LNURL. */
    fun fetchWithdrawRequest(url: String): WithdrawRequest {
        val body = httpGet(url)
        val o = runCatching { JSONObject(body) }.getOrElse { throw LNURLError.Decode("$it") }
        val tag = o.optString("tag", "")
        if (tag != "withdrawRequest") throw LNURLError.WrongTag(tag)
        return WithdrawRequest(
            tag = tag,
            callback = o.optString("callback", ""),
            k1 = o.optString("k1", ""),
            minWithdrawable = o.optLong("minWithdrawable", 0L),
            maxWithdrawable = o.optLong("maxWithdrawable", 0L),
            defaultDescription = o.optStringOrNull("defaultDescription"),
        )
    }

    /** Submit the payee's BOLT11 invoice to the voucher callback so the
     *  customer's service pays it (the pull). Throws on an ERROR status. */
    fun submitWithdraw(w: WithdrawRequest, bolt11: String) {
        val url = withdrawCallbackURL(w.callback, w.k1, bolt11)
            ?: throw LNURLError.Decode("withdraw callback URL is unparseable")
        val body = httpGet(url)
        val o = runCatching { JSONObject(body) }.getOrNull()
        if (o != null && o.optString("status", "").uppercase() == "ERROR") {
            throw LNURLError.Http(0, o.optString("reason", "withdraw callback returned ERROR"))
        }
    }

    /**
     * Read out the issuer-supplied display name from the metadata blob. The
     * blob is a JSON array of pairs: `[["text/plain", "Coffee shop"], ...]`.
     * Surfaces the first `text/plain` entry (LUD-06 human-readable description).
     */
    fun extractDescription(metadataJSON: String): String? {
        val arr = runCatching { JSONArray(metadataJSON) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONArray(i) ?: continue
            if (entry.length() == 2 && entry.optString(0) == "text/plain") {
                return entry.optStringOrNull(1)
            }
        }
        return null
    }

    // ---- transport helpers ----

    private fun httpGet(url: String): String =
        try {
            http.getJson(url)
        } catch (e: com.elabify.musnad.net.NetworkException) {
            throw LNURLError.Http(e.status, e.body)
        }

    /** Append query params to a URL, preserving any existing query string.
     *  Returns null if the base URL is malformed. */
    private fun appendQuery(base: String, params: Map<String, String>): String? {
        if (base.isBlank()) return null
        val sb = StringBuilder(base)
        var sep = if (base.contains("?")) "&" else "?"
        for ((k, v) in params) {
            sb.append(sep).append(urlEncode(k)).append("=").append(urlEncode(v))
            sep = "&"
        }
        return sb.toString()
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun JSONArray.optStringOrNull(index: Int): String? =
        if (!isNull(index)) getString(index).takeIf { it.isNotEmpty() } else null
}
