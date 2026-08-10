// One ID document the user has tapped to the phone. Generic enough
// to cover any chip-bearing identity document the phone can read:
// passports, national ID cards, residence permits, future formats.
//
// What we store is the bearer-visible data: name, photo, document
// number, dates. Country code is stored as a raw ISO 3166-1 alpha-3
// string ("USA", "ARE", "GBR", ...) but the UI looks it up to the
// user-readable country name so the rest of the app never has to
// know any country specifically.
//
// We also retain the signed SOD bytes and the signing certificate so
// that, in a follow-up, a verifier can validate the read against the
// issuing authority's PKI without having to trust Maknoon. That is
// what makes this a real credential rather than a typed-in form.
//
// Ported 1:1 from iOS IDDocument.swift. Where the iOS struct kept raw
// chip blobs as on-disk filename references, this Kotlin model carries
// the bytes directly (DG1/DG2/DG11/DG12/DG15 + SOD as ByteArray, the
// portrait JPEG as ByteArray); IDDocumentStore is responsible for
// sealing them at rest. SwiftUI presentation helpers (SF Symbol names,
// localized country lookup) move to the Compose layer.

package com.elabify.app.maknoon.iddocument

import com.elabify.app.maknoon.R

import androidx.annotation.StringRes

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/**
 * The kind of ID the user selected on the type-picker before scanning.
 * Drives copy in the entry form (e.g. "Card number on back" vs
 * "Passport number") and the labels we show in the saved-document
 * detail view (e.g. "Emirates ID number" for the TD1 optional-data
 * field). `iconName` is a logical key the Compose layer maps to a
 * Material icon (the iOS SF Symbol name does not carry over).
 */
enum class IDDocumentKind(val rawValue: String) {
    PASSPORT("passport"),
    OTHER("other");

    val id: String get() = rawValue

    // Copy lives in string RESOURCES, not in this enum.
    //
    // These were `when (this) -> "Passport"` literals, so the document picker,
    // its blurbs and the document-number field label shipped English in all 31
    // locales. An enum has no Context and cannot call stringResource, which is
    // why the literals looked unavoidable; the fix is to carry the id and let
    // the composable resolve it. Same shape as MiniAppCapability's labelRes.
    @get:StringRes
    val displayNameRes: Int
        get() = when (this) {
            PASSPORT -> R.string.passport_title
            OTHER -> R.string.id_kind_other_title
        }

    /** Logical icon key, resolved to a Material icon by the UI layer. */
    val iconName: String
        get() = when (this) {
            PASSPORT -> "passport"
            OTHER -> "id_document"
        }

    @get:StringRes
    val blurbRes: Int
        get() = when (this) {
            PASSPORT -> R.string.id_kind_passport_blurb
            OTHER -> R.string.id_kind_other_blurb
        }

    /**
     * User-facing label for the document-number field in the entry
     * form. The chip authenticates on this number (plus DOB + expiry),
     * so it has to be the same number that is encoded into the MRZ on
     * the data page of the document.
     */
    @get:StringRes
    val documentNumberLabelRes: Int
        get() = when (this) {
            PASSPORT -> R.string.id_kind_passport_number
            OTHER -> R.string.id_document_number
        }

    @get:StringRes
    val personalNumberLabelRes: Int get() = R.string.id_personal_number

    companion object {
        fun fromRaw(raw: String?): IDDocumentKind? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Coarse OpenSanctions screening outcome, mirroring the issuer's
 * SanctionsOutcome. Lives here (rather than a sibling client file) so
 * the model compiles standalone; a later SanctionsScreeningClient port
 * reuses this enum.
 */
enum class SanctionsOutcome(val rawValue: String) {
    CLEAN("clean"),
    SANCTIONED("sanctioned"),
    PEP("pep"),
    INCONCLUSIVE("inconclusive"),
    ERROR("error");

    companion object {
        fun fromRaw(raw: String?): SanctionsOutcome? =
            entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One matched OpenSanctions entity, surfaced to the holder so a
 * non-clean result is not an opaque "you are flagged". The issuer's
 * holder-facing endpoint returns the outcome only (no match detail),
 * so `matches` is usually empty for holder-run screens; it is kept on
 * the model so a future richer surface can populate it.
 */
data class SanctionsMatchDetail(
    val name: String,
    val listName: String,
)

/**
 * Persisted screening result on an IDDocument. `screenedAt` is epoch
 * milliseconds (the iOS Date carried over as a Long for JSON
 * stability across platforms).
 */
data class SanctionsScreenResult(
    val outcome: SanctionsOutcome,
    /** When the screen was performed (epoch millis). */
    val screenedAt: Long,
    /** OpenSanctions dataset version the issuer screened against. */
    val datasetVersion: String,
    /** Optional match detail (empty for holder-run screens today). */
    val matches: List<SanctionsMatchDetail> = emptyList(),
) {
    /** Human label for the badge / detail row. */
    val label: String
        get() = when (outcome) {
            SanctionsOutcome.CLEAN -> "Clean"
            SanctionsOutcome.SANCTIONED -> "Sanctioned"
            SanctionsOutcome.PEP -> "PEP match"
            SanctionsOutcome.INCONCLUSIVE -> "Inconclusive"
            SanctionsOutcome.ERROR -> "Screening error"
        }
}

/**
 * Outcome of on-device ICAO 9303 Passive Authentication. `reason` uses
 * the same strings as the backend verifyPassiveAuthentication() so the
 * on-device result predicts the server's. Advisory only: the issuer
 * re-verifies authoritatively at issuance. Defined here so the model
 * compiles standalone; a later PassportPassiveAuthVerifier port reuses
 * this type.
 */
data class PassiveAuthResult(
    val status: Status,
    /** "ok" or a backend-vocab reason. */
    val reason: String,
    val cscaCountry: String?,
    /** When the check ran (epoch millis). */
    val checkedAt: Long,
    val bundleVersion: String?,
    /**
     * Diagnostic: which signing authority the chip's DSC points at (the
     * CSCA subject DN the trust list must contain), set when the chain
     * did not build. Lets us pinpoint exactly which CSCA is missing.
     * Null when verified.
     */
    val dscIssuer: String? = null,
    val dscFingerprint: String? = null,
) {
    enum class Status(val rawValue: String) {
        // DG hashes match + SOD signed + DSC chains to a trusted CSCA.
        VERIFIED("verified"),
        // Chip data intact + SOD self-consistent, but no matching CSCA (stale bundle?).
        INTEGRITY_ONLY("integrityOnly"),
        // Tamper / bad SOD signature.
        FAILED("failed"),
        // Could not run (no SOD, no bundle, NFC-less build).
        UNAVAILABLE("unavailable");

        companion object {
            fun fromRaw(raw: String?): Status? =
                entries.firstOrNull { it.rawValue == raw }
        }
    }
}

/**
 * A parsed, persisted ID document. Plain Kotlin data class: bearer
 * biographical fields parsed from the MRZ (DG1) and DG11, the raw chip
 * blobs needed for later verification, and advisory screening / passive
 * auth results.
 *
 * `surname` / `givenNames` are what the reader reports as the primary
 * name: for most passports that is the Latin/ASCII MRZ form, but for
 * documents that expose DG11.fullName (Chinese e-passports, some Korean
 * and Japanese passports, Arabic-script issuers) the reader overrides
 * those fields with the native-script name. The pinyin/transliterated
 * form is preserved separately in `latinSurname` / `latinGivenNames`,
 * parsed from the MRZ.
 *
 * The raw blobs (`sod`, `dg1`, `dg2`, `dg11`, `dg12`, `dg15`,
 * `portraitJpeg`) are held as ByteArray on the in-memory model. They
 * are excluded from equals/hashCode (ByteArray identity is reference
 * equality, which would break list diffing); identity is the UUID.
 */
data class IDDocument(
    val id: UUID = UUID.randomUUID(),
    val nickname: String? = null,

    // ---- bearer biographical data ----
    val surname: String,
    val givenNames: String,
    val documentNumber: String,
    val nationality: String,        // ISO 3166-1 alpha-3
    val issuingAuthority: String,   // ISO 3166-1 alpha-3
    val sex: String? = null,        // "M" / "F" / "X", stored as-read
    val dateOfBirth: String,        // YYMMDD as read, or empty if unknown
    val dateOfExpiry: String,       // YYMMDD as read
    val documentType: String,       // e.g. "P", "ID", "IP", as read

    /**
     * Latin surname parsed straight from the MRZ (DG1 tag 0x5B, before
     * any DG11 override). For a Chinese passport reading "ZHANG<<SAN"
     * this is "ZHANG"; for a US passport it is identical to `surname`.
     * Always populated when we have an MRZ.
     */
    val latinSurname: String? = null,

    /**
     * Latin given names parsed from the MRZ. Pinyin for CHN passports,
     * Hepburn romaji for JPN, etc.
     */
    val latinGivenNames: String? = null,

    /**
     * Native-script full name from DG11 tag 0x5F0E ("name of holder in
     * national characters"). For a CHN passport this is the Chinese
     * characters; null if the chip did not expose DG11 or DG11 did not
     * include a fullName tag.
     */
    val nativeFullName: String? = null,

    /**
     * What the user declared the document to be on the type picker
     * before scanning. Decoupled from `documentType` (what the chip
     * reported) because the chip emits generic codes like "I" for any
     * TD1 card; the user's declaration tells us whether to render it as
     * "Emirates ID" vs "EU residence permit" vs "Other ID".
     */
    val userDeclaredKind: IDDocumentKind? = null,

    /**
     * MRZ optional data / DG11 personal number, if the chip exposed
     * one. For Emirates ID this is the 15-digit Emirates ID number.
     */
    val personalNumber: String? = null,

    /**
     * Place of birth from DG11 tag 0x5F11. Free-form string the issuer
     * chose at personalisation time (often a city, sometimes
     * "City, Country"). Many passports leave DG11 absent entirely.
     */
    val placeOfBirth: String? = null,

    // ---- raw chip material for later verification ----
    // The SOD is a CMS SignedData blob signed by the Document Signing
    // Certificate (DSC); the DSC sits inside the SOD's certificates
    // field and is itself signed by the issuing country's Country
    // Signing CA (CSCA). A verifier needs: the SOD bytes (we store
    // them), the DSC (extracted from the SOD), the CSCA (obtained
    // out-of-band from the ICAO PKD), and the raw bytes of each data
    // group whose hash is listed in the SOD so the verifier can re-hash
    // them and confirm the SOD hash table matches. Active
    // Authentication proves the chip itself is not a clone: we send a
    // challenge, the chip signs it with a DG15-protected private key, we
    // keep both the challenge and the signature so a verifier can replay
    // the check against DG15's public key.

    /** Raw SOD bytes (Security Object of the Document, ICAO 9303). */
    val sod: ByteArray? = null,

    /** Raw DG1 bytes (MRZ). Hashed by the SOD. */
    val dg1: ByteArray? = null,

    /** Raw DG2 bytes (facial image). Hashed by the SOD. */
    val dg2: ByteArray? = null,

    /** Raw DG11 bytes (additional personal details). */
    val dg11: ByteArray? = null,

    /** Raw DG12 bytes (additional document details). */
    val dg12: ByteArray? = null,

    /** Raw DG15 bytes (Active Authentication public key). */
    val dg15: ByteArray? = null,

    /** Bearer portrait JPEG decoded from DG2, if the chip exposed one. */
    val portraitJpeg: ByteArray? = null,

    /** Active Authentication challenge we sent to the chip, if attempted. Hex. */
    val activeAuthChallengeHex: String? = null,

    /** Active Authentication signature the chip returned over the challenge. Hex. */
    val activeAuthSignatureHex: String? = null,

    /** Whether the reader's local AA check passed at read time. */
    val activeAuthVerifiedLocally: Boolean? = null,

    /** When this document was read (epoch millis). */
    val readAt: Long = System.currentTimeMillis(),

    /**
     * OpenSanctions screening result, set when the user runs the opt-in
     * "Check sanctions" action in the detail view. Null means never
     * screened.
     */
    val sanctionsResult: SanctionsScreenResult? = null,

    /**
     * On-device ICAO 9303 Passive Authentication result, set when the
     * detail view runs the verifier against the cached CSCA bundle. Null
     * means not yet run. Advisory only.
     */
    val passiveAuthResult: PassiveAuthResult? = null,

    /**
     * ID-document record schema version (ADR-0037), shown small in the detail
     * header so different schema versions are trackable. Bumped when the stored
     * field set changes; default for documents read/migrated under the current
     * schema.
     */
    val schemaVersion: String = "1.0.0",
) {
    /**
     * User-visible best name. Prefers the Latin MRZ form so foreign
     * systems, verifiers, and travel infrastructure all see the same
     * string the user does. Falls back to the reader-reported name
     * (the native-script form for CHN/JPN/KOR/etc. when DG11 was
     * present).
     */
    val displayName: String
        get() {
            val latinParts = listOfNotNull(latinGivenNames, latinSurname)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (latinParts.isNotEmpty()) return latinParts.joinToString(" ")
            return listOf(givenNames, surname)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
        }

    /**
     * Native-script name when distinct from the Latin name (e.g.
     * Chinese characters on a CHN passport). Returns null when the chip
     * did not expose a separate native form, or when the two are
     * identical so the UI does not render the same string twice. Strips
     * MRZ-style `<` filler from the reader's surname/givenNames pair
     * before comparison and display.
     */
    val nativeDisplayName: String?
        get() {
            val nativeParts = listOf(givenNames, surname)
                .map { cleanMRZText(it) }
                .filter { it.isNotEmpty() }
            if (nativeParts.isEmpty()) return null
            val native = nativeParts.joinToString(" ")
            val latin = listOfNotNull(latinGivenNames, latinSurname)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            return if (latin.equals(native, ignoreCase = true)) null else native
        }

    /** One-line summary for the card view: the issuing country name. */
    val summary: String
        get() = countryName(issuingAuthority) ?: issuingAuthority

    /**
     * User-facing label for the document kind. Prefers the user-declared
     * kind (set on the type picker) because the chip's documentType is
     * generic ("I" matches any TD1 card). Falls back to a chip-derived
     * guess for legacy saved docs that pre-date the type picker.
     */
    @get:StringRes
    val kindLabelRes: Int
        get() {
            // The chip is authoritative for passports (ADR-0037): an MRZ document
            // type starting with "P" is always a Passport, regardless of the
            // pre-scan picker. The user-declared kind only disambiguates the
            // generic TD1 "I" cards (Emirates ID vs EU residence permit vs Other).
            if (documentType.take(1).uppercase(Locale.ROOT) == "P") return R.string.passport_title
            userDeclaredKind?.let { return it.displayNameRes }
            return when (documentType.take(1).uppercase(Locale.ROOT)) {
                "P" -> R.string.passport_title
                "I" -> R.string.id_kind_id_card
                "A" -> R.string.id_kind_residence_permit
                "V" -> R.string.id_kind_visa
                else -> R.string.id_kind_document
            }
        }

    /**
     * Identity key for de-duplication (ADR-0037): a document is "the same" when
     * its type + issuer + number + date-of-birth + expiry all match (normalized).
     * The store rejects a second import with this key.
     */
    val dedupeKey: String
        get() = listOf(documentType, issuingAuthority, documentNumber, dateOfBirth, dateOfExpiry)
            .joinToString("|") { it.trim().uppercase(Locale.ROOT) }

    /**
     * Logical icon key for the saved-document card, resolved to a
     * Material icon by the UI layer. Prefers the user-declared kind,
     * falls back to a chip-derived guess.
     */
    val iconName: String
        get() {
            userDeclaredKind?.let { return it.iconName }
            return when (documentType.take(1).uppercase(Locale.ROOT)) {
                "P" -> "passport"
                "I" -> "id_card"
                "A" -> "residence_permit"
                "V" -> "visa"
                else -> "id_document"
            }
        }

    /**
     * Place of birth normalized for display. Many issuers pack DG11
     * fields the same way as the MRZ (space to `<`, double-`<` as a
     * logical separator), so a US passport reports `PENNSYLVANIA<USA`
     * rather than `PENNSYLVANIA, USA`. We undo the MRZ-style filler here
     * without touching the raw string (which the issuer needs for
     * verification).
     */
    val formattedPlaceOfBirth: String?
        get() = placeOfBirth?.let { cleanMRZText(it) }

    /**
     * Apply the same cleanup to the native-script full name. CHN
     * passports usually do not put `<` filler into 5F0E (it is UTF-8
     * Chinese characters), but some issuers reuse MRZ packing in the
     * native field too. Cheap to apply unconditionally.
     */
    val displayNativeFullName: String?
        get() = nativeFullName?.let { cleanMRZText(it) }

    val formattedDateOfBirth: String?
        get() = formatYYMMDD(dateOfBirth, DateKind.BIRTH)

    val formattedDateOfExpiry: String?
        get() = formatYYMMDD(dateOfExpiry, DateKind.EXPIRY)

    // ByteArray fields break the data-class default equals/hashCode (reference
    // equality, which churns list diffs on every reload), so we do NOT compare
    // the raw chip blobs. We DO compare the id plus the mutable status fields
    // that change in place (passive-auth, sanctions, nickname): a StateFlow
    // dedups by equals, so excluding these made an in-place update (e.g. setting
    // the passive-auth result) look unchanged and never reach the UI until a
    // process restart reloaded from disk.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is IDDocument &&
                other.id == id &&
                other.passiveAuthResult == passiveAuthResult &&
                other.sanctionsResult == sanctionsResult &&
                other.nickname == nickname
            )

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + (passiveAuthResult?.hashCode() ?: 0)
        h = 31 * h + (sanctionsResult?.hashCode() ?: 0)
        h = 31 * h + (nickname?.hashCode() ?: 0)
        return h
    }

    companion object {
        /** Which field the YY two-digit year belongs to. */
        enum class DateKind { BIRTH, EXPIRY }

        /**
         * Tidy ICAO 9303 MRZ-style filler used by some issuers inside
         * DG11 free-text fields. DG11 is UTF-8 (Doc 9303 Part 10), so
         * `<` here is never a real space substitute: issuers had regular
         * spaces available and explicitly chose `<` to separate
         * components. We treat any run of `<` as a comma separator:
         *   - PENNSYLVANIA<USA      to  PENNSYLVANIA, USA
         *   - BEIJING<<CHINA        to  BEIJING, CHINA
         *   - RIYADH<SAUDI ARABIA   to  RIYADH, SAUDI ARABIA
         * Collapses repeated whitespace and trims edges + separators.
         */
        fun cleanMRZText(s: String): String {
            val parts = s.split("<")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            var out = parts.joinToString(", ")
            while (out.contains("  ")) {
                out = out.replace("  ", " ")
            }
            return out.trim().trim(',', ' ', '\n', '\t')
        }

        /**
         * Look up an ISO 3166-1 alpha-3 code via the JVM Locale
         * registry. Returns null for codes the platform does not know
         * (rare; this is the same registry passport authorities pull
         * from).
         */
        fun countryName(alpha3: String): String? {
            val alpha2 = ISO3166.alpha2(alpha3) ?: return null
            val name = Locale("", alpha2).getDisplayCountry(Locale.getDefault())
            // getDisplayCountry echoes the input code back when it does
            // not recognise the region; treat that as "unknown".
            return if (name.isBlank() || name.equals(alpha2, ignoreCase = true)) null else name
        }

        /**
         * "YYMMDD" to a medium-style localized date. Returns null if the
         * input is not six digits. The chip never emits century, so we
         * apply a context-dependent window per [DateKind]:
         *   - BIRTH: sliding window anchored at the current year. YY <=
         *     current-year-2-digit reads as 2000s, otherwise 1900s. So a
         *     YY of 25 today reads as 2025 (a 1-year-old, valid) and 26
         *     reads as 1926 (a 99-year-old, still plausible).
         *   - EXPIRY: always 2000s. Passports valid today expire between
         *     now and 2099. Picking the 1900s window for expiry is what
         *     produced the "1933" bug.
         */
        fun formatYYMMDD(s: String, kind: DateKind = DateKind.BIRTH): String? {
            if (s.length != 6 || !s.all { it.isDigit() }) return null
            val yy = s.substring(0, 2).toIntOrNull() ?: return null
            val mm = s.substring(2, 4).toIntOrNull() ?: return null
            val dd = s.substring(4, 6).toIntOrNull() ?: return null
            val century = when (kind) {
                DateKind.BIRTH -> {
                    val currentYY = LocalDate.now().year % 100
                    if (yy <= currentYY) 2000 else 1900
                }
                DateKind.EXPIRY -> 2000
            }
            val date = runCatching { LocalDate.of(century + yy, mm, dd) }.getOrNull()
                ?: return null
            return date.format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault()),
            )
        }
    }
}

/**
 * Read-time parameters for the MRZ-derived access key (BAC / PACE). The
 * chip authenticates on the document number plus date of birth plus
 * date of expiry; these three (with the document type / kind the user
 * declared) are everything the NFC reader needs to derive the BAC key
 * and open the secure channel.
 *
 * Equivalent to the inputs the iOS NFCPassportReader takes via
 * `setMRZKey`. Dates are YYMMDD strings exactly as printed in the MRZ.
 */
data class IDDocumentReadParameters(
    val documentNumber: String,
    val dateOfBirth: String,   // YYMMDD
    val dateOfExpiry: String,  // YYMMDD
    /** What the user declared on the type picker, drives entry-form copy. */
    val declaredKind: IDDocumentKind = IDDocumentKind.PASSPORT,
)

/**
 * In-flight result of an NFC read, before it is persisted into the
 * store. Carries the parsed [IDDocument] together with the raw chip
 * blobs keyed by group name ("sod", "dg1", "dg2", "dg11", "dg12",
 * "dg15") and the decoded portrait, mirroring the iOS reader handing a
 * document plus a `[String: Data]` rawChipData map and a UIImage to
 * IDDocumentStore.add(). The store seals these at rest.
 */
data class IDDocumentReadResult(
    val document: IDDocument,
    /** Raw chip blobs keyed by group: "sod", "dg1", "dg2", "dg11", "dg12", "dg15". */
    val rawChipData: Map<String, ByteArray> = emptyMap(),
    /** Decoded portrait JPEG bytes from DG2, if the chip exposed one. */
    val portraitJpeg: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is IDDocumentReadResult && other.document.id == document.id)

    override fun hashCode(): Int = document.id.hashCode()
}

/**
 * Minimal ISO 3166-1 alpha-3 to alpha-2 mapping so we can hand the
 * alpha-2 to the JVM Locale for the localized country name. Curated
 * subset; returns null for unknown codes so the UI falls back to the
 * alpha-3 string and never crashes on an exotic passport. Ported 1:1
 * from the iOS ISO3166 enum.
 */
object ISO3166 {
    fun alpha2(alpha3: String): String? = when (alpha3.uppercase(Locale.ROOT)) {
        "AFG" -> "AF"; "ALB" -> "AL"; "DZA" -> "DZ"
        "AND" -> "AD"; "AGO" -> "AO"; "ARG" -> "AR"
        "ARM" -> "AM"; "AUS" -> "AU"; "AUT" -> "AT"
        "AZE" -> "AZ"; "BHR" -> "BH"; "BGD" -> "BD"
        "BLR" -> "BY"; "BEL" -> "BE"; "BLZ" -> "BZ"
        "BEN" -> "BJ"; "BTN" -> "BT"; "BOL" -> "BO"
        "BIH" -> "BA"; "BWA" -> "BW"; "BRA" -> "BR"
        "BRN" -> "BN"; "BGR" -> "BG"; "BFA" -> "BF"
        "BDI" -> "BI"; "KHM" -> "KH"; "CMR" -> "CM"
        "CAN" -> "CA"; "CPV" -> "CV"; "TCD" -> "TD"
        "CHL" -> "CL"; "CHN" -> "CN"; "COL" -> "CO"
        "COM" -> "KM"; "COG" -> "CG"; "COD" -> "CD"
        "CRI" -> "CR"; "CIV" -> "CI"; "HRV" -> "HR"
        "CUB" -> "CU"; "CYP" -> "CY"; "CZE" -> "CZ"
        "DNK" -> "DK"; "DJI" -> "DJ"; "DOM" -> "DO"
        "ECU" -> "EC"; "EGY" -> "EG"; "SLV" -> "SV"
        "GNQ" -> "GQ"; "ERI" -> "ER"; "EST" -> "EE"
        "SWZ" -> "SZ"; "ETH" -> "ET"; "FJI" -> "FJ"
        "FIN" -> "FI"; "FRA" -> "FR"; "GAB" -> "GA"
        "GMB" -> "GM"; "GEO" -> "GE"; "DEU" -> "DE"
        "D" -> "DE"  // some passports emit "D<<<"
        "GHA" -> "GH"; "GRC" -> "GR"; "GTM" -> "GT"
        "GIN" -> "GN"; "GNB" -> "GW"; "GUY" -> "GY"
        "HTI" -> "HT"; "HND" -> "HN"; "HUN" -> "HU"
        "ISL" -> "IS"; "IND" -> "IN"; "IDN" -> "ID"
        "IRN" -> "IR"; "IRQ" -> "IQ"; "IRL" -> "IE"
        "ISR" -> "IL"; "ITA" -> "IT"; "JAM" -> "JM"
        "JPN" -> "JP"; "JOR" -> "JO"; "KAZ" -> "KZ"
        "KEN" -> "KE"; "KOR" -> "KR"; "PRK" -> "KP"
        "KWT" -> "KW"; "KGZ" -> "KG"; "LAO" -> "LA"
        "LVA" -> "LV"; "LBN" -> "LB"; "LSO" -> "LS"
        "LBR" -> "LR"; "LBY" -> "LY"; "LIE" -> "LI"
        "LTU" -> "LT"; "LUX" -> "LU"; "MDG" -> "MG"
        "MWI" -> "MW"; "MYS" -> "MY"; "MDV" -> "MV"
        "MLI" -> "ML"; "MLT" -> "MT"; "MRT" -> "MR"
        "MUS" -> "MU"; "MEX" -> "MX"; "MDA" -> "MD"
        "MCO" -> "MC"; "MNG" -> "MN"; "MNE" -> "ME"
        "MAR" -> "MA"; "MOZ" -> "MZ"; "MMR" -> "MM"
        "NAM" -> "NA"; "NPL" -> "NP"; "NLD" -> "NL"
        "NZL" -> "NZ"; "NIC" -> "NI"; "NER" -> "NE"
        "NGA" -> "NG"; "MKD" -> "MK"; "NOR" -> "NO"
        "OMN" -> "OM"; "PAK" -> "PK"; "PSE" -> "PS"
        "PAN" -> "PA"; "PNG" -> "PG"; "PRY" -> "PY"
        "PER" -> "PE"; "PHL" -> "PH"; "POL" -> "PL"
        "PRT" -> "PT"; "QAT" -> "QA"; "ROU" -> "RO"
        "RUS" -> "RU"; "RWA" -> "RW"; "SAU" -> "SA"
        "SEN" -> "SN"; "SRB" -> "RS"; "SLE" -> "SL"
        "SGP" -> "SG"; "SVK" -> "SK"; "SVN" -> "SI"
        "SOM" -> "SO"; "ZAF" -> "ZA"; "ESP" -> "ES"
        "LKA" -> "LK"; "SDN" -> "SD"; "SUR" -> "SR"
        "SWE" -> "SE"; "CHE" -> "CH"; "SYR" -> "SY"
        "TWN" -> "TW"; "TJK" -> "TJ"; "TZA" -> "TZ"
        "THA" -> "TH"; "TLS" -> "TL"; "TGO" -> "TG"
        "TON" -> "TO"; "TTO" -> "TT"; "TUN" -> "TN"
        "TUR" -> "TR"; "TKM" -> "TM"; "UGA" -> "UG"
        "UKR" -> "UA"; "ARE" -> "AE"; "GBR" -> "GB"
        "USA" -> "US"; "URY" -> "UY"; "UZB" -> "UZ"
        "VUT" -> "VU"; "VEN" -> "VE"; "VNM" -> "VN"
        "YEM" -> "YE"; "ZMB" -> "ZM"; "ZWE" -> "ZW"
        else -> null
    }
}
