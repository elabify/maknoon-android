// Open-ended share flow, ported from iOS PresentView.swift (PresentAttributesView
// + DropQrSheet). The holder picks claims, the wallet builds a signed Presentation
// with a self-issued nonce (or the scanned verifier's challenge), and the user
// chooses how to deliver it: render the one-shot drop pointer as a small QR, copy
// the JSON, POST to a pasted URL, or POST straight to the verifier's callback.
//
// There is no hardcoded verifier here. The signing path is the single shared
// com.elabify.musnad.present.PresentationBuilder so this surface and the mini-app
// identity bridge never drift on canonicalization / Merkle proofs / signature shape.
//
// APPROVAL GATE (no iOS analog needed there; Android-specific hardening):
//   - FLAG_SECURE on the window while this screen is composed, so the disclosed
//     claim values and the generated QR never land in a screenshot / recents
//     thumbnail.
//   - A BiometricPrompt confirm before any release (drop / copy / callback),
//     showing the verifier DID the presentation is bound to.
//
// GMS-free. ZXing for QR (no ML Kit). org.json only. No em-dashes.

package com.elabify.app.maknoon.ui.present

import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Slider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.elabify.app.maknoon.ui.components.qrBitmap
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.BiometricGate
import com.elabify.app.maknoon.ui.components.AddressChip
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import com.elabify.musnad.net.PresentationDrop
import com.elabify.musnad.present.DropEnvelope
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.Presentation
import com.elabify.musnad.present.PresentationBuilder
import com.elabify.musnad.present.VerifierRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A built, signed presentation plus its canonical JSON text (for copy). */
private data class BuiltShare(val presentation: Presentation, val jsonText: String)

/** A pending share that needs the biometric gate before release. */
private enum class ShareChannel { DROP, COPY, CALLBACK, URL }

/**
 * Claim picker + build + terminal share actions. Embedded as a section inside
 * CredentialPresentScreen's scroll column, so it is not its own Scaffold.
 *
 * @param credential      the parsed credential to disclose from
 * @param pendingRequest  the scanned verifier request (Respond mode), or null
 * @param sandwich        the unlocked Identity Sandwich (signs the challenge)
 * @param dropHost        base host for the one-shot drop (POST /v1/drop)
 * @param onShared        record a successful share (verifierDid, channel, keys)
 */
@Composable
fun PresentAttributesScreen(
    credential: ParsedCredential,
    pendingRequest: VerifierRequest?,
    sandwich: IdentitySandwich,
    dropHost: String,
    onShared: (verifierDid: String, channel: String, disclosedKeys: List<String>) -> Unit,
    // Passport (compact) layout, ADR-0039: Build Online QR on top (= secure
    // link), an advanced section with Build Offline QR + Copy, no Send-to-URL,
    // and no FLAG_SECURE / protected-screen banner (a biometric already gated
    // entry).
    compact: Boolean = false,
    // Dismiss the whole present flow (compact: "Done" on the online/offline QR
    // sheet returns to the passport card, not this builder).
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // FLAG_SECURE for the lifetime of this composable: the disclosed claim
    // values and the generated QR must not leak into a screenshot or the
    // recents thumbnail. Cleared on dispose so the rest of the app is normal.
    // Passports skip FLAG_SECURE: entry was biometric-gated and the holder
    // wants to mirror / screenshot their own passport QR without a "Protected
    // screen" overlay. Other credentials keep the screenshot guard.
    val window = activity?.window
    if (!compact) {
        DisposableEffect(window) {
            window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }

    val allKeys = credential.merkleTree.sortedKeys
    val requiredKeys = remember(pendingRequest) {
        (pendingRequest?.filter?.requiredClaims ?: emptyList()).toSet()
    }

    // Selection: required always on; everything else defaults on too (matches
    // iOS applyPendingDefaults: pre-select all in the open flow, required in
    // the respond flow plus the user can add more).
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    remember(allKeys, requiredKeys) {
        selected.clear()
        if (pendingRequest != null) {
            allKeys.forEach { selected[it] = requiredKeys.contains(it) }
        } else {
            allKeys.forEach { selected[it] = true }
        }
        true
    }

    var built by remember { mutableStateOf<BuiltShare?>(null) }
    var building by remember { mutableStateOf(false) }
    var buildError by remember { mutableStateOf<String?>(null) }

    // Drop QR state.
    var dropEnvelope by remember { mutableStateOf<DropEnvelope?>(null) }
    var dropError by remember { mutableStateOf<String?>(null) }

    // Callback / URL state.
    var urlPromptOpen by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf(pendingRequest?.response?.callbackUrl.orEmpty()) }
    var callbackStatus by remember { mutableStateOf<Int?>(null) }
    var callbackBody by remember { mutableStateOf<String?>(null) }
    var callbackError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    // Offline (multi-frame rotating) QR state for the compact passport flow.
    var offlineFrames by remember { mutableStateOf<List<LocalFrames.Frame>?>(null) }

    val selectedKeys: List<String> = allKeys.filter { selected[it] == true }

    // Records the share to history with the verifier the presentation is bound to.
    fun record(p: Presentation, channel: String) {
        val did = p.verifierRequest?.verifierDid ?: PresentationBuilder.OPEN_VERIFIER_DID
        onShared(did, channel, p.disclosed.map { it.key })
    }

    // Biometric approval gate, then run the release action. Shows the verifier DID.
    fun gateThen(action: suspend () -> Unit) {
        scope.launch {
            val verifierDid = pendingRequest?.verifierDid ?: PresentationBuilder.OPEN_VERIFIER_DID
            val approved = if (activity != null) {
                BiometricGate.authenticate(
                    activity,
                    title = context.getString(R.string.present_share_credential),
                    subtitle = context.getString(R.string.present_releasing_to, verifierDid),
                )
            } else {
                true
            }
            if (approved) action()
        }
    }

    // Build the presentation once, reused by Online QR / Offline QR / Copy in
    // the compact passport flow. Sets `built`; returns it (null on error).
    suspend fun buildNow(): BuiltShare? {
        built?.let { return it }
        building = true
        buildError = null
        return try {
            val challenge: String
            val verifierDidForMsg: String
            if (pendingRequest != null) {
                challenge = pendingRequest.challenge
                verifierDidForMsg = pendingRequest.verifierDid
            } else {
                challenge = "0x" + PresentationBuilder.selfNonceHex()
                verifierDidForMsg = PresentationBuilder.OPEN_VERIFIER_DID
            }
            val p = withContext(Dispatchers.Default) {
                PresentationBuilder.build(
                    credential = credential,
                    selectedClaims = selectedKeys.toSet(),
                    challenge = challenge,
                    verifierDid = verifierDidForMsg,
                    pendingRequest = pendingRequest,
                    sandwich = sandwich,
                )
            }
            BuiltShare(p, p.toJson().toString(2)).also { built = it }
        } catch (e: Exception) {
            buildError = e.message ?: e.toString()
            null
        } finally {
            building = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {

        // Screen-protection + biometric notice. FLAG_SECURE is on while this
        // screen is composed and a biometric confirm gates every release; the
        // info Banner makes that protection visible (Android-specific, no iOS
        // analog).
        if (!compact) {
            Banner(
                title = stringResource(R.string.present_protected_screen_title),
                variant = BannerVariant.INFO,
                icon = Icons.Filled.Lock,
                body = stringResource(R.string.present_protected_screen_body),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Compact passport flow: Build Online QR on top.
        if (compact) {
            Button(
                onClick = {
                    // Entry to this screen was already biometric-gated; the
                    // actions here don't re-verify.
                    scope.launch {
                        dropError = null
                        offlineFrames = null
                        val b = buildNow() ?: return@launch
                        try {
                            if (!com.elabify.app.maknoon.ui.settings.RelaySettings.enabled) {
                                error("The presentation relay is turned off in Settings, Identity. Turn it on to share an online QR.")
                            }
                            dropEnvelope = PresentationDrop(dropHost).upload(b.presentation)
                            record(b.presentation, "drop")
                        } catch (e: Exception) {
                            dropError = e.message ?: e.toString()
                        }
                    }
                },
                enabled = !building,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (building) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.present_build_online_qr))
            }
            dropError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // The online QR opens in its own full-screen sheet (iOS parity:
            // a dedicated DropQrSheet), not inline under the button.
            dropEnvelope?.let { env ->
                QrSheet(
                    title = stringResource(R.string.present_online_qr),
                    onDone = { dropEnvelope = null; onDone() },
                ) {
                    DropQrCard(env)
                }
            }
        }

        // Respond-mode summary.
        if (pendingRequest != null) {
            SoftCard {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.present_responding_to_verifier), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        pendingRequest.verifierName ?: stringResource(R.string.present_verifier),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The verifier DID as a tap-to-copy monospace chip.
                    AddressChip(
                        text = pendingRequest.verifierDid,
                        head = 18,
                        tail = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (pendingRequest.filter.requiredClaims.isNotEmpty()) {
                        Text(
                            stringResource(R.string.present_wants, pendingRequest.filter.requiredClaims.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Claims picker with required tags + select-all.
        SoftCard {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val optionalKeys = allKeys.filter { !requiredKeys.contains(it) }
                val allSelected = optionalKeys.isNotEmpty() && optionalKeys.all { selected[it] == true }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.present_attributes), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (optionalKeys.isNotEmpty()) {
                        TextButton(onClick = {
                            if (allSelected) {
                                optionalKeys.forEach { selected[it] = false }
                            } else {
                                allKeys.forEach { selected[it] = true }
                            }
                        }) { Text(if (allSelected) stringResource(R.string.present_deselect_all) else stringResource(R.string.present_select_all)) }
                    }
                }
                allKeys.forEach { key ->
                    val required = requiredKeys.contains(key)
                    // sdnScreen-aware so a sanctions object expands to
                    // "Sanctions: clean (screened ...)" instead of "3 fields".
                    val value = attrValue(context, credential, key)
                    val on = selected[key] == true
                    // Each claim is a tinted rounded cell; a selected claim picks
                    // up the brand surface tint so the disclosure set reads at a
                    // glance (the iOS toggle row equivalent).
                    val cellBg = if (on) {
                        MaterialTheme.colorScheme.primary.tint(MaknoonColors.TintCellAlpha)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.md))
                            .background(cellBg)
                            .padding(end = Spacing.md),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Checkbox(
                            checked = on,
                            enabled = !required,
                            onCheckedChange = { v -> if (!required) selected[key] = v },
                        )
                        Column(Modifier.weight(1f).padding(top = Spacing.md, bottom = Spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                if (required) {
                                    Spacer(Modifier.width(Spacing.xs))
                                    // Orange "required" pill (iOS verifier-pinned tag).
                                    Text(
                                        stringResource(R.string.present_required),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaknoonColors.warning,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(MaknoonColors.warning.tint(MaknoonColors.TintPillAlpha))
                                            .padding(horizontal = Spacing.sm, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Text(
                    stringResource(R.string.present_attributes_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Build button (non-compact: the generic credential present flow).
        if (!compact) {
            Button(
                onClick = {
                    scope.launch {
                        building = true
                        buildError = null
                        dropEnvelope = null
                        dropError = null
                        callbackStatus = null
                        callbackBody = null
                        callbackError = null
                        copied = false
                        try {
                            val challenge: String
                            val verifierDidForMsg: String
                            if (pendingRequest != null) {
                                challenge = pendingRequest.challenge
                                verifierDidForMsg = pendingRequest.verifierDid
                            } else {
                                challenge = "0x" + PresentationBuilder.selfNonceHex()
                                verifierDidForMsg = PresentationBuilder.OPEN_VERIFIER_DID
                            }
                            val p = withContext(Dispatchers.Default) {
                                PresentationBuilder.build(
                                    credential = credential,
                                    selectedClaims = selectedKeys.toSet(),
                                    challenge = challenge,
                                    verifierDid = verifierDidForMsg,
                                    pendingRequest = pendingRequest,
                                    sandwich = sandwich,
                                )
                            }
                            built = BuiltShare(p, p.toJson().toString(2))
                        } catch (e: Exception) {
                            buildError = e.message ?: e.toString()
                        } finally {
                            building = false
                        }
                    }
                },
                // Zero-attribute presentations are allowed (mirrors iOS): a holder
                // can prove control + anchor without disclosing any claim.
                enabled = !building,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (building) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (built != null) stringResource(R.string.present_rebuild_qr) else stringResource(R.string.present_build_qr))
            }
            Text(
                stringResource(R.string.present_build_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        buildError?.let {
            Text(stringResource(R.string.present_build_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // Share actions (non-compact). The compact passport flow uses the
        // Build Online QR button above + the advanced section below instead.
        if (!compact) built?.let { b ->
            SoftCard {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.present_share), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    // Direct callback (when the scanned request asked for one).
                    val cb = pendingRequest?.response?.takeIf { it.mode == "callback" }?.callbackUrl
                    if (!cb.isNullOrEmpty()) {
                        ShareRow(
                            icon = Icons.Filled.Send,
                            title = stringResource(R.string.present_send_to_verifier, pendingRequest.verifierName ?: stringResource(R.string.present_verifier_lower)),
                            detail = stringResource(R.string.present_send_callback_detail),
                        ) {
                            gateThen {
                                postToUrl(b.presentation, cb,
                                    onResult = { s, body -> callbackStatus = s; callbackBody = body; callbackError = null },
                                    onError = { callbackError = it; callbackStatus = null; callbackBody = null },
                                )
                                record(b.presentation, "callback")
                            }
                        }
                    }

                    ShareRow(
                        icon = Icons.Filled.Link,
                        title = stringResource(R.string.present_share_secure_link),
                        detail = stringResource(R.string.present_share_secure_link_detail),
                    ) {
                        gateThen {
                            dropError = null
                            try {
                                if (!com.elabify.app.maknoon.ui.settings.RelaySettings.enabled) {
                                    error("The presentation relay is turned off in Settings, Identity. Turn it on to share over a network link, or use the privacy QR.")
                                }
                                val env = PresentationDrop(dropHost).upload(b.presentation)
                                dropEnvelope = env
                                record(b.presentation, "drop")
                            } catch (e: Exception) {
                                dropError = e.message ?: e.toString()
                            }
                        }
                    }

                    ShareRow(
                        icon = Icons.Filled.ContentCopy,
                        title = stringResource(R.string.present_copy_presentation),
                        detail = stringResource(R.string.present_copy_presentation_detail),
                    ) {
                        gateThen {
                            clipboard.setText(AnnotatedString(b.jsonText))
                            copied = true
                            record(b.presentation, "copy")
                        }
                    }

                    ShareRow(
                        icon = Icons.Filled.Send,
                        title = stringResource(R.string.present_send_to_url),
                        detail = pendingRequest?.response?.callbackUrl ?: stringResource(R.string.present_send_to_url_detail),
                    ) {
                        urlDraft = pendingRequest?.response?.callbackUrl ?: urlDraft
                        urlPromptOpen = true
                    }

                    Text(
                        stringResource(R.string.present_signed_ready_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (copied) {
                Text(
                    stringResource(R.string.present_copied),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Drop QR result.
            dropEnvelope?.let { env -> DropQrCard(env) }
            dropError?.let {
                Text(stringResource(R.string.present_link_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // Callback result.
            callbackStatus?.let { status ->
                SoftCard {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(stringResource(R.string.present_verifier_response), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.present_http_status, status.toString()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (status in 200..299) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        callbackBody?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.take(400), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            callbackError?.let {
                Text(stringResource(R.string.present_callback_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Compact passport: advanced section (Build Offline QR + Copy). No
        // Send-to-URL; the online QR is the primary Build button above.
        if (compact) {
            SoftCard {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.present_advanced), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    // Entry was biometric-gated; these don't re-verify.
                    ShareRow(
                        icon = Icons.Filled.QrCode2,
                        title = stringResource(R.string.present_build_offline_qr),
                        detail = stringResource(R.string.present_build_offline_qr_detail),
                    ) {
                        scope.launch {
                            val b = buildNow() ?: return@launch
                            dropEnvelope = null
                            offlineFrames = LocalFrames.chunks(b.presentation.toJson().toString())
                            record(b.presentation, "offline-qr")
                        }
                    }
                    ShareRow(
                        icon = Icons.Filled.ContentCopy,
                        title = stringResource(R.string.present_copy_presentation),
                        detail = stringResource(R.string.present_copy_presentation_detail),
                    ) {
                        scope.launch {
                            val b = buildNow() ?: return@launch
                            clipboard.setText(AnnotatedString(b.jsonText))
                            copied = true
                            record(b.presentation, "copy")
                        }
                    }
                }
            }
            if (copied) {
                Text(stringResource(R.string.present_copied), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // Offline QR opens in its own full-screen sheet, like the online QR.
            offlineFrames?.let { frames ->
                QrSheet(
                    title = stringResource(R.string.present_offline_qr),
                    onDone = { offlineFrames = null; onDone() },
                ) {
                    RotatingFramesCard(frames)
                }
            }
        }
    }

    if (urlPromptOpen) {
        AlertDialog(
            onDismissRequest = { urlPromptOpen = false },
            title = { Text(stringResource(R.string.present_send_to_url)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.present_send_to_url_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextField(
                        value = urlDraft,
                        onValueChange = { urlDraft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.present_url_placeholder)) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = urlDraft.trim()
                    urlPromptOpen = false
                    val b = built ?: return@TextButton
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        callbackError = "Invalid URL, expected http(s)://..."
                        return@TextButton
                    }
                    gateThen {
                        postToUrl(b.presentation, url,
                            onResult = { s, body -> callbackStatus = s; callbackBody = body; callbackError = null },
                            onError = { callbackError = it; callbackStatus = null; callbackBody = null },
                        )
                        record(b.presentation, "callback:" + (hostOf(url) ?: "url"))
                    }
                }) { Text(stringResource(R.string.present_send)) }
            },
            dismissButton = { TextButton(onClick = { urlPromptOpen = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

// MARK: -- soft card

/// A card with the brand soft-shadow elevation + the 18 dp wallet-card radius,
/// shared by the grouped sections so they read consistently with the rest of
/// the present surface.
@Composable
private fun SoftCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) { content() }
}

// MARK: -- drop QR card

@Composable
private fun DropQrCard(env: DropEnvelope) {
    SoftCard {
        Column(
            Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.present_verifier_scans_this), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            QrCode(
                content = env.toJsonString(),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(Radii.md))
                    .padding(Spacing.lg),
                sizePx = 720,
            )
            Text(stringResource(R.string.present_drop_id), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(env.dropId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            env.expiresAt?.let {
                Text(stringResource(R.string.present_expires_at, formatDateUtc(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.present_drop_scan_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Full-screen QR sheet (iOS DropQrSheet parity): the online / offline QR opens
// in its own screen. "Done" returns all the way to the passport via onDone.
@Composable
private fun QrSheet(title: String, onDone: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Edge-to-edge: inset content from the status + nav bars
                    // (this Dialog sits outside the tab Scaffold). Surface
                    // background stays full-bleed.
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                content()
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_done))
                }
            }
        }
    }
}

// Offline (no-network) multi-frame QR: rotates through the frame sequence; the
// verifier collects every frame and reassembles the presentation locally.
@Composable
private fun RotatingFramesCard(frames: List<LocalFrames.Frame>) {
    var index by remember(frames) { mutableStateOf(0) }
    var secondsPerFrame by remember { mutableStateOf(0.7f) }
    // Keep the screen awake AND at full brightness for the whole transmission so
    // the peer scanner can read every dense frame without the display dimming or
    // locking mid-sequence (items 10 + 11).
    com.elabify.app.maknoon.ui.MaxBrightness()
    val keepAwakeView = LocalView.current
    DisposableEffect(Unit) {
        keepAwakeView.keepScreenOn = true
        onDispose { keepAwakeView.keepScreenOn = false }
    }
    LaunchedEffect(frames, secondsPerFrame) {
        if (frames.size <= 1) return@LaunchedEffect
        while (true) {
            delay((secondsPerFrame * 1000).toLong())
            index = (index + 1) % frames.size
        }
    }
    // Render the CURRENT frame off the main thread (never pre-render the whole
    // sequence on the UI thread: a multi-frame presentation is dozens of 600px
    // ZXing encodes and would ANR). One encode per tick, on Default.
    val safeIndex = if (frames.isEmpty()) 0 else index % frames.size
    val bitmap by produceState<ImageBitmap?>(initialValue = null, frames, safeIndex) {
        value = if (frames.isEmpty()) {
            null
        } else {
            withContext(Dispatchers.Default) { qrBitmap(frames[safeIndex].toJsonString(), 600).asImageBitmap() }
        }
    }
    SoftCard {
        Column(
            // Minimal horizontal padding so the QR fills nearly the full phone
            // width: a larger code gives the peer scanner more camera pixels per
            // module, which is what makes the dense multi-frame frames decode
            // reliably (item 10).
            Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.present_offline_qr), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    // Keep a small white quiet zone (required for scanning) but
                    // otherwise fill the width.
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(Radii.md))
                        .padding(Spacing.sm),
                )
            } else {
                Box(Modifier.fillMaxWidth().padding(Spacing.lg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            Text(
                stringResource(R.string.present_offline_frame, (index + 1).toString(), frames.size.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (frames.size > 1) {
                Slider(value = secondsPerFrame, onValueChange = { secondsPerFrame = it }, valueRange = 0.3f..1.5f)
            }
            Text(
                stringResource(R.string.present_offline_qr_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: -- share row

@Composable
private fun ShareRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) { Text(stringResource(R.string.present_go)) }
    }
}

// MARK: -- network + helpers

/**
 * POST the raw signed Presentation to an arbitrary verifier URL (the iOS
 * OpenVerifierPost path). The response body is opaque; we surface the HTTP
 * status + body so the UI can show whatever the verifier returned. A non-2xx
 * status arrives as a NetworkException carrying status + body, so we report
 * that as a result rather than a hard error.
 */
private suspend fun postToUrl(
    presentation: Presentation,
    url: String,
    onResult: (status: Int, body: String) -> Unit,
    onError: (String) -> Unit,
) {
    try {
        val body = withContext(Dispatchers.IO) {
            MaknoonHttp().postJson(url, presentation.toJson().toString())
        }
        onResult(200, body)
    } catch (e: NetworkException) {
        // Verifier replied with a non-2xx: still a meaningful response.
        onResult(e.status, e.body)
    } catch (e: Exception) {
        onError(e.message ?: e.toString())
    }
}

private fun truncatedDid(s: String): String =
    if (s.length <= 36) s else s.take(18) + "..." + s.takeLast(12)

private fun hostOf(url: String): String? =
    runCatching { java.net.URI(url).host }.getOrNull()
