// First-run onboarding, mirroring the shipped iOS simplified flow:
//   Welcome -> { Create new identity -> set passphrase -> reveal + back up the
//   24 words -> mandatory encrypted backup } or { Restore encrypted backup ->
//   passphrase }.
// The passphrase feeds the master derivation AND the backup KDF (so a restore
// rebuilds the same identity). Backup export / restore use the Storage Access
// Framework (no Google Drive dependency; GrapheneOS-friendly).
//
// Visual parity with iOS OnboardingView / RecoveryView / VerifyPhraseView:
//   - Welcome: branded logo header, centered heading + tagline, three
//     checkmark-seal bullet rows in the brand purple, a prominent primary
//     action and a bordered secondary action.
//   - Backup words: the freshly generated 24 words via RecoveryPhraseGrid
//     (masked until tapped to reveal), a WARNING Banner about offline-only
//     storage, and a confirm-saved gate before the encrypted-backup step.
//   - Banners and the shared spacing / shape tokens carry the rest of the
//     polish.

package com.elabify.app.maknoon.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.RecoveryPhraseGrid
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step { WELCOME, CREATE_PASSPHRASE, BACKUP_WORDS, BACKUP, RESTORE, RESTORE_DONE }

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }

    var step by remember { mutableStateOf(Step.WELCOME) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sandwich by remember { mutableStateOf<IdentitySandwich?>(null) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreReport by remember {
        mutableStateOf<com.elabify.app.maknoon.backup.MaknoonBackupV4.RestoreReport?>(null)
    }

    // Backup-words step state (mirrors iOS reveal: tap to reveal, gate before
    // continuing).
    var wordsRevealed by remember { mutableStateOf(false) }
    var confirmedSavedOffline by remember { mutableStateOf(false) }

    fun now() = System.currentTimeMillis() / 1000

    // SAF: save the encrypted backup to a user-chosen location.
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; error = null
            try {
                val blob = withContext(Dispatchers.IO) {
                    sandwich!!.exportEncryptedBackup(
                        com.elabify.app.maknoon.backup.MaknoonBackupV4.buildExtra(context),
                    )
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(blob) }
                }
                onComplete()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
            }
        }
    }

    // SAF: pick a backup file to restore.
    val pickBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; error = null
            try {
                restoreBytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Onboarding is the full-screen root (no Scaffold / tab bar to
            // consume insets), and the app is edge-to-edge, so inset the content
            // within the system bars; otherwise the top sits under the status bar.
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        when (step) {
            Step.WELCOME -> WelcomeStep(
                onCreate = { step = Step.CREATE_PASSPHRASE },
                onRestore = { step = Step.RESTORE },
            )

            Step.CREATE_PASSPHRASE -> {
                Text("Set a password", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "A password is used to back up your identity and any local assets using " +
                        "post-quantum encryption.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions.Default,
                    singleLine = true,
                    shape = RoundedCornerShape(Radii.sm),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm, onValueChange = { confirm = it },
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(Radii.sm),
                    modifier = Modifier.fillMaxWidth(),
                )
                val mismatch = confirm.isNotEmpty() && passphrase != confirm
                if (mismatch) {
                    Text(
                        "Passwords do not match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Banner(
                    title = "Save it to a password manager NOW",
                    body = "This is the last time the password will be shown. Add it to a vetted " +
                        "password manager before you continue.",
                    variant = BannerVariant.WARNING,
                    icon = Icons.Filled.Lock,
                )

                Button(
                    enabled = passphrase.isNotEmpty() && passphrase == confirm && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = {
                        scope.launch {
                            busy = true; error = null
                            try {
                                val (s, _) = withContext(Dispatchers.IO) {
                                    IdentitySandwich.generateFresh(passphrase, now(), store)
                                }
                                sandwich = s
                                wordsRevealed = false
                                confirmedSavedOffline = false
                                step = Step.BACKUP_WORDS
                            } catch (e: Exception) {
                                error = e.message ?: e.toString()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Continue") }
            }

            Step.BACKUP_WORDS -> BackupWordsStep(
                words = sandwich?.recoveryWords().orEmpty(),
                revealed = wordsRevealed,
                onToggleReveal = { wordsRevealed = !wordsRevealed },
                confirmedSaved = confirmedSavedOffline,
                onConfirmChange = { confirmedSavedOffline = it },
                busy = busy,
                onContinue = { step = Step.BACKUP },
            )

            Step.BACKUP -> {
                Text("Save your encrypted backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Maknoon stores an encrypted backup wherever you choose, locked by your " +
                        "password. Without the password the post-quantum backup is useless to " +
                        "anyone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Banner(
                    title = "This step is required",
                    body = "Your verified credentials and local wallet data are recoverable only " +
                        "from this encrypted backup plus your password.",
                    variant = BannerVariant.INFO,
                )
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { saveBackup.launch(com.elabify.app.maknoon.backup.MaknoonBackupV4.defaultBackupFilename()) },
                ) { Text(if (busy) "Preparing..." else "Save encrypted backup") }
            }

            Step.RESTORE -> {
                Text("Restore from encrypted backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Pick the encrypted backup file you saved earlier. Decryption and verification " +
                        "happen entirely on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                ) { Text(if (restoreBytes == null) "Choose backup file" else "Backup file selected") }

                if (restoreBytes != null) {
                    OutlinedTextField(
                        value = passphrase, onValueChange = { passphrase = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(Radii.sm),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = passphrase.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radii.md),
                        onClick = {
                            scope.launch {
                                busy = true; error = null
                                try {
                                    val report = withContext(Dispatchers.IO) {
                                        com.elabify.app.maknoon.backup.MaknoonBackupV4.restore(
                                            context, restoreBytes!!, passphrase, now(), store,
                                        )
                                    }
                                    // Show a confirmation of what restored (and
                                    // anything that didn't) before continuing.
                                    restoreReport = report
                                    step = Step.RESTORE_DONE
                                } catch (e: Exception) {
                                    error = e.message ?: e.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text("Restore") }
                }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = {
                        restoreBytes = null
                        passphrase = ""
                        error = null
                        step = Step.WELCOME
                    },
                ) { Text("Back") }
            }

            Step.RESTORE_DONE -> {
                val report = restoreReport
                val hadWarnings = report?.hadWarnings == true
                Text(
                    if (hadWarnings) "Restore completed with warnings" else "Restore complete",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (hadWarnings) {
                        "Your wallet was restored, but some items could not be imported. " +
                            "Review them below before continuing."
                    } else {
                        "Everything in your backup was restored to this phone."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report?.restored?.takeIf { it.isNotEmpty() }?.let { items ->
                    Text("Restored", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (hadWarnings) {
                    Banner(
                        title = "Not imported",
                        body = report!!.warnings.joinToString("\n") { "• $it" },
                        variant = BannerVariant.WARNING,
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { onComplete() },
                ) { Text("Continue") }
            }
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        error?.let {
            Banner(
                title = "Something went wrong",
                body = it,
                variant = BannerVariant.ERROR,
            )
        }
    }
}

// MARK: -- welcome

@Composable
private fun WelcomeStep(
    onCreate: () -> Unit,
    onRestore: () -> Unit,
) {
    Spacer(Modifier.height(Spacing.lg))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        BrandLogo()
        Text(
            "Maknoon",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Own your Identity, Assets, and Privacy",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    Spacer(Modifier.height(Spacing.sm))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CheckmarkBullet("Scan a passport into your phone with post-quantum encryption")
        CheckmarkBullet("Manage digital assets with a secure hardware wallet")
        CheckmarkBullet("Privately use your identity and assets with those you verify and trust")
    }

    Spacer(Modifier.height(Spacing.sm))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Button(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radii.md),
            onClick = onCreate,
        ) { Text("Create new") }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radii.md),
            onClick = onRestore,
        ) { Text("Restore encrypted backup") }
    }
}

// Branded launcher-style logo tile: the deep-purple ground (#3A1259) the iOS
// app icon uses, with a soft drop shadow and the brand accent monogram, since
// the Android project does not ship a dedicated logo drawable yet.
@Composable
private fun BrandLogo() {
    val deepPurple = MaknoonBrand.deepPurple
    val accent = MaknoonBrand.accent
    val accentLight = MaknoonBrand.accentLight
    Box(
        modifier = Modifier
            .size(120.dp)
            .shadow(
                elevation = Elevation.walletCard,
                shape = RoundedCornerShape(Radii.xl),
                clip = false,
            )
            .clip(RoundedCornerShape(Radii.xl))
            .background(
                Brush.linearGradient(listOf(deepPurple, accent)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "M",
            color = accentLight,
            fontWeight = FontWeight.Bold,
            fontSize = 64.sp,
        )
    }
}

@Composable
private fun CheckmarkBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaknoonBrand.accent,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: -- backup words (reveal + confirm gate)

@Composable
private fun BackupWordsStep(
    words: List<String>,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    confirmedSaved: Boolean,
    onConfirmChange: (Boolean) -> Unit,
    busy: Boolean,
    onContinue: () -> Unit,
) {
    Text(
        "Your recovery phrase",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )

    Banner(
        title = "Store this OFFLINE",
        body = "The only safe place for these 24 words without encryption is paper or stamped " +
            "metal, stored OFFLINE, in a locked safe. Never type them into any computer or phone. " +
            "Never take a photo or screenshot. Anyone with this recovery phrase AND your password " +
            "can recreate your identity.",
        variant = BannerVariant.WARNING,
    )

    // The grid is tappable to toggle masking, mirroring the iOS reveal gesture.
    Box(modifier = Modifier.clickable(onClick = onToggleReveal)) {
        RecoveryPhraseGrid(
            words = words,
            masked = !revealed,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .clickable(onClick = onToggleReveal)
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (revealed) {
                "Hide before screen-sharing or putting the phone down."
            } else {
                "Tap to reveal. Tap again to hide before walking away or screen-sharing."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Confirm-saved gate, switch styled to the brand accent.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "I have written this on paper or metal and stored it OFFLINE. I understand losing it " +
                "means losing access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = confirmedSaved,
            onCheckedChange = onConfirmChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaknoonBrand.accent),
        )
    }

    Button(
        enabled = confirmedSaved && words.isNotEmpty() && !busy,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        onClick = onContinue,
    ) { Text("Continue") }
}
