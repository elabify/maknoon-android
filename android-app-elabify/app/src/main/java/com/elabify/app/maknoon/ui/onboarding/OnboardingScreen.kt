// First-run onboarding, mirroring the shipped iOS OnboardingView flow:
//   Welcome -> { Create new identity -> set password -> encrypted backup
//   (skippable) -> scan passport (skippable) -> first wallet (skippable) } or
//   { Restore encrypted backup -> password }.
// The password feeds the master derivation AND the backup KDF (so a restore
// rebuilds the same identity). Backup export / restore use the Storage Access
// Framework (no Google Drive dependency; GrapheneOS-friendly).
//
// The 24-word seed phrase is NOT shown or verified during onboarding (it stays
// viewable later in Settings, Local Key); the encrypted backup is the primary
// recovery path and can be skipped (not recommended).
//
// Visual parity with iOS OnboardingView:
//   - Welcome: the real Maknoon logo, centered heading + tagline, three
//     checkmark-seal bullet rows in the brand purple, a prominent primary
//     action and a bordered secondary action.
//   - Post-identity: optionally scan a passport to mint a credential, then
//     recommend a first wallet (hardware or Bitcoin software).
//   - Banners and the shared spacing / shape tokens carry the rest of the
//     polish.

package com.elabify.app.maknoon.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.devices.AddHardwareDeviceFlow
import com.elabify.app.maknoon.ui.iddocument.TapIDDocumentFlow
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletEnv
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Step {
    WELCOME, CREATE_PASSPHRASE, BACKUP,
    PASSPORT_SCAN, PASSPORT_SCAN_TAP,
    RECOMMEND_WALLET, RECOMMEND_WALLET_HW,
    RESTORE, RESTORE_DONE,
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }
    val deviceRegistry = remember { DeviceRegistry(context) }

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
                step = Step.PASSPORT_SCAN
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

    // The passport-scan and add-hardware sub-flows are full-screen Scaffolds; they
    // must NOT live inside a verticalScroll Column, so render them edge-to-edge.
    // Every other step is a section inside the scrolling Column below.
    when (step) {
        Step.PASSPORT_SCAN_TAP -> TapIDDocumentFlow(
            onDone = { step = Step.RECOMMEND_WALLET },
            onClose = { step = Step.PASSPORT_SCAN },
            skipKindPicker = true,
        )
        Step.RECOMMEND_WALLET_HW -> AddHardwareDeviceFlow(
            registry = deviceRegistry,
            onFinished = { _ -> onComplete() },
            onCancel = { step = Step.RECOMMEND_WALLET },
        )
        else -> Column(
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
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PassphraseField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = "Password",
                )
                PassphraseField(
                    value = confirm, onValueChange = { confirm = it },
                    label = "Confirm password",
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
                                step = Step.BACKUP
                            } catch (e: Exception) {
                                error = e.message ?: e.toString()
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("Continue") }
            }

            Step.BACKUP -> {
                Text("Encrypted backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Maknoon stores an encrypted backup wherever you choose, locked by your " +
                        "password. Without the password the post-quantum backup is useless to " +
                        "anyone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Banner(
                    title = "Strongly recommended",
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
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.PASSPORT_SCAN },
                ) { Text("Skip, not recommended") }
                Text(
                    "Without a backup, losing this phone means losing your identity and any " +
                        "software wallet keys. You can still create one later in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Step.PASSPORT_SCAN -> {
                Text("Scan your passport", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap your passport and Maknoon reads its chip on-device and mints an identity " +
                        "credential signed by your post-quantum key, that you can present from your " +
                        "phone. Nothing is uploaded unless you then choose an Elabify-verified " +
                        "credential.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.PASSPORT_SCAN_TAP },
                ) { Text("Scan passport") }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.RECOMMEND_WALLET },
                ) { Text("Skip for now") }
            }

            Step.RECOMMEND_WALLET -> {
                Text("Create your first wallet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Maknoon supports many digital asset networks. For holding value, we only " +
                        "recommend Bitcoin: it is the only cryptocurrency proven as a long-term " +
                        "store of value. The safest option is a hardware wallet, but a software " +
                        "Bitcoin wallet is convenient for small amounts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.RECOMMEND_WALLET_HW },
                ) { Text("Add a hardware wallet") }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = {
                        // Explicit user choice seeds the default Bitcoin software
                        // wallet (no longer auto-seeded behind the Wallet tab).
                        BitcoinWalletEnv.create(context).store.seedDefaultIfNeeded()
                        onComplete()
                    },
                ) { Text("Create Bitcoin software wallet") }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { onComplete() },
                ) { Text("Skip for now") }
            }

            Step.RESTORE -> {
                Text("Restore from encrypted backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Pick the encrypted backup file you saved earlier. Decryption and verification " +
                        "happen entirely on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                ) { Text(if (restoreBytes == null) "Choose backup file" else "Backup file selected") }

                if (restoreBytes != null) {
                    PassphraseField(
                        value = passphrase, onValueChange = { passphrase = it },
                        label = "Password",
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
                    color = MaterialTheme.colorScheme.onSurface,
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

            // Full-screen sub-flows handled by the outer `when`; never reached here.
            Step.PASSPORT_SCAN_TAP, Step.RECOMMEND_WALLET_HW -> Unit
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
            color = MaterialTheme.colorScheme.onSurface,
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
        CheckmarkBullet("Verify and share your passport as a digital identity")
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

// The real Maknoon logo (same 1024px artwork as the iOS app icon), rounded with
// a soft drop shadow to match the iOS welcome screen.
@Composable
private fun BrandLogo() {
    Image(
        painter = painterResource(R.drawable.maknoon_logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(120.dp)
            .shadow(
                elevation = Elevation.walletCard,
                shape = RoundedCornerShape(Radii.xl),
                clip = false,
            )
            .clip(RoundedCornerShape(Radii.xl)),
    )
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

