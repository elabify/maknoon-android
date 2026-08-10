// Approval sheet for window.maknoon.identity.request. Android port of the iOS
// MiniAppIdentitySheet.swift (its coordinator role is filled by the host's
// ApprovalGate, so this is a stateless Compose sheet driven by the request
// payload and two callbacks).
//
// The user reviews who is asking, which claims are requested, and the
// freshness requirement, then confirms with the device biometric. Approve
// resolves the gate with the disclosure; Cancel / dismiss rejects with a 4001.
//
// Disclosure consent lives here (explicit confirm + biometric). The handler
// shapes the verdict envelope once this returns.

package com.elabify.app.maknoon.ui.miniapp
import android.icu.util.ULocale
import android.icu.util.MeasureUnit
import android.icu.util.Measure
import android.icu.text.MeasureFormat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.ApprovalRequest
import com.elabify.app.maknoon.ui.BiometricGate
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * MiniAppApprovalSheetHost contribution for the identity slice: renders the
 * identity-disclosure, customer-collect, and QR-scan sheets. The host-assembly
 * agent composes this into the screen's [MiniAppApprovalSheetHost] when over
 * ApprovalRequest.kind; [fallback] handles any kind this slice does not own
 * (so other handler agents' sheets still render). Returns true when this slice
 * handled the request's kind.
 */
@Composable
fun IdentityMiniAppSheets(
    request: ApprovalRequest,
    onDismiss: () -> Unit,
    fallback: @Composable (ApprovalRequest, () -> Unit) -> Unit = { req, _ -> req.cancel() },
) {
    when (request.kind) {
        "identity" -> MiniAppIdentitySheet(
            appTitle = request.appTitle,
            payloadJson = request.payloadJson,
            onApprove = { result -> request.approve(result); onDismiss() },
            onCancel = { request.cancel(); onDismiss() },
        )
        "collect" -> MiniAppCollectSheet(
            appTitle = request.appTitle,
            payloadJson = request.payloadJson,
            onResolve = { result -> request.approve(result); onDismiss() },
            onCancel = { request.cancel(); onDismiss() },
        )
        "scan" -> MiniAppScanSheet(
            appTitle = request.appTitle,
            payloadJson = request.payloadJson,
            onResolve = { result -> request.approve(result); onDismiss() },
            onCancel = { request.cancel(); onDismiss() },
        )
        else -> fallback(request, onDismiss)
    }
}

private data class SheetCred(
    val cid: String,
    val label: String,
    val holder: String,
    val sdn: String?,
    /** Unix seconds the credential was issued, or 0 when the handler sent none. */
    val issuedAt: Long,
)

/** Issue date plus the short credential id: the two fields that actually differ
 *  between a user's own passports. The label, holder and issuer lines above are
 *  identical for two passports from one issuer under one identity, which made
 *  the picker a stack of clones and read as "it just picks one". Data only, so
 *  it adds no translatable prose to a sheet that ships in 31 languages. */
private fun credentialSubtitle(c: SheetCred): String {
    val shortCid = if (c.cid.length > 10) c.cid.take(10) else c.cid
    if (c.issuedAt <= 0L) return shortCid
    val date = java.text.DateFormat
        .getDateInstance(java.text.DateFormat.MEDIUM)
        .format(java.util.Date(c.issuedAt * 1000))
    return "$date \u00b7 $shortCid"
}

/** The 0x the holder DID encodes (did:elabify:...:holder:0x…), so the user sees
 *  which passport/identity is being disclosed; falls back to a shortened DID. */
private fun holderShort(did: String): String {
    val idx = did.indexOf("0x")
    if (idx >= 0) {
        val hex = did.substring(idx)
        return if (hex.length > 14) hex.take(8) + "…" + hex.takeLast(6) else hex
    }
    return if (did.length <= 30) did else did.take(18) + "…" + did.takeLast(8)
}

@Composable
fun MiniAppIdentitySheet(
    appTitle: String,
    payloadJson: String,
    onApprove: (resultJson: String) -> Unit,
    onCancel: () -> Unit,
) {
    val payload = remember(payloadJson) { runCatching { JSONObject(payloadJson) }.getOrNull() ?: JSONObject() }
    val purpose = payload.optString("purpose").takeUnless { it.isEmpty() }
    val requiredClaims = remember(payloadJson) { stringList(payload.optJSONArray("requiredClaims")) }
    val maxAgeSec = if (payload.has("maxAgeSec") && !payload.isNull("maxAgeSec")) payload.optLong("maxAgeSec") else null
    // Pool-access disclosure context (empty/false for a plain identity.request).
    val recipientHost = payload.optString("recipientHost").takeUnless { it.isEmpty() }
    val walletAddress = payload.optString("walletAddress").takeUnless { it.isEmpty() }
    val showsDisclosedValues = payload.optBoolean("showsDisclosedValues", false)
    // Matched credentials the handler offers. The user picks one; the handler
    // builds the presentation from the chosen cid.
    val credentials = remember(payloadJson) {
        val arr = payload.optJSONArray("credentials")
        buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cid = o.optString("cid")
                if (cid.isNotEmpty()) add(
                    SheetCred(
                        cid = cid,
                        label = o.optString("label"),
                        holder = o.optString("holder"),
                        sdn = if (o.has("sdn")) o.optString("sdn") else null,
                        issuedAt = o.optLong("issuedAt", 0L),
                    ),
                )
            }
        }
    }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var selectedCid by remember(payloadJson) { mutableStateOf(credentials.firstOrNull()?.cid) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(appTitle, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.app_requesting_proof), style = MaterialTheme.typography.bodyMedium)
        purpose?.let {
            Text(stringResource(R.string.app_purpose, it), style = MaterialTheme.typography.bodySmall)
        }

        // Who receives this disclosure.
        recipientHost?.let {
            Text(stringResource(R.string.app_sending_to), style = MaterialTheme.typography.titleSmall)
            Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }

        Text(stringResource(R.string.app_will_disclose), style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            requiredClaims.forEach { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Text(stringResource(R.string.app_claim_indent, key), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Show the actual value being shared (expanded), not just the name, so
            // the user sees exactly what the recipient learns.
            if (showsDisclosedValues) {
                credentials.firstOrNull { it.cid == selectedCid }?.sdn?.let { sdn ->
                    Text(
                        sdn,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 24.dp),
                    )
                }
            }
        }
        maxAgeSec?.let {
            Text(
                stringResource(R.string.app_requires_fresh_screening, humanAge(it)),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // The EVM wallet address is ALSO shared + permanently linked to this KYC.
        walletAddress?.let {
            Text(stringResource(R.string.app_wallet_shared_title), style = MaterialTheme.typography.titleSmall)
            Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.app_wallet_shared_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Credential picker: always shown (even for a single match) so the holder
        // 0x being disclosed is visible. label + holder are data from the handler.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            credentials.forEach { c ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedCid = c.cid },
                ) {
                    RadioButton(selected = selectedCid == c.cid, onClick = { selectedCid = c.cid })
                    Column {
                        Text(c.label.ifEmpty { c.cid.take(8) }, style = MaterialTheme.typography.bodyMedium)
                        if (c.holder.isNotEmpty()) {
                            Text(
                                holderShort(c.holder),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            credentialSubtitle(c),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        authError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !working,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.common_cancel)) }
            Button(
                onClick = {
                    scope.launch {
                        working = true
                        authError = null
                        val ok = consent(activity, appTitle)
                        working = false
                        if (ok) {
                            // Return the chosen credential id; the handler builds
                            // the signed presentation from it and gets the
                            // authoritative disclosure back from the verifier.
                            val result = JSONObject().apply {
                                put("decision", "GRANT")
                                put("reason", "user_approved")
                                put("disclosed", JSONObject())
                                selectedCid?.let { put("cid", it) }
                            }
                            onApprove(result.toString())
                        } else {
                            authError = "Authentication failed or cancelled."
                        }
                    }
                },
                enabled = !working && requiredClaims.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.app_approve)) }
        }
    }
}

/** Explicit biometric consent before disclosing anything. If no biometric or
 *  device credential is enrolled (e.g. an emulator), the explicit Approve tap
 *  is itself the consent, mirroring the iOS LAContext fallback. */
private suspend fun consent(activity: FragmentActivity?, appTitle: String): Boolean {
    if (activity == null) return true
    return when (BiometricGate.availability(activity)) {
        BiometricGate.Availability.AVAILABLE ->
            BiometricGate.authenticate(
                activity,
                title = activity.getString(R.string.miniapp_verify_identity),
                subtitle = activity.getString(R.string.miniapp_approve_sharing_with, appTitle),
            )
        else -> true
    }
}

internal fun stringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        (arr.opt(i) as? String)?.let { out.add(it) }
    }
    return out
}

/**
 * Human duration, formatted by ICU rather than by us. Mirrors the iOS
 * DateComponentsFormatter path.
 *
 * This was a hand-rolled ladder emitting "year(s)" / "month(s)" / "day(s)". The
 * "(s)" hack cannot be translated: Arabic has six plural forms, and the machine
 * translator rendered the same trick elsewhere as a literal Arabic
 * parenthetical. ICU already ships correctly pluralized, correctly translated
 * durations for every locale, so deferring to it is less code and more correct
 * than authoring three plural keys by hand.
 */
internal fun humanAge(seconds: Long): String {
    val days = seconds / 86_400
    val (amount, unit) = when {
        days != 0L && days % 365 == 0L -> (days / 365) to MeasureUnit.YEAR
        days >= 30 -> (days / 30) to MeasureUnit.MONTH
        else -> days to MeasureUnit.DAY
    }
    return MeasureFormat
        .getInstance(ULocale.getDefault(), MeasureFormat.FormatWidth.WIDE)
        .format(Measure(amount, unit))
}
