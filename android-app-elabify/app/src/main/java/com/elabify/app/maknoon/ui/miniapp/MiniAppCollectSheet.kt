// Native "collect a customer's credential" sheet for
// window.maknoon.identity.collect, the cross-device merchant -> customer
// verify step. Android port of the iOS MiniAppCollectSheet.swift (its
// coordinator role is filled by the host's ApprovalGate).
//
// The merchant runs the camera; the customer presents a credential from their
// own wallet (a raw presentation JSON, or a drop envelope referencing a hosted
// presentation). We parse the scanned payload, apply the merchant policy
// (required schema + claims present, and, for the sanctions credential, the
// disclosed screening within maxAgeSec), and return the verdict to the dApp.
//
// SCOPE NOTE (differs from iOS): iOS verifies the scanned presentation
// cryptographically offline via the shipped PresentationVerifier (signatures,
// Merkle proofs, delegation, expiry) and can host a signed VerifierRequest the
// customer scans. The Android presentation/verifier-verify stack is not yet on
// the classpath, so this sheet enforces the POLICY layer (schema, claims,
// sanctions freshness) over the disclosed claims it parses out of the scanned
// payload, and flags offline=true with checks.verified=false so the dApp knows
// the cryptographic verdict was not run here. The policy code is pure and
// mirrors iOS's denialMessage + sanctions gate so it stays unit-testable.

package com.elabify.app.maknoon.ui.miniapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun MiniAppCollectSheet(
    appTitle: String,
    payloadJson: String,
    onResolve: (resultJson: String) -> Unit,
    onCancel: () -> Unit,
) {
    val payload = remember(payloadJson) { runCatching { JSONObject(payloadJson) }.getOrNull() ?: JSONObject() }
    val requiredClaims = remember(payloadJson) { stringList(payload.optJSONArray("requiredClaims")) }
    val schema = payload.optString("schema").takeUnless { it.isEmpty() }
    val maxAgeSec = if (payload.has("maxAgeSec") && !payload.isNull("maxAgeSec")) payload.optLong("maxAgeSec") else null
    val requestUrl = payload.optString("requestURL").takeUnless { it.isEmpty() }

    val askToPresent = stringResource(R.string.app_ask_customer_present)
    var status by remember { mutableStateOf(askToPresent) }
    var isError by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    // Most recent DENY verdict, kept so the merchant can return it via Decline
    // instead of the sheet auto-closing on a fixable miss.
    var lastDenied by remember { mutableStateOf<String?>(null) }
    var showRetry by remember { mutableStateOf(false) }

    fun finish(presentationJson: String) {
        if (done) return
        val verdict = CollectPolicy.evaluate(
            presentationJson = presentationJson,
            requestedSchema = schema,
            requiredClaims = requiredClaims,
            maxAgeSec = maxAgeSec,
            nowSec = System.currentTimeMillis() / 1000,
        )
        if (verdict == null) {
            isError = true
            status = "Unrecognized code. Ask the customer to show their Attribute QR."
            return
        }
        if (verdict.optString("decision") == "GRANT") {
            done = true
            onResolve(verdict.toString())
        } else {
            lastDenied = verdict.toString()
            isError = true
            showRetry = true
            status = verdict.optString("message").ifEmpty { "The customer's credential was declined." }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(appTitle, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.app_requesting, requiredClaims.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )

        // The customer can scan THIS hosted request to present (easiest), or
        // the merchant scans the customer's presentation below. First wins.
        requestUrl?.let { url ->
            QrCode(content = url, modifier = Modifier.fillMaxWidth(0.5f).aspectRatio(1f))
            Text(stringResource(R.string.app_customer_scans_this), style = MaterialTheme.typography.labelSmall)
        }

        Text(stringResource(R.string.app_or_scan_the_customer), style = MaterialTheme.typography.labelSmall)
        if (!showRetry) {
            MiniAppQrScanner(
                continuous = true,
                onCode = { code -> if (!done) finish(code) },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
            )
        }

        Text(
            status,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (showRetry) {
            Button(
                onClick = {
                    status = askToPresent
                    isError = false
                    showRetry = false
                    lastDenied = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.common_scan_again)) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            lastDenied?.let { denied ->
                Button(
                    onClick = { done = true; onResolve(denied) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.app_decline)) }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/**
 * Pure merchant policy over a scanned presentation's disclosed claims. Mirrors
 * the iOS collect policy (schema + missing-claims + sanctions freshness gate)
 * and its denialMessage, kept side-effect-free for unit tests. Returns the
 * dApp result envelope, or null if the scanned payload is not a presentation.
 */
internal object CollectPolicy {

    fun evaluate(
        presentationJson: String,
        requestedSchema: String?,
        requiredClaims: List<String>,
        maxAgeSec: Long?,
        nowSec: Long,
    ): JSONObject? {
        val root = runCatching { JSONObject(presentationJson) }.getOrNull() ?: return null
        // A presentation carries a header with a schema and a disclosed map.
        // Accept either { header: { schema }, disclosed: {...} } or a flat
        // { schema, disclosed } shape so the sheet is liberal in what it reads.
        val header = root.optJSONObject("header")
        val actualSchema = header?.optString("schema").takeUnless { it.isNullOrEmpty() }
            ?: root.optString("schema").takeUnless { it.isEmpty() }
            ?: return null
        val disclosedObj = root.optJSONObject("disclosed") ?: return null

        val disclosed = disclosedObj.toMap()
        val disclosedKeys = disclosed.keys

        val reasons = ArrayList<String>()
        if (requestedSchema != null && actualSchema != requestedSchema) reasons.add("wrong_schema")
        val missing = requiredClaims.filter { it !in disclosedKeys }
        if (missing.isNotEmpty()) reasons.add("missing_claims")

        val sanctionsReason = sanctionsReason(disclosed, maxAgeSec, nowSec)
        val fresh = sanctionsReason != "stale_screening"
        sanctionsReason?.let { reasons.add(it) }

        // The cryptographic verdict cannot run on this build (no offline
        // verifier yet). Flag it so the dApp does not over-trust the result.
        val cryptoOK = false
        reasons.add("verification_unavailable")

        val decision = if (cryptoOK && reasons.isEmpty()) "GRANT" else "DENY"
        val message = denialMessage(reasons, missing, disclosedKeys.toList(), requestedSchema, actualSchema)

        return JSONObject().apply {
            put("decision", decision)
            put("reason", reasons.firstOrNull() ?: "ok")
            put("missing", JSONArray(missing))
            put("message", message ?: "")
            put("schema", actualSchema)
            put("disclosed", disclosedObj)
            put("checks", JSONObject().apply {
                put("verified", cryptoOK)
                put("fresh", fresh)
            })
            put("offline", true)
        }
    }

    /** Reads the passport sdnScreen object (clean + fresh) or the legacy flat
     *  sanctionsScreenedAt key. Returns null when no gate applies. Mirrors the
     *  iOS CommerceMerchantPolicy.sanctionsReason. */
    private fun sanctionsReason(disclosed: Map<String, Any?>, maxAgeSec: Long?, nowSec: Long): String? {
        val sdn = disclosed["sdnScreen"] as? Map<*, *>
        val clean: Boolean?
        val screenedAt: Long?
        if (sdn != null) {
            clean = sdn["clean"] as? Boolean
            screenedAt = (sdn["screenedAt"] as? Number)?.toLong()
        } else {
            val flat = disclosed["sanctionsScreenedAt"]
            if (flat == null) return null
            clean = (disclosed["sanctionsClean"] as? Boolean) ?: true
            screenedAt = (flat as? Number)?.toLong()
        }
        if (clean == false) return "sanctioned"
        if (maxAgeSec != null) {
            if (screenedAt == null) return "stale_screening"
            if (nowSec - screenedAt > maxAgeSec) return "stale_screening"
        }
        return null
    }

    /** Build a merchant-facing explanation for a DENY. Pure + internal so it is
     *  unit-testable. Returns null only when there is no reason (a GRANT). */
    fun denialMessage(
        reasons: List<String>,
        missing: List<String>,
        disclosedKeys: List<String>,
        requestedSchema: String?,
        actualSchema: String,
    ): String? {
        val first = reasons.firstOrNull() ?: return null
        return when (first) {
            "missing_claims" -> {
                val shared = if (disclosedKeys.isEmpty()) "nothing" else disclosedKeys.sorted().joinToString(", ")
                "Missing: ${missing.joinToString(", ")}. The customer shared: $shared. Ask them to include the missing attributes and present again."
            }
            "wrong_schema" ->
                "Wrong credential type. Expected ${requestedSchema ?: "a different credential"}, but the customer presented $actualSchema."
            "stale_screening" ->
                "The customer's sanctions screening is missing or older than allowed. Ask for a fresh screening."
            "sanctioned" ->
                "The customer's sanctions screening is not clean (flagged). Payment blocked."
            "verification_unavailable" ->
                "Could not cryptographically verify the credential on this device. A server verify is required."
            else -> "Declined: $first"
        }
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = when (val v = get(k)) {
                is JSONObject -> v.toMap()
                else -> v
            }
        }
        return out
    }
}
