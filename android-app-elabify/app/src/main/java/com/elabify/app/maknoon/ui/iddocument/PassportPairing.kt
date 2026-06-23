// Pairs a scanned IDDocument with the issuer-issued credential minted from the
// same physical passport, matched on the normalized identity tuple
// {passportNumber, dateOfBirth(ISO), expiryDate(ISO)}. That tuple comes from the
// SAME normalization both sides already use (LocalCredentialFactory.passportClaims
// for the document, the issuer's mapPassportToClaims for the credential), so a
// document and its credential agree byte for byte.
//
// Display-only mirror of iOS PassportPairing: no new model fields, no wire
// change. Used to (a) fold the duplicate passport credential out of the Identity
// tab and (b) drive the pinned-network strip on the merged passport card from
// the credential's anchor.anchors.

package com.elabify.app.maknoon.ui.iddocument

import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.LocalCredentialFactory
import com.elabify.app.maknoon.iddocument.PASSPORT_SCHEMA_URI
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.present.AnchorEntry
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.ParsedCredential

object PassportPairing {

    data class Key(val number: String, val dob: String, val exp: String)

    /** The matched credential, its parsed form, and its anchors (possibly empty
     *  for a scan-only or unanchored credential). */
    data class Matched(
        val entity: CredentialEntity,
        val parsed: ParsedCredential,
        val anchors: List<AnchorEntry>,
    )

    /** Normalized key for a scanned document, reusing the mint normalization so
     *  it matches the credential side exactly. null when the document lacks any
     *  of the three fields (then it never folds / pairs). */
    fun key(doc: IDDocument): Key? {
        val claims = LocalCredentialFactory.passportClaims(doc).toMap()
        val number = (claims["passportNumber"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val dob = (claims["dateOfBirth"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val exp = (claims["expiryDate"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
        return Key(number, dob, exp)
    }

    /** Normalized key for a parsed passport credential. null for non-passport
     *  schemas or when any of the three fields is missing. */
    fun key(parsed: ParsedCredential): Key? {
        if (parsed.header.schema != PASSPORT_SCHEMA_URI) return null
        fun str(k: String): String? = (parsed.claims[k] as? JsonValue.Str)?.value
        val number = str("passportNumber")?.takeIf { it.isNotEmpty() } ?: return null
        val dob = str("dateOfBirth")?.takeIf { it.isNotEmpty() } ?: return null
        val exp = str("expiryDate")?.takeIf { it.isNotEmpty() } ?: return null
        return Key(number, dob, exp)
    }

    /** The best credential representing a scanned document: prefer one that is
     *  anchored, then an issuer-issued one (iss != holder), then the newest.
     *  null when no credential matches (scan-only -> card shows no chain logos). */
    fun matchedCredential(
        doc: IDDocument,
        credentials: List<CredentialEntity>,
        holderDid: String?,
    ): Matched? {
        val k = key(doc) ?: return null
        return credentials
            .mapNotNull { e ->
                runCatching { ParsedCredential.parse(e.credentialJson) }.getOrNull()?.let { e to it }
            }
            .filter { (_, p) -> key(p) == k }
            .sortedWith(
                compareByDescending<Pair<CredentialEntity, ParsedCredential>> { (_, p) ->
                    p.anchor?.anchors?.isNotEmpty() == true
                }
                    .thenByDescending { (_, p) -> p.header.iss != holderDid }
                    .thenByDescending { (_, p) -> p.header.iat },
            )
            .firstOrNull()
            ?.let { (e, p) -> Matched(e, p, p.anchor?.anchors ?: emptyList()) }
    }

    /** Keys of all scanned documents, to fold matching passport credentials out
     *  of the Identity-tab credential list. */
    fun documentKeys(docs: List<IDDocument>): Set<Key> =
        docs.mapNotNull { key(it) }.toSet()
}
