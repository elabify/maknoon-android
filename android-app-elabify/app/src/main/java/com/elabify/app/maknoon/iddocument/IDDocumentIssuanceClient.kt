// HTTP client for Maknoon's verified-identity credential issuance.
//
// Wire format follows ADR-0026 holder-initiated attestation:
//   - The holder constructs an AttestationPacket envelope with a
//     freshly-generated 16-byte packetId, signs the packetId bytes with the
//     master ML-DSA-65 key, and POSTs to the issuer.
//   - Server pre-verifies the proof (Passive Authentication: SOD, CSCA chain,
//     DG hash table; Active Authentication if captured; holder ML-DSA
//     signature over packetId; App Attest if present).
//   - If pre-verification passes, the packet is queued for operator review
//     (or, in the hybrid auto-mint mode, auto-approved). Either path returns a
//     pendingId; the credential becomes available through the standard pickup
//     flow once it is anchored.
//
// Endpoint: POST {issuer}/v1/passport-attestation/submit-packet.
//
// This is the Android port of iOS IDDocumentIssuanceClient.swift. The packet
// shape matches the issuer-backend's AttestationPacket schema byte-for-byte so
// the two land integrated. Crypto + identity material come from the SDK
// (com.elabify.musnad); we never reimplement signing here.
//
// Note on App Attest: the iOS path attaches an Apple App Attest assertion when
// the device supports it. Android has no App Attest equivalent under the
// GMS-free constraint (Play Integrity is a GMS API and is intentionally
// excluded), so the appAttest block is always omitted here. The issuer treats
// it as optional. If a GMS-free hardware-attestation path lands later (Android
// Keystore key attestation), it would slot in at the same point.

package com.elabify.app.maknoon.iddocument

import com.elabify.app.maknoon.ui.common.LocalizedThrowable
import com.elabify.app.maknoon.ui.common.userMessage
import com.elabify.app.maknoon.ui.common.hostOf
import androidx.annotation.StringRes
import com.elabify.app.maknoon.MaknoonApplication
import com.elabify.app.maknoon.R
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Typed failures for the issuance flow, mirroring iOS IDDocumentIssuanceError.
 *
 * These messages reach the user (the issuance sheet renders `e.message`), so
 * they carry a string resource rather than a Kotlin literal. There is no
 * Context here and two of the cases are `object` singletons, so the text is
 * resolved on READ through the process-wide application context: resolving it
 * at construction would freeze a singleton's message in whatever language was
 * active the first time it was touched, and survive an in-app language change.
 */
sealed class IDDocumentIssuanceException(
    @StringRes private val messageRes: Int,
    private val detail: String? = null,
) : Exception(), LocalizedThrowable {
    override val message: String
        get() = MaknoonApplication.appContext.let { ctx ->
            if (detail == null) ctx.getString(messageRes)
            else ctx.getString(messageRes, detail)
        }

    /** The document is missing the chip-signed objects (SOD) the issuer needs. */
    object MissingChipMaterial :
        IDDocumentIssuanceException(R.string.id_issuance_missing_chip_material)

    /** The Identity Sandwich is not loaded. */
    object IdentityNotLoaded :
        IDDocumentIssuanceException(R.string.id_issuance_identity_locked)

    class MldsaSignFailed(detail: String) :
        IDDocumentIssuanceException(R.string.id_issuance_sign_failed, detail)

    class SubmitFailed(detail: String) :
        IDDocumentIssuanceException(R.string.id_issuance_submit_failed, detail)

    class MalformedResponse(detail: String) :
        IDDocumentIssuanceException(R.string.id_issuance_malformed_response, detail)

    class IssuerDidLookupFailed(detail: String) :
        IDDocumentIssuanceException(R.string.id_issuance_did_lookup_failed, detail)
}

/**
 * The chip material plus MRZ-derived fields the issuer needs to mint a
 * verified-identity credential. This mirrors the subset of the iOS IDDocument
 * that the issuance packet reads. The caller (the IDDocument detail screen)
 * builds this from the saved document and its on-disk chip blobs so this client
 * stays decoupled from the document store / persistence layer.
 *
 * All `*Hex` fields are lowercase hex with no `0x` prefix (matching the SDK
 * [toHex]). `sodHex` is required; the data-group blobs are optional because not
 * every chip exposes every DG.
 */
data class PassportIssuanceInput(
    // MRZ + DG11/DG12 derived fields.
    val documentNumber: String,
    /** Library-reported surname (may be native-script for CHN/JPN passports). */
    val surname: String,
    val givenNames: String,
    val latinSurname: String?,
    val latinGivenNames: String?,
    val nativeFullName: String?,
    val nationality: String,
    val issuingAuthority: String,
    val sex: String?,
    val dateOfBirth: String,
    val dateOfExpiry: String,
    val documentType: String,
    val personalNumber: String?,
    val placeOfBirth: String?,
    // Chip blobs, hex-encoded. SOD is the minimum material the issuer needs.
    val sodHex: String,
    val dg1Hex: String?,
    val dg2Hex: String?,
    val dg11Hex: String?,
    val dg12Hex: String?,
    val dg15Hex: String?,
    // Active Authentication, when the chip supported it.
    val activeAuthChallengeHex: String?,
    val activeAuthSignatureHex: String?,
    val activeAuthVerifiedLocally: Boolean?,
)

/**
 * What the issuer returns after accepting (or queueing) a submitted packet.
 * Matches the ADR-0026 SubmitPacketResponse shape on the issuer-backend.
 */
data class AttestationSubmitAck(
    val v: Int,
    val pendingId: String,
    /**
     * `pending_review`, `approved`, or `rejected`. With auto-mint enabled
     * server-side, pre-verified passport packets come back as `approved` along
     * with `pickupUrl` and `credentialId` set; the holder polls the URL and
     * imports the credential. Without auto-mint, pickup waits for an operator
     * approve.
     */
    val status: String,
    val proofPreVerified: Boolean,
    val proofPreVerifiedReason: String,
    /** Set when the issuer auto-approved on submit; the holder polls this URL. */
    val pickupUrl: String?,
    /** Issuer-side credential id for the auto-minted credential. */
    val credentialId: String?,
    /** Operator hint: the next scheduled batch flush time (epoch seconds). */
    val estimatedAnchorAt: Long?,
)

/**
 * Builds and submits passport attestation packets. The optional flow that
 * sends a read passport to the Elabify issuer to mint an identity credential.
 */
class IDDocumentIssuanceClient(
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    /**
     * Build and submit a passport attestation packet. Returns the issuer's ack
     * containing the pendingId.
     *
     * The caller must gate this behind BiometricPrompt before calling, because
     * [IdentitySandwich.signWithMaster] reconstructs the master key (the slow,
     * high-trust path). This is a deliberate, high-trust operation, so we sign
     * with the master key (not the fast ephemeral key): the server verifies the
     * signature against the master pubkey carried in the packet.
     *
     * @param sandwich the loaded holder identity (master key + DID).
     * @param input the chip material + MRZ fields to attest.
     * @param issuerBaseUrl base URL to POST to (e.g. https://musnad-issuer.elabify.com).
     *   Defaults to [DEFAULT_ISSUER_BASE_URL].
     * @param issuerDid optional explicit issuer DID. When null, the DID is
     *   resolved dynamically via GET {base}/v1/issuer/info.
     */
    suspend fun submit(
        sandwich: IdentitySandwich,
        input: PassportIssuanceInput,
        issuerBaseUrl: String? = null,
        issuerDid: String? = null,
    ): AttestationSubmitAck = withContext(Dispatchers.IO) {
        // 1. Sanity: SOD is the minimum chip material the issuer needs.
        if (input.sodHex.isBlank()) throw IDDocumentIssuanceException.MissingChipMaterial

        // 2. Identity material. holderDid is stable across delegation renewals.
        val holderDid = sandwich.holderDid
        val masterPkHex = sandwich.masterPublicKey.toHex()

        // 2b. Resolve the issuer's DID. Each deployment configures its own
        // ELABIFY_ISSUER_DID, and the canonical place to learn it is
        // GET /v1/issuer/info on the issuer itself. Fetching it dynamically
        // means switching between local-dev, sepolia, and production issuers in
        // the picker Just Works without having to type DIDs by hand.
        val baseUrl = (issuerBaseUrl ?: DEFAULT_ISSUER_BASE_URL).trim('/')
        val resolvedIssuerDid = issuerDid ?: fetchIssuerDid(baseUrl)

        // 3. Fresh packetId. 16 bytes, hex-encoded with 0x prefix to match the
        //    issuer-backend's PACKET_ID_RE.
        val packetIdBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val packetId = "0x" + packetIdBytes.toHex()

        // 4. Holder ML-DSA-65 signature over the packetId bytes. Issuance is a
        // deliberate, high-trust operation so we sign with the master key. The
        // fast-path ephemeral key would be wrong here: the server verifies the
        // signature against the master pubkey carried in the packet.
        val holderSigHex = try {
            sandwich.signWithMaster(packetIdBytes).toHex()
        } catch (e: Exception) {
            throw IDDocumentIssuanceException.MldsaSignFailed(e.message ?: e.toString())
        }

        // 5. Build the AttestationPacket envelope. JSON object construction with
        //    org.json. The issuer canonicalises by sorting keys server-side, so
        //    field order here does not matter for the proof.
        val packet = buildPacket(
            packetId = packetId,
            issuerDid = resolvedIssuerDid,
            holderDid = holderDid,
            masterPkHex = masterPkHex,
            holderSigHex = holderSigHex,
            input = input,
        )

        // 6. POST. The submit body is the packet JSON.
        val submitUrl = "$baseUrl/v1/passport-attestation/submit-packet"
        val respBody = try {
            http.postJson(submitUrl, packet.toString())
        } catch (e: Exception) {
            // The detail is interpolated into a localized sentence, so it has
            // to be localized too. It used to be the raw exception text, which
            // on a phone with no network meant an Arabic sentence wrapped
            // around Android's English "Unable to resolve host ...". Both an
            // HTTP status line and a transport failure are covered here.
            throw IDDocumentIssuanceException.SubmitFailed(
                e.userMessage(MaknoonApplication.appContext, hostOf(submitUrl)),
            )
        }

        try {
            parseAck(JSONObject(respBody))
        } catch (e: Exception) {
            throw IDDocumentIssuanceException.MalformedResponse(e.message ?: e.toString())
        }
    }

    private fun buildPacket(
        packetId: String,
        issuerDid: String,
        holderDid: String,
        masterPkHex: String,
        holderSigHex: String,
        input: PassportIssuanceInput,
    ): JSONObject {
        val fields = JSONObject()
            .put("documentNumber", input.documentNumber)
            .put("surname", input.surname)
            .put("givenNames", input.givenNames)
            .putOpt("latinSurname", input.latinSurname)
            .putOpt("latinGivenNames", input.latinGivenNames)
            .putOpt("nativeFullName", input.nativeFullName)
            .put("nationality", input.nationality)
            .put("issuingAuthority", input.issuingAuthority)
            .putOpt("sex", input.sex)
            .put("dateOfBirth", input.dateOfBirth)
            .put("dateOfExpiry", input.dateOfExpiry)
            .put("documentType", input.documentType)
            .putOpt("personalNumber", input.personalNumber)
            .putOpt("placeOfBirth", input.placeOfBirth)

        val dataGroups = JSONObject()
            .putOpt("dg1", input.dg1Hex)
            .putOpt("dg2", input.dg2Hex)
            .putOpt("dg11", input.dg11Hex)
            .putOpt("dg12", input.dg12Hex)
            .putOpt("dg15", input.dg15Hex)

        val proof = JSONObject()
            .put("kind", "icao9303-passport")
            .put("holderMasterPkHex", masterPkHex)
            .put("holderSigHex", holderSigHex)
            .put("fields", fields)
            .put("sodHex", input.sodHex)
            .put("dataGroupsHex", dataGroups)

        if (input.activeAuthChallengeHex != null && input.activeAuthSignatureHex != null) {
            proof.put(
                "activeAuth",
                JSONObject()
                    .put("challengeHex", input.activeAuthChallengeHex)
                    .put("signatureHex", input.activeAuthSignatureHex)
                    .put("verifiedLocally", input.activeAuthVerifiedLocally ?: false),
            )
        }

        return JSONObject()
            .put("v", 1)
            .put("packetId", packetId)
            .put("packetType", "icao9303-passport")
            .put("issuerDid", issuerDid)
            .put("holderDid", holderDid)
            .put("generatedAt", System.currentTimeMillis() / 1000L)
            .put("proof", proof)
        // appAttest is intentionally omitted (no GMS-free App Attest equivalent).
    }

    private fun parseAck(o: JSONObject): AttestationSubmitAck = AttestationSubmitAck(
        v = o.optInt("v", 1),
        pendingId = o.getString("pendingId"),
        status = o.getString("status"),
        proofPreVerified = o.optBoolean("proofPreVerified", false),
        proofPreVerifiedReason = o.optString("proofPreVerifiedReason", ""),
        pickupUrl = o.optStringOrNull("pickupUrl"),
        credentialId = o.optStringOrNull("credentialId"),
        estimatedAnchorAt = o.optLongOrNull("estimatedAnchorAt"),
    )

    /**
     * Look up the issuer's DID via GET {base}/v1/issuer/info. The response
     * carries other fields (mlDsaPubkey, anchorManifest, schemas); we only need
     * the DID here.
     */
    private fun fetchIssuerDid(baseUrl: String): String {
        val body = try {
            http.getJson("$baseUrl/v1/issuer/info")
        } catch (e: Exception) {
            throw IDDocumentIssuanceException.IssuerDidLookupFailed(
                e.userMessage(MaknoonApplication.appContext, hostOf(baseUrl)),
            )
        }
        return try {
            JSONObject(body).getString("did")
        } catch (e: Exception) {
            throw IDDocumentIssuanceException.IssuerDidLookupFailed(
                "bad info JSON: ${e.message ?: e.toString()}",
            )
        }
    }

    companion object {
        /**
         * Default issuer endpoint. Configurable in Settings, Identity, Known
         * issuers if a private issuer needs to be used.
         */
        const val DEFAULT_ISSUER_BASE_URL = "https://musnad-issuer.elabify.com"

        /**
         * Default issuer DID the holder is enrolling with. Used only as a
         * documented fallback; the live path resolves the DID from the issuer's
         * /v1/issuer/info. The server validates that the packet's issuerDid
         * matches its own configured DID.
         */
        const val DEFAULT_ISSUER_DID =
            "did:elabify:sepolia:musnad:0x0000000000000000000000000000000000000001"
    }
}

// org.json helpers. putOpt drops null keys (so optional fields are absent
// rather than JSON null); the opt*OrNull readers distinguish absent/null from a
// present value.
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null
