// On-device ICAO 9303 Passive Authentication (Android port).
//
// Runs OFF the NFC reader thread, from persisted chip bytes (the SOD plus the
// captured data groups). Where the iOS port re-hydrates an NFCPassportModel and
// leans on its three verification booleans, Android has no equivalent library,
// so this performs the three Passive Auth checks directly:
//
//   1. integrity:   recompute each captured DG hash and compare it to the LDS
//                   SecurityObject (SOD) DataGroupHash table.
//   2. SOD signature: parse the SOD as a CMS SignedData (BouncyCastle), pull the
//                   Document Signer (DS) certificate, and cryptographically
//                   verify the signer's signature over the signed attributes
//                   (which themselves commit to the eContent / LDS object).
//   3. chain:       build a cert path from the DS cert to a trusted, in-date
//                   CSCA from the CSCATrustStore (java.security PKIX), which
//                   enforces the cert validity window.
//
// This is a holder-side SOFT signal: the issuer backend re-runs Passive Auth
// authoritatively at issuance (icao9303.ts). The verdict mirrors the backend's
// reason vocabulary so the on-device result predicts the server's. Trust-store
// skew (the phone's CSCA bundle lagging the server's) yields "integrity-OK but
// CSCA not on file" (amber, integrityOnly), never a hard reject. An expired but
// otherwise legitimate signer is a chain/expiry condition, also amber, never a
// red tamper verdict.
//
// Nothing here throws: every failure is reported as a PassiveAuthResult.Status.

package com.elabify.app.maknoon.iddocument

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertPathValidator
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.BERTags
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.Selector

// PassiveAuthResult (and its nested Status enum) is the shared model type
// declared in IDDocument.kt. This verifier produces it; it does not redeclare
// it.

object PassportPassiveAuthVerifier {

    /** Maknoon DG file-key (lowercase, as stored) -> ICAO DG number. */
    private val dgNumberForGroup: Map<String, Int> = mapOf(
        "dg1" to 1, "dg2" to 2, "dg11" to 11, "dg12" to 12, "dg15" to 15,
    )

    private val bc: BouncyCastleProvider by lazy { BouncyCastleProvider() }

    /**
     * Re-hydrate from stored bytes and run Passive Authentication. Pure and
     * synchronous; call it from a background thread (never the NFC reader
     * thread). Never throws.
     *
     * @param sod the raw EF.SOD bytes captured from the chip (the ICAO-tagged
     *   CMS SignedData), or null if the document shipped without it.
     * @param dataGroups captured data-group bytes keyed by the lowercase file
     *   key ("dg1", "dg2", ...). Each value is the raw DG TLV as read off-chip.
     * @param issuingAlpha3 the document's issuing-country alpha-3, surfaced as
     *   cscaCountry on a full pass.
     * @param trustedCscas the trusted CSCA set from CSCATrustStore; empty means
     *   no bundle is installed yet.
     * @param bundleVersion the installed CSCA bundle version label (diagnostic).
     */
    fun verify(
        sod: ByteArray?,
        dataGroups: Map<String, ByteArray>,
        issuingAlpha3: String?,
        trustedCscas: Set<X509Certificate>,
        bundleVersion: String?,
    ): PassiveAuthResult {
        val now = System.currentTimeMillis()

        fun result(
            status: PassiveAuthResult.Status,
            reason: String,
            csca: String? = null,
            dscIssuer: String? = null,
            dscFingerprint: String? = null,
        ) = PassiveAuthResult(
            status = status,
            reason = reason,
            cscaCountry = csca,
            checkedAt = now,
            bundleVersion = bundleVersion,
            dscIssuer = dscIssuer,
            dscFingerprint = dscFingerprint,
        )

        if (sod == null) return result(PassiveAuthResult.Status.UNAVAILABLE, "sod_missing")
        if (trustedCscas.isEmpty()) {
            return result(PassiveAuthResult.Status.UNAVAILABLE, "csca_bundle_unavailable")
        }

        // Parse the SOD into a CMS SignedData (unwrapping the ICAO 0x77 tag),
        // extract the DS cert, and read the LDS DataGroupHash table.
        val parsed = try {
            parseSod(sod)
        } catch (e: Exception) {
            // Malformed SOD: cannot run, not a tamper verdict.
            android.util.Log.w("CSCA", "parseSod failed: ${e.javaClass.simpleName}: ${e.message}", e)
            return result(PassiveAuthResult.Status.UNAVAILABLE, "sod_parse_failed")
        }

        val dsCert = parsed.signerCert
        val dscIssuer = dsCert?.issuerX500Principal?.name
        val dscFingerprint = dsCert?.let { sha256Fingerprint(it.encoded) }

        // --- 1. integrity: recompute DG hashes and compare to the SOD table ---
        // CRUCIAL distinction: a DG-hash mismatch is the only hard tamper signal.
        val mdName = digestJcaName(parsed.digestAlgorithmOid)
            ?: return result(PassiveAuthResult.Status.UNAVAILABLE, "sod_unknown_digest")
        val md = MessageDigest.getInstance(mdName)

        var comparedAny = false
        for ((group, bytes) in dataGroups) {
            val dgNo = dgNumberForGroup[group] ?: continue
            val expected = parsed.dgHashes[dgNo] ?: continue // SOD may not cover every captured DG
            comparedAny = true
            md.reset()
            val actual = md.digest(bytes)
            if (!actual.contentEquals(expected)) {
                return result(
                    PassiveAuthResult.Status.FAILED, "dg_hash_mismatch",
                    dscIssuer = dscIssuer, dscFingerprint = dscFingerprint,
                )
            }
        }
        if (!comparedAny) {
            // Nothing to compare (SOD covered none of the captured DGs): we
            // cannot assert integrity, so do not claim verified.
            return result(
                PassiveAuthResult.Status.UNAVAILABLE, "no_comparable_dg_hashes",
                dscIssuer = dscIssuer, dscFingerprint = dscFingerprint,
            )
        }

        // --- 2. SOD signature: the CMS SignedData signature must verify ---
        if (dsCert == null) {
            return result(
                PassiveAuthResult.Status.FAILED, "sod_signature_invalid",
                dscIssuer = dscIssuer, dscFingerprint = dscFingerprint,
            )
        }
        val sodSignatureValid = try {
            verifyCmsSignature(parsed.signerInfo, dsCert)
        } catch (_: Exception) {
            false
        }
        if (!sodSignatureValid) {
            return result(
                PassiveAuthResult.Status.FAILED, "sod_signature_invalid",
                dscIssuer = dscIssuer, dscFingerprint = dscFingerprint,
            )
        }

        // --- 3. chain: the DS cert must chain to a trusted, in-date CSCA ---
        // PKIX enforces the validity window, so an expired DS cert (or an
        // expired CSCA) fails here. That is authentic-but-expired, not forgery:
        // amber (integrityOnly), with a specific reason so we know what to source.
        val chainOutcome = buildChainToCsca(dsCert, trustedCscas)
        return when (chainOutcome) {
            ChainOutcome.OK ->
                result(PassiveAuthResult.Status.VERIFIED, "ok", csca = issuingAlpha3)
            ChainOutcome.EXPIRED -> result(
                PassiveAuthResult.Status.INTEGRITY_ONLY, "dsc_or_chain_expired",
                dscIssuer = dscIssuer, dscFingerprint = dscFingerprint,
            )
            ChainOutcome.NO_ANCHOR -> result(
                PassiveAuthResult.Status.INTEGRITY_ONLY, "no_matching_csca",
                dscIssuer = dscIssuer, dscFingerprint = dscFingerprint,
            )
        }
    }

    // ---- SOD parsing ---------------------------------------------------------

    /** The pieces of a parsed EF.SOD needed for Passive Authentication. */
    private class ParsedSod(
        val signedData: CMSSignedData,
        val signerInfo: SignerInformation,
        val signerCert: X509Certificate?,
        /** ICAO LDS SecurityObject: DG number -> stored hash. */
        val dgHashes: Map<Int, ByteArray>,
        /** OID of the DG-hash digest algorithm declared in the LDS object. */
        val digestAlgorithmOid: String,
    )

    /**
     * Parse EF.SOD. The chip wraps the CMS SignedData in an ICAO application
     * tag (0x77); strip it if present, then hand the inner ContentInfo to
     * BouncyCastle. The CMS eContent is the LDS SecurityObject (an
     * LDSSecurityObject ASN.1 SEQUENCE) which carries the per-DG hash table.
     */
    private fun parseSod(sod: ByteArray): ParsedSod {
        val cmsBytes = stripIcaoWrapper(sod)
        val signed = CMSSignedData(cmsBytes)

        // EF.SOD always has exactly one signer (the Document Signer).
        val signerInfo = signed.signerInfos.signers.first()

        // Resolve the DS cert that matches the signer id, when the SOD carries it.
        val dsCert = resolveSignerCert(signed, signerInfo)

        // The signed content is the LDS SecurityObject; parse its hash table.
        val eContent = signed.signedContent?.content as? ByteArray
            ?: throw IllegalStateException("SOD eContent is not raw bytes")
        val (digestOid, hashes) = parseLdsSecurityObject(eContent)

        return ParsedSod(signed, signerInfo, dsCert, hashes, digestOid)
    }

    /**
     * If [sod] begins with the ICAO application-specific tag 0x77, unwrap it to
     * the inner DER (the CMS ContentInfo). Otherwise return the bytes unchanged.
     */
    private fun stripIcaoWrapper(sod: ByteArray): ByteArray {
        // 0x77 = APPLICATION (0x40) | CONSTRUCTED (0x20) | tag 23 (0x17): the
        // ICAO EF.SOD application tag. If it is not present, the bytes already
        // are the bare CMS ContentInfo.
        if (sod.isEmpty() || (sod[0].toInt() and 0xFF) != 0x77) return sod
        // The 0x77 tag's VALUE is a complete CMS ContentInfo TLV (SEQUENCE {
        // contentType OID, content [0] SignedData }). Skip the tag byte + the DER
        // length and return the value bytes verbatim. Reinterpreting the wrapper
        // as an implicitly-tagged SEQUENCE (the previous approach) parsed the
        // ContentInfo's own SEQUENCE tag as its first element, so ContentInfo
        // saw a SEQUENCE where it expected the signedData OID and threw.
        return try {
            var idx = 1
            val firstLen = sod[idx].toInt() and 0xFF
            idx++
            val length = if (firstLen < 0x80) {
                firstLen
            } else {
                val numBytes = firstLen and 0x7F
                var acc = 0
                repeat(numBytes) {
                    acc = (acc shl 8) or (sod[idx].toInt() and 0xFF)
                    idx++
                }
                acc
            }
            val end = idx + length
            if (length <= 0 || end > sod.size) sod else sod.copyOfRange(idx, end)
        } catch (_: Exception) {
            sod
        }
    }

    /**
     * Parse an LDSSecurityObject:
     *   LDSSecurityObject ::= SEQUENCE {
     *     version                INTEGER,
     *     hashAlgorithm          AlgorithmIdentifier,
     *     dataGroupHashValues    SEQUENCE OF DataGroupHash }
     *   DataGroupHash ::= SEQUENCE { dataGroupNumber INTEGER, dataGroupHashValue OCTET STRING }
     *
     * @return the digest-algorithm OID plus the DG-number -> hash map.
     */
    private fun parseLdsSecurityObject(eContent: ByteArray): Pair<String, Map<Int, ByteArray>> {
        val top = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(eContent))
        // [0] version, [1] hashAlgorithm, [2] dataGroupHashValues.
        val hashAlg = AlgorithmIdentifier.getInstance(top.getObjectAt(1))
        val digestOid = hashAlg.algorithm.id
        val hashSeq = ASN1Sequence.getInstance(top.getObjectAt(2))

        val hashes = HashMap<Int, ByteArray>()
        for (i in 0 until hashSeq.size()) {
            val pair = ASN1Sequence.getInstance(hashSeq.getObjectAt(i))
            val dgNo = (pair.getObjectAt(0) as ASN1Integer).value.toInt()
            val hash = (pair.getObjectAt(1) as ASN1OctetString).octets
            hashes[dgNo] = hash
        }
        return digestOid to hashes
    }

    /** Find the DS X.509 cert in the SignedData that matches the signer id. */
    private fun resolveSignerCert(
        signed: CMSSignedData,
        signerInfo: SignerInformation,
    ): X509Certificate? {
        // signed.certificates is a Store<X509CertificateHolder>; getMatches
        // expects a Selector<X509CertificateHolder>. SignerId implements that
        // Selector, but Kotlin needs the cast to line the generics up. A null
        // selector returns every holder (the fallback when the SID does not
        // match any embedded cert).
        @Suppress("UNCHECKED_CAST")
        val sidSelector = signerInfo.sid as Selector<X509CertificateHolder>
        val store = signed.certificates
        val matched: Collection<X509CertificateHolder> = store.getMatches(sidSelector)
        val holder: X509CertificateHolder = matched.firstOrNull()
            ?: store.getMatches(null).firstOrNull()
            ?: return null
        return toX509(holder)
    }

    private fun toX509(holder: X509CertificateHolder): X509Certificate {
        // BC provider: ICAO DS/CSCA certs often carry EXPLICIT EC domain
        // parameters, which the platform (Conscrypt) CertificateFactory rejects
        // with "Only named ECParameters supported". BC parses them, and using it
        // here keeps the signer cert byte-aligned with the BC-parsed anchors.
        val cf = CertificateFactory.getInstance("X.509", bc)
        return cf.generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
    }

    // ---- step 2: CMS signature verification ----------------------------------

    private fun verifyCmsSignature(
        signerInfo: SignerInformation,
        dsCert: X509Certificate,
    ): Boolean {
        // Build a verifier from the DS public key (BC provider). This checks the
        // signature over the signed attributes (which include the messageDigest
        // attribute binding the eContent) cryptographically, independent of the
        // cert validity window (the window is enforced in step 3).
        val verifier = JcaSimpleSignerInfoVerifierBuilder()
            .setProvider(bc)
            .build(dsCert.publicKey)
        return signerInfo.verify(verifier)
    }

    // ---- step 3: chain to a trusted CSCA -------------------------------------

    private enum class ChainOutcome { OK, EXPIRED, NO_ANCHOR }

    /**
     * Try to build + validate a one-link path from the DS cert to a trusted
     * CSCA. Returns OK on a valid in-date chain, EXPIRED when a cert in the
     * path is out of its validity window, and NO_ANCHOR when no trusted CSCA
     * issued the DS cert (stale or missing bundle entry).
     */
    private fun buildChainToCsca(
        dsCert: X509Certificate,
        trustedCscas: Set<X509Certificate>,
    ): ChainOutcome {
        // Narrow to the anchors that actually issued this DS cert (subject DN of
        // the CSCA == issuer DN of the DS cert). If none match, the bundle is
        // missing the right CSCA: amber NO_ANCHOR, never a tamper verdict.
        val candidateAnchors = trustedCscas.filter {
            it.subjectX500Principal == dsCert.issuerX500Principal
        }
        if (candidateAnchors.isEmpty()) return ChainOutcome.NO_ANCHOR

        // BC provider throughout: explicit-EC DS/CSCA certs fail to build or
        // validate a path under the platform provider, which would force an
        // authentic passport to amber NO_ANCHOR. BC validates them to OK (green).
        val cf = CertificateFactory.getInstance("X.509", bc)
        val certPath = cf.generateCertPath(listOf(dsCert))
        val anchors = candidateAnchors.map { TrustAnchor(it, null) }.toSet()

        val params = PKIXParameters(anchors).apply {
            isRevocationEnabled = false // no CRL/OCSP infra on device (soft badge)
        }
        val validator = CertPathValidator.getInstance("PKIX", bc)
        return try {
            validator.validate(certPath, params)
            ChainOutcome.OK
        } catch (e: Exception) {
            // Distinguish expiry (authentic-but-expired -> amber) from a
            // genuine path-build failure (no usable anchor -> amber NO_ANCHOR).
            if (mentionsExpiry(e) || dsCert.isExpiredOrNotYetValid()) {
                ChainOutcome.EXPIRED
            } else {
                ChainOutcome.NO_ANCHOR
            }
        }
    }

    private fun X509Certificate.isExpiredOrNotYetValid(): Boolean = try {
        checkValidity()
        false
    } catch (_: CertificateExpiredException) {
        true
    } catch (_: CertificateNotYetValidException) {
        true
    }

    private fun mentionsExpiry(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is CertificateExpiredException || cur is CertificateNotYetValidException) return true
            val msg = cur.message?.lowercase()
            if (msg != null && (msg.contains("expired") || msg.contains("not yet valid"))) return true
            cur = cur.cause
        }
        return false
    }

    // ---- digest helpers ------------------------------------------------------

    /** Map a digest OID (or named OID) to a JCA MessageDigest algorithm name. */
    private fun digestJcaName(oid: String): String? = when (oid) {
        "2.16.840.1.101.3.4.2.1" -> "SHA-256"
        "2.16.840.1.101.3.4.2.2" -> "SHA-384"
        "2.16.840.1.101.3.4.2.3" -> "SHA-512"
        "2.16.840.1.101.3.4.2.4" -> "SHA-224"
        "1.3.14.3.2.26" -> "SHA-1" // legacy chips
        else -> null
    }

    private fun sha256Fingerprint(der: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(der)
        return d.joinToString(":") { "%02X".format(it) }
    }
}
