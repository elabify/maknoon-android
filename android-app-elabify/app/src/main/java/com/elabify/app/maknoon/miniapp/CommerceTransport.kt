// HTTP client for the server-mediated "verify and pay" flow (ADR-0031). Android
// port of CommerceTransport.swift. The merchant hosts a signed CommerceRequest
// (small URL QR) and polls for the holder's response; the holder fetches the
// request and posts back its presentation + payment proof. The server is a
// relay: verification happens on the merchant device.
//
// Transport is MaknoonHttp (OkHttp) + org.json instead of URLSession + Codable.
// All calls are blocking (the iOS `await` boundaries become coroutine
// withContext(Dispatchers.IO) at the call sites in the sheets/handlers).

package com.elabify.app.maknoon.miniapp

import com.elabify.app.maknoon.ui.common.LocalizedThrowable
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.MaknoonApplication
import androidx.annotation.StringRes
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import org.json.JSONObject

/**
 * A commerce transport failure, with copy the holder can read.
 *
 * Resolves its @StringRes lazily against the application context, the same
 * shape IDDocumentIssuanceException uses: these are thrown deep in networking
 * and coroutine code where no composable, and therefore no Context, is in
 * scope. It was English literals before, shown verbatim in every locale.
 */
class CommerceTransportException(
    @StringRes private val messageRes: Int,
    private val detail: String? = null,
) : Exception(), LocalizedThrowable {
    override val message: String
        get() = MaknoonApplication.appContext.let { ctx ->
            if (detail == null) ctx.getString(messageRes) else ctx.getString(messageRes, detail)
        }
}

/**
 * The holder to merchant payload, stored-and-forwarded by the server and polled
 * by the merchant. Shared shape for the POST (holder) and the GET result
 * (merchant). The presentation rides as a raw JSON object so its signed bytes
 * are preserved verbatim.
 */
data class CommerceServerResponse(
    val requestId: String,
    val presentation: JSONObject,
    val rail: PaymentRail,
    val txHash: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("presentation", presentation)
        put(
            "payment",
            JSONObject().put("rail", rail.toJson()).put("txHash", txHash),
        )
    }

    companion object {
        fun fromJson(o: JSONObject): CommerceServerResponse {
            val payment = o.optJSONObject("payment") ?: JSONObject()
            return CommerceServerResponse(
                requestId = o.optString("requestId", ""),
                presentation = o.optJSONObject("presentation") ?: JSONObject(),
                rail = PaymentRail.fromJson(payment.optJSONObject("rail") ?: JSONObject()),
                txHash = payment.optString("txHash", ""),
            )
        }
    }
}

/**
 * The merchant + holder relay endpoints. Stateless object; pass a shared
 * [MaknoonHttp] in if the caller wants connection reuse.
 */
class CommerceTransport(private val http: MaknoonHttp = MaknoonHttp()) {

    // ---- holder ----

    /**
     * Fetch + decode a hosted CommerceRequest from a request_uri. Accepts the
     * { v, request } envelope or a bare CommerceRequest.
     */
    fun fetchRequest(url: String): CommerceRequest {
        val body = getOrThrow(url)
        val o = JSONObject(body)
        val inner = o.optJSONObject("request")
        return CommerceRequest.fromJson(inner ?: o)
    }

    /** Post the holder's SEALED response for the merchant (server stays blind). */
    fun postResponse(baseURL: String, envelope: CommerceSealedEnvelope) {
        postOrThrow(joinPath(baseURL, "/v1/commerce-response"), envelope.toJson().toString())
    }

    // ---- merchant ----

    /** Host a signed CommerceRequest; returns its requestId (for the QR URL + polling). */
    fun hostRequest(baseURL: String, request: CommerceRequest): String {
        val body = JSONObject().put("request", request.toJson()).toString()
        val resp = postOrThrow(joinPath(baseURL, "/v1/commerce-request"), body)
        val id = JSONObject(resp).optStringOrNull("requestId")
            ?: throw CommerceTransportException(R.string.commerce_bad_response)
        return id
    }

    /**
     * Poll for the holder's SEALED response; null until the holder has posted
     * it. The merchant opens it locally with its ephemeral keypair.
     */
    fun pollResult(baseURL: String, requestId: String): CommerceSealedEnvelope? {
        val body = getOrThrow(joinPath(baseURL, "/v1/commerce-result/$requestId"))
        val o = JSONObject(body)
        if (!o.optBoolean("found", false)) return null
        val env = o.optJSONObject("response") ?: return null
        return CommerceSealedEnvelope.fromJson(env)
    }

    // ---- transport helpers ----

    private fun getOrThrow(url: String): String = try {
        http.getJson(url)
    } catch (e: NetworkException) {
        throw CommerceTransportException(R.string.commerce_http_status, e.status.toString())
    }

    private fun postOrThrow(url: String, body: String): String = try {
        http.postJson(url, body)
    } catch (e: NetworkException) {
        throw CommerceTransportException(R.string.commerce_http_status, e.status.toString())
    }

    private fun joinPath(base: String, path: String): String {
        val b = base.trimEnd('/')
        val p = if (path.startsWith('/')) path else "/$path"
        return b + p
    }
}
