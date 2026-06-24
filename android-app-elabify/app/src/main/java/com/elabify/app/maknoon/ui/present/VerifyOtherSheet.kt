// In-person Verify Other flow (Android port of iOS VerifyOtherSheet.swift).
// The holder acts as a verifier for a person in front of them: scan whatever
// the other person shows, validate locally, display the result. One-shot,
// nothing is saved.
//
// Accepted QR payloads (v1, ADR-0028 transport scope):
//   * Badge (elabify-badge-1): a no-PII credential reference (issuer + schema +
//     cid + anchors). Inspected as metadata only. Cryptographic proof needs an
//     online lookup; surfaced as informational.
//   * DropEnvelope: a one-shot drop pointer. Fetched from the drop host and run
//     through PresentationVerifier.verifyOffline.
//   * Raw Presentation JSON: same offline verification as the dropped variant.
//
// Out of scope on Android: BLE engagement and multi-frame / rotating QR (full
// ~32KB presentations go over the drop, per the QR-transport decision). A
// rotating-QR payload simply fails to parse here and lands in the rejected
// state.
//
// "Verify" here is intentionally limited: the verifier sees whatever the other
// person chose to disclose. No filter spec, no request. Stateless composable:
// the drop fetch + offline verify route through VerifyOtherActions.

package com.elabify.app.maknoon.ui.present

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.iddocument.isProductionChain
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.components.StatusDot
import com.elabify.app.maknoon.ui.components.StatusLevel
import com.elabify.app.maknoon.ui.components.color
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.musnad.present.DropEnvelope
import com.elabify.musnad.present.LocalCheckResult
import com.elabify.musnad.present.LocalVerdict
import com.elabify.musnad.present.Presentation
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------
// Caller-supplied actions (keeps the composable stateless).
// ---------------------------------------------------------------------------

/** Offline verdict bundle returned by PresentationVerifier.verifyOffline. */
typealias VerdictBundle = com.elabify.musnad.present.LocalCheckResultBundle

/** The IO surface the Verify Other flow needs from its host. */
interface VerifyOtherActions {
    /** GET /v1/drop/{dropId} on the drop host, returning the dropped Presentation. */
    suspend fun fetchDrop(dropId: String): Presentation

    /** Run PresentationVerifier.verifyOffline on a presentation. */
    fun verifyOffline(presentation: Presentation): VerdictBundle
}

// ---------------------------------------------------------------------------
// Parsed payload shapes (badge is UI-only metadata, parsed inline; the SDK has
// no Kotlin BadgePayload type and a badge carries no signing input).
// ---------------------------------------------------------------------------

private data class BadgeAnchorView(val chain: String, val batchTxHash: String)

private data class BadgeView(
    val iss: String,
    val schema: String,
    val iat: Long,
    val exp: Long?,
    val anchors: List<BadgeAnchorView>,
)

private sealed class VerifyPhase {
    object Scanning : VerifyPhase()
    object Fetching : VerifyPhase()
    // Collecting an offline multi-frame (elabify-frames-1) transmission.
    data class Collecting(val received: Int, val total: Int) : VerifyPhase()
    data class Badge(val badge: BadgeView) : VerifyPhase()
    data class Verdict(val presentation: Presentation, val bundle: VerdictBundle) : VerifyPhase()
    data class Rejected(val reason: String) : VerifyPhase()
}

@Composable
fun VerifyOtherSheet(
    actions: VerifyOtherActions,
    onClose: () -> Unit,
) {
    var phase by remember { mutableStateOf<VerifyPhase>(VerifyPhase.Scanning) }
    val scope = rememberCoroutineScope()

    // Offline multi-frame accumulator (elabify-frames-1). Persists across the
    // continuous scanner's repeated callbacks while a transmission is collected.
    val frameChunks = remember { mutableStateMapOf<Int, String>() }
    var frameId by remember { mutableStateOf<String?>(null) }
    var frameTotal by remember { mutableStateOf(0) }
    fun resetFrames() {
        frameChunks.clear(); frameId = null; frameTotal = 0
    }

    fun handle(raw: String) {
        // Keep handling during Collecting so the continuous scanner can gather
        // every frame of an offline transmission.
        if (phase !is VerifyPhase.Scanning && phase !is VerifyPhase.Collecting) return
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return

        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
        if (obj == null) {
            phase = VerifyPhase.Rejected(
                "Unrecognised QR payload (badge, drop envelope, or raw presentation expected).",
            )
            return
        }

        // Badge (small no-PII QR).
        if (obj.optStr("v") == "elabify-badge-1") {
            phase = VerifyPhase.Badge(parseBadge(obj))
            return
        }

        // Offline multi-frame QR (elabify-frames-1): collect every frame, then
        // reassemble the base64 chunks into the Presentation JSON.
        if (obj.optStr("v") == LocalFrames.VERSION) {
            val id = obj.optString("id")
            val total = obj.optInt("total")
            if (frameId == null) {
                frameId = id
                frameTotal = total
            }
            if (id != frameId) return // ignore a different concurrent transmission
            frameChunks[obj.optInt("idx")] = obj.optString("data")
            if (frameTotal > 0 && frameChunks.size >= frameTotal) {
                val b64 = (0 until frameTotal).joinToString("") { frameChunks[it].orEmpty() }
                val json = runCatching {
                    String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull()
                val pres = json?.let { runCatching { Presentation.fromJson(JSONObject(it)) }.getOrNull() }
                resetFrames()
                if (pres != null) {
                    phase = VerifyPhase.Fetching
                    scope.launch { phase = VerifyPhase.Verdict(pres, actions.verifyOffline(pres)) }
                } else {
                    phase = VerifyPhase.Rejected("Offline QR frames did not reassemble into a presentation.")
                }
            } else {
                phase = VerifyPhase.Collecting(frameChunks.size, frameTotal)
            }
            return
        }

        // Drop envelope: { v: 1, dropId, expiresAt? }.
        if (obj.has("dropId") && !obj.optString("dropId").isNullOrEmpty()) {
            val env = runCatching { DropEnvelope.fromJson(obj) }.getOrNull()
            if (env != null && env.dropId.isNotEmpty()) {
                phase = VerifyPhase.Fetching
                scope.launch {
                    try {
                        val p = actions.fetchDrop(env.dropId)
                        phase = VerifyPhase.Verdict(p, actions.verifyOffline(p))
                    } catch (e: Exception) {
                        phase = VerifyPhase.Rejected("Could not fetch drop: ${e.message ?: e}")
                    }
                }
                return
            }
        }

        // Raw Presentation (rare; only fits if the holder built a large QR).
        if (obj.has("header") && obj.has("headerSig") && obj.has("challengeSig")) {
            val p = runCatching { Presentation.fromJson(obj) }.getOrNull()
            if (p != null) {
                phase = VerifyPhase.Verdict(p, actions.verifyOffline(p))
                return
            }
        }

        phase = VerifyPhase.Rejected(
            "Unrecognised QR payload (badge, drop envelope, or raw presentation expected).",
        )
    }

    // Edge-to-edge (Android 15 / SDK 35): full-screen route with no Scaffold, so
    // inset the top past the status bar / cutout. The bottom nav inset is already
    // consumed by MainTabs, so apply status bars only.
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        when (val p = phase) {
            is VerifyPhase.Scanning -> ScanningView(onCode = { handle(it) }, onClose = onClose)
            is VerifyPhase.Collecting -> ScanningView(
                onCode = { handle(it) },
                onClose = { resetFrames(); onClose() },
                status = stringResource(R.string.present_offline_frame, p.received, p.total),
            )
            is VerifyPhase.Fetching -> VerifyProgress(stringResource(R.string.present_fetching_presentation))
            is VerifyPhase.Badge -> BadgeViewBody(p.badge, onScanAnother = { resetFrames(); phase = VerifyPhase.Scanning })
            is VerifyPhase.Verdict -> VerdictBody(
                presentation = p.presentation,
                bundle = p.bundle,
                onScanAnother = { resetFrames(); phase = VerifyPhase.Scanning },
            )
            is VerifyPhase.Rejected -> VerifyRejected(
                reason = p.reason,
                onScanAgain = { resetFrames(); phase = VerifyPhase.Scanning },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-views.
// ---------------------------------------------------------------------------

@Composable
private fun ScanningView(onCode: (String) -> Unit, onClose: () -> Unit, status: String? = null) {
    var noQr by remember { mutableStateOf(false) }
    Column(
        // Center the square preview + prompt vertically (mirrors ScanVerifierSheet);
        // without CenterVertically the preview sat flush to the top (too high up).
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        // Continuous so a drop / presentation that needs a couple of frames to
        // focus still decodes; the phase guard above dedups repeated hits.
        MiniAppQrScanner(
            continuous = true,
            onCode = onCode,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp)),
        )
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(R.string.present_scan_badge_drop_presentation),
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
private fun VerifyProgress(text: String) {
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
private fun BadgeViewBody(b: BadgeView, onScanAnother: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // Blue info Banner: a badge shares no PII (the iOS info.circle note).
        Banner(
            title = stringResource(R.string.present_badge),
            variant = BannerVariant.INFO,
            icon = Icons.Filled.Info,
            body = stringResource(R.string.present_badge_no_pii_body),
            modifier = Modifier.fillMaxWidth(),
        )
        SectionHeader(stringResource(R.string.present_what_this_shows))
        Kv(stringResource(R.string.present_issuer), shortIssuerName(b.iss))
        Kv(stringResource(R.string.present_type), schemaLabel(b.schema))
        Kv(stringResource(R.string.present_issued), formatDate(b.iat))
        b.exp?.let { Kv(stringResource(R.string.present_expires), formatDate(it)) }
        // Production chains only; testnet anchors are hidden in the client (ADR-0040).
        b.anchors.filter { isProductionChain(it.chain) }.forEach { a ->
            Kv(stringResource(R.string.present_anchor_dash, caip2Label(a.chain)), shortHex(a.batchTxHash))
        }
        HorizontalDivider()
        OutlinedButton(onClick = onScanAnother) { Text(stringResource(R.string.present_scan_another)) }
    }
}

@Composable
private fun VerdictBody(
    presentation: Presentation,
    bundle: VerdictBundle,
    onScanAnother: () -> Unit,
) {
    val (variant, title, icon) = when (bundle.decision) {
        LocalVerdict.SELF_ATTESTED ->
            Triple(BannerVariant.WARNING, stringResource(R.string.present_verdict_self_attested), Icons.Filled.GppMaybe)
        LocalVerdict.DENY ->
            Triple(BannerVariant.ERROR, stringResource(R.string.present_verdict_deny), Icons.Filled.GppBad)
        LocalVerdict.GRANT ->
            Triple(BannerVariant.SUCCESS, stringResource(R.string.present_verdict_locally_valid), Icons.Filled.GppGood)
        LocalVerdict.UNVERIFIED ->
            Triple(BannerVariant.SUCCESS, stringResource(R.string.present_verdict_locally_valid), Icons.Filled.GppGood)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // The local verdict as a colored Banner (green valid, orange
        // self-issued, red DENY), with the verifier summary as the body.
        Banner(
            title = title,
            variant = variant,
            icon = icon,
            body = bundle.summary,
            modifier = Modifier.fillMaxWidth(),
        )

        if (bundle.disclosed.isNotEmpty()) {
            HorizontalDivider()
            SectionHeader(stringResource(R.string.present_disclosed_claims))
            bundle.disclosed.keys.sorted().forEach { k ->
                Kv(k, bundle.disclosed[k]?.displayText() ?: "-")
            }
        }

        HorizontalDivider()
        SectionHeader(stringResource(R.string.present_credential))
        Kv(stringResource(R.string.present_issuer), presentation.header.iss)
        Kv(stringResource(R.string.present_schema), schemaLabel(presentation.header.schema))
        Kv(stringResource(R.string.present_cid), presentation.header.cid)

        HorizontalDivider()
        SectionHeader(stringResource(R.string.present_local_check_matrix))
        val c = bundle.checks
        CheckRow("headerSigValid", c.headerSigValid)
        CheckRow("merkleValid", c.merkleValid)
        CheckRow("challengeSigValid", c.challengeSigValid)
        CheckRow("timestampValid", c.timestampValid)
        CheckRow("expiryValid", c.expiryValid)
        CheckRow("verifierRequestValid", c.verifierRequestValid)
        CheckRow("issuerRegistered", c.issuerRegistered)
        CheckRow("credentialNotRevoked", c.credentialNotRevoked)
        CheckRow("rootCurrent", c.rootCurrent)

        HorizontalDivider()
        OutlinedButton(onClick = onScanAnother) { Text(stringResource(R.string.present_scan_another)) }
    }
}

@Composable
private fun VerifyRejected(reason: String, onScanAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.present_could_not_verify), style = MaterialTheme.typography.titleMedium, color = TrustRed)
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.common_scan_again)) }
    }
}

// ---------------------------------------------------------------------------
// Check-matrix row + small layout helpers.
// ---------------------------------------------------------------------------

@Composable
private fun CheckRow(name: String, result: LocalCheckResult) {
    // Each check is a row in a tinted cell, led by a traffic-light StatusDot:
    // green pass, red fail, yellow unverified / not-applicable. The verbose
    // mark + reason sit alongside so an auditor sees both.
    val (level, mark, suffix) = when (result) {
        is LocalCheckResult.Pass -> Triple(StatusLevel.OK, "PASS", null)
        is LocalCheckResult.Fail -> Triple(StatusLevel.EXPIRED, "FAIL", result.reason)
        is LocalCheckResult.Unverified -> Triple(StatusLevel.WARN, "UNVERIFIED", result.reason)
        is LocalCheckResult.NotApplicable -> Triple(StatusLevel.WARN, "N/A", result.reason)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(level = level)
            Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(mark, style = MaterialTheme.typography.labelSmall, color = level.color(), fontWeight = FontWeight.SemiBold)
        }
        if (suffix != null) {
            Text(
                suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2)
    }
}

// ---------------------------------------------------------------------------
// Badge parsing + date formatting (UI-only).
// ---------------------------------------------------------------------------

private fun parseBadge(o: JSONObject): BadgeView {
    val anchorsArr = o.optJSONArray("anchors")
    val anchors = when {
        anchorsArr != null -> (0 until anchorsArr.length()).mapNotNull { i ->
            anchorsArr.optJSONObject(i)?.let { a ->
                BadgeAnchorView(a.optString("chain", ""), a.optString("batchTxHash", ""))
            }
        }
        // Back-compat: a single legacy `anchor` field.
        o.optJSONObject("anchor") != null -> {
            val a = o.getJSONObject("anchor")
            listOf(BadgeAnchorView(a.optString("chain", ""), a.optString("batchTxHash", "")))
        }
        else -> emptyList()
    }
    return BadgeView(
        iss = o.optString("iss", ""),
        schema = o.optString("schema", ""),
        iat = o.optLong("iat", 0L),
        exp = if (o.has("exp") && !o.isNull("exp")) o.optLong("exp") else null,
        anchors = anchors,
    )
}

private fun formatDate(unix: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unix * 1000L))
