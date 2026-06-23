// Mint a SELF-SIGNED credential locally from a scanned passport, with no
// issuer and no on-chain anchor. Kotlin port of the iOS LocalCredentialFactory.
//
// The holder's ML-DSA-65 master key is the issuer: header.iss == header.sub ==
// holderDid, and headerSig is the holder's signature over the canonical header.
// Another Maknoon client can verify it fully offline (Merkle inclusion + the
// self-signature): PresentationVerifier detects header.iss == holderDID(
// holderLongTermPk) and checks headerSig against the holder's master public key.
//
// The credential carries the SAME schema + Merkle shape an Elabify-issued
// passport credential carries, so the existing Privacy QR / Attribute QR /
// presentation pipeline (CredentialPresentScreen + PresentationBuilder) renders
// and signs it unchanged.
//
// Claim normalization mirrors the iOS LocalCredentialFactory.passportClaims and
// the issuer-backend mapPassportToClaims so a self-signed passport and an
// issuer-signed one carry the same keys + value formats:
//   - dates: MRZ YYMMDD -> ISO 8601 YYYY-MM-DD
//   - countries: ISO 3166-1 alpha-3 (MRZ) -> alpha-2 (schema)
//   - names / placeOfBirth: MRZ "<" filler collapsed to single spaces
//   - passportNumber: uppercased, non-alphanumerics stripped
//   - issueDate: estimated as expiry - 10y (passports rarely expose it)
//   - over18 / over21 / notExpired: JSON booleans computed at mint time
//
// GMS-free: all crypto routes through com.elabify.core (Merkle, canonicalize,
// deriveCid) and the SDK MasterKey/IdentitySandwich. No em-dashes.

package com.elabify.app.maknoon.iddocument

import com.elabify.core.MerkleTree
import com.elabify.core.deriveCid
import com.elabify.core.sortClaimKeys
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.present.CredentialHeader
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.MerkleTreeDescriptor
import com.elabify.musnad.present.ParsedCredential
import java.util.Calendar
import java.util.TimeZone

/** Schema shared with the Elabify passport issuer so self-signed and
 *  issuer-signed passport credentials are structurally identical. */
const val PASSPORT_SCHEMA_URI = "elabify://schema/global/passport/v1"

/** Raised when a passport has no MRZ-derived fields to put in a credential. */
class LocalCredentialError(message: String) : Exception(message)

object LocalCredentialFactory {

    /** ML-DSA-65 alg + RPO-256 hash labels carried by every Elabify header. */
    private const val ALG = "ML-DSA-65"
    private const val HASH = "RPO-256"

    /**
     * Whether a document can be minted into a self-signed credential, used to
     * drive the Present button without gating on the lock state. True when the
     * normalized claim set is non-empty (it always is for a passport with a
     * name + number, which every scanned passport has).
     */
    fun isPresentable(doc: IDDocument): Boolean = passportClaims(doc).isNotEmpty()

    /**
     * Mint a self-signed passport credential from a scanned [doc], signing the
     * canonical header with the holder's ML-DSA master key. The UI gates this
     * behind a biometric confirm before calling; the master sign itself needs
     * the loaded sandwich's entropy (no hardware second factor for the present
     * flow). Returns a ParsedCredential the present screen can render + sign.
     */
    fun mint(doc: IDDocument, sandwich: IdentitySandwich): ParsedCredential {
        val claims = passportClaims(doc)
        if (claims.isEmpty()) {
            throw LocalCredentialError("This document has no fields to put in a credential.")
        }
        return buildCredential(
            claims = claims,
            holderDid = sandwich.holderDid,
            schema = PASSPORT_SCHEMA_URI,
            iat = System.currentTimeMillis() / 1000L,
            exp = null,
            signHeader = { sandwich.signWithMaster(it) },
        )
    }

    /**
     * Build a self-signed credential from an ordered claim set. Pure: the
     * header signature is produced by [signHeader] (the holder's ML-DSA master
     * key), so this is independently testable without a real IdentitySandwich.
     * Mirrors iOS LocalCredentialFactory.buildCredential.
     */
    fun buildCredential(
        claims: List<Pair<String, Any?>>,
        holderDid: String,
        schema: String,
        iat: Long,
        exp: Long?,
        signHeader: (ByteArray) -> ByteArray,
    ): ParsedCredential {
        require(claims.isNotEmpty()) { "buildCredential: claims must be non-empty" }

        // Deterministic claim order: sort keys exactly as the spec does.
        val claimMap = LinkedHashMap<String, Any?>()
        for ((k, v) in claims) claimMap[k] = v
        val sortedKeys = sortClaimKeys(claimMap)

        // Merkle tree over the sorted claims; root + per-leaf hashes (0x-hex).
        val entries: List<Pair<String, Any?>> = sortedKeys.map { it to claimMap[it] }
        val tree = MerkleTree(entries)
        val rootHex = "0x" + tree.rootHex
        val leafHashes = sortedKeys.map { key ->
            "0x" + com.elabify.core.claimLeafHash(key, claimMap[key]).toHex()
        }
        val merkle = MerkleTreeDescriptor(
            sortedKeys = sortedKeys,
            leafHashes = leafHashes,
            root = rootHex,
            depth = tree.depth,
        )

        // Header with an empty cid first, so deriveCid hashes the exact field
        // set the final (signed) header carries (minus the cid value). The
        // canonical map omits the nil optionals (exp / allowedNetworks), exactly
        // like the iOS JSONEncoder output.
        val header0 = CredentialHeader(
            v = 1, alg = ALG, hash = HASH,
            iss = holderDid, sub = holderDid, iat = iat, exp = exp,
            cid = "", root = rootHex, schema = schema, allowedNetworks = null,
        )
        val cidBytes = deriveCid(header0.toCanonicalMap(), iat)
        val cid = "0x" + cidBytes.toHex()

        val header = CredentialHeader(
            v = 1, alg = ALG, hash = HASH,
            iss = holderDid, sub = holderDid, iat = iat, exp = exp,
            cid = cid, root = rootHex, schema = schema, allowedNetworks = null,
        )
        val sig = signHeader(header.canonicalBytes())
        val headerSig = "0x" + sig.toHex()

        val claimsJson: Map<String, JsonValue> = claimMap.mapValues { anyToJsonValue(it.value) }
        // Self-issued credentials carry no on-chain anchor (anchor == null); the
        // verifier's chain checks land as UNVERIFIED and the verdict is
        // SELF_ATTESTED. Same framing as iOS (no issuer, no network).
        return ParsedCredential(
            header = header,
            headerSig = headerSig,
            claims = claimsJson,
            merkleTree = merkle,
            anchor = null,
        )
    }

    /**
     * Passport fields -> credential claims, NORMALIZED to match an
     * Elabify-issued passport credential. Un-normalizable fields are omitted
     * rather than blocking the mint. Mirrors iOS LocalCredentialFactory.
     */
    fun passportClaims(doc: IDDocument): List<Pair<String, Any?>> {
        val out = ArrayList<Pair<String, Any?>>()
        fun addStr(key: String, value: String?) {
            if (!value.isNullOrEmpty()) out.add(key to value)
        }

        addStr("givenName", cleanMrzText(doc.latinGivenNames ?: doc.givenNames))
        addStr("familyName", cleanMrzText(doc.latinSurname ?: doc.surname))
        val passportNumber = doc.documentNumber.uppercase().filter { it.isLetterOrDigit() }
        addStr("passportNumber", passportNumber)
        addStr("issuingCountry", alpha3ToAlpha2(doc.issuingAuthority))
        addStr("nationality", alpha3ToAlpha2(doc.nationality))
        val dateOfBirth = yymmddToIso(doc.dateOfBirth, DateKind.BIRTH)
        addStr("dateOfBirth", dateOfBirth)
        val expiryDate = yymmddToIso(doc.dateOfExpiry, DateKind.EXPIRY)
        addStr("expiryDate", expiryDate)
        if (expiryDate != null) addStr("issueDate", estimateIssueDate(expiryDate))
        addStr("sex", normalizeSex(doc.sex))
        addStr("placeOfBirth", cleanMrzText(doc.placeOfBirth))

        // Issuer-derived predicate claims, computed against mint time (UTC),
        // matching the issuer's mapPassportToClaims. Emitted as JSON booleans so
        // a self-signed and an issuer-signed passport carry the same value types.
        val nowMillis = System.currentTimeMillis()
        if (dateOfBirth != null) {
            val age = ageYears(dateOfBirth, nowMillis)
            if (age != null) {
                out.add("over18" to (age >= 18))
                out.add("over21" to (age >= 21))
            }
        }
        if (expiryDate != null) {
            val expMillis = parseIsoDateUtcMillis(expiryDate)
            if (expMillis != null) out.add("notExpired" to (expMillis >= nowMillis))
        }
        return out
    }

    // MARK: -- passport normalization (mirrors issuer-backend passport-attestation.ts)

    private enum class DateKind { BIRTH, EXPIRY }

    /** Collapse MRZ "<" filler and whitespace runs to single spaces; trim.
     *  Matches the issuer's replace(/[<\s]+/g, ' ').trim(). */
    private fun cleanMrzText(s: String?): String? {
        if (s == null) return null
        val joined = s.replace("<", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return joined.ifEmpty { null }
    }

    /** MRZ YYMMDD -> ISO YYYY-MM-DD. Birth uses a sliding window (yy <= current
     *  2-digit year -> 2000s, else 1900s); expiry is always 2000s. Returns null
     *  on malformed input (caller omits the claim). */
    private fun yymmddToIso(yymmdd: String, kind: DateKind): String? {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return null
        val yy = yymmdd.substring(0, 2).toInt()
        val mm = yymmdd.substring(2, 4)
        val dd = yymmdd.substring(4, 6)
        val century = when (kind) {
            DateKind.BIRTH -> {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                val currentYy = cal.get(Calendar.YEAR) % 100
                if (yy <= currentYy) 2000 else 1900
            }
            DateKind.EXPIRY -> 2000
        }
        return "%04d-%s-%s".format(century + yy, mm, dd)
    }

    /** expiry - 10y, keeping month/day. Modal passport validity is 10y. */
    private fun estimateIssueDate(iso: String): String? {
        val parts = iso.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        return "${year - 10}-${parts[1]}-${parts[2]}"
    }

    private fun normalizeSex(sex: String?): String? {
        if (sex.isNullOrEmpty()) return null
        val up = sex.uppercase()
        return if (up in setOf("M", "F", "X")) up else "X"
    }

    /** ISO 3166-1 alpha-3 -> alpha-2. Mirrors the issuer's map. Returns null for
     *  an unmapped code (caller omits the claim rather than failing the mint). */
    private fun alpha3ToAlpha2(alpha3: String): String? {
        val map = mapOf(
            "ARE" to "AE", "AUS" to "AU", "AUT" to "AT", "BEL" to "BE", "BGR" to "BG", "BRA" to "BR", "CAN" to "CA",
            "CHE" to "CH", "CHN" to "CN", "CZE" to "CZ", "DEU" to "DE", "DNK" to "DK", "ESP" to "ES", "EST" to "EE",
            "FIN" to "FI", "FRA" to "FR", "GBR" to "GB", "GRC" to "GR", "HUN" to "HU", "IND" to "IN", "IRL" to "IE",
            "ISL" to "IS", "ITA" to "IT", "JPN" to "JP", "KOR" to "KR", "LTU" to "LT", "LUX" to "LU", "LVA" to "LV",
            "MEX" to "MX", "NLD" to "NL", "NOR" to "NO", "NZL" to "NZ", "POL" to "PL", "PRT" to "PT", "ROU" to "RO",
            "SAU" to "SA", "SGP" to "SG", "SVK" to "SK", "SVN" to "SI", "SWE" to "SE", "TUR" to "TR", "UKR" to "UA",
            "USA" to "US", "ZAF" to "ZA",
        )
        return map[alpha3.uppercase()]
    }

    /** Parse an ISO YYYY-MM-DD to a UTC-midnight epoch-millis (matches the
     *  issuer's new Date("YYYY-MM-DD"), which is UTC). Null on malformed input. */
    private fun parseIsoDateUtcMillis(iso: String): Long? {
        val parts = iso.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(y, m - 1, d, 0, 0, 0)
        return cal.timeInMillis
    }

    /** Completed years between an ISO birth date and a reference instant (UTC). */
    private fun ageYears(dobIso: String, refMillis: Long): Int? {
        val dobMillis = parseIsoDateUtcMillis(dobIso) ?: return null
        val dob = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dobMillis }
        val ref = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = refMillis }
        var years = ref.get(Calendar.YEAR) - dob.get(Calendar.YEAR)
        // Subtract a year if the birthday has not occurred yet in the ref year.
        if (ref.get(Calendar.DAY_OF_YEAR) < dob.get(Calendar.DAY_OF_YEAR)) years -= 1
        return years
    }

    /** Map a claim value to its JsonValue wrapper. Bool is matched before the
     *  numeric branches so a boolean never serializes as a number. */
    private fun anyToJsonValue(v: Any?): JsonValue = when (v) {
        null -> JsonValue.Null
        is Boolean -> JsonValue.Bool(v)
        is String -> JsonValue.Str(v)
        is Int -> JsonValue.IntVal(v.toLong())
        is Long -> JsonValue.IntVal(v)
        is Double -> JsonValue.DoubleVal(v)
        else -> JsonValue.Str(v.toString())
    }
}
