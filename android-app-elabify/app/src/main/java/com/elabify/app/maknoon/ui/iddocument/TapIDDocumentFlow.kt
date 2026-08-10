// The full Tap-ID-Document capture + issuance flow, wired once and shared by the
// Identity tab and first-run onboarding so the NFC reader, document store, and
// issuer-submit closures live in exactly one place (mirrors the self-contained
// iOS TapIDDocumentSheet).

package com.elabify.app.maknoon.ui.iddocument

import android.nfc.tech.IsoDep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.iddocument.IDDocumentIssuanceClient
import com.elabify.app.maknoon.iddocument.IDDocumentNfcReaderMode
import com.elabify.app.maknoon.iddocument.IDDocumentReader
import com.elabify.app.maknoon.iddocument.IDDocumentReaderError
import com.elabify.app.maknoon.iddocument.IDDocumentStore
import com.elabify.app.maknoon.iddocument.IssuerSelection
import com.elabify.app.maknoon.iddocument.PASSPORT_SCHEMA_URI
import com.elabify.app.maknoon.ui.identity.PendingPickup
import com.elabify.app.maknoon.ui.identity.PendingPickupsStore
import com.elabify.app.maknoon.ui.settings.KnownIssuersStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Tap a passport (or other ICAO document), read it on-device, save it, and
 * optionally submit it to the configured issuer for a verified credential.
 *
 * @param onDone the saved-document id once the user finishes (null if nothing
 *   was saved).
 * @param onClose dismissed without finishing.
 */
@Composable
fun TapIDDocumentFlow(
    onDone: (savedId: String?) -> Unit,
    onClose: () -> Unit,
    skipKindPicker: Boolean = false,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val idDocumentStore = remember { IDDocumentStore.shared(context) }
    val reader = remember(context) { IDDocumentReader(context) }
    val store = remember { IdentityStore(context) }
    val knownIssuers = remember { KnownIssuersStore(context) }
    val pendingPickups = remember { PendingPickupsStore(context) }
    val issuerBaseUrl = remember { IssuerSelection.resolveBaseUrl("", "", knownIssuers) }
    val nfcAvailable = activity != null && IDDocumentNfcReaderMode.isAvailable(activity)

    TapIDDocumentScreen(
        nfcAvailable = nfcAvailable,
        // Drive foreground reader mode: suspend until an ICAO chip is tapped,
        // then read it. stop() releases the radio whether the read succeeds or
        // throws.
        read = { params, onProgress ->
            val act = activity ?: throw IDDocumentReaderError.NfcUnavailable
            val isoDep = awaitIsoDepTag(act)
            try {
                reader.read(isoDep, params, onProgress)
            } finally {
                IDDocumentNfcReaderMode.stop(act)
            }
        },
        save = { result -> idDocumentStore.save(result).id.toString() },
        canIssue = { savedId ->
            issuerBaseUrl != null &&
                idDocumentStore.document(UUID.fromString(savedId))?.sod != null
        },
        issuanceDisabledHint = { savedId ->
            when {
                issuerBaseUrl == null ->
                    "Add a trusted issuer in Settings > Identity to request a verified credential."
                idDocumentStore.document(UUID.fromString(savedId))?.sod == null ->
                    "This document exposed no security object (SOD), so it can't be issuer-verified."
                else -> null
            }
        },
        submitIssuance = { savedId ->
            val doc = idDocumentStore.document(UUID.fromString(savedId))
                ?: throw IllegalStateException("Document not found.")
            val sandwich = withContext(Dispatchers.IO) { IdentitySandwich.load(store) }
                ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
            val ack = IDDocumentIssuanceClient()
                .submit(sandwich, doc.toPassportIssuanceInput(), issuerBaseUrl)
            val pickupUrl = ack.pickupUrl?.takeIf { it.isNotEmpty() }
            val credentialId = ack.credentialId?.takeIf { it.isNotEmpty() }
            if (ack.status == "approved" && pickupUrl != null && credentialId != null) {
                pendingPickups.add(
                    PendingPickup(
                        credentialId = credentialId,
                        pickupUrl = pickupUrl,
                        humanLabel = PendingPickupsStore.PASSPORT_LABEL,
                        schemaUri = PASSPORT_SCHEMA_URI,
                        startedAt = System.currentTimeMillis(),
                    ),
                )
            }
            ack.toIssuanceOutcome()
        },
        submittingHost = issuerHostLabel(issuerBaseUrl),
        onSaved = onDone,
        onClose = onClose,
        skipKindPicker = skipKindPicker,
    )
}

/**
 * Resume with the first ICAO IsoDep tag the platform delivers via foreground
 * reader mode. Cancelling (coroutine cancellation) releases the radio; the
 * caller owns stop() on the success path so the radio stays live for the full
 * data-group read.
 */
private suspend fun awaitIsoDepTag(activity: FragmentActivity): IsoDep =
    suspendCancellableCoroutine { cont ->
        val started = IDDocumentNfcReaderMode.start(activity) { isoDep ->
            if (cont.isActive) cont.resume(isoDep)
        }
        if (!started && cont.isActive) {
            cont.resumeWithException(IDDocumentReaderError.NfcUnavailable)
        }
        cont.invokeOnCancellation { IDDocumentNfcReaderMode.stop(activity) }
    }

/** host[:port] label for the "Submitting to …" line, or "issuer" when unknown. */
private fun issuerHostLabel(baseUrl: String?): String {
    if (baseUrl.isNullOrBlank()) return "issuer"
    return runCatching {
        val u = java.net.URI(baseUrl)
        val host = u.host ?: return "issuer"
        if (u.port > 0) "$host:${u.port}" else host
    }.getOrDefault("issuer")
}
