// Scan a verifier's request QR (Android port of iOS ScanVerifierSheet.swift).
//
// After the camera reads a payload, the sheet:
//   1. Decodes + cryptographically validates the request via
//      VerifierRequestValidator. Both trust tiers are accepted; the UI surfaces
//      the tier as a coloured badge (green = registered, orange = self-signed,
//      red = unverified / rejected).
//   2. Filters the held credentials with the verifier's filter (MatchMaknoon).
//      If at least one matches, it lists them and the user picks one.
//   3. Shows a single Approve / Reject confirm. Approve builds the signed
//      Presentation (PresentationBuilder) and POSTs it to the verifier's
//      callback URL. Reject closes.
//
// "No matching credential" is the terminal state when nothing matches.
//
// Out of scope on Android (ADR-0028): the Verify & Pay (CommerceRequest)
// redirect and the BLE / X-Wing transports. The unified-entry URL fetch here is
// the plain verifier request_uri path only; CommerceRequest detection is left
// to a separate Verify & Pay sheet.
//
// Stateless composable: all IO (validate, build, post) routes through the
// caller-supplied PresentScanActions so this file holds no store / sandwich /
// network singletons. GMS-free: camera + decode reuse MiniAppQrScanner.

package com.elabify.app.maknoon.ui.present

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.components.StatusDot
import com.elabify.app.maknoon.ui.components.StatusLevel
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.present.Presentation
import com.elabify.musnad.present.VerifierRequestValidator
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Caller-supplied actions. Keeps the composable stateless: the host wires its
// IdentitySandwich, credential store, registry host, and network here.
// ---------------------------------------------------------------------------

/** The IO surface the Scan Verifier flow needs from its host. */
interface PresentScanActions {
    /** All held credentials, parsed from CredentialEntity.credentialJson. */
    suspend fun heldCredentials(): List<CredentialEntity>

    /**
     * Validate a scanned payload (request_uri fetch + registry lookup happen
     * inside VerifierRequestValidator). Runs on an IO dispatcher. Null when the
     * payload is not structurally a VerifierRequest at all.
     */
    suspend fun validate(scanned: String): VerifierRequestValidator.Decision?

    /**
     * Build the signed Presentation disclosing the verifier's required claims
     * from [credential], bound to the request's challenge + DID. Runs on IO.
     */
    suspend fun buildPresentation(
        credential: CredentialEntity,
        decision: VerifierRequestValidator.Decision,
    ): Presentation

    /**
     * POST the presentation JSON to the verifier callback. Returns the HTTP
     * status and a short body excerpt. Runs on IO. The host wires MaknoonHttp.
     */
    suspend fun postToCallback(presentation: Presentation, callbackUrl: String): PostOutcome
}

/** Outcome of POSTing a presentation to the verifier callback. */
data class PostOutcome(val status: Int, val bodyText: String)

// ---------------------------------------------------------------------------
// Phase model (mirrors iOS ScanVerifierSheet.Phase, minus Commerce / camera
// permission, which MiniAppQrScanner owns).
// ---------------------------------------------------------------------------

private sealed class ScanPhase {
    object Scanning : ScanPhase()
    object Validating : ScanPhase()
    data class Rejected(val reason: String) : ScanPhase()
    data class NoMatch(val decision: VerifierRequestValidator.Decision) : ScanPhase()
    data class Confirm(
        val decision: VerifierRequestValidator.Decision,
        val matches: List<CredentialEntity>,
    ) : ScanPhase()
    data class Sent(val verifierName: String) : ScanPhase()
}

@Composable
fun ScanVerifierSheet(
    actions: PresentScanActions,
    onClose: () -> Unit,
    /** Called when the scanned payload is a merchant Verify & Pay request (a
     *  CommerceRequest URL) rather than a plain verifier request. The host
     *  fetches the CommerceRequest and opens the Verify & Pay sheet. */
    onCommerce: (String) -> Unit = {},
) {
    val requestRejectedMsg = stringResource(R.string.present_request_rejected)
    val theVerifierLabel = stringResource(R.string.present_the_verifier)
    var phase by remember { mutableStateOf<ScanPhase>(ScanPhase.Scanning) }
    var selectedCredId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // One-shot guard so the continuous-less scanner does not re-fire while we
    // validate (MiniAppQrScanner with continuous=false stops itself, but the
    // composition can re-enter on recomposition before the phase flips).
    fun handleScanned(code: String) {
        if (phase !is ScanPhase.Scanning) return
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        phase = ScanPhase.Validating
        sendError = null
        scope.launch {
            val decision = actions.validate(trimmed)
            if (decision == null) {
                // A merchant Verify & Pay QR is a short URL to a server-hosted
                // CommerceRequest (verifier request + payment terms), NOT a plain
                // verifier request, so the validator returns null. Hand it to the
                // host, which fetches the CommerceRequest and opens Verify & Pay.
                if (looksLikeCommerceRequest(trimmed)) {
                    onCommerce(trimmed)
                } else {
                    phase = ScanPhase.Rejected("Scanned payload is not a valid verifier request.")
                }
                return@launch
            }
            if (!decision.isValid) {
                phase = ScanPhase.Rejected(decision.reason ?: requestRejectedMsg)
                return@launch
            }
            val held = actions.heldCredentials()
            val matches = MatchMaknoon.match(held, decision.request.filter)
            if (matches.isEmpty()) {
                phase = ScanPhase.NoMatch(decision)
            } else {
                selectedCredId = matches.first().cid
                phase = ScanPhase.Confirm(decision, matches)
            }
        }
    }

    fun approve(decision: VerifierRequestValidator.Decision, cred: CredentialEntity) {
        sending = true
        sendError = null
        scope.launch {
            try {
                val presentation = actions.buildPresentation(cred, decision)
                val callback = decision.request.response.callbackUrl
                if (callback.isNullOrEmpty()) {
                    sendError = "This verifier did not provide a delivery URL."
                    sending = false
                    return@launch
                }
                val outcome = actions.postToCallback(presentation, callback)
                if (outcome.status in 200..299) {
                    phase = ScanPhase.Sent(decision.request.verifierName ?: theVerifierLabel)
                } else {
                    sendError = "Verifier responded HTTP ${outcome.status}. " +
                        outcome.bodyText.take(200)
                }
                sending = false
            } catch (e: Exception) {
                sendError = e.message ?: e.toString()
                sending = false
            }
        }
    }

    // Edge-to-edge (Android 15 / SDK 35): this is a full-screen route with no
    // Scaffold, so inset the top past the status bar / cutout. The bottom nav
    // inset is already consumed by MainTabs, so apply status bars only (full
    // systemBars would double-pad the bottom).
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        when (val p = phase) {
            is ScanPhase.Scanning -> ScannerView(
                prompt = stringResource(R.string.present_scan_verifier_prompt),
                onCode = { handleScanned(it) },
                onClose = onClose,
            )
            is ScanPhase.Validating -> ProgressView(stringResource(R.string.present_validating_signature))
            is ScanPhase.Rejected -> RejectedView(
                reason = p.reason,
                onScanAgain = { phase = ScanPhase.Scanning },
            )
            is ScanPhase.NoMatch -> NoMatchView(
                decision = p.decision,
                verifierName = p.decision.request.verifierName ?: stringResource(R.string.present_verifier),
                onScanAgain = { phase = ScanPhase.Scanning },
            )
            is ScanPhase.Confirm -> {
                val selected = p.matches.firstOrNull { it.cid == selectedCredId } ?: p.matches.first()
                ConfirmView(
                    decision = p.decision,
                    matches = p.matches,
                    selected = selected,
                    sending = sending,
                    sendError = sendError,
                    onSelect = { selectedCredId = it },
                    onApprove = { approve(p.decision, selected) },
                    onReject = onClose,
                )
            }
            is ScanPhase.Sent -> SentView(verifierName = p.verifierName, onClose = onClose)
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-views.
// ---------------------------------------------------------------------------

@Composable
private fun ScannerView(prompt: String, onCode: (String) -> Unit, onClose: () -> Unit) {
    // Center the square preview + prompt vertically (iOS uses Spacer above/below
    // the reticle). Without CenterVertically the preview sat flush to the top.
    var noQr by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        MiniAppQrScanner(
            continuous = false,
            onCode = onCode,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp)),
        )
        Text(
            prompt,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        if (noQr) {
            Text(
                stringResource(R.string.present_no_qr_in_photo),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        // Parity with iOS: pick a QR from the photo library, or cancel.
        QrPhotoPickerButton(
            onCode = { noQr = false; onCode(it) },
            onNoQr = { noQr = true },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}

@Composable
private fun ProgressView(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RejectedView(reason: String, onScanAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.present_request_rejected), style = MaterialTheme.typography.titleMedium, color = TrustRed)
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.common_scan_again)) }
    }
}

@Composable
private fun NoMatchView(
    decision: VerifierRequestValidator.Decision,
    verifierName: String,
    onScanAgain: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrustBadge(
            tier = decision.tier,
            verifierName = verifierName,
        )
        Text(stringResource(R.string.present_no_matching_credential), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.present_no_matching_credential_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.common_scan_again)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmView(
    decision: VerifierRequestValidator.Decision,
    matches: List<CredentialEntity>,
    selected: CredentialEntity,
    sending: Boolean,
    sendError: String?,
    onSelect: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val request = decision.request
    val claims = request.filter.requiredClaims
    val parsed = remember(selected.cid) { runCatching { selected.parsed() }.getOrNull() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Verifier.
        SectionHeader(stringResource(R.string.present_verifier))
        TrustBadge(tier = decision.tier, verifierName = request.verifierName ?: stringResource(R.string.present_verifier))
        Text(
            verifierDidDisplay(request.verifierDid),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        // Matching credential picker.
        SectionHeader(stringResource(R.string.present_your_matching_credential))
        Text(
            schemaLabel(LocalContext.current, selected.schema),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            shortIssuerName(selected.issuerDid),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val nick = selected.nickname
        if (!nick.isNullOrEmpty()) {
            Text(
                nick,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (matches.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                matches.forEach { c ->
                    FilterChip(
                        selected = c.cid == selected.cid,
                        onClick = { onSelect(c.cid) },
                        label = { Text(credLabel(LocalContext.current, c)) },
                    )
                }
            }
        }

        HorizontalDivider()

        // What will be shared.
        SectionHeader(stringResource(R.string.present_you_will_share))
        if (claims.isEmpty()) {
            Text(
                stringResource(R.string.present_no_attributes_requested),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            claims.forEach { key ->
                // A tinted cell per disclosed attribute, led by a green status
                // dot to signal it will be shared (the iOS disclosure row).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(level = StatusLevel.OK)
                    Text(key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        attrValue(LocalContext.current, parsed, key),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.present_approve_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (sendError != null) {
            Text(sendError, style = MaterialTheme.typography.bodyMedium, color = TrustRed)
        }

        HorizontalDivider()

        Button(
            onClick = onApprove,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (sending) stringResource(R.string.present_sending) else stringResource(R.string.present_approve_share))
        }
        TextButton(
            onClick = onReject,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(contentColor = TrustRed),
        ) {
            Text(stringResource(R.string.present_reject))
        }
    }
}

@Composable
private fun SentView(verifierName: String, onClose: () -> Unit) {
    // Auto-dismiss after a short beat, matching iOS (1.4s).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_400)
        onClose()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.present_shared_with, verifierName), style = MaterialTheme.typography.titleMedium, color = TrustGreen)
        Text(
            stringResource(R.string.present_attributes_sent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.common_done)) }
    }
}

// ---------------------------------------------------------------------------
// Trust badge (green = registered, orange = self-signed, red = unknown).
// ---------------------------------------------------------------------------

@Composable
private fun TrustBadge(tier: VerifierRequestValidator.TrustTier, verifierName: String) {
    // The trust tier as a colored Banner: green Registered, orange Self-signed,
    // red Unverified. Mirrors the iOS coloured trust badge.
    val (variant, label, icon) = when (tier) {
        is VerifierRequestValidator.TrustTier.Registered ->
            Triple(BannerVariant.SUCCESS, stringResource(R.string.present_registered_verifier), Icons.Filled.GppGood)
        is VerifierRequestValidator.TrustTier.SelfSigned ->
            Triple(BannerVariant.WARNING, stringResource(R.string.present_self_signed_verifier), Icons.Filled.GppMaybe)
        is VerifierRequestValidator.TrustTier.Unknown ->
            Triple(BannerVariant.ERROR, stringResource(R.string.present_unverified), Icons.Filled.GppBad)
    }
    Banner(
        title = verifierName,
        variant = variant,
        icon = icon,
        body = label,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---------------------------------------------------------------------------
// Local display helpers (no on-device SchemaPalette / MatchingEngine yet, so
// these mirror the iOS helpers verbatim).
// ---------------------------------------------------------------------------

/**
 * A merchant Verify & Pay QR is a short https URL whose path hosts a
 * CommerceRequest (see iOS MiniAppCommerceSheet: /v1/commerce-request/<id>).
 * Used only to give an honest "not supported yet" message instead of the
 * generic verifier-request rejection. Heuristic, no network fetch.
 */
private fun looksLikeCommerceRequest(s: String): Boolean {
    val t = s.trim()
    val isUrl = t.startsWith("https://", ignoreCase = true) ||
        t.startsWith("http://", ignoreCase = true)
    return isUrl && t.contains("/commerce-request/", ignoreCase = true)
}

private fun credLabel(context: Context, c: CredentialEntity): String {
    val base = schemaLabel(context, c.schema)
    return if (!c.nickname.isNullOrEmpty()) "$base - ${c.nickname}" else base
}

private fun verifierDidDisplay(did: String): String {
    if (did.length <= 42) return did
    return did.take(20) + "..." + did.takeLast(16)
}

internal val TrustGreen = Color(0xFF1B873F)
internal val TrustOrange = Color(0xFFD97706)
internal val TrustRed = Color(0xFFB91C1C)
