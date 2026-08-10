// Multi-step screen for the "Tap ID document" action. Kotlin port of
// the iOS TapIDDocumentSheet.
//
// Step kindPicker: pick passport vs other ICAO-compatible ID.
// Step form:       collect document number, birth date, expiry. The chip
//                  only unlocks to these three values.
// Step scanning:   drive IDDocumentReader (Android NFC reader mode);
//                  show a quiet "looking for the chip" screen while the
//                  system reads the document.
// Step review:     show what came off the chip with a Save action.
// Step minted:     local credential saved; optional inline issuer flow.
// Step error:      the read failed; offer try-again / edit-details.
//
// Stateless at the boundary: the orchestrator passes the saved-document
// callback (onSaved) plus onClose, and supplies the read + save + issue
// operations as suspending lambdas so this file stays free of crypto,
// networking, and persistence. All step state is local UI state.

package com.elabify.app.maknoon.ui.iddocument

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.IDDocumentKind
import com.elabify.app.maknoon.iddocument.IDDocumentReadParameters
import com.elabify.app.maknoon.iddocument.IDDocumentReadResult
import com.elabify.app.maknoon.iddocument.IssuerSelection

/// Outcome of the inline "get a verified credential" issuance call. Mirrors
/// the iOS IssuanceState enum's terminal cases. The submit lambda returns
/// one of these (or throws, which the screen renders as Failed).
sealed interface IDDocumentIssuanceOutcome {
    data class SubmittedForAnchor(val credentialId: String) : IDDocumentIssuanceOutcome
    data class PendingReview(
        val pendingId: String,
        val proofPreVerified: Boolean,
        val reason: String,
    ) : IDDocumentIssuanceOutcome
}

private enum class TapStep { KindPicker, Form, Scanning, Review, Minted, Error }

private sealed interface TapIssuance {
    data object Idle : TapIssuance
    data object Submitting : TapIssuance
    data class SubmittedForAnchor(val credentialId: String) : TapIssuance
    data class PendingReview(
        val pendingId: String,
        val proofPreVerified: Boolean,
        val reason: String,
    ) : TapIssuance
    data class Failed(val message: String) : TapIssuance
}

/**
 * Top-level read flow.
 *
 * @param nfcAvailable whether the device can read ICAO documents over NFC.
 * @param read drives IDDocumentReader; throws on failure (message shown on the error step).
 * @param save persists the read result and returns a stable saved-document id.
 * @param canIssue gate for the inline issuer button given the saved id (SOD present, identity unlocked, issuer picked).
 * @param issuanceDisabledHint one-line reason the issue button is off, or null.
 * @param submitIssuance uploads the chip-signed fields to the selected issuer; throws on failure.
 * @param submittingHost host[:port] shown in the "Submitting to ..." line.
 * @param onSaved called with the saved-document id when the user finishes.
 * @param onClose dismiss without finishing (Cancel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapIDDocumentScreen(
    nfcAvailable: Boolean,
    read: suspend (IDDocumentReadParameters, onProgress: (String) -> Unit) -> IDDocumentReadResult,
    save: suspend (IDDocumentReadResult) -> String,
    canIssue: (savedId: String) -> Boolean,
    issuanceDisabledHint: (savedId: String) -> String?,
    submitIssuance: suspend (savedId: String) -> IDDocumentIssuanceOutcome,
    submittingHost: String,
    onSaved: (savedId: String?) -> Unit,
    onClose: () -> Unit,
    skipKindPicker: Boolean = false,
) {
    val somethingWentWrongMsg = stringResource(R.string.iddoc_something_went_wrong)
    // Onboarding's "Scan your passport" is passport-specific, so it skips the
    // document-kind picker and opens the passport form directly.
    var step by remember { mutableStateOf(if (skipKindPicker) TapStep.Form else TapStep.KindPicker) }
    var parameters by remember {
        mutableStateOf(
            IDDocumentReadParameters(
                documentNumber = "",
                dateOfBirth = "",
                dateOfExpiry = "",
                declaredKind = IDDocumentKind.PASSPORT,
            ),
        )
    }
    var lastError by remember { mutableStateOf<String?>(null) }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var readResult by remember { mutableStateOf<IDDocumentReadResult?>(null) }
    var savedId by remember { mutableStateOf<String?>(null) }
    var issuance by remember { mutableStateOf<TapIssuance>(TapIssuance.Idle) }

    val navTitle = when (step) {
        TapStep.KindPicker -> stringResource(R.string.id_tap_document_title)
        TapStep.Minted -> stringResource(R.string.id_saved_title)
        else -> stringResource(parameters.declaredKind.displayNameRes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(navTitle) },
                navigationIcon = {
                    if (step != TapStep.Minted) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (step) {
                TapStep.KindPicker -> KindPickerStep(
                    onPick = { kind ->
                        parameters = parameters.copy(declaredKind = kind)
                        step = TapStep.Form
                    },
                )

                TapStep.Form -> FormStep(
                    parameters = parameters,
                    nfcAvailable = nfcAvailable,
                    onParametersChange = { parameters = it },
                    onContinue = { step = TapStep.Scanning },
                    onChangeKind = { step = TapStep.KindPicker },
                    showChangeKind = !skipKindPicker,
                )

                TapStep.Scanning -> {
                    ScanningStep(status = scanStatus, onCancel = { step = TapStep.Form })
                    LaunchedEffect(step) {
                        if (step == TapStep.Scanning) {
                            scanStatus = null
                            try {
                                readResult = read(parameters) { msg -> scanStatus = msg }
                                step = TapStep.Review
                            } catch (e: Throwable) {
                                lastError = e.message ?: somethingWentWrongMsg
                                step = TapStep.Error
                            }
                        }
                    }
                }

                TapStep.Review -> readResult?.let { result ->
                    ReviewStep(
                        result = result,
                        onDiscard = {
                            readResult = null
                            step = TapStep.Form
                        },
                        save = save,
                        onSaved = { id ->
                            savedId = id
                            step = TapStep.Minted
                        },
                    )
                }

                TapStep.Minted -> MintedStep(
                    savedId = savedId,
                    issuance = issuance,
                    canIssue = savedId?.let { canIssue(it) } ?: false,
                    issuanceDisabledHint = savedId?.let { issuanceDisabledHint(it) },
                    submittingHost = submittingHost,
                    onIssue = { issuance = TapIssuance.Submitting },
                    submitIssuance = submitIssuance,
                    onIssuanceResult = { issuance = it },
                    onDone = { onSaved(savedId) },
                    onRetryIssuance = { issuance = TapIssuance.Idle },
                )

                TapStep.Error -> ErrorStep(
                    message = lastError ?: stringResource(R.string.id_something_went_wrong),
                    onTryAgain = { step = TapStep.Scanning },
                    onEditDetails = { step = TapStep.Form },
                )
            }
        }
    }
}

// MARK: -- step 0: kind picker

@Composable
private fun KindPickerStep(onPick: (IDDocumentKind) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.id_kind_picker_prompt),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IDDocumentKind.entries.forEach { kind ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(kind) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    kindIcon(kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(32.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(kind.displayNameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(kind.blurbRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            HorizontalDivider()
        }
    }
}

// MARK: -- step 1: form

@Composable
private fun FormStep(
    parameters: IDDocumentReadParameters,
    nfcAvailable: Boolean,
    onParametersChange: (IDDocumentReadParameters) -> Unit,
    onContinue: () -> Unit,
    onChangeKind: () -> Unit,
    showChangeKind: Boolean = true,
) {
    // Full 4-digit-year date entry (YYYYMMDD digits). The chip's BAC key only
    // needs the 2-digit-year YYMMDD, so `parameters` stays MRZ-shaped; these
    // local fields drive the YYYY-MM-DD display. Seeded from any value already
    // in `parameters` so navigating back to this step keeps what was typed.
    var dobDigits by remember { mutableStateOf(expandToYYYYMMDD(parameters.dateOfBirth, isExpiry = false)) }
    var expDigits by remember { mutableStateOf(expandToYYYYMMDD(parameters.dateOfExpiry, isExpiry = true)) }

    // Track that the user deliberately set both dates. The chip only
    // unlocks to the exact printed values, so we gate Continue on the
    // user having actually typed both rather than leaving placeholders.
    val canContinue = parameters.documentNumber.trim().isNotEmpty() &&
        dobDigits.length == 8 && expDigits.length == 8

    val formIntro = when (parameters.declaredKind) {
        IDDocumentKind.PASSPORT ->
            stringResource(R.string.id_form_intro_passport)
        IDDocumentKind.OTHER ->
            stringResource(R.string.id_form_intro_other)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (parameters.declaredKind == IDDocumentKind.PASSPORT) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.passport_nfc),
                    contentDescription = stringResource(R.string.id_chip_enabled_passport),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(150.dp)
                        .widthIn(max = 280.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.id_passport_symbol_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(formIntro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.id_document_details), style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = parameters.documentNumber,
            onValueChange = { onParametersChange(parameters.copy(documentNumber = it.uppercase())) },
            label = { Text(stringResource(parameters.declaredKind.documentNumberLabelRes)) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        // Dates are entered as YYYY-MM-DD: the user types the full 4-digit year
        // and the dashes appear automatically after the year and the month. The
        // stored value stays the chip-native 2-digit-year YYMMDD the MRZ key
        // derivation expects (the century is dropped on the way in; ADR-0037).
        OutlinedTextField(
            value = dobDigits,
            onValueChange = {
                dobDigits = it.filter(Char::isDigit).take(8)
                onParametersChange(parameters.copy(dateOfBirth = yymmddFromYYYYMMDD(dobDigits)))
            },
            label = { Text(stringResource(R.string.id_date_of_birth_field)) },
            singleLine = true,
            visualTransformation = YyyymmddDashTransformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = expDigits,
            onValueChange = {
                expDigits = it.filter(Char::isDigit).take(8)
                onParametersChange(parameters.copy(dateOfExpiry = yymmddFromYYYYMMDD(expDigits)))
            },
            label = { Text(stringResource(R.string.id_date_of_expiry_field)) },
            singleLine = true,
            visualTransformation = YyyymmddDashTransformation,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.id_form_accuracy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onContinue, enabled = canContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_continue))
        }
        if (showChangeKind) OutlinedButton(onClick = onChangeKind, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.id_change_document_type))
        }

        if (!nfcAvailable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Text(
                    stringResource(R.string.id_nfc_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

// MARK: -- step 2: scanning

@Composable
private fun ScanningStep(status: String?, onCancel: () -> Unit) {
    // Before the chip answers, status is null: prompt to position the phone.
    // Once the read starts, status carries the live stage (Authenticating,
    // Reading DG1, Reading DG2, ...) so the user knows it is still reading and
    // does not pull the passport away mid-transfer.
    val reading = status != null
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        // Passport + NFC artwork (matches the iOS PassportNFC asset), not a bare
        // NFC glyph, so the scanning screen is consistent across platforms (ADR-0037).
        Image(
            painter = painterResource(R.drawable.passport_nfc),
            contentDescription = stringResource(R.string.id_chip_enabled_passport),
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(150.dp).widthIn(max = 280.dp),
        )
        Text(
            status ?: stringResource(R.string.id_hold_passport_to_phone),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            if (reading) {
                stringResource(R.string.id_reading_chip_hold)
            } else {
                stringResource(R.string.id_move_slowly_find_chip)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        CircularProgressIndicator()
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.id_cancel_scan))
        }
    }
}

// MARK: -- step 3: review

@Composable
private fun ReviewStep(
    result: IDDocumentReadResult,
    onDiscard: () -> Unit,
    save: suspend (IDDocumentReadResult) -> String,
    onSaved: (String) -> Unit,
) {
    val couldNotSaveDocMsg = stringResource(R.string.iddoc_could_not_save_document)
    val doc = result.document
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saveRequested by remember { mutableStateOf(false) }

    if (saveRequested) {
        LaunchedEffect(Unit) {
            saving = true
            try {
                val id = save(result)
                onSaved(id)
            } catch (e: Throwable) {
                saveError = e.message ?: couldNotSaveDocMsg
                saving = false
                saveRequested = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val portrait = rememberPortraitBitmap(result.portraitJpeg)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IDDocumentThumbnail(document = doc, photo = portrait, width = 80.dp, height = 100.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(doc.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                doc.nativeDisplayName?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(R.string.id_kind_summary, stringResource(doc.kindLabelRes), doc.summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(stringResource(R.string.id_details), style = MaterialTheme.typography.titleSmall)
        val docLabel = stringResource(doc.userDeclaredKind?.documentNumberLabelRes ?: R.string.id_document_number)
        DetailRow(docLabel, doc.documentNumber)
        doc.personalNumber?.takeIf { it.isNotEmpty() }?.let {
            DetailRow(stringResource(doc.userDeclaredKind?.personalNumberLabelRes ?: R.string.id_personal_number), it)
        }
        doc.formattedDateOfBirth?.let { DetailRow(stringResource(R.string.id_date_of_birth), it) }
        doc.formattedDateOfExpiry?.let { DetailRow(stringResource(R.string.id_expires), it) }
        doc.sex?.takeIf { it.isNotEmpty() }?.let { DetailRow(stringResource(R.string.id_sex), it) }
        // countryName returns null for codes the platform doesn't recognise:
        // fall back to the raw alpha-3 so an exotic passport still shows it.
        doc.nationality.takeIf { it.isNotEmpty() }?.let {
            DetailRow(stringResource(R.string.id_nationality), IDDocument.countryName(it) ?: it)
        }
        doc.formattedPlaceOfBirth?.takeIf { it.isNotEmpty() }?.let { DetailRow(stringResource(R.string.id_place_of_birth), it) }

        Button(
            onClick = {
                saveError = null
                saveRequested = true
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (saving) CircularProgressIndicator(modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.id_save_to_wallet))
        }
        TextButton(onClick = onDiscard, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.id_discard), color = MaterialTheme.colorScheme.error)
        }
        saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            stringResource(R.string.id_saved_docs_stay_on_phone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MARK: -- step minted (local credential saved + inline issuer issuance)

@Composable
private fun MintedStep(
    savedId: String?,
    issuance: TapIssuance,
    canIssue: Boolean,
    issuanceDisabledHint: String?,
    submittingHost: String,
    onIssue: () -> Unit,
    submitIssuance: suspend (savedId: String) -> IDDocumentIssuanceOutcome,
    onIssuanceResult: (TapIssuance) -> Unit,
    onDone: () -> Unit,
    onRetryIssuance: () -> Unit,
) {
    val submissionFailedMsg = stringResource(R.string.iddoc_submission_failed)
    // Fire the issuance request when the state flips to Submitting.
    if (issuance is TapIssuance.Submitting && savedId != null) {
        LaunchedEffect(savedId) {
            try {
                val outcome = submitIssuance(savedId)
                onIssuanceResult(
                    when (outcome) {
                        is IDDocumentIssuanceOutcome.SubmittedForAnchor ->
                            TapIssuance.SubmittedForAnchor(outcome.credentialId)
                        is IDDocumentIssuanceOutcome.PendingReview ->
                            TapIssuance.PendingReview(outcome.pendingId, outcome.proofPreVerified, outcome.reason)
                    },
                )
            } catch (e: Throwable) {
                onIssuanceResult(TapIssuance.Failed(e.message ?: submissionFailedMsg))
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.id_document_saved_blurb),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        HorizontalDivider()

        when (issuance) {
            is TapIssuance.Idle -> {
                Text(
                    stringResource(R.string.id_issue_idle_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IssuanceConsentDisclosure()
                Button(
                    onClick = onIssue,
                    enabled = canIssue,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Verified, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.id_get_verified_credential))
                }
                issuanceDisabledHint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.id_done_keep_local_only))
                }
            }

            is TapIssuance.Submitting -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.id_submitting_to_host, IssuerSelection.issuerDisplayName(submittingHost)), style = MaterialTheme.typography.bodyMedium)
            }

            is TapIssuance.SubmittedForAnchor -> {
                StatusLabel(Icons.Filled.Verified, stringResource(R.string.id_submitted_anchoring), MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.id_anchoring_blurb_short),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
            }

            is TapIssuance.PendingReview -> {
                val preVerified = issuance.proofPreVerified
                StatusLabel(
                    if (preVerified) Icons.Filled.Verified else Icons.Filled.Search,
                    if (preVerified) stringResource(R.string.id_submitted_pre_verified) else stringResource(R.string.id_submitted_awaiting_review),
                    if (preVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    if (preVerified) {
                        stringResource(R.string.id_pending_pre_verified_blurb_short)
                    } else {
                        stringResource(R.string.id_pre_verification_failed_short, issuance.reason)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonoCaption(stringResource(R.string.id_pending_id_value, issuance.pendingId))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
            }

            is TapIssuance.Failed -> {
                StatusLabel(Icons.Filled.Warning, issuance.message, MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetryIssuance, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_try_again)) }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.id_done_keep_local_only))
                }
            }
        }
    }
}

// MARK: -- step error

@Composable
private fun ErrorStep(message: String, onTryAgain: () -> Unit, onEditDetails: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(60.dp))
        Text(stringResource(R.string.id_scan_didnt_finish), style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_try_again)) }
        OutlinedButton(onClick = onEditDetails, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.id_edit_details)) }
    }
}

// MARK: -- small shared building blocks

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun StatusLabel(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun MonoCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )
}

private fun kindIcon(kind: IDDocumentKind): ImageVector = when (kind) {
    IDDocumentKind.PASSPORT -> Icons.Filled.MenuBook
    IDDocumentKind.OTHER -> Icons.Filled.Search
}

/// Decode the raw DG2 portrait JPEG bytes into an ImageBitmap, remembered so
/// the decode runs once per byte array. Returns null when there is no portrait
/// or the platform decoder cannot open the encoding (e.g. JPEG2000 on older
/// Android). The read result carries raw bytes, not a Bitmap, so the decode
/// lives here in the Compose layer.
@Composable
private fun rememberPortraitBitmap(bytes: ByteArray?): ImageBitmap? =
    remember(bytes) {
        if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

/** Visual mask that renders a typed 8-digit YYYYMMDD value as "YYYY-MM-DD" while
 *  the field's underlying value stays the raw digits (so the cursor maps
 *  correctly and backspace deletes a digit). The separator is EAGER: the moment
 *  a group is complete it shows a trailing dash ("2017-" the instant the 4th
 *  digit is typed), so the next dash never lags behind the year/month. */
private val YyyymmddDashTransformation = VisualTransformation { text ->
    val digits = text.text.filter { it.isDigit() }.take(8)
    // Eager: a dash exists as soon as a group is complete (>= 4 / >= 6).
    val dash1 = digits.length >= 4
    val dash2 = digits.length >= 6
    val sb = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i == 4 || i == 6) sb.append('-')
        sb.append(c)
    }
    if (digits.length == 4 || digits.length == 6) sb.append('-')
    val mapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            offset + (if (offset >= 4 && dash1) 1 else 0) + (if (offset >= 6 && dash2) 1 else 0)

        override fun transformedToOriginal(offset: Int): Int {
            var o = offset
            if (dash1 && offset > 4) o -= 1
            if (dash2 && offset > 7) o -= 1
            return o.coerceIn(0, digits.length)
        }
    }
    TransformedText(AnnotatedString(sb.toString()), mapping)
}

/** Drop the century from a complete YYYYMMDD to the MRZ-shaped YYMMDD the chip's
 *  BAC key needs; returns "" until all 8 digits are present. */
private fun yymmddFromYYYYMMDD(digits: String): String {
    val d = digits.filter { it.isDigit() }
    return if (d.length == 8) d.substring(2) else ""
}

/** Expand a stored 2-digit-year YYMMDD back to the 4-digit-year YYYYMMDD the UI
 *  edits. A passport expiry is always in the 2000s; a birth date with a 2-digit
 *  year above 30 is treated as 19xx (matches the on-chip MRZ convention). */
private fun expandToYYYYMMDD(yymmdd: String, isExpiry: Boolean): String {
    val d = yymmdd.filter { it.isDigit() }
    if (d.length != 6) return ""
    val yy = d.substring(0, 2).toInt()
    val year = if (isExpiry) 2000 + yy else if (yy > 30) 1900 + yy else 2000 + yy
    return "%04d%s".format(year, d.substring(2))
}
