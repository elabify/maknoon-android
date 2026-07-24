// Per-credential present screen, ported from iOS CredentialPresentView.swift.
//
// Two modes via a segmented control at the top:
//
//   1. Privacy QR (default): renders a tiny PII-safe badge payload as a QR.
//      No claims, no holder pubkey, no PII. Static, so anyone with the QR
//      could replay it, but since the payload reveals nothing private the
//      replay is harmless. A verifier scanning it sees issuer + schema + cid
//      + anchor reference and can confirm the credential exists by checking
//      the on-chain anchor. The badge wire format (elabify-badge-1) matches
//      iOS BadgeQR.swift so a verifier can scan either platform's badge.
//
//   2. Share attributes / Respond: the claim-by-claim disclosure flow. Hands
//      off to PresentAttributesScreen, which has the pre-selected credential
//      (no picker). When opened after a scanned VerifierRequest it opens in
//      Respond mode with the required claims pinned on.
//
// Underneath both modes there is a collapsible "Technical details" section
// mirroring the old detail view.
//
// Stateless composable: all persistence (nickname, folder, remove) and the
// terminal share actions are callbacks the host wires to the SDK store. The
// signing path lives entirely in PresentAttributesScreen via
// com.elabify.musnad.present.PresentationBuilder.
//
// GMS-free. ZXing for QR (no ML Kit). No em-dashes.

package com.elabify.app.maknoon.ui.present

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.iddocument.isProductionChain
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.R
import com.elabify.musnad.present.AnchorEntry
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.Presentation
import com.elabify.musnad.present.VerifierRequest
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** Which top-level mode the segmented control shows. */
enum class PresentMode { BADGE, ATTRIBUTES }

/**
 * Per-credential present screen.
 *
 * @param credential        the parsed stored credential to present
 * @param nickname          the local-only nickname (not in the signed payload)
 * @param folderName        the current folder display label ("None" if unfiled)
 * @param availableFolders  (id, name) pairs the credential can be moved into
 * @param currentFolderId   the credential's current folder id, or null for root
 * @param pendingRequest    when non-null, opens in Respond mode with the
 *                          verifier's required claims pinned on
 * @param initialMode       initial segmented mode (BADGE for normal taps,
 *                          ATTRIBUTES when opened from a scanned request)
 * @param sandwich          the unlocked Identity Sandwich (signs the challenge)
 * @param dropHost          base host for the one-shot drop (POST /v1/drop)
 * @param onSetNickname     persist a new nickname (null clears it)
 * @param onAssignFolder    move the credential to a folder id (null for root)
 * @param onRemove          remove the credential from the wallet
 * @param onShared          record a successful share (verifierDid, channel, keys)
 * @param onBack            pop this screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPresentScreen(
    credential: ParsedCredential,
    nickname: String?,
    folderName: String,
    availableFolders: List<Pair<String, String>>,
    currentFolderId: String?,
    pendingRequest: VerifierRequest?,
    initialMode: PresentMode,
    sandwich: com.elabify.musnad.identity.IdentitySandwich,
    dropHost: String,
    onSetNickname: (String?) -> Unit,
    onAssignFolder: (String?) -> Unit,
    onRemove: () -> Unit,
    onShared: (verifierDid: String, channel: String, disclosedKeys: List<String>) -> Unit,
    onBack: () -> Unit,
    // Passport Show-QR is lean (ADR-0039): no Privacy-QR mode picker, nickname,
    // technical details, folder, or remove. Just the attribute (Build Online) QR.
    passportMode: Boolean = false,
) {
    var mode by remember { mutableStateOf(if (passportMode) PresentMode.ATTRIBUTES else initialMode) }
    var renameOpen by remember { mutableStateOf(false) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var technicalOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(schemaLabel(credential.header.schema)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!passportMode) {
                NicknameCard(nickname = nickname, onEdit = { renameOpen = true })

                // Segmented mode picker (Privacy QR vs Attribute QR / Respond),
                // mirroring the iOS .segmented Picker. Hidden for passports.
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = mode == PresentMode.BADGE,
                        onClick = { mode = PresentMode.BADGE },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.present_privacy_qr)) }
                    SegmentedButton(
                        selected = mode == PresentMode.ATTRIBUTES,
                        onClick = { mode = PresentMode.ATTRIBUTES },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(if (pendingRequest == null) stringResource(R.string.present_attribute_qr) else stringResource(R.string.present_respond)) }
                }
            }

            when (mode) {
                PresentMode.BADGE -> BadgeMode(credential)
                PresentMode.ATTRIBUTES -> PresentAttributesScreen(
                    credential = credential,
                    pendingRequest = pendingRequest,
                    sandwich = sandwich,
                    dropHost = dropHost,
                    onShared = onShared,
                    compact = passportMode,
                    // Done on the online/offline QR sheet returns to the passport.
                    onDone = onBack,
                )
            }

            if (!passportMode) {
                // Technical details (collapsible).
                SoftCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.present_technical_details),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { technicalOpen = !technicalOpen }) {
                            Icon(
                                if (technicalOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (technicalOpen) stringResource(R.string.present_collapse) else stringResource(R.string.present_expand),
                            )
                        }
                    }
                    if (technicalOpen) {
                        TechnicalDetails(credential, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp))
                    }
                }

                // Folder picker.
                SoftCard {
                    Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(R.string.present_folder), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = { folderMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(folderName)
                        }
                        Text(
                            stringResource(R.string.present_folder_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRemove,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.present_remove_credential))
                }
                Text(
                    stringResource(R.string.present_remove_credential_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            current = nickname.orEmpty(),
            onSave = { onSetNickname(it.ifBlank { null }); renameOpen = false },
            onClear = { onSetNickname(null); renameOpen = false },
            onDismiss = { renameOpen = false },
        )
    }

    if (folderMenuOpen) {
        FolderDialog(
            folders = availableFolders,
            currentFolderId = currentFolderId,
            onSelect = { id -> onAssignFolder(id); folderMenuOpen = false },
            onDismiss = { folderMenuOpen = false },
        )
    }
}

// MARK: -- Badge mode

@Composable
private fun BadgeMode(credential: ParsedCredential) {
    val payload = remember(credential.cid) { badgePayloadJson(credential) }
    // Production chains only; testnet anchors (Sepolia) are admin-only in the
    // issuer console, never shown in the client (ADR-0040).
    val anchors = (credential.anchor?.anchors ?: emptyList()).filter { isProductionChain(it.chain) }

    SoftCard {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.present_scan_to_verify), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            QrCode(
                content = payload,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(Radii.md))
                    .padding(Spacing.lg),
                sizePx = 720,
            )
            Text(
                stringResource(R.string.present_static_qr_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    SoftCard {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.present_what_this_shares), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Kv(stringResource(R.string.present_issuer), shortIssuer(credential.header.iss))
            Kv(stringResource(R.string.present_type), schemaLabel(credential.header.schema))
            Kv(stringResource(R.string.present_issued), formatDateUtc(credential.header.iat))
            credential.header.exp?.let { Kv(stringResource(R.string.present_expires), formatDateUtc(it)) }
            anchors.forEach { a ->
                Kv(stringResource(R.string.present_anchor_chain, caip2Label(a.chain)), shortHex(a.batchTxHash))
            }
        }
    }

    // The PII-safe reassurance is a green success Banner (the iOS green
    // "PII stays on this device" lock note).
    com.elabify.app.maknoon.ui.components.Banner(
        title = stringResource(R.string.present_pii_stays_title),
        variant = com.elabify.app.maknoon.ui.components.BannerVariant.SUCCESS,
        icon = Icons.Filled.Lock,
        body = stringResource(R.string.present_pii_stays_body),
        modifier = Modifier.fillMaxWidth(),
    )
}

/// A card with the brand soft-shadow elevation + the 18 dp wallet-card radius,
/// shared by every grouped section on this screen so they read consistently.
@Composable
private fun SoftCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) { content() }
}

// MARK: -- Technical details

@Composable
private fun TechnicalDetails(credential: ParsedCredential, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.present_header), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoKv("iss", credential.header.iss)
        MonoKv("sub", credential.header.sub)
        MonoKv("schema", credential.header.schema)
        MonoKv("cid", credential.header.cid)
        MonoKv("root", credential.header.root)
        MonoKv("iat", "${credential.header.iat}  (${iso8601Utc(credential.header.iat)})")
        credential.header.exp?.let { MonoKv("exp", "$it  (${iso8601Utc(it)})") }

        Text(stringResource(R.string.present_cryptography), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoKv("headerSig", credential.headerSig)
        val sigHex = credential.headerSig.removePrefix("0x")
        MonoKv("sigBytes", (sigHex.length / 2).toString())
        // Production chains only; testnet anchors (Sepolia) are admin-only in the
    // issuer console, never shown in the client (ADR-0040).
    val anchors = (credential.anchor?.anchors ?: emptyList()).filter { isProductionChain(it.chain) }
        anchors.forEachIndexed { idx, a ->
            val n = if (anchors.size > 1) " #${idx + 1}" else ""
            MonoKv("anchor$n chain", "${caip2Label(a.chain)} (${a.chain})")
            MonoKv("anchor$n registry", a.registry)
            MonoKv("anchor$n tx", a.batchTxHash)
            MonoKv("anchor$n batch root", a.batchRoot)
        }
    }
}

// MARK: -- small reusable rows + dialogs

@Composable
private fun NicknameCard(nickname: String?, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (!nickname.isNullOrEmpty()) nickname else stringResource(R.string.present_set_nickname),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.present_nickname_local_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.present_rename))
            }
        }
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun MonoKv(key: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.present_rename_credential_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.present_rename_credential_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextField(value = draft, onValueChange = { draft = it }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text(stringResource(R.string.present_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun FolderDialog(
    folders: List<Pair<String, String>>,
    currentFolderId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.present_move_to_folder)) },
        text = {
            Column {
                TextButton(onClick = { onSelect(null) }) {
                    Text(if (currentFolderId == null) stringResource(R.string.present_none_all_credentials_selected) else stringResource(R.string.present_none_all_credentials))
                }
                folders.forEach { (id, name) ->
                    TextButton(onClick = { onSelect(id) }) {
                        Text(if (currentFolderId == id) stringResource(R.string.present_folder_selected, name) else name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

// MARK: -- badge payload + formatting helpers

/**
 * Build the PII-safe badge payload JSON (elabify-badge-1), byte-shape matching
 * iOS BadgeQR.swift so a verifier can scan either platform's badge. Carries only
 * public identifiers (iss, sub, schema, cid, iat, optional exp) plus the on-chain
 * anchor reference(s). No claims, no holder pubkey.
 */
private fun badgePayloadJson(credential: ParsedCredential): String {
    val all = (credential.anchor?.anchors ?: emptyList()).map { a -> badgeAnchorJson(a) }
    val o = JSONObject()
    o.put("v", "elabify-badge-1")
    o.put("iss", credential.header.iss)
    o.put("sub", credential.header.sub)
    o.put("schema", credential.header.schema)
    o.put("cid", credential.header.cid)
    o.put("iat", credential.header.iat)
    credential.header.exp?.let { o.put("exp", it) }
    // First anchor under `anchor` (back-compat with elabify-badge-1 decoders),
    // the full list under `anchors` (ADR-0030 multi-network), matching iOS.
    all.firstOrNull()?.let { o.put("anchor", it) }
    if (all.isNotEmpty()) o.put("anchors", JSONArray(all))
    return o.toString()
}

private fun badgeAnchorJson(a: AnchorEntry): JSONObject =
    JSONObject()
        .put("chain", a.chain)
        .put("batchTxHash", a.batchTxHash)
        .put("batchRoot", a.batchRoot)
        .put("registry", a.registry)

/** Render a unix-seconds instant as a medium date in UTC (matches iOS). */
internal fun formatDateUtc(unix: Long): String {
    val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return "${fmt.format(Date(unix * 1000L))} UTC"
}

/** ISO 8601 / RFC 3339 in UTC, e.g. 2024-03-07T23:59:59Z (matches iOS). */
internal fun iso8601Utc(unix: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date(unix * 1000L))
}

/** Short 0x...tail hex for display. */
internal fun shortHex(hex: String): String {
    val s = hex.removePrefix("0x")
    return if (s.length <= 14) "0x$s" else "0x${s.take(8)}...${s.takeLast(6)}"
}

/** Trim a long DID to the trailing identifier for compact display. */
internal fun shortIssuer(did: String): String {
    val tail = did.substringAfterLast(":")
    return if (tail.isBlank()) did else tail
}

/** Best-effort CAIP-2 -> human chain label. */
internal fun caip2Label(chain: String): String = when (chain) {
    "eip155:11155111" -> "Sepolia"
    "eip155:1" -> "Ethereum"
    "eip155:59144" -> "Linea"
    "eip155:5000" -> "Mantle"
    "eip155:56" -> "BNB"
    else -> chain
}
