// Client-side HAVID cross-endorsement (ADR-0051, completed under ADR-0054).
// Android port of iOS HavidVerifier.swift.
//
// HAVID binds an issuer's real-world X.509 organisational identity to its DID,
// bidirectionally, and the holder confirms it with NO chain read and NO verifier
// server, purely over local HTTPS + crypto:
//
//   DID  -> cert : the issuer's .well-known doc carries the cert chain (havid.x5c)
//                  and is ML-DSA self-signed by the issuer key. We reconfirm that
//                  key by checking it ALSO signed the credential being presented,
//                  so the endorsing key provably is the issuer's, not a MITM key.
//   cert -> DID : the leaf certificate's subjectAltName carries URI:<issuerDid>.
//
// Both directions holding => cross-endorsed. This is an ISSUER-assurance badge,
// never a credential gate; it answers "did this come from a verifiable real-world
// organisation?", distinct from the passport<->CSCA link (ADR-0050, surfaced as
// the on-chain CSCA provenance tier).
//
// SCOPE (matching verifier-server/src/havid.ts minus trusted-root anchoring): the
// leaf is parsed with the JDK CertificateFactory. We do NOT bundle CA roots, so
// the client caps at "cross-endorsed" and never claims the server's "full_havid".
// No CRL/OCSP, no full RFC 5280 path build. Fingerprint integrity + the leaf
// validity window are enforced.

package com.elabify.musnad.present

import android.util.Base64
import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.net.MaknoonHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** HAVID assurance state, holder-side. Caps at CROSS_ENDORSED (no bundled roots). */
enum class HavidState {
    /** Bidirectional cross-endorsement holds and the leaf is within validity. */
    CROSS_ENDORSED,
    /** Leaf SAN does not carry the issuer DID. */
    KEY_ALIGNMENT_FAILURE,
    /** Cert cannot be parsed, or its fingerprint disagrees with the advertised one. */
    INTEGRITY_FAILURE,
    /** Leaf certificate is outside its validity window. */
    EXPIRED_REVOKED,
    /** The issuer publishes a well-known doc but no HAVID binding. */
    NO_ENDORSEMENT,
    /** Issuer identity could not be reached / did not bind to this credential. */
    NOT_RESOLVABLE,
}

data class HavidResult(
    val state: HavidState,
    val detail: String? = null,
    /** Leaf certificate subject (common name), when parsed, for display. */
    val subject: String? = null,
)

object HavidVerifier {
    /**
     * Resolve + validate the issuer's HAVID binding for a presented credential.
     * [candidateBaseUrls] are the known-issuer hosts to probe for the signed
     * well-known doc (same source iOS uses via KnownIssuersStore).
     */
    suspend fun verify(
        candidateBaseUrls: List<String>,
        header: CredentialHeader,
        headerSig: String,
        http: MaknoonHttp = MaknoonHttp(),
    ): HavidResult = withContext(Dispatchers.IO) {
        val headerBytes = header.canonicalBytes()
        val sigBytes = hexToBytes(headerSig)
        // DID -> cert binding: the doc key must ALSO have signed this credential.
        resolve(candidateBaseUrls, header.iss, http) { pubkey ->
            MasterKey.verify(pubkey, sigBytes, headerBytes)
        }
    }

    /**
     * HAVID for a credential REFERENCE (a badge) with no headerSig. The DID -> cert
     * binding is the doc key equalling the issuer's ON-CHAIN registered key
     * ([issuerPubkey], from OnChainVerifier.verifyReference). Null -> falls back to
     * the doc self-signature only (weaker; still confirms the SAN cross-endorsement).
     */
    suspend fun verifyReference(
        candidateBaseUrls: List<String>,
        did: String,
        issuerPubkey: ByteArray?,
        http: MaknoonHttp = MaknoonHttp(),
    ): HavidResult = withContext(Dispatchers.IO) {
        resolve(candidateBaseUrls, did, http) { pubkey ->
            if (issuerPubkey == null || issuerPubkey.isEmpty()) true
            else pubkey.contentEquals(issuerPubkey)
        }
    }

    /** Fetch + verify the issuer doc (self-signature + the caller's [bind] check),
     *  then evaluate the X.509 cross-endorsement. */
    private fun resolve(
        candidateBaseUrls: List<String>,
        did: String,
        http: MaknoonHttp,
        bind: (ByteArray) -> Boolean,
    ): HavidResult {
        var sawIssuerDoc = false
        for (base in candidateBaseUrls) {
            val doc = fetchDoc(http, base) ?: continue
            if (doc.optStringOrNull("did") != did) continue
            val pubkey = doc.optStringOrNull("mlDsaPubkey")?.let { hexToBytes(it) } ?: continue
            val docSig = doc.optStringOrNull("signature")?.let { hexToBytes(it) } ?: continue
            val unsigned = JSONObject(doc.toString()).apply { remove("signature") }
            val docBytes = runCatching { canonicalize(jsonToValue(unsigned)) }.getOrNull() ?: continue
            if (!MasterKey.verify(pubkey, docSig, docBytes)) continue
            if (!bind(pubkey)) continue
            sawIssuerDoc = true
            return evaluateCert(doc, did)
        }
        return HavidResult(
            HavidState.NOT_RESOLVABLE,
            detail = if (sawIssuerDoc) "Issuer identity did not bind to this credential"
            else "Could not reach the issuer's published identity",
        )
    }

    /** cert -> DID: fingerprint integrity, SAN carries the DID, validity window. */
    private fun evaluateCert(doc: JSONObject, did: String): HavidResult {
        val havid = doc.optJSONObject("havid")
        val x5c = havid?.optJSONArray("x5c")
        val advertisedFp = havid?.optStringOrNull("certFingerprintSha256")
        if (havid == null || x5c == null || x5c.length() == 0 || advertisedFp == null) {
            return HavidResult(HavidState.NO_ENDORSEMENT, detail = "Issuer publishes no X.509 cross-endorsement")
        }
        val der = runCatching { Base64.decode(x5c.getString(0), Base64.DEFAULT) }.getOrNull()
            ?: return HavidResult(HavidState.INTEGRITY_FAILURE, "Leaf certificate is not valid base64")
        val fp = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        if (fp != advertisedFp.lowercase()) {
            return HavidResult(HavidState.INTEGRITY_FAILURE, "Leaf certificate fingerprint mismatch")
        }
        val cert = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull()
            ?: return HavidResult(HavidState.INTEGRITY_FAILURE, "Leaf certificate could not be parsed")
        val subjectCn = subjectCommonName(cert)
        val sanUris = runCatching {
            cert.subjectAlternativeNames?.mapNotNull { entry ->
                if ((entry.getOrNull(0) as? Int) == 6) entry.getOrNull(1) as? String else null
            } ?: emptyList()
        }.getOrDefault(emptyList())
        if (!sanUris.contains(did)) {
            return HavidResult(HavidState.KEY_ALIGNMENT_FAILURE, detail = "Certificate SAN does not carry $did", subject = subjectCn)
        }
        val validity = runCatching { cert.checkValidity(); true }.getOrDefault(false)
        if (!validity) {
            return HavidResult(HavidState.EXPIRED_REVOKED, detail = "Certificate is outside its validity window", subject = subjectCn)
        }
        return HavidResult(HavidState.CROSS_ENDORSED, subject = subjectCn)
    }

    private fun fetchDoc(http: MaknoonHttp, base: String): JSONObject? {
        val url = base.trimEnd('/') + "/v1/issuer/well-known-doc"
        val body = runCatching { http.getJson(url) }.getOrNull() ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** Common name from the leaf's subject DN, else the whole DN (display only). */
    private fun subjectCommonName(cert: X509Certificate): String {
        val dn = cert.subjectX500Principal.name
        return dn.split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substring(3)
            ?: dn
    }

    /** Recursively convert org.json into the plain Map/List/scalar tree that
     *  com.elabify.core.canonicalize accepts (byte-equal with the issuer). */
    private fun jsonToValue(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> LinkedHashMap<String, Any?>().apply {
            for (k in v.keys()) put(k, jsonToValue(v.get(k)))
        }
        is JSONArray -> (0 until v.length()).map { jsonToValue(v.get(it)) }
        else -> v
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun hexToBytes(hex: String): ByteArray {
        var s = if (hex.startsWith("0x") || hex.startsWith("0X")) hex.substring(2) else hex
        if (s.length % 2 != 0) s = "0$s"
        return ByteArray(s.length / 2) {
            ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
