// Opt-in OpenSanctions screening for a scanned passport.
//
// The holder taps "Check against OpenSanctions" in the document detail view;
// we POST the name + date of birth + nationality to the issuer's
// /v1/sanctions-check endpoint (the issuer proxies the self-hosted yente
// matcher so passport PII never goes to a third party). The outcome is
// persisted on the IDDocument and surfaced as a shield badge on the card.
//
// This is screening only, it does NOT mint a credential. The user can run it
// before or after issuing a verified credential; the issuer independently
// screens at issuance time regardless.
//
// Android port of iOS SanctionsScreeningClient.swift. Networking goes through
// the SDK MaknoonHttp (OkHttp); responses are parsed with org.json.

package com.elabify.app.maknoon.iddocument

import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// SanctionsOutcome, SanctionsMatchDetail, and SanctionsScreenResult are the
// shared model types declared in IDDocument.kt. This client uses them as-is:
// the model's SanctionsScreenResult carries screenedAt as epoch millis and
// already exposes the human-readable label.

/** Typed failures for sanctions screening, mirroring iOS SanctionsScreeningError. */
sealed class SanctionsScreeningException(message: String) : Exception(message) {
    /** The issuer has sanctions screening turned off (HTTP 503). */
    object ScreeningDisabled : SanctionsScreeningException(
        "This issuer has sanctions screening turned off.",
    )

    class RequestFailed(detail: String) :
        SanctionsScreeningException("Screening request failed: $detail")

    class MalformedResponse(detail: String) :
        SanctionsScreeningException("Unexpected response from the screening service: $detail")
}

/** Screens a scanned-passport subject against the issuer's OpenSanctions proxy. */
class SanctionsScreeningClient(
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    /**
     * Screen a subject against the issuer's OpenSanctions proxy.
     *
     * @param givenName the subject's given name(s).
     * @param familyName the subject's family name.
     * @param dateOfBirth ISO 8601 date, YYYY-MM-DD.
     * @param nationality optional ISO 3166-1 alpha-3 nationality.
     * @param issuerBaseUrl the same base the issuance flow uses
     *   (e.g. https://musnad-issuer1.elabify.com). Trailing slashes are trimmed.
     */
    suspend fun check(
        givenName: String,
        familyName: String,
        dateOfBirth: String,
        nationality: String?,
        issuerBaseUrl: String,
    ): SanctionsScreenResult = withContext(Dispatchers.IO) {
        val base = issuerBaseUrl.trim('/')
        val url = "$base/v1/sanctions-check"
        val requestBody = JSONObject()
            .put("givenName", givenName)
            .put("familyName", familyName)
            .put("dateOfBirth", dateOfBirth)
            .putOpt("nationality", nationality)
            .toString()

        val respBody = try {
            http.postJson(url, requestBody)
        } catch (e: NetworkException) {
            // 503 is the issuer's "screening disabled" signal, surfaced as a
            // distinct error so the UI can hide the action rather than show a
            // generic failure.
            if (e.status == 503) throw SanctionsScreeningException.ScreeningDisabled
            throw SanctionsScreeningException.RequestFailed("HTTP ${e.status}: ${e.body.take(200)}")
        } catch (e: Exception) {
            throw SanctionsScreeningException.RequestFailed(e.message ?: e.toString())
        }

        val o = try {
            JSONObject(respBody)
        } catch (e: Exception) {
            throw SanctionsScreeningException.MalformedResponse(e.message ?: e.toString())
        }

        val resultStr = if (o.has("result") && !o.isNull("result")) o.getString("result") else null
        val outcome = SanctionsOutcome.fromRaw(resultStr)
            ?: throw SanctionsScreeningException.MalformedResponse(
                "missing or unknown result: ${resultStr ?: "nil"}",
            )

        val screenedAtMillis = if (o.has("screenedAt") && !o.isNull("screenedAt")) {
            // The issuer reports screenedAt as epoch seconds (possibly
            // fractional); the model stores epoch millis, so scale up.
            (o.getDouble("screenedAt") * 1000.0).toLong()
        } else {
            System.currentTimeMillis()
        }
        val datasetVersion = if (o.has("datasetVersion") && !o.isNull("datasetVersion")) {
            o.getString("datasetVersion")
        } else {
            "unknown"
        }

        SanctionsScreenResult(
            outcome = outcome,
            screenedAt = screenedAtMillis,
            datasetVersion = datasetVersion,
            matches = emptyList(),
        )
    }
}
