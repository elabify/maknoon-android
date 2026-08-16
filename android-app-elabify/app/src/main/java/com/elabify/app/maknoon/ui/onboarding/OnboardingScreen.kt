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

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.elabify.app.maknoon.crypto.PassphraseCharset
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
import androidx.compose.runtime.DisposableEffect
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
import com.elabify.app.maknoon.ui.devices.DiscoverHardwareWalletsScreen
import com.elabify.app.maknoon.ui.devices.persistDiscoveredSelection
import com.elabify.app.maknoon.ui.iddocument.TapIDDocumentFlow
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletEnv
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.windowInsetsPadding
import com.elabify.app.maknoon.ui.safeBarsInsets

private enum class Step {
    WELCOME, CREATE_PASSPHRASE, BACKUP,
    PASSPORT_SCAN, PASSPORT_SCAN_TAP,
    RECOMMEND_WALLET, RECOMMEND_WALLET_HW, RECOMMEND_WALLET_HW_DISCOVER,
    RESTORE, RESTORE_DONE,
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }
    val deviceRegistry = remember { DeviceRegistry(context) }

    // Lock the welcome / onboarding flow to portrait (the welcome screens are
    // designed portrait-first); restore the activity's default rotation when
    // onboarding finishes and this composable leaves composition.
    val onboardingActivity = remember(context) {
        var c: android.content.Context = context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return@remember c
            c = c.baseContext
        }
        null
    }
    DisposableEffect(onboardingActivity) {
        onboardingActivity?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            onboardingActivity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var step by remember { mutableStateOf(Step.WELCOME) }
    // The freshly-registered hardware device to sweep for existing wallets, handed
    // back by AddHardwareDeviceFlow so onboarding shows the SAME discover screen as
    // the Devices flow (Trezor passphrase handling lives in that screen).
    var hwDiscoverDevice by remember { mutableStateOf<RegisteredDevice?>(null) }
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
            // A Ledger/Trezor hands back a non-null device to sweep; show the
            // discover-wallets screen (as the Devices flow does) instead of
            // discarding it. Otherwise finish onboarding.
            onFinished = { discoverTarget ->
                if (discoverTarget != null) {
                    hwDiscoverDevice = discoverTarget
                    step = Step.RECOMMEND_WALLET_HW_DISCOVER
                } else {
                    onComplete()
                }
            },
            onCancel = { step = Step.RECOMMEND_WALLET },
        )
        Step.RECOMMEND_WALLET_HW_DISCOVER -> {
            val dev = hwDiscoverDevice
            if (dev == null) {
                onComplete()
            } else {
                DiscoverHardwareWalletsScreen(
                    registry = deviceRegistry,
                    device = dev,
                    onDone = { selected ->
                        if (selected.isEmpty()) {
                            onComplete()
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    persistDiscoveredSelection(
                                        context = context.applicationContext,
                                        registry = deviceRegistry,
                                        device = dev,
                                        selected = selected,
                                    )
                                }
                                onComplete()
                            }
                        }
                    },
                )
            }
        }
        else -> Column(
        modifier = Modifier
            .fillMaxSize()
            // Onboarding is the full-screen root (no Scaffold / tab bar to
            // consume insets), and the app is edge-to-edge, so inset the content
            // within the system bars; otherwise the top sits under the status bar.
            .windowInsetsPadding(safeBarsInsets())
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
                Text(stringResource(R.string.onboarding_set_a_password), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.onboarding_no_username_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                PassphraseField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = stringResource(R.string.common_password),
                )
                PassphraseField(
                    value = confirm, onValueChange = { confirm = it },
                    label = stringResource(R.string.onboarding_confirm_password),
                )
                val mismatch = confirm.isNotEmpty() && passphrase != confirm
                if (mismatch) {
                    Text(
                        stringResource(R.string.onboarding_passwords_do_not_match),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Hidden unless the password actually contains something we
                // cannot carry through PBKDF2 identically to a spec-conformant
                // wallet, so almost nobody ever sees it. Blocks at CREATE only;
                // the restore paths warn instead. See PassphraseCharset.
                val badChars = PassphraseCharset.offendingCodePoints(passphrase)
                if (badChars.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.onboarding_password_unsafe_characters,
                            PassphraseCharset.describe(badChars),
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Banner(
                    title = stringResource(R.string.onboarding_save_password_now),
                    body = stringResource(R.string.onboarding_save_password_now_body),
                    variant = BannerVariant.WARNING,
                    icon = Icons.Filled.Lock,
                )

                Button(
                    enabled = passphrase.isNotEmpty() && passphrase == confirm &&
                        badChars.isEmpty() && !busy,
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
                ) { Text(stringResource(R.string.common_continue)) }
            }

            Step.BACKUP -> {
                Text(stringResource(R.string.settings_encrypted_backup), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.onboarding_backup_where_you_choose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Banner(
                    title = stringResource(R.string.onboarding_backup_recommended),
                    body = stringResource(R.string.onboarding_backup_recommended_body),
                    variant = BannerVariant.INFO,
                )
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { saveBackup.launch(com.elabify.app.maknoon.backup.MaknoonBackupV4.defaultBackupFilename()) },
                ) {
                    Text(
                        stringResource(
                            if (busy) R.string.app_preparing else R.string.onboarding_save_encrypted_backup,
                        ),
                    )
                }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.PASSPORT_SCAN },
                ) { Text(stringResource(R.string.onboarding_skip_not_recommended)) }
                Text(
                    stringResource(R.string.onboarding_without_a_backup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Step.PASSPORT_SCAN -> {
                Text(stringResource(R.string.onboarding_tap_your_passport), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.onboarding_passport_chip_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.PASSPORT_SCAN_TAP },
                ) { Text(stringResource(R.string.onboarding_add_passport)) }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.RECOMMEND_WALLET },
                ) { Text(stringResource(R.string.onboarding_skip_for_now)) }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CheckmarkBullet(stringResource(R.string.onboarding_bullet_read_here))
                    CheckmarkBullet(stringResource(R.string.onboarding_bullet_stays_here))
                    CheckmarkBullet(stringResource(R.string.onboarding_bullet_sent_only_if_you_choose))
                }
            }

            Step.RECOMMEND_WALLET -> {
                Text(stringResource(R.string.onboarding_create_your_first_wallet), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.onboarding_bitcoin_only_recommendation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Button(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { step = Step.RECOMMEND_WALLET_HW },
                ) { Text(stringResource(R.string.onboarding_add_a_hardware_wallet)) }
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
                ) { Text(stringResource(R.string.onboarding_create_bitcoin_software_wallet)) }
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { onComplete() },
                ) { Text(stringResource(R.string.onboarding_skip_for_now)) }
            }

            Step.RESTORE -> {
                Text(stringResource(R.string.onboarding_restore_from_encrypted_backup), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.onboarding_pick_backup_file_blurb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                ) {
                    Text(
                        stringResource(
                            if (restoreBytes == null) R.string.onboarding_choose_backup_file
                            else R.string.onboarding_backup_file_selected,
                        ),
                    )
                }

                if (restoreBytes != null) {
                    Banner(
                        title = stringResource(R.string.onboarding_restore_replaces),
                        variant = BannerVariant.WARNING,
                        body = stringResource(R.string.onboarding_restore_replaces_body),
                    )
                    PassphraseField(
                        value = passphrase, onValueChange = { passphrase = it },
                        label = stringResource(R.string.common_password),
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
                    ) { Text(stringResource(R.string.onboarding_restore)) }
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
                ) { Text(stringResource(R.string.common_back)) }
            }

            Step.RESTORE_DONE -> {
                val report = restoreReport
                val hadWarnings = report?.hadWarnings == true
                Text(
                    stringResource(
                        if (hadWarnings) R.string.onboarding_restore_completed_with_warnings
                        else R.string.onboarding_restore_complete,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (hadWarnings) R.string.onboarding_restored_with_warnings_body
                        else R.string.onboarding_restored_everything_body,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                report?.restored?.takeIf { it.isNotEmpty() }?.let { items ->
                    Text(stringResource(R.string.onboarding_restored), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (hadWarnings) {
                    Banner(
                        title = stringResource(R.string.onboarding_not_imported),
                        body = report!!.warnings.joinToString("\n") { "• $it" },
                        variant = BannerVariant.WARNING,
                    )
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.md),
                    onClick = {
                        // A restored backup can carry a different app language
                        // than the device was using. The preference is already
                        // written, but stringResource() resolves against the
                        // Activity's Configuration, so the UI would stay in the
                        // old language until the next launch. recreate() re-runs
                        // attachBaseContext, which is what the language picker
                        // does. Deferred to here so the confirmation the user is
                        // reading is not torn down under them.
                        if (restoreReport?.languageChanged == true) {
                            (context as? android.app.Activity)?.recreate()
                        } else {
                            onComplete()
                        }
                    },
                ) { Text(stringResource(R.string.common_continue)) }
            }

            // Full-screen sub-flows handled by the outer `when`; never reached here.
            Step.PASSPORT_SCAN_TAP, Step.RECOMMEND_WALLET_HW,
            Step.RECOMMEND_WALLET_HW_DISCOVER -> Unit
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        error?.let {
            Banner(
                title = stringResource(R.string.id_something_went_wrong),
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
            stringResource(R.string.onboarding_own_your_identity_assets),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onboarding_your_data_lives_on),
            style = MaterialTheme.typography.bodySmall,
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
        CheckmarkBullet(stringResource(R.string.onboarding_verify_and_carry_your_passport))
        CheckmarkBullet(stringResource(R.string.onboarding_hold_assets_in_a))
        CheckmarkBullet(stringResource(R.string.onboarding_share_only_what_you))
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
        ) { Text(stringResource(R.string.onboarding_create_new)) }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radii.md),
            onClick = onRestore,
        ) { Text(stringResource(R.string.onboarding_restore_encrypted_backup)) }
        Text(
            stringResource(R.string.onboarding_use_applications_with_your),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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

