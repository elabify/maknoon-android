// The global Settings hub, ported 1:1 from the iOS SettingsView.swift.
//
// iOS SettingsView is a grouped Form with three blocks, in this exact order:
//   1. A single Section of nine NavigationLink rows:
//        Local Key, Identity, Devices, Networks, Apps, Address book,
//        Currency, Display, About.
//   2. The Encrypted backup section.
//   3. The Reset Maknoon (destructive wipe) section.
// On Android we render the same three blocks as section-grouped rows inside a
// scrollable Column under a Scaffold titled "Settings".
//
// Navigation is in-screen state (the IdentityScreen / WalletScreen sealed-route
// + BackHandler pattern) so the system back button pops the open sub-screen back
// to the hub instead of leaving Settings. Each row routes to its dedicated
// sub-screen composable. Devices routes to the EXISTING
// com.elabify.app.maknoon.ui.devices.DevicesScreen(registry, onBack); every
// other sub-screen has a fixed composable name + signature (declared in this
// file's routing block) that the dedicated sub-screen files implement.

package com.elabify.app.maknoon.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.devices.DevicesScreen
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.musnad.devices.DeviceRegistry

// In-screen routes layered above the hub. Each is popped by a BackHandler back
// to [SettingsRoute.Hub]. The order of the data objects mirrors the iOS
// topLevelSections row order, then the two trailing sections.
sealed interface SettingsRoute {
    data object Hub : SettingsRoute
    data object LocalKey : SettingsRoute
    data object Identity : SettingsRoute
    data object Devices : SettingsRoute
    data object Networks : SettingsRoute
    data object Apps : SettingsRoute
    data object AddressBook : SettingsRoute
    data object Currency : SettingsRoute
    data object Display : SettingsRoute
    data object About : SettingsRoute
    data object EncryptedBackup : SettingsRoute
    data object ResetMaknoon : SettingsRoute
}

/**
 * The global Settings hub. [onBack] pops Settings off the caller (the per-tab
 * gear opened it), returning to the tab. [deviceRegistry] is the single
 * long-lived SDK device list the rest of the app observes; it is handed to the
 * existing DevicesScreen when the Devices row is opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceRegistry: DeviceRegistry,
    onBack: () -> Unit,
) {
    var route by remember { mutableStateOf<SettingsRoute>(SettingsRoute.Hub) }

    when (route) {
        is SettingsRoute.Hub -> {
            // At the hub, the system back button leaves Settings entirely.
            BackHandler { onBack() }
            SettingsHub(
                onOpen = { route = it },
                onBack = onBack,
            )
        }

        is SettingsRoute.LocalKey -> {
            BackHandler { route = SettingsRoute.Hub }
            LocalKeySettingsScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.Identity -> {
            BackHandler { route = SettingsRoute.Hub }
            IdentitySettingsScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.Devices -> {
            BackHandler { route = SettingsRoute.Hub }
            DevicesScreen(registry = deviceRegistry, onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.Networks -> {
            BackHandler { route = SettingsRoute.Hub }
            NetworksSettingsScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.Apps -> {
            BackHandler { route = SettingsRoute.Hub }
            AppsSettingsScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.AddressBook -> {
            BackHandler { route = SettingsRoute.Hub }
            AddressBookScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.Currency -> {
            BackHandler { route = SettingsRoute.Hub }
            CurrencySettingsScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.Display -> {
            BackHandler { route = SettingsRoute.Hub }
            DisplaySettingsScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.About -> {
            BackHandler { route = SettingsRoute.Hub }
            AboutScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.EncryptedBackup -> {
            BackHandler { route = SettingsRoute.Hub }
            EncryptedBackupScreen(onBack = { route = SettingsRoute.Hub })
        }

        is SettingsRoute.ResetMaknoon -> {
            BackHandler { route = SettingsRoute.Hub }
            ResetMaknoonScreen(onBack = { route = SettingsRoute.Hub })
        }
    }
}

// A single hub row: a leading Material icon, the iOS label, and a chevron.
// Mirrors the iOS Label-in-NavigationLink rows.
private data class HubEntry(
    @StringRes val label: Int,
    val icon: ImageVector,
    val route: SettingsRoute,
)

// The nine rows in iOS topLevelSections order. Material-icon equivalents of the
// iOS SF Symbols: key.fill -> Key, person.crop.circle.badge.checkmark ->
// VerifiedUser, key.radiowaves.forward.fill -> Key, network -> Hub,
// square.grid.2x2 -> Apps, person.text.rectangle -> ContactPage,
// dollarsign.circle -> Paid, paintbrush -> Palette, info.circle -> Info.
private val TOP_LEVEL_ENTRIES: List<HubEntry> = listOf(
    HubEntry(R.string.settings_local_key, Icons.Filled.Key, SettingsRoute.LocalKey),
    HubEntry(R.string.settings_identity, Icons.Filled.VerifiedUser, SettingsRoute.Identity),
    HubEntry(R.string.settings_devices, Icons.Filled.Key, SettingsRoute.Devices),
    HubEntry(R.string.settings_networks, Icons.Filled.Hub, SettingsRoute.Networks),
    HubEntry(R.string.settings_apps, Icons.Filled.Apps, SettingsRoute.Apps),
    HubEntry(R.string.settings_address_book, Icons.Filled.ContactPage, SettingsRoute.AddressBook),
    HubEntry(R.string.settings_currency, Icons.Filled.Paid, SettingsRoute.Currency),
    HubEntry(R.string.settings_display, Icons.Filled.Palette, SettingsRoute.Display),
    HubEntry(R.string.settings_about, Icons.Filled.Info, SettingsRoute.About),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHub(
    onOpen: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
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
            // Block 1: the nine navigation rows, grouped into one rounded card
            // (the iOS first Section).
            SectionCard {
                TOP_LEVEL_ENTRIES.forEach { entry ->
                    HubRow(entry = entry, onClick = { onOpen(entry.route) })
                }
            }

            // Block 2: Encrypted backup. iOS renders this as its own Section
            // with an explanatory body; the row opens the backup sub-screen.
            SectionHeader(title = stringResource(R.string.settings_encrypted_backup))
            SectionCard {
                HubRow(
                    entry = HubEntry(
                        R.string.settings_encrypted_backup,
                        Icons.Filled.Key,
                        SettingsRoute.EncryptedBackup,
                    ),
                    onClick = { onOpen(SettingsRoute.EncryptedBackup) },
                )
            }

            // Block 3: Reset Maknoon, the destructive wipe (iOS resetMaknoonSection).
            SectionHeader(title = stringResource(R.string.settings_reset_maknoon))
            SectionCard {
                HubRow(
                    entry = HubEntry(
                        R.string.settings_reset_maknoon,
                        Icons.Filled.Settings,
                        SettingsRoute.ResetMaknoon,
                    ),
                    onClick = { onOpen(SettingsRoute.ResetMaknoon) },
                    tint = MaknoonColors.error,
                )
            }
        }
    }
}

// A rounded "section card" group, the Android stand-in for an iOS Form Section.
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun HubRow(
    entry: HubEntry,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaknoonBrand.accent,
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
            Icon(entry.icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(entry.label),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (tint == MaknoonColors.error) MaknoonColors.error else MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Sub-screen contracts. These exact composable names + signatures are
// implemented in their own files. Devices is NOT listed here: it reuses the
// already-shipped DevicesScreen(registry, onBack).
//
//   fun LocalKeySettingsScreen(onBack: () -> Unit)
//   fun IdentitySettingsScreen(onBack: () -> Unit)
//   fun NetworksSettingsScreen(onBack: () -> Unit)
//   fun AppsSettingsScreen(onBack: () -> Unit)
//   fun AddressBookScreen(onBack: () -> Unit)
//   fun CurrencySettingsScreen(onBack: () -> Unit)
//   fun DisplaySettingsScreen(onBack: () -> Unit)
//   fun AboutScreen(onBack: () -> Unit)
//   fun EncryptedBackupScreen(onBack: () -> Unit)
//   fun ResetMaknoonScreen(onBack: () -> Unit)
//
// AddressBookScreen / CurrencySettingsScreen / DisplaySettingsScreen /
// AboutScreen are implemented in SettingsMisc.kt.
// ---------------------------------------------------------------------------
