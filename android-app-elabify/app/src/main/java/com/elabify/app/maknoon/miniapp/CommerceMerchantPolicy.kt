// Merchant-side accept decision for a holder's CommerceResponse (ADR-0031).
// Android port of CommerceMerchantPolicy.swift.
//
// Cryptography is delegated to the identity slice's offline presentation
// verifier (CommerceHolderContext.verifyPresentationOffline); this layer adds
// the commerce policy: the response is bound to the request (nonce), the
// presented credential satisfies the identity ask (schema / required claims /
// freshness), and the chosen payment rail is one the merchant published.
//
// The presentation is a raw JSON object (header.schema + disclosed[]); we read
// the fields the policy needs directly.

package com.elabify.app.maknoon.miniapp

import org.json.JSONObject

data class CommerceAcceptResult(
    val granted: Boolean,
    // First failing reason, or "ok". One of: nonce_mismatch, wrong_schema,
    // missing_claims, stale_screening, sanctioned, rail_not_accepted,
    // verification_failed.
    val reason: String,
    val missing: List<String>,
    val message: String?,
)

object CommerceMerchantPolicy {

    /**
     * The non-cryptographic checks, as pure inputs so they are unit-testable
     * without fabricating a full Presentation. Returns the failing reasons (in
     * priority order) plus the missing required-claim keys.
     */
    fun policyReasons(
        schema: String,
        disclosedKeys: Set<String>,
        rail: PaymentRail,
        responseNonce: String,
        sanctions: SanctionsDisclosure?,
        request: CommerceRequest,
        nowSec: Long,
    ): Pair<List<String>, List<String>> {
        val reasons = mutableListOf<String>()

        if (responseNonce != request.paymentTerms.nonce) {
            reasons.add("nonce_mismatch")
        }

        val schemas = request.schemasClause
        if (schemas != null && schemas.mode == "allow" && !(schemas.list ?: emptyList()).contains(schema)) {
            reasons.add("wrong_schema")
        }

        val missing = request.requiredClaims.filter { !disclosedKeys.contains(it) }
        if (missing.isNotEmpty()) reasons.add("missing_claims")

        sanctionsReason(sanctions, request.identityMaxAgeSec, nowSec)?.let { reasons.add(it) }

        if (!railAccepted(rail, request.paymentTerms.acceptedRails)) {
            reasons.add("rail_not_accepted")
        }

        return reasons to missing
    }

    /**
     * Full evaluation: the policy checks above plus the offline cryptographic
     * verdict (signatures, Merkle proofs, expiry) from the identity slice.
     * Blocking (the offline verify may touch crypto); call off the main thread.
     */
    fun evaluate(
        response: CommerceResponse,
        request: CommerceRequest,
        ctx: CommerceHolderContext,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): CommerceAcceptResult {
        val p = response.presentation
        val schema = p.optJSONObject("header")?.optString("schema", "") ?: ""
        val disclosed = disclosedMap(p)
        val disclosedKeys = disclosed.keys

        val (reasons, missing) = policyReasons(
            schema = schema,
            disclosedKeys = disclosedKeys,
            rail = response.payment.rail,
            responseNonce = response.nonce,
            sanctions = extractSanctions(disclosed),
            request = request,
            nowSec = nowSec,
        ).let { it.first.toMutableList() to it.second }

        if (!ctx.verifyPresentationOffline(p, nowSec)) {
            reasons.add("verification_failed")
        }

        val granted = reasons.isEmpty()
        return CommerceAcceptResult(
            granted = granted,
            reason = reasons.firstOrNull() ?: "ok",
            missing = missing,
            message = if (granted) null
            else message(reasons.firstOrNull() ?: "", missing, disclosedKeys.toList()),
        )
    }

    // ---- helpers ----

    /**
     * A rail matches when the merchant published the same chain/network/asset
     * and receiving address (address compared case-insensitively for EVM).
     */
    fun railAccepted(rail: PaymentRail, accepted: List<PaymentRail>): Boolean =
        accepted.any { a ->
            a.chain == rail.chain &&
                (a.network ?: "") == (rail.network ?: "") &&
                a.asset == rail.asset &&
                a.address.equals(rail.address, ignoreCase = true)
        }

    /**
     * Sanctions screening as disclosed. Either the passport sdnScreen object or
     * the legacy flat sanctionsScreenedAt (musnadMaknoon), normalized.
     */
    data class SanctionsDisclosure(val result: String?, val screenedAt: String?)

    /**
     * Pull sanctions info from a disclosed-claims map (claim key -> value).
     * Prefers the passport sdnScreen object; falls back to the flat key.
     */
    fun extractSanctions(disclosed: Map<String, Any?>): SanctionsDisclosure? {
        (disclosed["sdnScreen"] as? JSONObject)?.let { sdn ->
            return SanctionsDisclosure(sdn.optStringOrNull("result"), sdn.optStringOrNull("screenedAt"))
        }
        (disclosed["sanctionsScreenedAt"] as? String)?.let { flat ->
            return SanctionsDisclosure(result = "clean", screenedAt = flat)
        }
        return null
    }

    /**
     * Fail-closed sanctions gate. null maxAgeSec means not requested (pass).
     * Requires a "clean" result screened within maxAgeSec; anything else
     * (sanctioned/pep/inconclusive/error, missing, or stale) fails.
     */
    fun sanctionsReason(s: SanctionsDisclosure?, maxAgeSec: Long?, nowSec: Long): String? {
        val maxAge = maxAgeSec ?: return null
        if (s == null) return "stale_screening"
        if (s.result != null && s.result.lowercase() != "clean") return "sanctioned"
        val iso = s.screenedAt
        val age = iso?.let { ageSeconds(it, nowSec) }
        if (iso == null || age == null || age > maxAge) return "stale_screening"
        return null
    }

    private fun ageSeconds(iso: String, nowSec: Long): Long? {
        // ISO-8601; accept both with and without fractional seconds via the
        // built-in offset parsers (java.time, no external deps).
        val epoch = parseIsoEpochSeconds(iso) ?: return null
        return nowSec - epoch
    }

    private fun parseIsoEpochSeconds(iso: String): Long? {
        return try {
            java.time.OffsetDateTime.parse(iso).toEpochSecond()
        } catch (_: Exception) {
            try {
                java.time.Instant.parse(iso).epochSecond
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun disclosedMap(presentation: JSONObject): Map<String, Any?> {
        val arr = presentation.optJSONArray("disclosed") ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val key = item.optStringOrNull("key") ?: continue
            // The disclosed value may be a string, an object (sdnScreen), etc.
            out[key] = if (item.isNull("value")) null else item.opt("value")
        }
        return out
    }

    fun message(reason: String, missing: List<String>, disclosedKeys: List<String>): String = when (reason) {
        "missing_claims" -> {
            val shared = if (disclosedKeys.isEmpty()) "nothing" else disclosedKeys.sorted().joinToString(", ")
            "Missing: ${missing.joinToString(", ")}. The customer shared: $shared."
        }
        "wrong_schema" -> "The customer presented the wrong credential type."
        "stale_screening" -> "The customer's sanctions screening is missing or too old."
        "sanctioned" -> "The customer's sanctions screening is not clean (flagged)."
        "rail_not_accepted" -> "The customer chose a payment rail this merchant does not accept."
        "nonce_mismatch" -> "The response did not match this request (replay or stale QR)."
        "verification_failed" -> "The credential failed cryptographic verification."
        else -> "Declined: $reason"
    }
}
