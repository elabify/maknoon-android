// Detail screen for a saved ID document. Tap a card on the Identity tab
// to land here. Kotlin port of the iOS IDDocumentDetailView.
//
// Shows the bearer fields, the DG2 portrait, the on-device Passive
// Authentication soft badge (ICAO 9303), and the Present (QR) + Issue
// verified credential + sanctions actions. Stateless at the boundary:
// the orchestrator passes the resolved IDDocument plus the operations as
// suspending lambdas and owns navigation (onBack, onPresentQr).

package com.elabify.app.maknoon.ui.iddocument

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.PassiveAuthResult
import com.elabify.app.maknoon.iddocument.SanctionsMatchDetail
import com.elabify.app.maknoon.iddocument.SanctionsOutcome
import com.elabify.app.maknoon.iddocument.SanctionsScreenResult
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.StatusDot
import com.elabify.app.maknoon.ui.components.StatusLevel
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import java.text.DateFormat
import java.util.Date

private const val THIRTY_DAYS_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

private sealed interface DetailIssuance {
    data object Idle : DetailIssuance
    data object Submitting : DetailIssuance
    data class SubmittedForAnchor(val credentialId: String) : DetailIssuance
    data class PendingReview(
        val pendingId: String,
        val proofPreVerified: Boolean,
        val reason: String,
    ) : DetailIssuance
    data class Failed(val message: String) : DetailIssuance
}

private sealed interface DetailSanctions {
    data object Idle : DetailSanctions
    data object Checking : DetailSanctions
    data class Failed(val message: String) : DetailSanctions
}

/**
 * Saved-document detail.
 *
 * @param document the resolved IDDocument (the orchestrator removes the screen if it disappears).
 * @param photo DG2 portrait already decoded to an ImageBitmap, or null.
 * @param passiveAuth latest cached Passive Authentication verdict, or null if never run.
 * @param passiveAuthRunning whether a Passive Auth pass is in flight (drives the spinner).
 * @param sanctionsResult latest cached OpenSanctions verdict, or null.
 * @param canPresent whether the document has the fields to mint a self-signed QR (the unlock happens on tap, not here).
 * @param canIssue whether the issue button is enabled (SOD present, unlocked, issuer picked).
 * @param sodMissing surfaces the "re-tap for SOD" hint.
 * @param submittingHost host[:port] shown while submitting.
 * @param onPresentQr mint + present the self-signed credential (orchestrator opens the QR sheet).
 * @param runPassiveAuth re-run the on-device chip check (force = user tapped Re-check).
 * @param submitIssuance upload the chip-signed fields to the selected issuer; throws on failure.
 * @param runSanctions OpenSanctions screening against the selected issuer; throws on failure.
 * @param onSaveNickname persist a new nickname (null clears it).
 * @param onDelete remove the document from this phone.
 * @param onBack pop the detail screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IDDocumentDetailScreen(
    document: IDDocument,
    photo: ImageBitmap?,
    passiveAuth: PassiveAuthResult?,
    passiveAuthRunning: Boolean,
    sanctionsResult: SanctionsScreenResult?,
    canPresent: Boolean,
    presentError: String?,
    canIssue: Boolean,
    sodMissing: Boolean,
    submittingHost: String,
    onPresentQr: () -> Unit,
    runPassiveAuth: suspend (force: Boolean) -> Unit,
    submitIssuance: suspend () -> IDDocumentIssuanceOutcome,
    runSanctions: suspend () -> Unit,
    onSaveNickname: (String?) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    // Folders (ADR-0037): an ID document can live in a folder alongside
    // credentials. availableFolders is (id, name); currentFolderId is null for
    // the "All" root; onAssignFolder(null) moves it back to root.
    folderName: String = "None",
    availableFolders: List<Pair<String, String>> = emptyList(),
    currentFolderId: String? = null,
    onAssignFolder: (String?) -> Unit = {},
) {
    var issuance by remember { mutableStateOf<DetailIssuance>(DetailIssuance.Idle) }
    var sanctions by remember { mutableStateOf<DetailSanctions>(DetailSanctions.Idle) }
    var nicknameDraft by remember(document.id) { mutableStateOf(document.nickname ?: "") }
    var folderMenuOpen by remember { mutableStateOf(false) }

    // Run on-device Passive Authentication only when no verdict is cached yet.
    // The passport card already shows the cached chip result, so re-running on
    // every open just froze the UI (loading hundreds of CSCA certs + verifying);
    // the user can force a fresh pass with "Re-check". Matches iOS.
    LaunchedEffect(document.id) { if (passiveAuth == null) runPassiveAuth(false) }

    if (issuance is DetailIssuance.Submitting) {
        LaunchedEffect("issue") {
            try {
                val outcome = submitIssuance()
                issuance = when (outcome) {
                    is IDDocumentIssuanceOutcome.SubmittedForAnchor ->
                        DetailIssuance.SubmittedForAnchor(outcome.credentialId)
                    is IDDocumentIssuanceOutcome.PendingReview ->
                        DetailIssuance.PendingReview(outcome.pendingId, outcome.proofPreVerified, outcome.reason)
                }
            } catch (e: Throwable) {
                issuance = DetailIssuance.Failed(e.message ?: "Submission failed.")
            }
        }
    }

    if (sanctions is DetailSanctions.Checking) {
        LaunchedEffect("sanctions") {
            try {
                runSanctions()
                sanctions = DetailSanctions.Idle
            } catch (e: Throwable) {
                sanctions = DetailSanctions.Failed(e.message ?: "Screening failed.")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.nickname ?: document.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            SectionCard { PhotoSection(document, photo) }
            // The field-by-field details table was removed from Advanced (0.6.2):
            // the hero card already shows the key fields.
            // Present moved to the passport card's Build QR flow (ADR-0039);
            // Advanced no longer carries a Present section.
            SectionCard {
                ChipAuthSection(passiveAuth, passiveAuthRunning)
                Spacer(Modifier.size(Spacing.md))
                RecheckTrigger(runPassiveAuth)
            }
            SectionCard {
                IssueVerifiedSection(
                    issuance = issuance,
                    sanctions = sanctions,
                    sanctionsResult = sanctionsResult,
                    canIssue = canIssue,
                    sodMissing = sodMissing,
                    submittingHost = submittingHost,
                    onIssue = { issuance = DetailIssuance.Submitting },
                    onRetryIssuance = { issuance = DetailIssuance.Idle },
                    onCheckSanctions = { sanctions = DetailSanctions.Checking },
                )
            }
            SectionCard {
                NicknameSection(
                    draft = nicknameDraft,
                    onDraftChange = { nicknameDraft = it },
                    isDirty = nicknameDraft != (document.nickname ?: ""),
                    onSave = { onSaveNickname(nicknameDraft.trim().ifEmpty { null }) },
                )
            }
            SectionCard {
                FolderSection(folderName = folderName, onOpen = { folderMenuOpen = true })
            }
            SectionCard { DeleteSection(onDelete) }
        }
    }

    if (folderMenuOpen) {
        FolderPickerDialog(
            folders = availableFolders,
            currentFolderId = currentFolderId,
            onSelect = { fid -> onAssignFolder(fid); folderMenuOpen = false },
            onDismiss = { folderMenuOpen = false },
        )
    }
}

// MARK: -- folder section

@Composable
private fun FolderSection(folderName: String, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.id_folder), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text(folderName)
        }
        Text(
            stringResource(R.string.id_folders_blurb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderPickerDialog(
    folders: List<Pair<String, String>>,
    currentFolderId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.id_move_to_folder)) },
        text = {
            Column {
                TextButton(onClick = { onSelect(null) }) {
                    Text(if (currentFolderId == null) stringResource(R.string.id_none_all_selected) else stringResource(R.string.id_none_all))
                }
                folders.forEach { (id, name) ->
                    TextButton(onClick = { onSelect(id) }) {
                        Text(if (currentFolderId == id) stringResource(R.string.id_folder_name_selected, name) else name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

// MARK: -- section card shell

/// A grouped section card: the brand soft-shadow elevation + the 18 dp
/// wallet-card radius, with the section's rows arranged in a padded column at
/// the 12 dp rhythm. Replaces the flat divider-separated layout so each
/// section reads as its own raised surface (the iOS Form section feel).
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) { content() }
    }
}

// MARK: -- photo + details

@Composable
private fun PhotoSection(doc: IDDocument, photo: ImageBitmap?) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IDDocumentThumbnail(document = doc, photo = photo, width = 90.dp, height = 110.dp, cornerRadius = 10.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(doc.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            doc.nativeDisplayName?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.id_kind_summary, doc.kindLabel, doc.summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Small, unobtrusive schema version so different record schemas are
            // trackable (ADR-0037), replaces the old unclear "v1" label.
            Text(
                stringResource(R.string.id_schema_version, doc.schemaVersion.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: -- present (self-signed QR)

@Composable
private fun PresentSection(canPresent: Boolean, presentError: String?, onPresentQr: () -> Unit) {
    Text(stringResource(R.string.id_present), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        stringResource(R.string.id_present_blurb),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onPresentQr, enabled = canPresent, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.QrCode, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.id_present_show_qr))
    }
    if (presentError != null) {
        Text(
            presentError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (!canPresent) {
        // canPresent is true whenever the passport has the fields a self-signed
        // credential needs; the biometric unlock happens on tap. So this only
        // shows for a document with nothing presentable (no readable name /
        // number), not for a locked identity.
        Text(
            stringResource(R.string.id_no_presentable_fields),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// MARK: -- chip authenticity (ICAO 9303 Passive Auth)

@Composable
private fun ChipAuthSection(
    result: PassiveAuthResult?,
    running: Boolean,
) {
    Text(stringResource(R.string.id_passport_chip), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            // A traffic-light dot summarizing the on-device verdict, alongside
            // the label (the iOS soft-badge feel).
            if (result != null) StatusDot(level = chipAuthLevel(result))
            Text(stringResource(R.string.id_chip_authenticity), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        when {
            running -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
            result != null -> ChipAuthPill(result)
            else -> Text(stringResource(R.string.id_not_checked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // When the chip is not cleanly Verified, surface the explanation as a
    // colored soft Banner: red on a real failure, orange / blue otherwise.
    if (result != null && result.status != PassiveAuthResult.Status.VERIFIED) {
        val detail = chipAuthDetail(result)
        if (detail.isNotEmpty()) {
            val variant = if (result.status == PassiveAuthResult.Status.FAILED) {
                BannerVariant.ERROR
            } else {
                BannerVariant.INFO
            }
            Banner(
                title = stringResource(R.string.id_chip_check_note),
                variant = variant,
                body = detail,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/// Traffic-light level for the chip verdict dot: verified is green, a true
/// failure is red, everything in between (integrity-only / unavailable) reads
/// as a yellow soft signal rather than a hard pass or fail.
private fun chipAuthLevel(r: PassiveAuthResult): StatusLevel = when (r.status) {
    PassiveAuthResult.Status.VERIFIED -> StatusLevel.OK
    PassiveAuthResult.Status.FAILED -> StatusLevel.EXPIRED
    else -> StatusLevel.WARN
}

/// A request flag for the user-driven "Re-check chip" action. Kept as its
/// own composable so the suspend op runs from a LaunchedEffect rather than
/// inside a click handler.
@Composable
private fun RecheckTrigger(runPassiveAuth: suspend (force: Boolean) -> Unit) {
    var requested by remember { mutableStateOf(false) }
    if (requested) {
        LaunchedEffect("recheck") {
            runPassiveAuth(true)
            requested = false
        }
    }
    OutlinedButton(onClick = { requested = true }, enabled = !requested, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.id_recheck_chip))
    }
}

@Composable
private fun ChipAuthPill(r: PassiveAuthResult) {
    data class Look(val label: String, val color: Color, val icon: ImageVector)
    val look = when (r.status) {
        // Green "Verified" (not the app's purple primary) so chip authenticity
        // reads as a positive seal, matching the navy card's green seal.
        PassiveAuthResult.Status.VERIFIED -> Look(stringResource(R.string.id_chip_verified), Color(0xFF34D399), Icons.Filled.Verified)
        PassiveAuthResult.Status.INTEGRITY_ONLY ->
            if (r.reason == "dsc_or_chain_expired") {
                Look(stringResource(R.string.id_chip_signer_expired), MaterialTheme.colorScheme.tertiary, Icons.Filled.Warning)
            } else {
                // Genuine chip whose signer just isn't in the on-device CSCA
                // trust list. Confident blue "Genuine" (mirrors iOS); the CSCA
                // nuance lives in the description below, not the pill label.
                Look(stringResource(R.string.id_chip_signer_not_in_list), Color(0xFF3B82F6), Icons.Filled.Verified)
            }
        PassiveAuthResult.Status.FAILED -> Look(stringResource(R.string.id_chip_failed), MaterialTheme.colorScheme.error, Icons.Filled.Warning)
        PassiveAuthResult.Status.UNAVAILABLE -> Look(stringResource(R.string.id_chip_unavailable), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Search)
    }
    Pill(look.icon, look.label, look.color)
}

private fun chipAuthDetail(r: PassiveAuthResult): String = when (r.status) {
    PassiveAuthResult.Status.VERIFIED -> ""
    PassiveAuthResult.Status.INTEGRITY_ONLY ->
        if (r.reason == "dsc_or_chain_expired") {
            "The chip's data is intact and validly signed, but the signer certificate is past its validity window (expected for an expired passport). This checks chip authenticity, not document validity, so it is not a forgery signal."
        } else {
            "The chip's data is genuine and validly signed, but this passport's national signing certificate (CSCA) isn't in the on-device trust list, which doesn't cover every country. The issuer verifies it on its side. Not a sign of tampering."
        }
    PassiveAuthResult.Status.FAILED -> {
        val signer = r.dscIssuer?.let { "\n\nSigner (DSC) issuer: $it" } ?: ""
        "On-device check failed (${r.reason}): the chip's data did not match its signed hashes, or the SOD signature did not verify. The issuer makes the authoritative decision.$signer"
    }
    PassiveAuthResult.Status.UNAVAILABLE -> "Could not run on-device (${r.reason})."
}

// MARK: -- identity verified credential + sanctions

@Composable
private fun IssueVerifiedSection(
    issuance: DetailIssuance,
    sanctions: DetailSanctions,
    sanctionsResult: SanctionsScreenResult?,
    canIssue: Boolean,
    sodMissing: Boolean,
    submittingHost: String,
    onIssue: () -> Unit,
    onRetryIssuance: () -> Unit,
    onCheckSanctions: () -> Unit,
) {
    Text(stringResource(R.string.id_identity_verified_credential), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    when (issuance) {
        is DetailIssuance.Idle -> {
            Text(
                stringResource(R.string.id_issue_detail_blurb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.id_uploaded_fields_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Button(onClick = onIssue, enabled = canIssue, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Verified, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.id_issue_verified_credential))
            }
        }
        is DetailIssuance.Submitting -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.id_submitting_to_host, submittingHost), style = MaterialTheme.typography.bodyMedium)
        }
        is DetailIssuance.PendingReview -> {
            val preVerified = issuance.proofPreVerified
            Pill(
                if (preVerified) Icons.Filled.Verified else Icons.Filled.Search,
                if (preVerified) stringResource(R.string.id_submitted_pre_verified) else stringResource(R.string.id_submitted_awaiting_review),
                if (preVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
            Text(
                if (preVerified) {
                    stringResource(R.string.id_pending_pre_verified_blurb)
                } else {
                    stringResource(R.string.id_pre_verification_failed, issuance.reason)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonoLine(stringResource(R.string.id_pending_id_value, issuance.pendingId))
        }
        is DetailIssuance.SubmittedForAnchor -> {
            Pill(Icons.Filled.Verified, stringResource(R.string.id_submitted_anchoring), MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.id_anchoring_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MonoLine(stringResource(R.string.id_credential_value, issuance.credentialId))
        }
        is DetailIssuance.Failed -> {
            Pill(Icons.Filled.Warning, issuance.message, MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetryIssuance, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_try_again)) }
        }
    }

    if (sodMissing) {
        Banner(
            title = stringResource(R.string.id_retap_needed),
            variant = BannerVariant.WARNING,
            body = stringResource(R.string.id_retap_needed_body),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Sanctions screening sits directly below the issue action: same issuer.
    SanctionsCheckButton(sanctions, sanctionsResult, onCheckSanctions)
    SanctionsResultView(sanctionsResult)
}

@Composable
private fun SanctionsCheckButton(
    sanctions: DetailSanctions,
    sanctionsResult: SanctionsScreenResult?,
    onCheck: () -> Unit,
) {
    val checking = sanctions is DetailSanctions.Checking
    OutlinedButton(onClick = onCheck, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Icon(Icons.Filled.Shield, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (sanctionsResult == null) stringResource(R.string.id_check_opensanctions) else stringResource(R.string.id_recheck_sanctions))
    }
    if (sanctions is DetailSanctions.Failed) {
        Text(sanctions.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SanctionsResultView(result: SanctionsScreenResult?) {
    if (result != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.id_screening_result), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SanctionsPill(result.outcome)
            }
            Row1(stringResource(R.string.id_last_screened), formatScreenedAt(result.screenedAt))
            Row1(stringResource(R.string.id_dataset), result.datasetVersion, monospaced = true)
            if (result.outcome != SanctionsOutcome.CLEAN) {
                result.matches.forEach { m: SanctionsMatchDetail ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(m.listName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // The model carries screenedAt as epoch millis only (no stale flag);
            // surface a "re-check recommended" hint when it is older than 30 days.
            val ageMillis = System.currentTimeMillis() - result.screenedAt
            if (ageMillis > THIRTY_DAYS_MILLIS) {
                Text(
                    stringResource(R.string.id_screened_over_30_days),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
    Text(
        stringResource(R.string.id_sanctions_footer_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

@Composable
private fun SanctionsPill(outcome: SanctionsOutcome) {
    data class Look(val label: String, val color: Color, val icon: ImageVector)
    val look = when (outcome) {
        SanctionsOutcome.CLEAN -> Look(stringResource(R.string.id_sanctions_clean), MaterialTheme.colorScheme.primary, Icons.Filled.Shield)
        SanctionsOutcome.SANCTIONED -> Look(stringResource(R.string.id_sanctions_sanctioned), MaterialTheme.colorScheme.error, Icons.Filled.Warning)
        SanctionsOutcome.PEP -> Look(stringResource(R.string.id_sanctions_pep_match), MaterialTheme.colorScheme.tertiary, Icons.Filled.Warning)
        SanctionsOutcome.INCONCLUSIVE -> Look(stringResource(R.string.id_sanctions_inconclusive), MaterialTheme.colorScheme.tertiary, Icons.Filled.Search)
        SanctionsOutcome.ERROR -> Look(stringResource(R.string.id_sanctions_error), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Warning)
    }
    Pill(look.icon, look.label, look.color)
}

// MARK: -- nickname + delete

@Composable
private fun NicknameSection(
    draft: String,
    onDraftChange: (String) -> Unit,
    isDirty: Boolean,
    onSave: () -> Unit,
) {
    Text(stringResource(R.string.id_nickname), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text(stringResource(R.string.id_nickname_optional)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave, enabled = isDirty) { Text(stringResource(R.string.common_save)) }
    }
    Text(
        stringResource(R.string.id_nickname_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeleteSection(onDelete: () -> Unit) {
    OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.id_delete_document), color = MaterialTheme.colorScheme.error)
    }
    Text(
        stringResource(R.string.id_delete_document_blurb),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// MARK: -- small shared building blocks

@Composable
private fun Row1(label: String, value: String, monospaced: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun Pill(icon: ImageVector, label: String, color: Color) {
    // Tinted capsule mirroring the iOS pill: foreground colour at full
    // strength, the same colour at 12 percent as the background fill.
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.tint(MaknoonColors.TintPillAlpha))
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/// Format the screening timestamp (epoch millis) as a localized
/// medium-style date+time. The model stores only the millis, so the
/// human-readable label is computed in the UI layer.
private fun formatScreenedAt(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMillis))

@Composable
private fun MonoLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        maxLines = 2,
    )
}
