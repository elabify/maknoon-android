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

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

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

        Text(stringResource(R.string.app_will_disclose), style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            requiredClaims.forEach { key ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null)
                    Text(stringResource(R.string.app_claim_indent, key), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        maxAgeSec?.let {
            Text(
                stringResource(R.string.app_requires_fresh_screening, humanAge(it)),
                style = MaterialTheme.typography.bodySmall,
            )
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
                            // The disclosure the user approved. The on-device
                            // credential store is not yet ported (see handoff),
                            // so disclosed is empty here; the handler flags the
                            // verdict offline. When the presentation stack lands,
                            // this carries the selected credential's claims.
                            val disclosed = JSONObject()
                            val result = JSONObject().apply {
                                put("decision", "GRANT")
                                put("reason", "user_approved")
                                put("disclosed", disclosed)
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
                title = "Verify identity",
                subtitle = "Approve sharing your credential with $appTitle",
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

internal fun humanAge(seconds: Long): String {
    val days = seconds / 86_400
    if (days != 0L && days % 365 == 0L) return "${days / 365} year(s)"
    if (days >= 30) return "${days / 30} month(s)"
    return "$days day(s)"
}
