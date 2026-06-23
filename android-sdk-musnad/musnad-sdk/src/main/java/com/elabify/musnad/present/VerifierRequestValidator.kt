// Authenticates a scanned VerifierRequest, ported from iOS
// VerifierRequestValidator.swift. Two trust tiers:
//
//   - Self-signed: verifierPublicKey + signature inline. Validates the
//     signature against the embedded pubkey. UI badge: yellow.
//   - Registered: verifierPublicKey omitted. Resolves the pubkey via
//     GET /v1/verifier-registry/:did and validates the signature against the
//     server-vouched pubkey. UI badge: green.
//
// Cryptographic verification routes through MasterKey.verify (uniffi
// pq-crypto-core ML-DSA-65). Canonical bytes are produced by
// com.elabify.core.canonicalize (the same the server uses), so byte-equality
// across Android / iOS / TS holds without a separate KAT.
//
// request_uri indirection: a self-signed VerifierRequest (inline ML-DSA pubkey
// + signature, ~10 KB) is far over a QR's ~3 KB ceiling, so the scanned QR may
// carry an https URL to fetch the full request from (mirrors OpenID4VP
// request_uri). Raw JSON is still accepted for back-compat.
//
// GMS-free; uses the SDK's pinned OkHttp (MaknoonHttp) for IO. The fetch +
// registry lookup are blocking, so call validate(...) off the main thread
// (e.g. Dispatchers.IO).

package com.elabify.musnad.present

import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.net.MaknoonHttp
import org.json.JSONObject

object VerifierRequestValidator {

    /** Trust tier resolved for a scanned request. */
    sealed class TrustTier {
        /** Inline pubkey + signature. */
        object SelfSigned : TrustTier()
        /** Pubkey vouched by the registry; carries the verifier's display name. */
        data class Registered(val name: String) : TrustTier()
        /** Could not be placed in a tier (expired, missing sig, not in registry). */
        object Unknown : TrustTier()
    }

    /** The outcome of validating a scanned request. */
    data class Decision(
        val request: VerifierRequest,
        val tier: TrustTier,
        val isValid: Boolean,
        val reason: String?, // populated iff isValid == false
    )

    /**
     * Parse + authenticate a scanned QR payload. Performs blocking IO when the
     * scanned value is a fetchable request_uri, and again when the request
     * omits its inline pubkey (registry lookup). Returns null when the payload
     * cannot be parsed at all (not even structurally a VerifierRequest).
     *
     * @param scanned       the raw scanned string (JSON, an envelope, or a URL)
     * @param registryHost  base host for the verifier registry, e.g.
     *                       "https://musnad-verifier.elabify.com"
     * @param nowSec        current unix seconds (for the expiry check)
     * @param http          pinned HTTP client (defaults to the SDK's MaknoonHttp)
     */
    fun validate(
        scanned: String,
        registryHost: String,
        nowSec: Long = System.currentTimeMillis() / 1000L,
        http: MaknoonHttp = MaknoonHttp(),
    ): Decision? {
        val trimmed = scanned.trim()

        // request_uri indirection: fetch the full request from an https URL.
        val payload: String = if (looksLikeFetchableUrl(trimmed)) {
            try {
                http.getJson(trimmed)
            } catch (e: Exception) {
                return null
            }
        } else {
            trimmed
        }

        // Decode either a raw VerifierRequest or the { v, request } envelope.
        val request = VerifierRequest.parse(payload) ?: return null

        // Cheap structural checks first.
        if (request.v != 1) {
            return Decision(request, TrustTier.Unknown, false, "Unsupported request version")
        }
        if (nowSec > request.expiresAt) {
            return Decision(request, TrustTier.Unknown, false, "Request has expired")
        }
        val sigHex = request.signature
            ?: return Decision(request, TrustTier.Unknown, false, "Request is missing a signature")

        // Resolve the pubkey + tier.
        val pubkey: ByteArray?
        val tier: TrustTier
        if (request.verifierPublicKey != null) {
            pubkey = hexFrom0xOrNull(request.verifierPublicKey)
            tier = TrustTier.SelfSigned
        } else {
            val rec = lookupRegistry(http, registryHost, request.verifierDid)
            if (rec != null) {
                pubkey = hexFrom0xOrNull(rec.verifierPublicKey)
                tier = TrustTier.Registered(rec.verifierName)
            } else {
                return Decision(request, TrustTier.Unknown, false, "Verifier DID not in registry")
            }
        }

        val sig = hexFrom0xOrNull(sigHex)
        if (pubkey == null || sig == null) {
            return Decision(request, tier, false, "Malformed pubkey or signature hex")
        }

        // Canonicalize the request WITHOUT the signature field, byte-for-byte
        // identical to the server-side check (verifier-server checks.ts
        // verifyVerifierRequest): the exact field set the wire object carries
        // (nulls omitted), minus `signature`.
        val msgBytes = try {
            canonicalize(request.canonicalMapWithoutSignature())
        } catch (e: Exception) {
            return Decision(request, tier, false, "Could not re-canonicalize: ${e.message}")
        }

        val ok = MasterKey.verify(pubkey, sig, msgBytes)
        return Decision(request, tier, ok, if (ok) null else "Signature does not validate")
    }

    /** Thin client for GET /v1/verifier-registry/:did. Returns null on 404 or
     *  any network error, the caller falls back to "not in registry" semantics. */
    private fun lookupRegistry(
        http: MaknoonHttp,
        host: String,
        did: String,
    ): VerifierRegistryRecord? {
        val encoded = try {
            java.net.URLEncoder.encode(did, "UTF-8")
        } catch (e: Exception) {
            did
        }
        val base = host.trimEnd('/')
        val url = "$base/v1/verifier-registry/$encoded"
        return try {
            VerifierRegistryRecord.fromJson(JSONObject(http.getJson(url)))
        } catch (e: Exception) {
            null
        }
    }

    /** Only https (or http to localhost for dev) is fetchable, a light SSRF
     *  guard on an attacker-chosen QR URL (matches iOS looksLikeFetchableURL). */
    private fun looksLikeFetchableUrl(s: String): Boolean {
        val lower = s.lowercase()
        if (lower.startsWith("https://")) return true
        if (lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1")) return true
        return false
    }
}
