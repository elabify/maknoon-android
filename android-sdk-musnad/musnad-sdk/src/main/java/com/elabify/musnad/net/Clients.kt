// Issuer + verifier API clients, mirroring iOS Network.swift. Blocking calls
// (run on Dispatchers.IO from the app/store); responses parsed with org.json.

package com.elabify.musnad.net

import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.Presentation
import org.json.JSONObject

/** Outcome of an issuance pickup poll (ADR-0022 batch-anchor window). */
sealed class PickupOutcome {
    /** The credential is ready; `credentialJson` is the raw issuer payload. */
    data class Ready(val credentialJson: String) : PickupOutcome()
    data class Pending(val estimatedAnchorAt: Long?) : PickupOutcome()
}

data class ChallengeResponse(
    val requestId: String,
    val challenge: String,
    val issuedAt: Long,
    val expiresAt: Long,
    /** The DID the server minted this challenge under. The holder must sign the
     *  challenge against THIS (challengeSig is checked against the server's own
     *  verifier DID), which can differ from an issuer/audience DID. Null for
     *  back-compat with servers that do not echo it. */
    val verifierDid: String? = null,
)

/** The challenge context echoed back with the presentation on POST /v1/verify.
 *  Matches iOS `ChallengeContext` (the requestId + window from /v1/challenge). */
data class ChallengeContext(
    val requestId: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("issuedAt", issuedAt)
        .put("expiresAt", expiresAt)

    companion object {
        /** Build directly from a /v1/challenge response. */
        fun from(c: ChallengeResponse): ChallengeContext =
            ChallengeContext(c.requestId, c.issuedAt, c.expiresAt)
    }
}

/** Server verdict from POST /v1/verify. Mirrors iOS `VerifyResponse`.
 *  `decision` is "GRANT"/"DENY"; `checks` is the per-check matrix (values may
 *  be null) and `disclosed` the claims the server echoed back (null if none). */
data class VerifyResponse(
    val decision: String,
    val reason: String,
    val ms: Double,
    val checks: Map<String, JsonValue?>,
    val disclosed: Map<String, JsonValue>?,
) {
    companion object {
        fun fromJson(o: JSONObject): VerifyResponse {
            val checks = LinkedHashMap<String, JsonValue?>()
            o.optJSONObject("checks")?.let { c ->
                c.keys().forEach { k ->
                    // A JSON null in the matrix maps to a null entry, NOT JsonValue.Null,
                    // so callers can tell "check absent/not-run" from "value is null".
                    checks[k] = if (c.isNull(k)) null else JsonValue.fromJsonField(c.opt(k))
                }
            }
            val disclosed = o.optJSONObject("disclosed")?.let { d ->
                val m = LinkedHashMap<String, JsonValue>()
                d.keys().forEach { k -> m[k] = JsonValue.fromJsonField(d.opt(k)) }
                m
            }
            return VerifyResponse(
                decision = o.optString("decision"),
                reason = o.optString("reason"),
                ms = o.optDouble("ms", 0.0),
                checks = checks,
                disclosed = disclosed,
            )
        }
    }
}

/** Issuer: GET pickup, POST reissue/challenge, POST reissue. */
class IssuerClient(
    private val baseUrl: String, // e.g. https://musnad-issuer.elabify.com
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    fun pickup(pickupUrl: String): PickupOutcome {
        val o = JSONObject(http.getJson(pickupUrl))
        return if (o.optString("state") == "ready" && o.has("credential")) {
            PickupOutcome.Ready(o.getJSONObject("credential").toString())
        } else {
            PickupOutcome.Pending(o.optLongOrNull("estimatedAnchorAt"))
        }
    }

    /** POST /v1/issuance/reissue/challenge { holderDid } -> nonce. */
    fun reissueChallenge(holderDid: String): String {
        val body = JSONObject().put("holderDid", holderDid).toString()
        return JSONObject(http.postJson("$baseUrl/v1/issuance/reissue/challenge", body))
            .getString("nonce")
    }

    /** POST /v1/issuance/reissue -> raw result JSON ({ reissued, skipped }). */
    fun reissue(
        holderDid: String,
        masterPublicKeyHex: String,
        nonce: String,
        signatureHex: String,
    ): String {
        val body = JSONObject()
            .put("v", 1)
            .put("holderDid", holderDid)
            .put("masterPublicKey", masterPublicKeyHex)
            .put("nonce", nonce)
            .put("signature", signatureHex)
            .toString()
        return http.postJson("$baseUrl/v1/issuance/reissue", body)
    }
}

/** Verifier: POST /v1/challenge (and /v1/verify once Presentation is ported). */
class VerifierClient(
    private val baseUrl: String, // e.g. https://musnad-verifier.elabify.com
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    fun challenge(requestedClaims: List<String>): ChallengeResponse {
        val body = JSONObject()
            .put("v", 1)
            .put("requestedClaims", org.json.JSONArray(requestedClaims))
            .toString()
        val o = JSONObject(http.postJson("$baseUrl/v1/challenge", body))
        return ChallengeResponse(
            requestId = o.getString("requestId"),
            challenge = o.getString("challenge"),
            issuedAt = o.getLong("issuedAt"),
            expiresAt = o.getLong("expiresAt"),
            verifierDid = if (o.isNull("verifierDid")) null else o.optString("verifierDid").ifEmpty { null },
        )
    }

    /**
     * POST /v1/verify. Runs the full server-side check matrix and returns the
     * GRANT/DENY verdict with per-check details. The wire body mirrors iOS
     * `VerifyRequest`: { v:1, challengeContext, presentation }. Blocking; call
     * off the main thread (e.g. on Dispatchers.IO), like challenge().
     */
    fun verify(challengeContext: ChallengeContext, presentation: Presentation): VerifyResponse {
        val body = JSONObject()
            .put("v", 1)
            .put("challengeContext", challengeContext.toJson())
            .put("presentation", presentation.toJson())
            .toString()
        return VerifyResponse.fromJson(JSONObject(http.postJson("$baseUrl/v1/verify", body)))
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
