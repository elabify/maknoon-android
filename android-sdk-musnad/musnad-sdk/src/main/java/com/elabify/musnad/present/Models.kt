// Wire-format data types for the "present a credential to a verifier" flow,
// ported from iOS Models.swift + PresentationFactory.swift +
// PresentationVerifier.swift. These shapes are a cross-platform contract:
// the verifier-server and iOS are the ground truth, so field names,
// canonicalization, and signing inputs must match byte-for-byte.
//
// Everything routes through org.json for encode/decode. Canonical signing
// inputs route through com.elabify.core.canonicalize so the local verdict
// and the built presentation match the server's checks for the subset we
// run here.
//
// GMS-free. No third-party JSON binding (Moshi/Gson/kotlinx): org.json only.

package com.elabify.musnad.present

import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.toHex
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// JSON value wrapper (mirrors iOS JSONValue). Round-trips claim payloads
// through org.json without losing types, and exposes anyValue() for
// canonicalize() (which accepts null / Boolean / Long / Double / String /
// List / Map).
// ---------------------------------------------------------------------------

sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class IntVal(val value: Long) : JsonValue()
    data class DoubleVal(val value: Double) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    object Null : JsonValue()
    data class Arr(val value: List<JsonValue>) : JsonValue()
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()

    /** Value shaped for com.elabify.core.canonicalize(...). Bool is matched
     *  before the numeric branches so a boolean never serializes as a number. */
    fun anyValue(): Any? = when (this) {
        is Bool -> value
        is Str -> value
        is IntVal -> value
        is DoubleVal -> value
        is Null -> null
        is Arr -> value.map { it.anyValue() }
        is Obj -> value.mapValues { it.value.anyValue() }
    }

    /** Encode back into an org.json-compatible value (for re-serialization). */
    fun toJsonField(): Any = when (this) {
        is Bool -> value
        is Str -> value
        is IntVal -> value
        is DoubleVal -> value
        is Null -> JSONObject.NULL
        is Arr -> JSONArray().also { a -> value.forEach { a.put(it.toJsonField()) } }
        is Obj -> JSONObject().also { o -> value.forEach { (k, v) -> o.put(k, v.toJsonField()) } }
    }

    /** Single-line display rendering for UI surfaces. */
    fun displayText(): String = when (this) {
        is Str -> value
        is IntVal -> value.toString()
        is DoubleVal -> value.toString()
        is Bool -> if (value) "yes" else "no"
        is Null -> "-"
        is Arr -> "[${value.size} item${if (value.size == 1) "" else "s"}]"
        is Obj -> "{${value.size} field${if (value.size == 1) "" else "s"}}"
    }

    companion object {
        /** Decode an arbitrary org.json field (the result of JSONObject.get /
         *  JSONArray.get, or a raw scalar) into a JsonValue. Distinguishes
         *  integers from doubles the way iOS does (Int64 first, then Double). */
        fun fromJsonField(raw: Any?): JsonValue = when (raw) {
            null, JSONObject.NULL -> Null
            is Boolean -> Bool(raw)
            is Int -> IntVal(raw.toLong())
            is Long -> IntVal(raw)
            is Double -> {
                // org.json yields Double even for integral literals like 42; if
                // it is integral, keep it as an integer to match canonicalize.
                if (raw == Math.floor(raw) && !raw.isInfinite()) IntVal(raw.toLong()) else DoubleVal(raw)
            }
            is Float -> {
                val d = raw.toDouble()
                if (d == Math.floor(d) && !d.isInfinite()) IntVal(d.toLong()) else DoubleVal(d)
            }
            is String -> Str(raw)
            is JSONArray -> Arr((0 until raw.length()).map { fromJsonField(raw.opt(it)) })
            is JSONObject -> Obj(raw.keys().asSequence().associateWith { fromJsonField(raw.opt(it)) })
            else -> Str(raw.toString())
        }
    }
}

// ---------------------------------------------------------------------------
// Credential header + supporting wire structs.
// ---------------------------------------------------------------------------

/** Per the issuer backend CredentialHeader. Optional fields are
 *  null when absent and are OMITTED from the canonical header dict (iOS
 *  JSONEncoder omits nil), which is load-bearing for headerSig verification. */
data class CredentialHeader(
    val v: Int,
    val alg: String?,
    val hash: String?,
    val iss: String,
    val sub: String,
    val iat: Long,
    val exp: Long?,
    val cid: String,
    val root: String,
    val schema: String,
    val allowedNetworks: List<String>?,
) {
    /** Map matching iOS JSONEncoder output (nil optionals omitted). This is the
     *  exact field set canonicalize() hashes for headerSig. */
    fun toCanonicalMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = v
        if (alg != null) m["alg"] = alg
        if (hash != null) m["hash"] = hash
        m["iss"] = iss
        m["sub"] = sub
        m["iat"] = iat
        if (exp != null) m["exp"] = exp
        m["cid"] = cid
        m["root"] = root
        m["schema"] = schema
        if (allowedNetworks != null) m["allowedNetworks"] = allowedNetworks
        return m
    }

    /** Canonical bytes the issuer/holder signs for headerSig. */
    fun canonicalBytes(): ByteArray = canonicalize(toCanonicalMap())

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", v)
        if (alg != null) o.put("alg", alg)
        if (hash != null) o.put("hash", hash)
        o.put("iss", iss)
        o.put("sub", sub)
        o.put("iat", iat)
        if (exp != null) o.put("exp", exp)
        o.put("cid", cid)
        o.put("root", root)
        o.put("schema", schema)
        if (allowedNetworks != null) o.put("allowedNetworks", JSONArray(allowedNetworks))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): CredentialHeader = CredentialHeader(
            v = o.getInt("v"),
            alg = o.optStringOrNull("alg"),
            hash = o.optStringOrNull("hash"),
            iss = o.getString("iss"),
            sub = o.getString("sub"),
            iat = o.getLong("iat"),
            exp = if (o.has("exp") && !o.isNull("exp")) o.getLong("exp") else null,
            cid = o.getString("cid"),
            root = o.getString("root"),
            schema = o.getString("schema"),
            allowedNetworks = o.optStringList("allowedNetworks"),
        )
    }
}

/** Merkle inclusion-proof step (matches iOS ProofEntry). sibling is 0x-hex. */
data class ProofEntry(val sibling: String, val isRight: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("sibling", sibling).put("isRight", isRight)

    companion object {
        fun fromJson(o: JSONObject): ProofEntry =
            ProofEntry(o.getString("sibling"), o.getBoolean("isRight"))

        fun listFromJson(a: JSONArray?): List<ProofEntry> =
            if (a == null) emptyList() else (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/** ADR-0022 batch-anchoring metadata, echoed verbatim into the presentation. */
data class AnchorEntry(
    val chain: String,
    val registry: String,
    val batchRoot: String,
    val batchTxHash: String,
    val anchoredAt: Long,
    val batchProof: List<ProofEntry>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("chain", chain)
        .put("registry", registry)
        .put("batchRoot", batchRoot)
        .put("batchTxHash", batchTxHash)
        .put("anchoredAt", anchoredAt)
        .put("batchProof", JSONArray().also { arr -> batchProof.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): AnchorEntry = AnchorEntry(
            chain = o.getString("chain"),
            registry = o.getString("registry"),
            batchRoot = o.getString("batchRoot"),
            batchTxHash = o.getString("batchTxHash"),
            anchoredAt = o.getLong("anchoredAt"),
            batchProof = ProofEntry.listFromJson(o.optJSONArray("batchProof")),
        )
    }
}

data class AnchorDescriptor(val v: Int, val anchors: List<AnchorEntry>) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", v)
        .put("anchors", JSONArray().also { arr -> anchors.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): AnchorDescriptor = AnchorDescriptor(
            v = o.getInt("v"),
            anchors = (o.optJSONArray("anchors") ?: JSONArray()).let { a ->
                (0 until a.length()).map { AnchorEntry.fromJson(a.getJSONObject(it)) }
            },
        )
    }
}

/** Descriptor of the issuer's Merkle tree over the claim set (sortedKeys is
 *  the on-the-wire claim order; we recompute proofs from it). */
data class MerkleTreeDescriptor(
    val sortedKeys: List<String>,
    val leafHashes: List<String>,
    val root: String,
    val depth: Int,
) {
    companion object {
        fun fromJson(o: JSONObject): MerkleTreeDescriptor = MerkleTreeDescriptor(
            sortedKeys = o.optStringList("sortedKeys") ?: emptyList(),
            leafHashes = o.optStringList("leafHashes") ?: emptyList(),
            root = o.getString("root"),
            depth = o.optInt("depth", 0),
        )
    }
}

// ---------------------------------------------------------------------------
// Disclosed claim + presentation delegation/attestations.
// ---------------------------------------------------------------------------

data class DisclosedClaim(
    val key: String,
    val value: JsonValue,
    val leafIndex: Int,
    val proof: List<ProofEntry>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("value", value.toJsonField())
        .put("leafIndex", leafIndex)
        .put("proof", JSONArray().also { arr -> proof.forEach { arr.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): DisclosedClaim = DisclosedClaim(
            key = o.getString("key"),
            value = JsonValue.fromJsonField(o.opt("value")),
            leafIndex = o.optInt("leafIndex", 0),
            proof = ProofEntry.listFromJson(o.optJSONArray("proof")),
        )
    }
}

/** Wire delegation cert (Delegation in verifier-server types.ts). Mirrors the
 *  SDK DelegationCert but as a present-layer wire struct. */
data class PresentationDelegation(
    val ephemeralPk: String,
    val validFrom: Long,
    val validUntil: Long,
    val scope: List<String>,
    val delegationSig: String,
) {
    /** Canonical bytes the master signs (everything but delegationSig). */
    fun innerCanonicalBytes(): ByteArray = canonicalize(
        linkedMapOf<String, Any?>(
            "ephemeralPk" to ephemeralPk,
            "validFrom" to validFrom,
            "validUntil" to validUntil,
            "scope" to scope,
        ),
    )

    fun toJson(): JSONObject = JSONObject()
        .put("ephemeralPk", ephemeralPk)
        .put("validFrom", validFrom)
        .put("validUntil", validUntil)
        .put("scope", JSONArray(scope))
        .put("delegationSig", delegationSig)

    companion object {
        fun fromJson(o: JSONObject): PresentationDelegation = PresentationDelegation(
            ephemeralPk = o.getString("ephemeralPk"),
            validFrom = o.getLong("validFrom"),
            validUntil = o.getLong("validUntil"),
            scope = o.optStringList("scope") ?: emptyList(),
            delegationSig = o.getString("delegationSig"),
        )
    }
}

/** Optional secp256k1 hardware-wallet attestation. Carried verbatim; the
 *  offline verifier does not run hardwareAttestationValid (online-only). */
data class HardwareAttestation(
    val kind: String,
    val masterPubkey: String,
    val attestorPubkey: String,
    val attestorSig: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind)
        .put("masterPubkey", masterPubkey)
        .put("attestorPubkey", attestorPubkey)
        .put("attestorSig", attestorSig)

    companion object {
        fun fromJson(o: JSONObject): HardwareAttestation = HardwareAttestation(
            kind = o.getString("kind"),
            masterPubkey = o.getString("masterPubkey"),
            attestorPubkey = o.getString("attestorPubkey"),
            attestorSig = o.getString("attestorSig"),
        )
    }
}

/** App Attest binding for a self-issued credential. On Android self-issued
 *  credentials are key-only (no App Attest), so the builder always omits this;
 *  it is decoded/echoed when present from an iOS-built presentation. */
data class SelfIssuerAttestation(
    val keyId: String,
    val attestation: String,
    val assertion: String,
    val bindingHashHex: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("keyId", keyId)
        .put("attestation", attestation)
        .put("assertion", assertion)
        .put("bindingHashHex", bindingHashHex)

    companion object {
        fun fromJson(o: JSONObject): SelfIssuerAttestation = SelfIssuerAttestation(
            keyId = o.getString("keyId"),
            attestation = o.getString("attestation"),
            assertion = o.getString("assertion"),
            bindingHashHex = o.getString("bindingHashHex"),
        )
    }
}

// ---------------------------------------------------------------------------
// Verifier request (open-verifier flow) + registry record.
// ---------------------------------------------------------------------------

data class VerifierFilterClause(val mode: String, val list: List<String>?) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("mode", mode)
        if (list != null) o.put("list", JSONArray(list))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): VerifierFilterClause =
            VerifierFilterClause(o.getString("mode"), o.optStringList("list"))
    }
}

data class VerifierFilter(
    val issuers: VerifierFilterClause?,
    val schemas: VerifierFilterClause?,
    val requiredClaims: List<String>,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        if (issuers != null) o.put("issuers", issuers.toJson())
        if (schemas != null) o.put("schemas", schemas.toJson())
        o.put("requiredClaims", JSONArray(requiredClaims))
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): VerifierFilter = VerifierFilter(
            issuers = o.optJSONObject("issuers")?.let { VerifierFilterClause.fromJson(it) },
            schemas = o.optJSONObject("schemas")?.let { VerifierFilterClause.fromJson(it) },
            requiredClaims = o.optStringList("requiredClaims") ?: emptyList(),
        )
    }
}

data class VerifierResponseDirective(val mode: String, val callbackUrl: String?) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("mode", mode)
        if (callbackUrl != null) o.put("callbackUrl", callbackUrl)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): VerifierResponseDirective =
            VerifierResponseDirective(o.getString("mode"), o.optStringOrNull("callbackUrl"))
    }
}

/** Verifier-published request encoded into a QR (or fetched via request_uri).
 *  Matches verifier-server types.ts byte-for-byte. Optional fields are OMITTED
 *  when null so the canonical bytes (minus `signature`) match the server. */
data class VerifierRequest(
    val v: Int,
    val verifierDid: String,
    val verifierName: String?,
    val verifierPublicKey: String?,
    val requestId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val challenge: String,
    val filter: VerifierFilter,
    val response: VerifierResponseDirective,
    val signature: String?,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", v)
        o.put("verifierDid", verifierDid)
        if (verifierName != null) o.put("verifierName", verifierName)
        if (verifierPublicKey != null) o.put("verifierPublicKey", verifierPublicKey)
        o.put("requestId", requestId)
        o.put("issuedAt", issuedAt)
        o.put("expiresAt", expiresAt)
        o.put("challenge", challenge)
        o.put("filter", filter.toJson())
        o.put("response", response.toJson())
        if (signature != null) o.put("signature", signature)
        return o
    }

    /** The map the server canonicalizes WITHOUT `signature`, used for the
     *  verifier-request signature check. Built from the same field set the
     *  wire object carries (nulls omitted), then `signature` is dropped. */
    fun canonicalMapWithoutSignature(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["v"] = v
        m["verifierDid"] = verifierDid
        if (verifierName != null) m["verifierName"] = verifierName
        if (verifierPublicKey != null) m["verifierPublicKey"] = verifierPublicKey
        m["requestId"] = requestId
        m["issuedAt"] = issuedAt
        m["expiresAt"] = expiresAt
        m["challenge"] = challenge
        m["filter"] = filterToMap()
        m["response"] = responseToMap()
        // `signature` intentionally omitted.
        return m
    }

    private fun filterToMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        filter.issuers?.let { m["issuers"] = clauseToMap(it) }
        filter.schemas?.let { m["schemas"] = clauseToMap(it) }
        m["requiredClaims"] = filter.requiredClaims
        return m
    }

    private fun clauseToMap(c: VerifierFilterClause): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["mode"] = c.mode
        if (c.list != null) m["list"] = c.list
        return m
    }

    private fun responseToMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["mode"] = response.mode
        if (response.callbackUrl != null) m["callbackUrl"] = response.callbackUrl
        return m
    }

    companion object {
        fun fromJson(o: JSONObject): VerifierRequest = VerifierRequest(
            v = o.getInt("v"),
            verifierDid = o.getString("verifierDid"),
            verifierName = o.optStringOrNull("verifierName"),
            verifierPublicKey = o.optStringOrNull("verifierPublicKey"),
            requestId = o.getString("requestId"),
            issuedAt = o.getLong("issuedAt"),
            expiresAt = o.getLong("expiresAt"),
            challenge = o.getString("challenge"),
            filter = VerifierFilter.fromJson(o.getJSONObject("filter")),
            response = VerifierResponseDirective.fromJson(o.getJSONObject("response")),
            signature = o.optStringOrNull("signature"),
        )

        /** Decode either a raw VerifierRequest or the { v, request } envelope
         *  returned by GET /v1/verifier-request/:id. */
        fun parse(jsonString: String): VerifierRequest? {
            return try {
                val o = JSONObject(jsonString)
                if (o.has("verifierDid") && o.has("requestId")) {
                    fromJson(o)
                } else if (o.has("request")) {
                    fromJson(o.getJSONObject("request"))
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Registry record returned by GET /v1/verifier-registry/:did. */
data class VerifierRegistryRecord(
    val verifierDid: String,
    val verifierName: String,
    val verifierPublicKey: String,
    val addedAt: Long,
) {
    companion object {
        fun fromJson(o: JSONObject): VerifierRegistryRecord = VerifierRegistryRecord(
            verifierDid = o.getString("verifierDid"),
            verifierName = o.getString("verifierName"),
            verifierPublicKey = o.getString("verifierPublicKey"),
            addedAt = o.optLong("addedAt", 0L),
        )
    }
}

// ---------------------------------------------------------------------------
// Presentation (the signed, disclosable object posted to the verifier).
// ---------------------------------------------------------------------------

data class Presentation(
    val v: Int,
    val header: CredentialHeader,
    val headerSig: String,
    val challenge: String,
    val challengeSig: String,
    val disclosed: List<DisclosedClaim>,
    val timestamp: Long,
    val holderLongTermPk: String,
    val anchor: AnchorDescriptor?,
    val verifierRequest: VerifierRequest?,
    val delegation: PresentationDelegation?,
    val hardwareAttestation: HardwareAttestation?,
    val selfIssuerAttestation: SelfIssuerAttestation?,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("v", v)
        o.put("header", header.toJson())
        o.put("headerSig", headerSig)
        o.put("challenge", challenge)
        o.put("challengeSig", challengeSig)
        o.put("disclosed", JSONArray().also { arr -> disclosed.forEach { arr.put(it.toJson()) } })
        o.put("timestamp", timestamp)
        o.put("holderLongTermPk", holderLongTermPk)
        if (anchor != null) o.put("anchor", anchor.toJson())
        if (verifierRequest != null) o.put("verifierRequest", verifierRequest.toJson())
        if (delegation != null) o.put("delegation", delegation.toJson())
        if (hardwareAttestation != null) o.put("hardwareAttestation", hardwareAttestation.toJson())
        if (selfIssuerAttestation != null) o.put("selfIssuerAttestation", selfIssuerAttestation.toJson())
        return o
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJson(o: JSONObject): Presentation = Presentation(
            v = o.getInt("v"),
            header = CredentialHeader.fromJson(o.getJSONObject("header")),
            headerSig = o.getString("headerSig"),
            challenge = o.getString("challenge"),
            challengeSig = o.getString("challengeSig"),
            disclosed = (o.optJSONArray("disclosed") ?: JSONArray()).let { a ->
                (0 until a.length()).map { DisclosedClaim.fromJson(a.getJSONObject(it)) }
            },
            timestamp = o.getLong("timestamp"),
            holderLongTermPk = o.getString("holderLongTermPk"),
            anchor = o.optJSONObject("anchor")?.let { AnchorDescriptor.fromJson(it) },
            verifierRequest = o.optJSONObject("verifierRequest")?.let { VerifierRequest.fromJson(it) },
            delegation = o.optJSONObject("delegation")?.let { PresentationDelegation.fromJson(it) },
            hardwareAttestation = o.optJSONObject("hardwareAttestation")?.let { HardwareAttestation.fromJson(it) },
            selfIssuerAttestation = o.optJSONObject("selfIssuerAttestation")?.let { SelfIssuerAttestation.fromJson(it) },
        )

        fun parse(jsonString: String): Presentation = fromJson(JSONObject(jsonString))
    }
}

/** One-shot drop envelope returned by POST /v1/drop and rendered as a QR.
 *  GET /v1/drop/{dropId} returns { presentation }. */
data class DropEnvelope(val v: Int, val dropId: String, val expiresAt: Long?) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("v", v).put("dropId", dropId)
        if (expiresAt != null) o.put("expiresAt", expiresAt)
        return o
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJson(o: JSONObject): DropEnvelope = DropEnvelope(
            v = o.optInt("v", 1),
            dropId = o.getString("dropId"),
            expiresAt = if (o.has("expiresAt") && !o.isNull("expiresAt")) o.getLong("expiresAt") else null,
        )

        fun parse(jsonString: String): DropEnvelope = fromJson(JSONObject(jsonString))
    }
}

// ---------------------------------------------------------------------------
// Parsed credential (decomposes a stored credentialJson into the pieces the
// builder + verifier need). Mirrors iOS Credential.
// ---------------------------------------------------------------------------

/** A credential parsed out of a stored CredentialEntity.credentialJson (the
 *  raw v2 issuer payload, same shape iOS stores). Carries the header, its
 *  signature, the claim set, the issuer Merkle descriptor, and an optional
 *  anchor. The builder recomputes Merkle proofs from `merkleTree.sortedKeys`. */
data class ParsedCredential(
    val header: CredentialHeader,
    val headerSig: String,
    val claims: Map<String, JsonValue>,
    val merkleTree: MerkleTreeDescriptor,
    val anchor: AnchorDescriptor?,
) {
    val cid: String get() = header.cid

    /** Sorted-key (key, value-as-canonicalize-input) entries for the Merkle
     *  tree, in the exact order the issuer used. A missing claim maps to null
     *  (canonicalize emits `null`), matching iOS `NSNull()`. */
    fun merkleEntries(): List<Pair<String, Any?>> =
        merkleTree.sortedKeys.map { key -> key to (claims[key]?.anyValue()) }

    companion object {
        /** Parse a raw stored credentialJson into its pieces. Tolerant of the
         *  unknown fields (schemaUri, issuanceMetadata) the issuer may add. */
        fun parse(credentialJson: String): ParsedCredential = fromJson(JSONObject(credentialJson))

        fun fromJson(o: JSONObject): ParsedCredential {
            val claimsObj = o.optJSONObject("claims") ?: JSONObject()
            val claims = claimsObj.keys().asSequence()
                .associateWith { JsonValue.fromJsonField(claimsObj.opt(it)) }
            return ParsedCredential(
                header = CredentialHeader.fromJson(o.getJSONObject("header")),
                headerSig = o.getString("headerSig"),
                claims = claims,
                merkleTree = MerkleTreeDescriptor.fromJson(o.getJSONObject("merkleTree")),
                anchor = o.optJSONObject("anchor")?.let { AnchorDescriptor.fromJson(it) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Offline check matrix + verdict (mirrors iOS LocalCheckResult / LocalVerdict).
// ---------------------------------------------------------------------------

/** Verdict bucket for a presentation checked offline. */
enum class LocalVerdict { GRANT, DENY, UNVERIFIED, SELF_ATTESTED }

/** Result of one local check. PASS / FAIL move the verdict; UNVERIFIED and
 *  NOT_APPLICABLE never drag it down. */
sealed class LocalCheckResult {
    object Pass : LocalCheckResult()
    data class Fail(val reason: String) : LocalCheckResult()
    data class Unverified(val reason: String) : LocalCheckResult()
    data class NotApplicable(val reason: String) : LocalCheckResult()

    val isPass: Boolean get() = this is Pass
    val isFail: Boolean get() = this is Fail
}

/** The full offline check matrix. `overallPass` is true iff no non-unverified
 *  check failed (issuerRegistered / revocation / rootCurrent never fail here). */
data class LocalCheckMatrix(
    val headerSigValid: LocalCheckResult,
    val merkleValid: LocalCheckResult,
    val challengeSigValid: LocalCheckResult,
    val timestampValid: LocalCheckResult,
    val expiryValid: LocalCheckResult,
    val verifierRequestValid: LocalCheckResult,
    val issuerRegistered: LocalCheckResult,
    val credentialNotRevoked: LocalCheckResult,
    val rootCurrent: LocalCheckResult,
) {
    val overallPass: Boolean
        get() = listOf(
            headerSigValid, merkleValid, challengeSigValid,
            timestampValid, expiryValid, verifierRequestValid,
        ).none { it.isFail }
}

/** The offline verdict for a presentation. */
data class LocalCheckResultBundle(
    val decision: LocalVerdict,
    val summary: String,
    val disclosed: Map<String, JsonValue>,
    val checks: LocalCheckMatrix,
)

// ---------------------------------------------------------------------------
// org.json helpers (local; the elabify-core hex/string helpers are internal).
// ---------------------------------------------------------------------------

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.optStringList(key: String): List<String>? {
    if (!has(key) || isNull(key)) return null
    val a = optJSONArray(key) ?: return null
    return (0 until a.length()).map { a.getString(it) }
}

/** Strip an optional 0x/0X prefix and decode hex. Returns null on malformed
 *  input (odd length / non-hex), matching iOS hexFrom0x semantics. */
internal fun hexFrom0xOrNull(s: String): ByteArray? {
    val stripped = if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2) else s
    if (stripped.length % 2 != 0) return null
    val out = ByteArray(stripped.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(stripped[i * 2], 16)
        val lo = Character.digit(stripped[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** Lowercase-hex with a 0x prefix (the wire convention for the present layer). */
internal fun ByteArray.to0xHex(): String = "0x" + this.toHex()
