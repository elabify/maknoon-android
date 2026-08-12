// Display + matching helpers shared by the Scan Verifier and Verify Other
// sheets. These mirror the iOS helpers (MatchingEngine.swift, SchemaPalette /
// shortIssuerName / caip2Label in CredentialCard.swift + IssuerIdentity
// Resolver.swift) since the on-device SDK does not yet expose them as Kotlin.
//
// MatchMaknoon mirrors the cross-surface MatchingEngine (issuer + schema +
// requiredClaims, fail-closed on unknown clause modes). Label helpers are
// presentation-only and have no wire-format role.

package com.elabify.app.maknoon.ui.present

import android.content.Context
import com.elabify.app.maknoon.R
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.VerifierFilter
import com.elabify.musnad.present.VerifierFilterClause
import org.json.JSONObject

/** Parse a stored credential row's raw v2 payload into its disclosable pieces. */
internal fun CredentialEntity.parsed(): ParsedCredential = ParsedCredential.parse(credentialJson)

/**
 * Credential filtering for the open-verifier flow (iOS MatchingEngine /
 * shared/MatchingEngine.ts). A credential matches iff it satisfies all three
 * dimensions: issuer clause, schema clause, and every required claim is present
 * (values are not inspected; presence is sufficient, predicates are out of
 * scope per ADR-0028).
 */
internal object MatchMaknoon {
    fun match(credentials: List<CredentialEntity>, filter: VerifierFilter): List<CredentialEntity> =
        credentials.filter { matches(it, filter) }

    private fun matches(c: CredentialEntity, f: VerifierFilter): Boolean {
        f.issuers?.let { if (!clausePasses(it, c.issuerDid)) return false }
        f.schemas?.let { if (!clausePasses(it, c.schema)) return false }
        if (f.requiredClaims.isNotEmpty()) {
            val parsed = runCatching { c.parsed() }.getOrNull() ?: return false
            for (required in f.requiredClaims) {
                if (!parsed.claims.containsKey(required)) return false
            }
        }
        return true
    }

    /** Unknown modes fail closed (matches iOS). */
    private fun clausePasses(clause: VerifierFilterClause, value: String): Boolean = when (clause.mode) {
        "wildcard" -> true
        "allow" -> (clause.list ?: emptyList()).contains(value)
        else -> false
    }
}

/** Human label for a credential schema URI (mirrors iOS SchemaPalette). */
internal fun schemaLabel(context: Context, schemaUri: String): String = when (schemaUri) {
    "elabify://schema/global/passport/v1" -> "Passport"
    "elabify://schema/adgm/emiratesId/v1" -> context.getString(R.string.schema_label_emirates_id)
    "elabify://schema/global/musnadMaknoon/v1" -> "Musnad-Maknoon membership"
    "elabify://schema/global/walletControlEth/v1" -> "Ethereum wallet control"
    "elabify://schema/global/walletControlBtc/v1" -> "Bitcoin wallet control"
    "elabify://schema/global/corporateIdentity/v1" -> "Corporate identity"
    "elabify://schema/global/corporateOfficer/v1" -> "Corporate officer"
    else -> {
        val tail = schemaUri.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")
        if (tail.isEmpty()) "Verified credential" else tail
    }
}

/**
 * Offline issuer label, mirroring iOS shortIssuerName(). For
 * did:method:network:type:slug it title-cases the slug, then falls back to the
 * network segment, then a neutral "Issuer".
 */
internal fun shortIssuerName(issuerDid: String): String {
    val parts = issuerDid.split(":").filter { it.isNotEmpty() }
    if (parts.size >= 5 && parts.last().isNotEmpty()) return parts.last().replaceFirstChar { it.uppercase() }
    if (parts.size >= 3) return parts[2].replaceFirstChar { it.uppercase() }
    return "Issuer"
}

// caip2Label(...) and shortHex(...) already live in this package
// (CredentialPresentScreen.kt); reuse those rather than redefining them.

/**
 * Display value for a required claim on the confirm screen, sdnScreen-aware
 * (mirrors iOS ScanVerifierSheet.attrValue / CommercePaySheet).
 */
internal fun attrValue(context: Context, parsed: ParsedCredential?, key: String): String {
    val value = parsed?.claims?.get(key) ?: return "-"
    if (key == "sdnScreen" && value is JsonValue.Obj) {
        val obj = value.value
        val result = (obj["result"] as? JsonValue.Str)?.value ?: "?"
        val screenedAt = (obj["screenedAt"] as? JsonValue.Str)?.value
        val when10 = screenedAt?.take(10).orEmpty()
        return if (when10.isEmpty()) {
            context.getString(R.string.commerce_sanctions_claim, result)
        } else {
            context.getString(R.string.commerce_sanctions_claim_dated, result, when10)
        }
    }
    return value.displayText()
}

/** Null-safe org.json string accessor (used by the badge parser). getString
 *  (guarded) rather than optString(_, null), which K2 flags for passing the null
 *  literal to a non-null Java param (KT-73255). */
internal fun JSONObject.optStr(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
