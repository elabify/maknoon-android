// Settings > Local Key, Encrypted backup, and Reset Maknoon, ported 1:1 from
// the iOS SettingsView.swift (localKeyDestination + recoverySection +
// lockdownSectionInline, encryptedBackupSection, resetMaknoonSection).
//
// These are the most sensitive Settings sub-screens: Local Key is the ONLY
// place the recovery phrase is revealed (behind a BiometricGate, the Android
// analog of the iOS Face-ID gate), Encrypted backup writes the AES-256-GCM +
// ML-DSA-65 blob via the Storage Access Framework, and Reset Maknoon wipes
// every key, wallet, credential, and setting on the device.
//
// Lockdown state has no iOS-shared SDK store on Android (iOS keeps it in
// BackupState), so this file owns a tiny LockdownState prefs helper. Once
// Lockdown is enabled the "Show seed phrase" / "Verify seed phrase" rows
// disappear and the inline Lockdown section is hidden, exactly as iOS hides
// recoverySection's reveal buttons and lockdownSectionInline under
// BackupState.lockdownEnabled.
//
// As with the rest of the Settings hub, each screen is its own Scaffold with a
// leading back arrow, an iOS-faithful grouped layout (a scrollable Column of
// rounded "section card" groups with SectionHeader titles), and in-screen
// state for the sheets/dialogs (no nav-graph). Labels match iOS exactly.

package com.elabify.app.maknoon.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.IDDocumentStore
import com.elabify.app.maknoon.ui.BiometricGate
import com.elabify.app.maknoon.ui.components.AdvancedSection
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.RecoveryPhraseGrid
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.app.maknoon.yubikey.SecondFactorRecoverDialog
import com.elabify.musnad.backup.EncryptedBackup
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Lockdown state. iOS keeps this in BackupState (a UserDefaults flag); Android
// has no shared SDK store for it, so this file owns a one-flag prefs helper.
// Once enabled, the reveal/verify-phrase rows disappear and the inline
// Lockdown section is hidden, matching iOS BackupState.lockdownEnabled.
// ---------------------------------------------------------------------------
private object LockdownState {
    private const val PREFS = "maknoon.backupstate.v1"
    private const val K_LOCKDOWN = "lockdown.enabled"

    fun enabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(K_LOCKDOWN, false)

    fun enable(context: android.content.Context) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(K_LOCKDOWN, true).apply()
    }
}

// ===========================================================================
// Local Key. iOS localKeyDestination is a Form with: an "Identity" section
// holding the Holder DID + Copy, the "Recovery" section (Show seed phrase /
// Verify seed phrase / Verify encrypted backup, with a lockdown notice when
// locked), and (only when not locked) the inline "Lockdown" section.
// ===========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalKeySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }

    var holderDid by remember { mutableStateOf<String?>(null) }
    var hasPassphrase by remember { mutableStateOf(false) }
    var didCopied by remember { mutableStateOf(false) }
    var lockdownEnabled by remember { mutableStateOf(LockdownState.enabled(context)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Sheet routing, mirroring iOS Route (showPhrase / verifyPhrase /
    // verifyBackup / lockdown). The revealed words ride along the showPhrase
    // case so a reveal can never present an empty grid.
    var revealedWords by remember { mutableStateOf<List<String>?>(null) }
    var sheet by remember { mutableStateOf<LocalKeySheet>(LocalKeySheet.None) }

    // Hoisted strings for non-composable callback / coroutine bodies below.
    val showPhraseTitle = stringResource(R.string.settings_show_recovery_phrase)
    val showPhraseSubtitle = stringResource(R.string.settings_show_recovery_phrase_subtitle)
    val couldNotReadRecovery = stringResource(R.string.settings_could_not_read_recovery)

    LaunchedEffect(Unit) {
        val sandwich = withContext(Dispatchers.IO) {
            runCatching { IdentitySandwich.load(store) }.getOrNull()
        }
        holderDid = sandwich?.holderDid
        hasPassphrase = sandwich?.hasPassphrase() ?: false
    }

    // Reveal the phrase behind a biometric gate, then load the words off the
    // sandwich. Mirrors iOS revealPhrase() (sandwich.recoveryMaterial under a
    // Face-ID localizedReason).
    //
    // ADR-0032: when a second factor is on, the recovery phrase is an
    // entropy-requiring reveal, so revealing it needs biometric AND a security
    // key tap. Biometric runs first (it always applies); then we prompt for the
    // key tap via the SecondFactorTapDialog, which recovers the entropy through
    // loadWithSecondFactor. Routine presentation signing is NOT gated by this.
    fun revealPhrase() {
        scope.launch {
            val approved = if (activity != null) {
                BiometricGate.authenticate(
                    activity,
                    title = showPhraseTitle,
                    subtitle = showPhraseSubtitle,
                )
            } else {
                true
            }
            if (!approved) return@launch
            if (store.secondFactorEnabled()) {
                // Tap a security key to recover the entropy. The dialog runs the
                // NFC tap + recompute and reports the recovered words back.
                sheet = LocalKeySheet.RevealWithKey
                return@launch
            }
            val sandwich = withContext(Dispatchers.IO) {
                runCatching { IdentitySandwich.load(store) }.getOrNull()
            }
            if (sandwich == null) {
                errorMessage = couldNotReadRecovery
                return@launch
            }
            revealedWords = withContext(Dispatchers.IO) {
                runCatching { sandwich.recoveryWords() }.getOrNull()
            }
            if (revealedWords == null) {
                errorMessage = couldNotReadRecovery
                return@launch
            }
            sheet = LocalKeySheet.ShowPhrase
        }
    }

    when (sheet) {
        LocalKeySheet.ShowPhrase -> {
            val words = revealedWords
            if (words != null) {
                RevealSheet(
                    words = words,
                    hasPassphrase = hasPassphrase,
                    onDone = { sheet = LocalKeySheet.None; revealedWords = null },
                )
                return
            }
        }
        LocalKeySheet.RevealWithKey -> {
            // ADR-0032 (OR-among-keys): the shared recovery coordinator lists the
            // enrolled factors, lets the user pick one when more than one is
            // enrolled, and routes by kind (YubiKey NFC tap + PIN, or Ledger /
            // Trezor BLE connect + approve). Any one recovers the same CEK; we
            // then rebuild the sandwich via loadWithSecondFactor to read the words.
            SecondFactorRecoverDialog(
                activity = activity,
                registry = remember { DeviceRegistry(context) },
                title = stringResource(R.string.settings_unlock_second_factor_title),
                message = stringResource(R.string.settings_unlock_second_factor_message),
                onRecovered = { cek ->
                    scope.launch {
                        val sandwich = withContext(Dispatchers.IO) {
                            runCatching { IdentitySandwich.loadWithSecondFactor(store) { cek } }.getOrNull()
                        }
                        revealedWords = sandwich?.let { runCatching { it.recoveryWords() }.getOrNull() }
                        sheet = if (revealedWords != null) LocalKeySheet.ShowPhrase else LocalKeySheet.None
                        if (revealedWords == null) errorMessage = "Could not read recovery material."
                    }
                },
                onError = { errorMessage = it; sheet = LocalKeySheet.None },
                onCancel = { sheet = LocalKeySheet.None },
            )
            return
        }
        LocalKeySheet.VerifyPhrase -> {
            VerifyPhraseSheet(
                store = store,
                activity = activity,
                onDone = { sheet = LocalKeySheet.None },
                onError = { errorMessage = it; sheet = LocalKeySheet.None },
            )
            return
        }
        LocalKeySheet.VerifyBackup -> {
            VerifyBackupSheet(onDone = { sheet = LocalKeySheet.None })
            return
        }
        LocalKeySheet.Lockdown -> {
            LockdownSheet(
                store = store,
                activity = activity,
                onCancel = { sheet = LocalKeySheet.None },
                onEnabled = {
                    LockdownState.enable(context)
                    lockdownEnabled = true
                    sheet = LocalKeySheet.None
                },
            )
            return
        }
        LocalKeySheet.None -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_local_key)) },
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
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            errorMessage?.let {
                Banner(
                    title = stringResource(R.string.settings_settings_error),
                    variant = BannerVariant.ERROR,
                    body = it,
                    trailing = { TextButton(onClick = { errorMessage = null }) { Text(stringResource(R.string.common_ok)) } },
                )
            }

            // iOS "Identity" section: the Holder DID with a Copy button.
            holderDid?.let { did ->
                SectionHeader(title = stringResource(R.string.settings_identity))
                SectionCardGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                stringResource(R.string.settings_holder_did),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(did))
                                    didCopied = true
                                    scope.launch {
                                        kotlinx.coroutines.delay(1_500)
                                        didCopied = false
                                    }
                                },
                            ) {
                                Icon(
                                    if (didCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    if (didCopied) stringResource(R.string.settings_copied) else stringResource(R.string.settings_copy),
                                    modifier = Modifier.padding(start = Spacing.xs),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Text(
                            did,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // iOS "Recovery" section. Verify encrypted backup is the only
            // recovery action shown to normal users; the seed-phrase tools and
            // Lockdown live under Advanced (0.6.1 friendliness pass).
            SectionHeader(title = stringResource(R.string.settings_recovery))
            SectionCardGroup {
                // Verifying an encrypted backup only needs the file + passphrase,
                // so it stays available even under Lockdown.
                ActionRow(
                    label = stringResource(R.string.settings_verify_encrypted_backup),
                    icon = Icons.Filled.Lock,
                    onClick = { sheet = LocalKeySheet.VerifyBackup },
                )
                if (lockdownEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResource(R.string.settings_lockdown_enabled_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Advanced: seed-phrase tools + Lockdown (only when not already locked).
            if (!lockdownEnabled) {
                AdvancedSection {
                    SectionCardGroup {
                        ActionRow(
                            label = stringResource(R.string.settings_show_seed_phrase),
                            icon = Icons.Filled.DocumentScanner,
                            onClick = { revealPhrase() },
                        )
                        ActionRow(
                            label = stringResource(R.string.settings_verify_seed_phrase),
                            icon = Icons.Filled.Shield,
                            onClick = { sheet = LocalKeySheet.VerifyPhrase },
                        )
                        ActionRow(
                            label = stringResource(R.string.settings_lockdown_wallet),
                            icon = Icons.Filled.LockReset,
                            tint = MaknoonColors.error,
                            onClick = { sheet = LocalKeySheet.Lockdown },
                        )
                    }
                    Text(
                        stringResource(R.string.settings_lockdown_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
            }
        }
    }
}

private enum class LocalKeySheet { None, ShowPhrase, RevealWithKey, VerifyPhrase, VerifyBackup, Lockdown }

// ---------------------------------------------------------------------------
// RevealSheet. iOS RevealSheet: an OFFLINE-only warning, the tap-to-reveal word
// grid, and (when set) the passphrase reminder.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RevealSheet(
    words: List<String>,
    hasPassphrase: Boolean,
    onDone: () -> Unit,
) {
    var revealed by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_seed_phrase)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.common_done)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Banner(
                title = stringResource(R.string.settings_offline_only),
                variant = BannerVariant.WARNING,
                body = stringResource(R.string.settings_offline_only_body),
            )
            RecoveryPhraseGrid(
                words = words,
                masked = !revealed,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { revealed = !revealed },
            )
            Text(
                if (revealed) stringResource(R.string.settings_hide_before_sharing) else stringResource(R.string.settings_tap_grid_to_reveal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasPassphrase) {
                Banner(
                    title = stringResource(R.string.settings_also_have_password),
                    variant = BannerVariant.INFO,
                    body = stringResource(R.string.settings_also_have_password_body),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// VerifyPhraseSheet. iOS verifyPhraseSheet asks the user to retype the 24
// words and confirms they match the on-device phrase, never revealing it.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyPhraseSheet(
    store: IdentityStore,
    activity: FragmentActivity?,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    var verified by remember { mutableStateOf(false) }

    val verifyTitle = stringResource(R.string.settings_verify_seed_phrase)
    val verifySubtitle = stringResource(R.string.settings_verify_seed_phrase_subtitle)
    val couldNotLoadPhrase = stringResource(R.string.settings_could_not_load_phrase)

    fun check() {
        scope.launch {
            val approved = if (activity != null) {
                BiometricGate.authenticate(
                    activity,
                    title = verifyTitle,
                    subtitle = verifySubtitle,
                )
            } else {
                true
            }
            if (!approved) return@launch
            val sandwich = withContext(Dispatchers.IO) {
                runCatching { IdentitySandwich.load(store) }.getOrNull()
            }
            if (sandwich == null) {
                onError(couldNotLoadPhrase)
                return@launch
            }
            val actual = sandwich.recoveryWords().map { it.lowercase() }
            val entered = typed.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (entered == actual) {
                mismatch = false
                verified = true
            } else {
                mismatch = true
            }
        }
    }

    val wordCount = typed.split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_verify_seed_phrase)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (verified) {
                Banner(
                    title = stringResource(R.string.settings_phrase_verified),
                    variant = BannerVariant.SUCCESS,
                    body = stringResource(R.string.settings_phrase_verified_body),
                    trailing = { TextButton(onClick = onDone) { Text(stringResource(R.string.common_done)) } },
                )
            } else {
                Text(
                    stringResource(R.string.settings_type_all_24_words),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.settings_separate_words_with_spaces),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it; mismatch = false },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = false,
                )
                if (mismatch) {
                    Text(
                        stringResource(R.string.settings_phrase_mismatch),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaknoonColors.error,
                    )
                }
                TextButton(
                    onClick = { check() },
                    enabled = wordCount == 24,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.common_continue))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// VerifyBackupSheet. iOS VerifyBackupSheet: pick a backup file, enter the
// passphrase, decrypt-and-discard (EncryptedBackup.verify), report pass/fail
// without changing anything on the device.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerifyBackupSheet(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var blob by remember { mutableStateOf<ByteArray?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<VerifyBackupStatus>(VerifyBackupStatus.Idle) }
    val wrongPasswordError = stringResource(R.string.settings_wrong_password_or_tampered)

    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = VerifyBackupStatus.Idle
            blob = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }.getOrNull()
            }
            fileName = uri.lastPathSegment
        }
    }

    fun verify() {
        val b = blob ?: return
        scope.launch {
            status = VerifyBackupStatus.Working
            val result = withContext(Dispatchers.IO) {
                runCatching { EncryptedBackup.verify(b, passphrase) }
            }
            status = if (result.isSuccess) {
                VerifyBackupStatus.Ok
            } else {
                VerifyBackupStatus.Failed(result.exceptionOrNull()?.message ?: wrongPasswordError)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_verify_encrypted_backup)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
                actions = { TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                stringResource(R.string.settings_verify_backup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { pickBackup.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    fileName ?: stringResource(R.string.settings_choose_backup_file),
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.common_password)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { verify() },
                enabled = blob != null && status != VerifyBackupStatus.Working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (status == VerifyBackupStatus.Working) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                } else {
                    Text(stringResource(R.string.settings_verify_backup_action))
                }
            }
            when (val s = status) {
                is VerifyBackupStatus.Ok -> Banner(
                    title = stringResource(R.string.settings_backup_verified),
                    variant = BannerVariant.SUCCESS,
                    body = stringResource(R.string.settings_backup_verified_body),
                )
                is VerifyBackupStatus.Failed -> Banner(
                    title = stringResource(R.string.settings_backup_did_not_open),
                    variant = BannerVariant.ERROR,
                    body = s.message,
                )
                else -> Unit
            }
        }
    }
}

private sealed interface VerifyBackupStatus {
    data object Idle : VerifyBackupStatus
    data object Working : VerifyBackupStatus
    data object Ok : VerifyBackupStatus
    data class Failed(val message: String) : VerifyBackupStatus
}

// ---------------------------------------------------------------------------
// LockdownSheet. iOS lockdownSheet steps: explain -> typeWords -> (typePassphrase
// if set) -> confirm -> enableLockdownNow. Irreversibly hides the reveal path.
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockdownSheet(
    store: IdentityStore,
    activity: FragmentActivity?,
    onCancel: () -> Unit,
    onEnabled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(LockdownStep.Explain) }
    var typedWords by remember { mutableStateOf("") }
    var typedPassphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val lockdownTitle = stringResource(R.string.settings_lockdown)
    val lockdownVerifySubtitle = stringResource(R.string.settings_lockdown_verify_phrase_subtitle)
    val couldNotVerifyPhrase = stringResource(R.string.settings_could_not_verify_phrase)
    val phraseMismatchError = stringResource(R.string.settings_phrase_mismatch)
    val couldNotVerifyPassword = stringResource(R.string.settings_could_not_verify_password)
    val passwordMismatchError = stringResource(R.string.settings_password_mismatch)

    fun checkWords() {
        scope.launch {
            val approved = if (activity != null) {
                BiometricGate.authenticate(
                    activity,
                    title = lockdownTitle,
                    subtitle = lockdownVerifySubtitle,
                )
            } else {
                true
            }
            if (!approved) return@launch
            val sandwich = withContext(Dispatchers.IO) {
                runCatching { IdentitySandwich.load(store) }.getOrNull()
            }
            if (sandwich == null) {
                error = couldNotVerifyPhrase
                return@launch
            }
            val actual = sandwich.recoveryWords().map { it.lowercase() }
            val entered = typedWords.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (entered != actual) {
                error = phraseMismatchError
                return@launch
            }
            error = null
            step = if (sandwich.hasPassphrase()) LockdownStep.TypePassphrase else LockdownStep.Confirm
        }
    }

    fun checkPassphrase() {
        scope.launch {
            val sandwich = withContext(Dispatchers.IO) {
                runCatching { IdentitySandwich.load(store) }.getOrNull()
            }
            // recoveryWords() does not return the passphrase; verify it by
            // re-deriving and round-tripping a backup. A wrong passphrase fails
            // the ML-DSA signature check on decrypt.
            if (sandwich == null) {
                error = couldNotVerifyPassword
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val blob = sandwich.exportEncryptedBackup()
                    EncryptedBackup.verify(blob, typedPassphrase)
                    true
                }.getOrDefault(false)
            }
            if (!ok) {
                error = passwordMismatchError
                return@launch
            }
            error = null
            step = LockdownStep.Confirm
        }
    }

    val wordCount = typedWords.split(Regex("\\s+")).filter { it.isNotEmpty() }.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_lockdown)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
                actions = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (step) {
                LockdownStep.Explain -> {
                    Banner(
                        title = stringResource(R.string.settings_cannot_be_undone),
                        variant = BannerVariant.WARNING,
                        body = stringResource(R.string.settings_lockdown_explain_body),
                    )
                    Text(
                        stringResource(R.string.settings_lockdown_recovery_options),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_lockdown_prove_backups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { step = LockdownStep.TypeWords },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_continue), color = MaknoonColors.warning)
                    }
                }

                LockdownStep.TypeWords -> {
                    Text(
                        stringResource(R.string.settings_type_all_24_words),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_separate_words_with_spaces),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = typedWords,
                        onValueChange = { typedWords = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        singleLine = false,
                    )
                    TextButton(
                        onClick = { checkWords() },
                        enabled = wordCount == 24,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_continue))
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaknoonColors.error)
                    }
                }

                LockdownStep.TypePassphrase -> {
                    Text(
                        stringResource(R.string.settings_type_your_password),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_must_match_onboarding_password),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = typedPassphrase,
                        onValueChange = { typedPassphrase = it; error = null },
                        label = { Text(stringResource(R.string.common_password)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = { checkPassphrase() },
                        enabled = typedPassphrase.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.common_continue))
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaknoonColors.error)
                    }
                }

                LockdownStep.Confirm -> {
                    Banner(
                        title = stringResource(R.string.settings_final_confirmation),
                        variant = BannerVariant.WARNING,
                        icon = Icons.Filled.LockReset,
                        body = stringResource(R.string.settings_lockdown_confirm_body),
                    )
                    TextButton(
                        onClick = onEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_enable_lockdown), color = MaknoonColors.error)
                    }
                }
            }
        }
    }
}

private enum class LockdownStep { Explain, TypeWords, TypePassphrase, Confirm }

// ===========================================================================
// Encrypted backup. iOS encryptedBackupSection: when a passphrase is set, a
// "Save encrypted backup…" action + status; otherwise a note that no
// passphrase was set so no backup can be encrypted.
// ===========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptedBackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }

    var hasPassphrase by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var backupWorking by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    // Manifest of what the just-saved backup contains, shown so it can be
    // eyeballed against the import confirmation.
    var exportSummary by remember { mutableStateOf<List<String>?>(null) }
    val savedFallbackName = stringResource(R.string.settings_encrypted_backup)
    val unknownError = stringResource(R.string.settings_unknown_error)
    val savedFmt = stringResource(R.string.settings_saved_backup)
    val saveFailedFmt = stringResource(R.string.settings_save_failed)

    LaunchedEffect(Unit) {
        val sandwich = withContext(Dispatchers.IO) {
            runCatching { IdentitySandwich.load(store) }.getOrNull()
        }
        hasPassphrase = sandwich?.hasPassphrase() ?: false
        loaded = true
    }

    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupWorking = true
            backupStatus = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sandwich = IdentitySandwich.load(store)
                        ?: throw IllegalStateException("Your wallet is locked.")
                    // When a second factor is on, the entropy is absent from a
                    // routine load, so exportEncryptedBackup throws a clear
                    // "tap your security key" error rather than producing a bad
                    // backup. The 24-word phrase remains the escape hatch.
                    val extra = com.elabify.app.maknoon.backup.MaknoonBackupV4.buildExtra(context)
                    val blob = sandwich.exportEncryptedBackup(extra)
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(blob) }
                    com.elabify.app.maknoon.backup.MaknoonBackupV4.summarize(context, extra).items
                }
            }
            backupWorking = false
            if (result.isSuccess) {
                statusIsError = false
                // lastPathSegment on a SAF document URI is the opaque document id
                // ("9"), not the filename. Query the real display name instead.
                val name = runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()
                backupStatus = String.format(savedFmt, name ?: savedFallbackName)
                exportSummary = result.getOrNull()
            } else {
                statusIsError = true
                backupStatus = String.format(saveFailedFmt, result.exceptionOrNull()?.message ?: unknownError)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_encrypted_backup)) },
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
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionHeader(title = stringResource(R.string.settings_encrypted_backup))
            if (loaded && hasPassphrase) {
                Text(
                    stringResource(R.string.settings_save_backup_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionCardGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !backupWorking) {
                                saveBackup.launch(com.elabify.app.maknoon.backup.MaknoonBackupV4.defaultBackupFilename())
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        if (backupWorking) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(Radii.xs))
                                    .background(MaknoonBrand.accent.tint(MaknoonColors.TintCellAlpha)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Upload,
                                    contentDescription = null,
                                    tint = MaknoonBrand.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Text(
                            stringResource(R.string.settings_save_encrypted_backup_action),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                backupStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (statusIsError) MaknoonColors.error else MaknoonColors.success,
                    )
                }
            } else if (loaded) {
                Text(
                    stringResource(R.string.settings_no_password_no_backup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    exportSummary?.let { items ->
        AlertDialog(
            onDismissRequest = { exportSummary = null },
            title = { Text(stringResource(R.string.settings_backup_saved)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(stringResource(R.string.settings_backup_includes), style = MaterialTheme.typography.bodyMedium)
                    items.forEach { Text(stringResource(R.string.settings_bullet_item, it), style = MaterialTheme.typography.bodySmall) }
                    Text(
                        stringResource(R.string.settings_compare_on_restore),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { exportSummary = null }) { Text(stringResource(R.string.common_done)) } },
        )
    }
}

// ===========================================================================
// Reset Maknoon. iOS resetMaknoonSection: a destructive "Reset Maknoon" action
// with a confirmation dialog ("Wipe everything") that clears the identity and
// all app data, then a done alert telling the user to restart.
// ===========================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetMaknoonScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showConfirm by remember { mutableStateOf(false) }
    var showDone by remember { mutableStateOf(false) }

    fun performReset() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    // Identity Sandwich: wipe the sealed material + drop the wrap key.
                    IdentityStore(context).wipe()
                    // ID documents: drop the vault key + clear the prefs.
                    IDDocumentStore(context).reset()
                    // Credentials DB (MaknoonStore): delete the encrypted DB file.
                    context.deleteDatabase("maknoon.db")
                    // Every remaining UserDefault-equivalent: clear all prefs files
                    // (RPC overrides, known issuers, address book, devices, installed
                    // dApps, lightning accounts). Equivalent to a reinstall.
                    val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                    prefsDir.listFiles()?.forEach { file ->
                        val name = file.name.removeSuffix(".xml")
                        context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                            .edit().clear().commit() // commit (sync) so the wipe lands before relaunch
                    }
                }
            }
            // Clearing prefs / deleting the DB does NOT reset the already-loaded
            // in-memory stores (the wallet lists, the identity sandwich, the live
            // Compose state), so the UI would keep showing the old wallets and
            // never reach onboarding. Relaunch into a FRESH process: every
            // singleton re-initializes from the now-empty stores, the app
            // re-evaluates identity (absent) and lands on the welcome screen.
            val relaunch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (relaunch != null) {
                relaunch.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK,
                )
                context.startActivity(relaunch)
                Runtime.getRuntime().exit(0)
            } else {
                // Fallback: can't relaunch automatically; tell the user to reopen.
                showDone = true
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_maknoon_question)) },
            text = {
                Text(
                    stringResource(R.string.settings_reset_confirm_body),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showConfirm = false; performReset() },
                ) {
                    Text(stringResource(R.string.settings_wipe_everything), color = MaknoonColors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showDone) {
        AlertDialog(
            onDismissRequest = { showDone = false; onBack() },
            title = { Text(stringResource(R.string.settings_maknoon_reset_done)) },
            text = {
                Text(
                    stringResource(R.string.settings_reset_done_body),
                )
            },
            confirmButton = {
                TextButton(onClick = { showDone = false; onBack() }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_reset_maknoon)) },
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
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            SectionHeader(title = stringResource(R.string.settings_reset_maknoon))
            SectionCardGroup {
                ActionRow(
                    label = stringResource(R.string.settings_reset_maknoon),
                    icon = Icons.Filled.LockReset,
                    tint = MaknoonColors.error,
                    onClick = { showConfirm = true },
                )
            }
            Text(
                stringResource(R.string.settings_reset_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared row + card helpers, local to this file (the hub's SectionCard / HubRow
// are private to SettingsScreen.kt). A tinted leading icon, the iOS label, and
// a tap target, matching the Networks screen's row style.
// ---------------------------------------------------------------------------
@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = MaknoonBrand.accent,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radii.xs))
                .background(tint.tint(MaknoonColors.TintCellAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (tint == MaknoonColors.error) MaknoonColors.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionCardGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}
