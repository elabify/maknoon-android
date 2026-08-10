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

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.musnad.present.HavidResult
import com.elabify.musnad.present.HavidState
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.LocalVerdict
import com.elabify.musnad.present.OnChainTier
import com.elabify.musnad.present.OnChainVerdict
import com.elabify.musnad.present.OnChainVerifier
import com.elabify.musnad.present.RegistryConfig
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

    /** Resolve the issuer's client-side HAVID cross-endorsement (ADR-0051): a
     *  local HTTPS + X.509 check of the issuer's org certificate against its DID. */
    suspend fun resolveHavid(presentation: Presentation): HavidResult

    /** HAVID for a badge reference (no headerSig): binds via the on-chain issuer
     *  key (from OnChainVerifier.verifyReference) instead of a credential signature. */
    suspend fun resolveHavidReference(did: String, issuerPubkey: ByteArray?): HavidResult

    /** Effective RPC for a CAIP-2 chain from the app's Ethereum settings (honoring
     *  per-network overrides), or null if the app doesn't support that chain. Used
     *  for the identity chain (Sepolia) and the credential's anchor chain (e.g.
     *  Base Sepolia), so anchoring is not limited to a single chain. */
    fun chainRpcUrl(caip2: String): String?
}

// ---------------------------------------------------------------------------
// Parsed payload shapes (badge is UI-only metadata, parsed inline; the SDK has
// no Kotlin BadgePayload type and a badge carries no signing input).
// ---------------------------------------------------------------------------

private data class BadgeAnchorView(
    val chain: String,
    val batchTxHash: String,
    val batchRoot: String,
    val registry: String?,
)

private data class BadgeView(
    val iss: String,
    val cid: String,
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
                status = stringResource(R.string.present_offline_frame, p.received.toString(), p.total.toString()),
            )
            is VerifyPhase.Fetching -> VerifyProgress(stringResource(R.string.present_fetching_presentation))
            is VerifyPhase.Badge -> BadgeViewBody(p.badge, actions, onScanAnother = { resetFrames(); phase = VerifyPhase.Scanning })
            is VerifyPhase.Verdict -> VerdictBody(
                presentation = p.presentation,
                bundle = p.bundle,
                actions = actions,
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
private fun BadgeViewBody(b: BadgeView, actions: VerifyOtherActions, onScanAnother: () -> Unit) {
    // A badge carries no header, so the on-chain pass is the "reference" variant:
    // issuerRegistered + notRevoked + rootCurrent + HAVID (bound via the on-chain
    // issuer key), everything except the header signature.
    var onChain by remember(b.cid) { mutableStateOf<OnChainVerdict?>(null) }
    var havid by remember(b.cid) { mutableStateOf<HavidResult?>(null) }
    var running by remember(b.cid) { mutableStateOf(false) }
    LaunchedEffect(b.cid) {
        running = true
        val identityRpc = actions.chainRpcUrl("eip155:11155111")
            ?: "https://ethereum-sepolia-rpc.publicnode.com"
        val anchor = b.anchors.firstOrNull { it.chain == "eip155:11155111" && actions.chainRpcUrl(it.chain) != null }
            ?: b.anchors.firstOrNull { actions.chainRpcUrl(it.chain) != null }
        val ref = OnChainVerifier.verifyReference(
            RegistryConfig.sepolia(identityRpc),
            b.iss, b.cid, b.iat, null,
            anchor?.batchRoot,
            anchor?.let { actions.chainRpcUrl(it.chain) },
            anchor?.registry,
            anchor?.batchTxHash,
        )
        onChain = ref.verdict
        havid = runCatching { actions.resolveHavidReference(b.iss, ref.issuerPubkey) }.getOrNull()
        running = false
    }

    val oc = onChain
    val banner: VerdictBanner = run {
        val core = oc != null &&
            oc.issuerRegistered is OnChainTier.Pass &&
            oc.notRevoked is OnChainTier.Pass &&
            oc.rootCurrent is OnChainTier.Pass
        when {
            oc == null -> VerdictBanner(BannerVariant.INFO, "Checking on-chain…", "Confirming the reference against the chain.", Icons.Filled.GppMaybe)
            !oc.reachedChain -> VerdictBanner(BannerVariant.WARNING, "Reference (offline)", "Couldn't reach the chain to confirm this reference. Tap Scan another to retry.", Icons.Filled.GppMaybe)
            firstOnChainFailure(oc) != null -> VerdictBanner(BannerVariant.ERROR, "Verification failed", firstOnChainFailure(oc)!!, Icons.Filled.GppBad)
            core -> VerdictBanner(
                BannerVariant.SUCCESS,
                "Reference verified on-chain",
                buildString {
                    append("Registered issuer, not revoked, current root, confirmed on-chain.")
                    if (havid?.state == HavidState.CROSS_ENDORSED) append(" Issuer certificate matches its DID.")
                    append(" Full signature check needs the complete credential.")
                },
                Icons.Filled.GppGood,
            )
            else -> VerdictBanner(BannerVariant.WARNING, "Reference verified, with limits", onChainLimitsSummary(oc), Icons.Filled.GppMaybe)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Banner(
            title = banner.title,
            variant = banner.variant,
            icon = banner.icon,
            body = banner.body,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.present_badge_no_pii_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader(stringResource(R.string.present_what_this_shows))
        Kv(stringResource(R.string.present_issuer), shortIssuerName(b.iss))
        Kv(stringResource(R.string.present_type), schemaLabel(b.schema))
        Kv(stringResource(R.string.present_cid), b.cid)
        Kv(stringResource(R.string.present_issued), formatDate(b.iat))
        b.exp?.let { Kv(stringResource(R.string.present_expires), formatDate(it)) }
        b.anchors.forEach { a ->
            Kv(stringResource(R.string.present_anchor_dash, caip2Label(a.chain)), shortHex(a.batchTxHash))
        }

        ExpandableSection(stringResource(R.string.present_online_verification_on_chain), badgeOnchainSectionStatus(oc)) {
            when {
                running && oc == null -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.present_checking_on_chain), style = MaterialTheme.typography.bodyMedium)
                }
                oc == null -> Unit
                !oc.reachedChain -> Text(
                    stringResource(R.string.present_couldnt_reach_the_chain),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    OnChainRow(stringResource(R.string.present_issuer_registered), oc.issuerRegistered)
                    OnChainRow(stringResource(R.string.present_not_revoked), oc.notRevoked)
                    OnChainRow(stringResource(R.string.present_root_current), oc.rootCurrent)
                    oc.cscaProvenance?.let { OnChainRow(stringResource(R.string.present_passport_csca_provenance), it) }
                }
            }
            Text(
                stringResource(R.string.present_checks_talk_directly_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ExpandableSection(stringResource(R.string.present_organisation_identity_havid), havidSectionStatus(havid)) {
            HavidRow(havid)
            Text(
                stringResource(R.string.present_confirms_the_issuers_real_world),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        OutlinedButton(onClick = onScanAnother) { Text(stringResource(R.string.present_scan_another)) }
    }
}

/** On-chain section glyph for a badge: core issuer-assurance (no headerSig). */
private fun badgeOnchainSectionStatus(oc: OnChainVerdict?): SectionStatus {
    if (oc == null) return SectionStatus.PENDING
    if (!oc.reachedChain) return SectionStatus.WARN
    val core = listOf(oc.issuerRegistered, oc.notRevoked, oc.rootCurrent)
    if (core.any { it is OnChainTier.Fail }) return SectionStatus.FAIL
    return if (core.all { it is OnChainTier.Pass }) SectionStatus.PASS else SectionStatus.WARN
}

@Composable
private fun VerdictBody(
    presentation: Presentation,
    bundle: VerdictBundle,
    actions: VerifyOtherActions,
    onScanAnother: () -> Unit,
) {
    // Holder-independent on-chain pass + HAVID. This app IS the online verifier,
    // so the banner reflects the full result (not just the offline crypto).
    var onChain by remember(presentation.header.cid) { mutableStateOf<OnChainVerdict?>(null) }
    var running by remember(presentation.header.cid) { mutableStateOf(false) }
    var havid by remember(presentation.header.cid) { mutableStateOf<HavidResult?>(null) }
    if (bundle.decision != LocalVerdict.SELF_ATTESTED) {
        LaunchedEffect(presentation.header.cid) {
            running = true
            val certId = (bundle.disclosed["cscaCertId"] as? JsonValue.Str)?.value
            // Identity checks on Sepolia; revocation + root on whichever chain the
            // credential is anchored on (e.g. Base Sepolia), using the anchor's own
            // RevocationRegistry address + that chain's RPC (ADR-0022 / ADR-0054).
            val identityRpc = actions.chainRpcUrl("eip155:11155111")
                ?: "https://ethereum-sepolia-rpc.publicnode.com"
            val anchors = presentation.anchor?.anchors.orEmpty()
            val anchor = anchors.firstOrNull { it.chain == "eip155:11155111" && actions.chainRpcUrl(it.chain) != null }
                ?: anchors.firstOrNull { actions.chainRpcUrl(it.chain) != null }
            onChain = OnChainVerifier.verify(
                RegistryConfig.sepolia(identityRpc),
                presentation.header,
                presentation.headerSig,
                certId,
                anchorBatchRoot = anchor?.batchRoot,
                anchorRPCURL = anchor?.let { actions.chainRpcUrl(it.chain) },
                anchorRevocationRegistry = anchor?.registry,
                anchorBatchTxHash = anchor?.batchTxHash,
            )
            running = false
            havid = runCatching { actions.resolveHavid(presentation) }.getOrNull()
        }
    }

    // Single combined verdict: offline crypto + on-chain + HAVID.
    val denyTitle = stringResource(R.string.present_verdict_deny)
    val banner: VerdictBanner = run {
        val oc = onChain
        when {
            bundle.decision == LocalVerdict.DENY ->
                VerdictBanner(BannerVariant.ERROR, denyTitle, bundle.summary, Icons.Filled.GppBad)
            bundle.decision == LocalVerdict.SELF_ATTESTED ->
                VerdictBanner(BannerVariant.WARNING, "Self-issued", "Self-issued by the holder, no third-party issuer.", Icons.Filled.GppMaybe)
            oc == null ->
                VerdictBanner(BannerVariant.INFO, "Checking on-chain…", "Cryptographic checks passed. Confirming issuer registration, revocation, and root on-chain.", Icons.Filled.GppMaybe)
            !oc.reachedChain ->
                VerdictBanner(BannerVariant.WARNING, "Valid on this device (offline)", "Cryptographically valid, but the chain could not be reached to confirm the issuer.", Icons.Filled.GppMaybe)
            firstOnChainFailure(oc) != null ->
                VerdictBanner(BannerVariant.ERROR, "Verification failed", firstOnChainFailure(oc)!!, Icons.Filled.GppBad)
            oc.fullyVerified ->
                VerdictBanner(
                    BannerVariant.SUCCESS,
                    "Fully verified",
                    buildString {
                        append("Registered issuer, not revoked, current root, and issuer signature valid on-chain.")
                        if (havid?.state == HavidState.CROSS_ENDORSED) append(" Issuer certificate matches its DID.")
                    },
                    Icons.Filled.GppGood,
                )
            else ->
                VerdictBanner(BannerVariant.WARNING, "Verified, with limits", onChainLimitsSummary(oc), Icons.Filled.GppMaybe)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Banner(
            title = banner.title,
            variant = banner.variant,
            icon = banner.icon,
            body = banner.body,
            modifier = Modifier.fillMaxWidth(),
        )

        // Detail lives in collapsed sections, each headed by one status glyph, so
        // the verdict fits a screen. The banner is the answer; expand to audit.
        if (bundle.disclosed.isNotEmpty()) {
            // Disclosed claims are the point of the scan, so open this expanded.
            ExpandableSection(stringResource(R.string.present_disclosed_claims_count, bundle.disclosed.size.toString()), SectionStatus.NEUTRAL, initiallyExpanded = true) {
                bundle.disclosed.keys.sorted().forEach { k ->
                    Kv(k, bundle.disclosed[k]?.prettyText() ?: "-")
                }
            }
        }

        ExpandableSection(stringResource(R.string.present_credential), SectionStatus.NEUTRAL) {
            Kv(stringResource(R.string.present_issuer), presentation.header.iss)
            Kv(stringResource(R.string.present_schema), schemaLabel(presentation.header.schema))
            Kv(stringResource(R.string.present_cid), presentation.header.cid)
        }

        ExpandableSection(stringResource(R.string.present_cryptographic_checks), cryptoSectionStatus(bundle)) {
            val c = bundle.checks
            // Issuer-bound header signature is verified in the online tier; shown
            // here only for self-attested (holder key, offline).
            if (bundle.decision == LocalVerdict.SELF_ATTESTED) CheckRow("headerSigValid", c.headerSigValid)
            CheckRow("merkleValid", c.merkleValid)
            CheckRow("challengeSigValid", c.challengeSigValid)
            CheckRow("timestampValid", c.timestampValid)
            CheckRow("expiryValid", c.expiryValid)
            // verifierRequestValid omitted: the open flow sends no verifier request.
        }

        if (bundle.decision != LocalVerdict.SELF_ATTESTED) {
            ExpandableSection(stringResource(R.string.present_online_verification_on_chain), onchainSectionStatus(onChain)) {
                val oc = onChain
                when {
                    running && oc == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.present_checking_on_chain), style = MaterialTheme.typography.bodyMedium)
                    }
                    oc == null -> Unit
                    !oc.reachedChain -> Text(
                        stringResource(R.string.present_couldnt_reach_the_chain),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        OnChainRow(stringResource(R.string.present_issuer_registered), oc.issuerRegistered)
                        OnChainRow(stringResource(R.string.present_not_revoked), oc.notRevoked)
                        OnChainRow(stringResource(R.string.present_root_current), oc.rootCurrent)
                        OnChainRow(stringResource(R.string.present_header_signature_on_chain_key), oc.headerSigValid)
                        oc.cscaProvenance?.let { OnChainRow(stringResource(R.string.present_passport_csca_provenance), it) }
                    }
                }
                Text(
                    stringResource(R.string.present_checks_talk_directly_to),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExpandableSection(stringResource(R.string.present_organisation_identity_havid), havidSectionStatus(havid)) {
                HavidRow(havid)
                Text(
                    stringResource(R.string.present_confirms_the_issuers_real_world),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = onScanAnother) { Text(stringResource(R.string.present_scan_another)) }
    }
}

/** HAVID cross-endorsement tier (ADR-0051). */
@Composable
private fun HavidRow(havid: HavidResult?) {
    if (havid == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.present_checking_issuer_certificate), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    when (havid.state) {
        HavidState.CROSS_ENDORSED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(Icons.Filled.GppGood, contentDescription = stringResource(R.string.present_matched), tint = MaknoonColors.success)
                Text(stringResource(R.string.present_issuer_certificate_matched), style = MaterialTheme.typography.bodyMedium, color = MaknoonColors.success)
            }
            havid.subject?.takeIf { it.isNotEmpty() }?.let { Kv(stringResource(R.string.present_certificate_subject), it) }
        }
        HavidState.KEY_ALIGNMENT_FAILURE, HavidState.INTEGRITY_FAILURE, HavidState.EXPIRED_REVOKED ->
            Text(
                havid.detail ?: stringResource(R.string.present_issuer_certificate_does_not),
                style = MaterialTheme.typography.bodyMedium,
                color = TrustRed,
            )
        HavidState.NO_ENDORSEMENT ->
            Text(
                stringResource(R.string.present_this_issuer_publishes_no),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        HavidState.NOT_RESOLVABLE ->
            Text(
                havid.detail ?: stringResource(R.string.present_issuer_identity_could_not),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

/** Per-section roll-up shown as one glyph on the collapsed header. */
private enum class SectionStatus { PASS, FAIL, WARN, NEUTRAL, PENDING }

private fun cryptoSectionStatus(bundle: VerdictBundle): SectionStatus {
    val c = bundle.checks
    val checks = buildList {
        if (bundle.decision == LocalVerdict.SELF_ATTESTED) add(c.headerSigValid)
        add(c.merkleValid); add(c.challengeSigValid); add(c.timestampValid)
        add(c.expiryValid) // verifierRequestValid omitted (no request in open flow)
    }
    if (checks.any { it is LocalCheckResult.Fail }) return SectionStatus.FAIL
    return if (checks.all { it is LocalCheckResult.Pass || it is LocalCheckResult.NotApplicable }) {
        SectionStatus.PASS
    } else {
        SectionStatus.WARN
    }
}

private fun onchainSectionStatus(oc: OnChainVerdict?): SectionStatus {
    if (oc == null) return SectionStatus.PENDING
    if (!oc.reachedChain) return SectionStatus.WARN
    val tiers = listOf(oc.issuerRegistered, oc.notRevoked, oc.rootCurrent, oc.headerSigValid) +
        listOfNotNull(oc.cscaProvenance)
    if (tiers.any { it is OnChainTier.Fail }) return SectionStatus.FAIL
    return if (oc.fullyVerified) SectionStatus.PASS else SectionStatus.WARN
}

private fun havidSectionStatus(havid: HavidResult?): SectionStatus = when (havid?.state) {
    null -> SectionStatus.PENDING
    HavidState.CROSS_ENDORSED -> SectionStatus.PASS
    HavidState.KEY_ALIGNMENT_FAILURE, HavidState.INTEGRITY_FAILURE, HavidState.EXPIRED_REVOKED -> SectionStatus.FAIL
    HavidState.NO_ENDORSEMENT, HavidState.NOT_RESOLVABLE -> SectionStatus.NEUTRAL
}

/** A collapsible detail section headed by its name + one pass/fail glyph. */
@Composable
private fun ExpandableSection(
    title: String,
    status: SectionStatus,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        when (status) {
            SectionStatus.PASS -> Icon(Icons.Filled.GppGood, "verified", tint = MaknoonColors.success, modifier = Modifier.size(20.dp))
            SectionStatus.FAIL -> Icon(Icons.Filled.GppBad, "failed", tint = TrustRed, modifier = Modifier.size(20.dp))
            SectionStatus.WARN -> Icon(Icons.Filled.GppMaybe, "incomplete", tint = MaknoonColors.warning, modifier = Modifier.size(20.dp))
            SectionStatus.PENDING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            SectionStatus.NEUTRAL -> Unit
        }
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}

/** Combined top-line verdict (offline crypto + on-chain + HAVID). */
private data class VerdictBanner(
    val variant: BannerVariant,
    val title: String,
    val body: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** First genuine on-chain FAILURE reason, or null when nothing failed (some
 *  checks may still be "unknown"/unavailable, which is not a failure). */
private fun firstOnChainFailure(oc: OnChainVerdict): String? {
    val tiers = listOf(oc.issuerRegistered, oc.notRevoked, oc.rootCurrent, oc.headerSigValid) +
        listOfNotNull(oc.cscaProvenance)
    for (t in tiers) if (t is OnChainTier.Fail) return t.reason
    return null
}

/** Summary for the "verified but not everything could be confirmed" case. */
private fun onChainLimitsSummary(oc: OnChainVerdict): String {
    val confirmed = mutableListOf<String>()
    val couldNot = mutableListOf<String>()
    fun note(name: String, t: OnChainTier) {
        when (t) {
            is OnChainTier.Pass -> confirmed.add(name)
            is OnChainTier.Unknown -> couldNot.add(name)
            else -> {}
        }
    }
    note("registration", oc.issuerRegistered)
    note("revocation", oc.notRevoked)
    note("signature", oc.headerSigValid)
    note("anchor freshness", oc.rootCurrent)
    return buildString {
        if (confirmed.isNotEmpty()) append("Confirmed on-chain: ${confirmed.joinToString(", ")}. ")
        if (couldNot.isNotEmpty()) append("Couldn't confirm: ${couldNot.joinToString(", ")}.")
    }.ifEmpty { "Some on-chain checks couldn't be completed." }
}

/** One on-chain check tier row (item 7). */
@Composable
private fun OnChainRow(name: String, tier: OnChainTier) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when (tier) {
            is OnChainTier.Pass ->
                Icon(Icons.Filled.GppGood, contentDescription = stringResource(R.string.present_verified), tint = MaknoonColors.success)
            is OnChainTier.Fail ->
                Text(tier.reason, style = MaterialTheme.typography.labelSmall, color = TrustRed)
            is OnChainTier.Unknown ->
                Text(tier.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Kv(key: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press any field (disclosed claim, issuer, schema, CID) to copy
            // its full value to the clipboard.
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, context.getString(R.string.settings_copied), Toast.LENGTH_SHORT).show()
                },
            ),
    ) {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// ---------------------------------------------------------------------------
// Badge parsing + date formatting (UI-only).
// ---------------------------------------------------------------------------

private fun parseBadge(o: JSONObject): BadgeView {
    val anchorsArr = o.optJSONArray("anchors")
    fun anchor(a: JSONObject) = BadgeAnchorView(
        chain = a.optString("chain", ""),
        batchTxHash = a.optString("batchTxHash", ""),
        batchRoot = a.optString("batchRoot", ""),
        registry = a.optStr("registry"),
    )
    val anchors = when {
        anchorsArr != null -> (0 until anchorsArr.length()).mapNotNull { i ->
            anchorsArr.optJSONObject(i)?.let { anchor(it) }
        }
        // Back-compat: a single legacy `anchor` field.
        o.optJSONObject("anchor") != null -> listOf(anchor(o.getJSONObject("anchor")))
        else -> emptyList()
    }
    return BadgeView(
        iss = o.optString("iss", ""),
        cid = o.optString("cid", ""),
        schema = o.optString("schema", ""),
        iat = o.optLong("iat", 0L),
        exp = if (o.has("exp") && !o.isNull("exp")) o.optLong("exp") else null,
        anchors = anchors,
    )
}

private fun formatDate(unix: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unix * 1000L))
