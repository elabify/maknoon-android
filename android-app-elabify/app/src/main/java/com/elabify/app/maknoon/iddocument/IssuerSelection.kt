// Shared issuer-selection + issuance plumbing used by both the post-scan
// "minted" step and the full document detail screen. Keeping the picker state,
// the URL resolver, and the submit-and-queue logic in one place means the two
// entry points can't drift apart.
//
// Android port of iOS IssuerSelection.swift. The SwiftUI IssuerPickerField View
// is ported separately under ui/iddocument; this file holds the non-UI model:
// the base-URL resolver, the picker state holder, and the submit-and-queue
// outcome logic.

package com.elabify.app.maknoon.iddocument

import com.elabify.musnad.identity.IdentitySandwich

/**
 * Read-only view of the user's known-issuer allow-list, mirroring the subset of
 * the iOS KnownIssuersStore the issuance flow needs. The concrete store
 * (persistence, add/remove, trust checks) is owned elsewhere; this interface
 * lets IssuerSelection resolve a picked entry to an outbound base URL without
 * depending on that store's implementation.
 */
interface KnownIssuersProvider {
    /** Stored issuer entries, each a bare `host` or `host:port` string. */
    val hosts: List<String>

    /**
     * Build the outbound base URL for a stored issuer entry (the issuance POST
     * targets `{baseUrl}/v1/passport-attestation/submit-packet`). Applies the
     * local-dev scheme heuristic (http for localhost / RFC 1918 / link-local,
     * https otherwise). Returns null for a blank entry.
     */
    fun outboundBaseUrl(entry: String): String?
}

/**
 * Resolves the issuer picker selection (a known-issuer `host` / `host:port`
 * entry, or the custom sentinel plus a typed URL) into the outbound base URL
 * the issuance POST should target.
 */
object IssuerSelection {
    /**
     * Sentinel value placed at the end of the issuer picker; selecting it
     * reveals an inline text field for a one-off URL (e.g. an ngrok tunnel, or
     * a LAN dev server not yet added to Known Issuers).
     */
    const val CUSTOM_SENTINEL = "__custom__"

    /**
     * The base URL the issuance / sanctions calls should target. Returns null
     * when the user picked Custom and hasn't typed a parseable URL yet; callers
     * use that null to disable the submit action so we don't fire half-formed
     * requests.
     *
     * When `selectedEntry` is empty (picker hasn't seeded a selection yet, or
     * is off screen) we fall back to the first known issuer so actions stay
     * enabled whenever a trusted issuer is configured.
     */
    fun resolveBaseUrl(
        selectedEntry: String,
        customUrl: String,
        knownIssuers: KnownIssuersProvider,
    ): String? {
        val entry = if (selectedEntry.isEmpty()) {
            knownIssuers.hosts.firstOrNull() ?: CUSTOM_SENTINEL
        } else {
            selectedEntry
        }
        if (entry == CUSTOM_SENTINEL) {
            val trimmed = customUrl.trim()
            if (trimmed.isEmpty()) return null
            // Accept either a full URL (http://...) or a bare host[:port]; fall
            // back to the known-issuers helper for the second case so the
            // local-dev scheme heuristic applies.
            if (looksLikeFullUrl(trimmed)) return trimmed
            return knownIssuers.outboundBaseUrl(trimmed)
        }
        return knownIssuers.outboundBaseUrl(entry)
    }

    /** True when the string already carries a scheme + host (http(s)://host...). */
    private fun looksLikeFullUrl(s: String): Boolean {
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val afterScheme = s.substringAfter("://")
        // Require a non-empty host before the first path/port/query separator.
        val host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return host.isNotBlank()
    }
}

/**
 * Plain (Compose-free) picker state for the issuer dropdown. The Compose
 * IssuerPickerField (under ui/iddocument) owns the remember/mutableState and
 * mirrors into this immutable snapshot when it needs to resolve a base URL,
 * keeping this model layer independent of the Compose runtime.
 *
 * Use [seededSelection] to compute the initial selection (matching the iOS
 * picker's onAppear behaviour) and [resolveBaseUrl] to resolve at submit time.
 */
data class IssuerPickerState(
    val selectedEntry: String = "",
    val customUrl: String = "",
) {
    /** True when the custom-URL field should be shown. */
    val isCustomSelected: Boolean
        get() = selectedEntry == IssuerSelection.CUSTOM_SENTINEL

    /** Resolve the current selection to an outbound base URL, or null if not ready. */
    fun resolveBaseUrl(knownIssuers: KnownIssuersProvider): String? =
        IssuerSelection.resolveBaseUrl(selectedEntry, customUrl, knownIssuers)

    companion object {
        /**
         * The selection to seed the first time the picker renders. Prefer a
         * previously-picked entry; otherwise the first known issuer; otherwise
         * drop to the custom sentinel.
         */
        fun seededSelection(currentSelection: String, knownIssuers: KnownIssuersProvider): String =
            if (currentSelection.isEmpty()) {
                knownIssuers.hosts.firstOrNull() ?: IssuerSelection.CUSTOM_SENTINEL
            } else {
                currentSelection
            }
    }
}

/**
 * Submit-and-queue logic shared by both issuance entry points. Posts the
 * passport attestation packet and, on an auto-approved ack, reports a
 * [Outcome.SubmittedForAnchor] so the caller can queue the background pickup
 * (the pending-pickup store + background poller live in the app layer, so the
 * actual queueing is the caller's job; see [PickupToQueue]).
 */
object IDDocumentIssuance {
    /** Schema URI + human label the auto-mint credential pickup uses. */
    const val PASSPORT_SCHEMA_URI = "elabify://schema/global/passport/v1"
    const val PASSPORT_HUMAN_LABEL = "Verified Identity"

    /** Details the caller needs to enqueue a background credential pickup. */
    data class PickupToQueue(
        val credentialId: String,
        val pickupUrl: String,
        val schemaUri: String = PASSPORT_SCHEMA_URI,
        val humanLabel: String = PASSPORT_HUMAN_LABEL,
    )

    sealed class Outcome {
        /**
         * Issuer auto-approved on submit; `pickup` carries the (LAN-rewritten)
         * URL the caller should enqueue with the holder's background poller.
         */
        data class SubmittedForAnchor(
            val credentialId: String,
            val pickup: PickupToQueue,
        ) : Outcome()

        /**
         * Packet accepted but waiting for an operator to approve (or
         * pre-verification didn't pass).
         */
        data class PendingReview(
            val pendingId: String,
            val proofPreVerified: Boolean,
            val reason: String,
        ) : Outcome()
    }

    /**
     * Submit the passport attestation packet and classify the issuer's ack.
     * The caller must gate this behind BiometricPrompt (the client signs with
     * the master key).
     *
     * @param sandwich the loaded holder identity.
     * @param input the chip material + MRZ fields to attest.
     * @param baseUrl the resolved issuer base URL (from [IssuerSelection]).
     * @param client the issuance client (override in tests).
     */
    suspend fun submit(
        sandwich: IdentitySandwich,
        input: PassportIssuanceInput,
        baseUrl: String,
        client: IDDocumentIssuanceClient = IDDocumentIssuanceClient(),
    ): Outcome {
        val ack = client.submit(
            sandwich = sandwich,
            input = input,
            issuerBaseUrl = baseUrl,
        )
        // Auto-mint branch: server pre-verified + auto-approved. Hand the caller
        // a pickup descriptor (with the localhost-to-LAN rewrite applied) so it
        // can enqueue the pickup with the background poller and let the user
        // close this screen while the credential anchors.
        val pickupUrl = ack.pickupUrl
        val credentialId = ack.credentialId
        if (ack.status == "approved" && pickupUrl != null && credentialId != null) {
            val resolvedPickup = rewritePickupUrlForLan(pickupUrl, baseUrl)
            return Outcome.SubmittedForAnchor(
                credentialId = credentialId,
                pickup = PickupToQueue(
                    credentialId = credentialId,
                    pickupUrl = resolvedPickup,
                ),
            )
        }
        // Pending-review branch: operator approves later (or pre-verification
        // failed and the operator is the backstop).
        return Outcome.PendingReview(
            pendingId = ack.pendingId,
            proofPreVerified = ack.proofPreVerified,
            reason = ack.proofPreVerifiedReason,
        )
    }

    /**
     * The issuer builds pickup URLs from its configured base (typically
     * http://localhost:4000/... in dev mode). When the holder reaches the
     * issuer through a LAN IP, the localhost URL won't resolve from the phone,
     * so rewrite its scheme + host + port to match the base we submitted to.
     * Production deployments configure ELABIFY_PICKUP_BASE_URL with their public
     * hostname and this rewrite is a no-op.
     */
    fun rewritePickupUrlForLan(url: String, fallbackBase: String): String {
        val original = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = original.host ?: return url
        if (host != "localhost" && host != "127.0.0.1") return url
        val base = runCatching { java.net.URI(fallbackBase) }.getOrNull() ?: return url
        // Swap scheme + host + port from the base we submitted to, keeping the
        // original path / query / fragment. base.port == -1 (no explicit port)
        // drops the port, matching the iOS URLComponents behaviour.
        return runCatching {
            java.net.URI(
                base.scheme ?: original.scheme,
                original.userInfo,
                base.host ?: host,
                base.port,
                original.path,
                original.query,
                original.fragment,
            ).toString()
        }.getOrDefault(url)
    }
}
