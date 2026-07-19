// Pure-function credential filtering for the open-verifier / identity.request
// flow. Given the holder's parsed credentials and a verifier's filter spec,
// returns the credentials that satisfy ALL three dimensions: issuer, schema,
// required claims. Kotlin port of iOS Maknoon/MatchingEngine.swift and the
// React/TS shared/MatchingEngine.ts, so all surfaces agree on what counts as a
// match.
//
// Filter semantics:
//   - issuers / schemas: a "wildcard" clause matches anything; an "allow"
//     clause matches only values in `list`; an absent clause is a wildcard.
//   - requiredClaims: every key must be present in the credential's claims;
//     values are not inspected (predicates are out of scope, ADR-0028).

package com.elabify.musnad.present

object MatchingEngine {

    /** Filter [credentials] to those that satisfy [filter]. */
    fun match(credentials: List<ParsedCredential>, filter: VerifierFilter): List<ParsedCredential> =
        credentials.filter { matches(it, filter) }

    /** True iff [credential] satisfies every dimension of [filter]. */
    fun matches(credential: ParsedCredential, filter: VerifierFilter): Boolean {
        filter.issuers?.let { if (!clausePasses(it, credential.header.iss)) return false }
        filter.schemas?.let { if (!clausePasses(it, credential.header.schema)) return false }
        for (required in filter.requiredClaims) {
            if (!credential.claims.containsKey(required)) return false
        }
        return true
    }

    /** True iff [value] is accepted by [clause]. Unknown modes fail closed. */
    private fun clausePasses(clause: VerifierFilterClause, value: String): Boolean = when (clause.mode) {
        "wildcard" -> true
        "allow" -> clause.list?.contains(value) == true
        else -> false
    }
}
