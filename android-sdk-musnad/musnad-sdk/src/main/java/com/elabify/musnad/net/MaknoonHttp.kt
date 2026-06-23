// GMS-free HTTP core (OkHttp) for the issuer + verifier + drop services,
// mirroring the iOS Network.swift. Optional certificate pinning is supported
// for deployments that pin the issuer/verifier TLS keys; the live musnad-dev
// hosts use standard CA TLS, so pinning is off by default.

package com.elabify.musnad.net

import java.util.concurrent.TimeUnit
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class NetworkException(val status: Int, val body: String) :
    Exception("HTTP $status: ${body.take(200)}")

class MaknoonHttp(private val client: OkHttpClient = defaultClient()) {

    fun postJson(url: String, jsonBody: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(JSON))
            .build()
        return execute(req)
    }

    fun getJson(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return execute(req)
    }

    /** GET with a Bearer token (e.g. LNDHub access token). */
    fun getJsonAuthed(url: String, bearerToken: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $bearerToken")
            .get()
            .build()
        return execute(req)
    }

    private fun execute(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw NetworkException(resp.code, body)
            return body
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Pin format: host -> "sha256/<base64>" pins (OkHttp). Empty = CA TLS. */
        fun defaultClient(pins: Map<String, List<String>> = emptyMap()): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
            if (pins.isNotEmpty()) {
                val cp = CertificatePinner.Builder().apply {
                    pins.forEach { (host, hostPins) -> hostPins.forEach { add(host, it) } }
                }.build()
                builder.certificatePinner(cp)
            }
            return builder.build()
        }
    }
}
