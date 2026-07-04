// The Apps tab. Android port of the iOS AppsView.swift. Two sections, in iOS
// order:
//
//   1. "Installed" - the apps the holder has installed from an Apps catalog
//      (MiniAppInstallRegistry.installedApps). Empty by default; the empty state
//      is "No apps installed yet". Tapping an installed app opens its detail
//      sheet (icon, channel + version, summary, the permissions it can use with
//      revoke toggles, Open, Uninstall). The trailing "+" opens the browse view
//      to install more.
//
//   2. "Connected verifiers" - the verifier history (every successful Share),
//      grouped by verifier DID, newest-first, with the last-share time and a
//      per-verifier share count.
//
// Toolbar mirrors iOS: a LEADING gear (opens the global Settings hub) and a
// TRAILING "+" (opens the browse view). System back from the browse / a
// launched app / the settings hub returns to the list rather than leaving the
// tab.
//
// CATALOG + INSTALL: the catalog model, the seed catalog, and the install
// registry live in the miniapp package (MiniAppInstallRegistry.kt), shared with
// Settings > Apps. Browse filters out beta-channel entries unless "Show beta
// apps" is on (installed apps are never filtered); installing collects the
// declared capabilities as consent and writes them to the bridge's grant store;
// uninstalling evicts the app's settings, merchant identity, and cached bundle.
//
// GMS-free.

package com.elabify.app.maknoon.ui.miniapp

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.CapabilityTier
import com.elabify.app.maknoon.miniapp.DAppCompatibility
import com.elabify.app.maknoon.miniapp.DefaultMiniAppHandlerFactory
import com.elabify.app.maknoon.miniapp.MiniAppCapabilityRegistry
import com.elabify.app.maknoon.miniapp.MiniAppCatalogEntry
import com.elabify.app.maknoon.miniapp.MiniAppCatalogFetcher
import com.elabify.app.maknoon.miniapp.groupCatalogForBrowse
import com.elabify.app.maknoon.miniapp.MiniAppCatalogSettings
import com.elabify.app.maknoon.miniapp.MiniAppInstallRegistry
import com.elabify.app.maknoon.miniapp.MiniAppSettingsStore
import com.elabify.app.maknoon.miniapp.SEED_CATALOG
import com.elabify.app.maknoon.miniapp.SEED_CATALOG_NAME
import com.elabify.app.maknoon.ui.components.EmptyState
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.settings.SettingsScreen
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.present.VerifierHistory
import com.elabify.musnad.present.VerifierHistoryGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(resetKey: Int = 0) {
    val context = LocalContext.current
    // The hosting activity, used so device.authenticate can present a
    // BiometricPrompt (it needs a FragmentActivity).
    val activity = context as? FragmentActivity

    // Per-host stores + factory. The settings store is shared so the merchant
    // display-name override, per-app durable storage, AND granted capabilities
    // persist across launches.
    val settingsStore = remember { MiniAppSettingsStore(context) }
    val registry = remember(settingsStore) { MiniAppInstallRegistry(context, settingsStore) }
    val catalogSettings = remember(settingsStore) { MiniAppCatalogSettings(settingsStore) }
    val handlerFactory = remember(settingsStore) {
        DefaultMiniAppHandlerFactory(
            context = context,
            settingsStore = settingsStore,
            activityProvider = { activity },
        )
    }
    // The approval-sheet host MUST share the factory's coordinators so the
    // tokens the payment / commerce handlers stash resolve when the sheet shows.
    val approvalSheetHost = remember(handlerFactory) {
        MiniAppApprovalSheetHostImpl(
            paymentCoordinator = handlerFactory.paymentCoordinator,
            commerceCoordinator = handlerFactory.commerceCoordinator,
        )
    }

    // Bump to re-read the install registry after an install / uninstall.
    var refreshKey by remember { mutableIntStateOf(0) }
    val installed = remember(refreshKey) { registry.installedApps() }

    var launched by remember { mutableStateOf<MiniAppInstallRegistry.InstalledApp?>(null) }
    var detailFor by remember { mutableStateOf<MiniAppInstallRegistry.InstalledApp?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showBrowse by remember { mutableStateOf(false) }

    // Re-tap-to-home: re-tapping the Apps tab while already on it closes any
    // launched app / detail / sheet, returning to the apps grid (iOS parity).
    LaunchedEffect(resetKey) {
        if (resetKey > 0) {
            launched = null
            detailFor = null
            showSettings = false
            showBrowse = false
        }
    }

    // Connected-verifiers history, grouped by verifier, newest-first. Read off
    // the IO thread from the encrypted Room DB through the SDK VerifierHistory.
    val verifierGroups by produceState(initialValue = emptyList<VerifierHistoryGroup>()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                VerifierHistory(MaknoonStore.open(context)).groupedByVerifier()
            }.getOrDefault(emptyList())
        }
    }

    // The leading gear opens the global Settings hub; system back returns here.
    if (showSettings) {
        BackHandler(enabled = true) { showSettings = false }
        SettingsScreen(
            deviceRegistry = remember { DeviceRegistry(context) },
            onBack = { showSettings = false },
        )
        return
    }

    // The trailing "+" opens the catalog-LIST view (iOS BrowseAppStoreView):
    // a list of Apps catalogs; tapping one drills into its apps. System back
    // pops the per-catalog apps screen back to the list, then the list back to
    // the tab.
    if (showBrowse) {
        // Which catalog the user drilled into (null = showing the catalog list).
        var openCatalog by remember { mutableStateOf<MiniAppCatalogSummary?>(null) }
        // Fetch the LIVE catalog (parity with iOS AppStoreRegistry.refresh);
        // start from the offline seed and replace it when the fetch lands. A
        // failed/empty fetch keeps the seed, so the tab never shows empty.
        var catalogEntries by remember { mutableStateOf(SEED_CATALOG) }
        var catalogRefreshing by remember { mutableStateOf(false) }
        val catalogScope = rememberCoroutineScope()
        suspend fun loadCatalog() {
            catalogRefreshing = true
            MiniAppCatalogFetcher.fetch()?.takeIf { it.isNotEmpty() }?.let { catalogEntries = it }
            catalogRefreshing = false
        }
        LaunchedEffect(Unit) { loadCatalog() }
        val catalogs = listOf(
            MiniAppCatalogSummary(MiniAppCatalogEntry.DEFAULT_STORE_ID, SEED_CATALOG_NAME, catalogEntries),
        )
        val current = openCatalog
        if (current == null) {
            BackHandler(enabled = true) { showBrowse = false }
            BrowseCatalogListScreen(
                catalogs = catalogs,
                onOpenCatalog = { openCatalog = it },
                onBack = { showBrowse = false },
            )
        } else {
            BackHandler(enabled = true) { openCatalog = null }
            BrowseAppStoreScreen(
                catalogName = current.name,
                entries = current.entries,
                showBetaApps = catalogSettings.showBetaApps(),
                isInstalled = { entry -> registry.isInstalled(MiniAppCatalogEntry.DEFAULT_STORE_ID, entry.appId) },
                installedVersion = { appId -> registry.installedApps().firstOrNull { it.entry.appId == appId }?.entry?.version },
                isRefreshing = catalogRefreshing,
                onRefresh = { catalogScope.launch { loadCatalog() } },
                // Installing a runnable app opens it immediately (iOS parity): the
                // install sheet's Install / Open / channel choice all land here with
                // the chosen variant.
                onOpen = { entry ->
                    registry.install(entry) // upsert; installs/switches to the chosen channel + granted
                    refreshKey++
                    showBrowse = false
                    openCatalog = null
                    launched = registry.installedApps().firstOrNull { it.entry.appId == entry.appId }
                },
                onBack = { openCatalog = null },
            )
        }
        return
    }

    val current = launched
    if (current != null) {
        // System back returns to the list rather than leaving the tab.
        BackHandler(enabled = true) { launched = null }
        val granted = remember(current.installedAppId) {
            registry.grantedCapabilities(current.installedAppId).ifEmpty { current.entry.permissions }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(current.entry.title) },
                    navigationIcon = {
                        IconButton(onClick = { launched = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.app_back_to_apps),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            // Open-time compatibility recheck: the host app version can change
            // after install, so re-evaluate the installed entry's bounds against
            // the CURRENT host and warn (non-blocking) if it's now out of range.
            val openCompat = remember(current.installedAppId) {
                DAppCompatibility.evaluate(
                    current.entry.requiresMaknoonVersion, current.entry.supersededAtMaknoonVersion,
                )
            }
            var compatWarnDismissed by remember(current.installedAppId) { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (openCompat.warnsAtOpen && !compatWarnDismissed) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaknoonColors.warning.copy(alpha = 0.12f))
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaknoonColors.warning, modifier = Modifier.size(16.dp))
                        Text(
                            openCompat.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaknoonColors.warning,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { compatWarnDismissed = true }) { Text(stringResource(R.string.app_dismiss)) }
                    }
                }
                MiniAppHostScreen(
                    spec = current.toLaunchSpec(granted),
                    handlerFactory = handlerFactory,
                    approvalSheetHost = approvalSheetHost,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_apps)) },
                navigationIcon = {
                    IconButton(onClick = { showSettings = true }) {
                        // iOS: "gearshape" (outlined gear), tinted .purple.
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showBrowse = true }) {
                        // iOS: "plus.circle" (plus in an outlined circle), tinted .purple.
                        Icon(
                            Icons.Outlined.AddCircle,
                            contentDescription = stringResource(R.string.app_add_app),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // -- Installed (iOS installedSection) --
            item { SectionHeader(title = stringResource(R.string.app_installed)) }
            if (installed.isEmpty()) {
                item {
                    // iOS emptyState: "No apps installed yet" + the +-tip copy.
                    EmptyState(
                        icon = Icons.Filled.Apps,
                        title = stringResource(R.string.app_no_apps_installed),
                        subtitle = stringResource(R.string.app_no_apps_subtitle),
                    )
                }
            } else {
                items(installed, key = { "installed/${it.installedAppId}" }) { app ->
                    InstalledRow(app = app, onTap = { detailFor = app })
                }
            }

            // -- Connected verifiers (iOS verifierHistorySection) --
            item { SectionHeader(title = stringResource(R.string.app_connected_verifiers)) }
            if (verifierGroups.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.app_no_verifiers),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    )
                }
            } else {
                items(verifierGroups, key = { "verifier/${it.verifierDid}" }) { group ->
                    VerifierHistoryRow(group = group)
                }
            }
        }
    }

    // Installed-app detail sheet (iOS InstalledAppDetailSheet): channel + version,
    // summary, the permissions it can use (revocable), Open, Uninstall.
    detailFor?.let { app ->
        InstalledAppDetailSheet(
            app = app,
            grantedCapabilities = { registry.grantedCapabilities(app.installedAppId) },
            onSetGranted = { registry.setGrantedCapabilities(app.installedAppId, it) },
            onOpen = {
                detailFor = null
                launched = app
            },
            onUninstall = {
                registry.uninstall(app.installedAppId)
                refreshKey++
                detailFor = null
            },
            onDismiss = { detailFor = null },
        )
    }
}

// MARK: -- installed apps

@Composable
private fun InstalledRow(app: MiniAppInstallRegistry.InstalledApp, onTap: () -> Unit) {
    val entry = app.entry
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppIcon(entry.iconToken)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.version?.let { v ->
                    Text(
                        "v$v",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            StatusPill(text = entry.channelLabel, color = channelColor(entry.channel))
        }
    }
}

// MARK: -- verifier history

@Composable
private fun VerifierHistoryRow(group: VerifierHistoryGroup) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // iOS: person.crop.circle.dashed (unknown, secondary) vs
            // person.crop.circle.fill.badge.checkmark (named, green).
            val known = group.verifierName != null
            val accent = if (known) MaknoonColors.success else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(Radii.md))
                    .background(accent.tint(MaknoonColors.TintCellAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (known) Icons.Filled.Verified else Icons.Filled.PersonOutline,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    group.verifierName ?: group.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    verifierDidShort(group.verifierDid),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                group.entries.firstOrNull()?.let { first ->
                    Text(
                        stringResource(R.string.app_last_share, relativeSinceSec(first.lastUsedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            // Per-verifier share count (iOS trailing monospaced count).
            Text(
                group.entries.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// MARK: -- installed-app detail sheet (iOS InstalledAppDetailSheet)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppDetailSheet(
    app: MiniAppInstallRegistry.InstalledApp,
    grantedCapabilities: () -> Set<String>,
    onSetGranted: (Set<String>) -> Unit,
    onOpen: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val entry = app.entry
    // Live, revocable grant set; initialized from the store, persisted on toggle.
    var granted by remember(app.installedAppId) {
        mutableStateOf(grantedCapabilities().ifEmpty { entry.permissions }.map { it.lowercase() }.toSet())
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AppIcon(entry.iconToken, large = true)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(entry.channelLabel)
                            entry.version?.let { append(" · v$it") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = channelColor(entry.channel),
                    )
                }
            }
            Text(entry.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.details.isNotEmpty()) {
                Text(entry.details, style = MaterialTheme.typography.bodyMedium)
            }

            // Permissions (revocable). iOS capabilitiesSection.
            val caps = MiniAppCapabilityRegistry.disclosable(entry.permissions)
            if (caps.isNotEmpty()) {
                Text(
                    stringResource(R.string.app_permissions),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                caps.forEach { cap ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(capIcon(cap.icon), contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(22.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cap.label, style = MaterialTheme.typography.bodyMedium)
                            Text(cap.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = granted.contains(cap.token.lowercase()),
                            onCheckedChange = { on ->
                                granted = if (on) granted + cap.token.lowercase() else granted - cap.token.lowercase()
                                onSetGranted(granted)
                            },
                        )
                    }
                }
                Text(
                    stringResource(R.string.app_revoke_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(Spacing.xs))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.sm))
                Text(stringResource(R.string.app_open))
            }
            OutlinedButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.RemoveCircle, contentDescription = null, tint = MaknoonColors.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.sm))
                Text(stringResource(R.string.app_uninstall), color = MaknoonColors.error)
            }
        }
    }
}

// MARK: -- helpers

/** iOS verifierDidShort: keep DIDs <= 36 chars whole, else 20...10 with an ellipsis. */
private fun verifierDidShort(did: String): String =
    if (did.length <= 36) did else "${did.take(20)}…${did.takeLast(10)}"

/** Relative "3 min ago" style caption from a unix-seconds timestamp. */
private fun relativeSinceSec(unixSec: Long): String {
    val delta = System.currentTimeMillis() / 1000L - unixSec
    if (delta < 0) return "just now"
    return when {
        delta < 5 -> "just now"
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60} min ago"
        delta < 86_400 -> "${delta / 3600} h ago"
        else -> "${delta / 86_400} d ago"
    }
}

/** Soft brand-tinted rounded-square app icon (mirrors the iOS purple glyph). */
@Composable
private fun AppIcon(iconToken: String, large: Boolean = false) {
    val box = if (large) 56.dp else 44.dp
    val glyph = if (large) 30.dp else 24.dp
    Box(
        modifier = Modifier
            .size(box)
            .clip(RoundedCornerShape(Radii.md))
            .background(MaknoonBrand.accent.tint(MaknoonColors.TintCellAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(appIcon(iconToken), contentDescription = null, modifier = Modifier.size(glyph), tint = MaknoonBrand.accent)
    }
}

/** Map an app icon token to a Material icon. */
private fun appIcon(token: String): ImageVector = when (token.lowercase()) {
    "creditcard" -> Icons.Filled.CreditCard
    "storefront" -> Icons.Filled.Storefront
    else -> Icons.Filled.Apps
}

/** Map a capability icon name (from MiniAppCapabilityRegistry) to a Material icon. */
private fun capIcon(name: String): ImageVector = when (name) {
    "Badge" -> Icons.Filled.Badge
    "CreditCard" -> Icons.Filled.CreditCard
    "Link" -> Icons.Filled.Link
    "AccountBalanceWallet" -> Icons.Filled.AccountBalanceWallet
    "QrCodeScanner" -> Icons.Filled.QrCodeScanner
    "Share" -> Icons.Filled.Share
    "ContentCopy" -> Icons.Filled.ContentCopy
    else -> Icons.Filled.Lock
}

/**
 * Color tint for the release chip. Beta = warning (orange), Stable = success
 * (green), otherwise secondary. Mirrors the iOS statusColor.
 */
@Composable
private fun channelColor(channel: String?): Color = when ((channel ?: "stable").lowercase()) {
    "stable", "live" -> MaknoonColors.success
    "beta", "demo" -> MaknoonColors.warning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/// A small capsule status label: the channel color at full strength on the
/// same color tinted to the iOS pill alpha. Mirrors the iOS channelLabel pill.
@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.tint(MaknoonColors.TintPillAlpha))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

private fun MiniAppInstallRegistry.InstalledApp.toLaunchSpec(granted: Set<String>): MiniAppLaunchSpec =
    MiniAppLaunchSpec(
        installedAppId = installedAppId,
        appId = entry.appId,
        title = entry.title,
        manifestUrl = entry.manifestUrl,
        manifestSha256 = entry.manifestSha256,
        grantedPermissions = granted,
    )

// MARK: -- catalog list (iOS BrowseAppStoreView equivalent)

/**
 * A browsable Apps catalog: a named set of entries the user can drill into. The
 * Android SDK ships no remote catalog registry, so the only catalog with
 * browsable apps is the built-in seed ("Maknoon Apps"). User-added catalogs in
 * Settings > Apps are URL-only (no runtime fetch), so they are not listed here.
 */
private data class MiniAppCatalogSummary(
    val id: String,
    val name: String,
    val entries: List<MiniAppCatalogEntry>,
)

/**
 * The Apps catalog list, reached from the Apps tab "+". Mirrors iOS
 * BrowseAppStoreView: an "Apps catalogs" section listing each catalog (name +
 * app count); tapping a catalog drills into its apps (BrowseAppStoreScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseCatalogListScreen(
    catalogs: List<MiniAppCatalogSummary>,
    onOpenCatalog: (MiniAppCatalogSummary) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_browse_apps)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.app_back_to_apps),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { SectionHeader(title = stringResource(R.string.app_apps_catalogs)) }
            items(catalogs, key = { "catalog/${it.id}" }) { catalog ->
                CatalogListRow(catalog = catalog, onTap = { onOpenCatalog(catalog) })
            }
            item {
                Text(
                    stringResource(R.string.app_add_catalogs_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun CatalogListRow(catalog: MiniAppCatalogSummary, onTap: () -> Unit) {
    val count = catalog.entries.size
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(Radii.md))
                    .background(MaknoonBrand.accent.tint(MaknoonColors.TintCellAlpha)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Storefront,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaknoonBrand.accent,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    catalog.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (count == 1) {
                        stringResource(R.string.app_app_count_one, count)
                    } else {
                        stringResource(R.string.app_app_count_other, count)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// MARK: -- browse view (per-catalog apps list)

/**
 * The per-catalog Apps browser, reached by tapping a catalog in the catalog
 * list (BrowseCatalogListScreen). Presents the catalog's entries under a
 * "Browse Apps" bar; tapping an entry opens an install sheet that discloses the
 * capabilities the app requests before the user installs (or opens an
 * already-installed one). Beta-channel entries are hidden unless "Show beta
 * apps" is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseAppStoreScreen(
    catalogName: String,
    entries: List<MiniAppCatalogEntry>,
    showBetaApps: Boolean,
    isInstalled: (MiniAppCatalogEntry) -> Boolean,
    installedVersion: (String) -> String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (MiniAppCatalogEntry) -> Unit,
    onBack: () -> Unit,
) {
    var sheetFor by remember { mutableStateOf<MiniAppCatalogEntry?>(null) }
    // One tile per app id (ADR-0052): group channels/versions of the same app so
    // it never shows twice. The beta toggle picks the channel (default stable);
    // within it, prefer a host-compatible variant then the highest version.
    val visible = remember(entries, showBetaApps) {
        groupCatalogForBrowse(entries, showBetaApps) {
            !DAppCompatibility.evaluate(it.requiresMaknoonVersion, it.supersededAtMaknoonVersion).blocksInstall
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_browse_apps)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.app_back_to_catalogs),
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                SectionHeader(title = catalogName)
            }
            if (visible.isEmpty()) {
                item {
                    Text(
                        if (entries.isEmpty()) {
                            stringResource(R.string.app_catalog_empty)
                        } else {
                            stringResource(R.string.app_beta_hidden)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                    )
                }
            } else {
                items(visible, key = { "browse/${it.appId}" }) { entry ->
                    BrowseRow(entry = entry, installed = isInstalled(entry), onTap = { sheetFor = entry })
                }
            }
        }
        }
    }

    sheetFor?.let { entry ->
        InstallSheet(
            entry = entry,
            variants = entries.filter { it.appId == entry.appId },
            showBeta = showBetaApps,
            installedVersion = installedVersion(entry.appId),
            onOpen = { chosen ->
                sheetFor = null
                onOpen(chosen) // installs/switches to the chosen channel + opens
            },
            onDismiss = { sheetFor = null },
        )
    }
}

@Composable
private fun BrowseRow(entry: MiniAppCatalogEntry, installed: Boolean, onTap: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(Radii.card),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppIcon(entry.iconToken)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Channel + version, mirroring the install/detail sheets
                // (e.g. "Beta · v0.1.4"). No "curated by".
                Text(
                    buildString {
                        append(entry.channelLabel)
                        entry.version?.let { append(" · v$it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = channelColor(entry.channel),
                )
                if (installed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaknoonColors.success, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.app_installed), style = MaterialTheme.typography.labelSmall, color = MaknoonColors.success)
                    }
                }
            }
            StatusPill(text = entry.channelLabel, color = channelColor(entry.channel))
        }
    }
}

// MARK: -- install sheet (iOS InstallSheet)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallSheet(
    entry: MiniAppCatalogEntry,
    variants: List<MiniAppCatalogEntry>,
    showBeta: Boolean,
    installedVersion: String?,
    onOpen: (MiniAppCatalogEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Channel selection (ADR-0052): default stable; show a Stable|Beta picker only
    // when both channels exist AND beta apps are enabled. `chosen` drives the whole
    // sheet + install; installing a different channel upserts (a channel switch).
    var channel by remember { mutableStateOf("stable") }
    val stableVariant = variants.firstOrNull { !it.isBeta }
    val betaVariant = variants.firstOrNull { it.isBeta }
    val chosen = if (channel == "beta") (betaVariant ?: entry) else (stableVariant ?: entry)
    val showPicker = showBeta && stableVariant != null && betaVariant != null
    val chosenInstalled = installedVersion != null && installedVersion == chosen.version
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AppIcon(chosen.iconToken, large = true)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(chosen.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(chosen.channelLabel)
                            chosen.version?.let { append(" · v$it") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = channelColor(chosen.channel),
                    )
                }
            }
            if (showPicker) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = channel == "stable",
                        onClick = { channel = "stable" },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text(stringResource(R.string.app_channel_stable)) }
                    SegmentedButton(
                        selected = channel == "beta",
                        onClick = { channel = "beta" },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text(stringResource(R.string.app_channel_beta)) }
                }
            }
            // Compatibility badge (Compatible / Requires Maknoon X.Y.Z / unknown),
            // mirroring the iOS DAppCompatibilityRow.
            val compatibility = DAppCompatibility.evaluate(
                chosen.requiresMaknoonVersion, chosen.supersededAtMaknoonVersion,
            )
            val compatColor = when (compatibility) {
                is DAppCompatibility.Compatible -> MaknoonColors.success
                is DAppCompatibility.RecommendsNewer -> MaknoonColors.warning
                is DAppCompatibility.Superseded -> MaknoonColors.warning
                is DAppCompatibility.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val compatIcon = when (compatibility) {
                is DAppCompatibility.Compatible -> Icons.Filled.CheckCircle
                is DAppCompatibility.RecommendsNewer -> Icons.Filled.Warning
                is DAppCompatibility.Superseded -> Icons.Filled.Warning
                is DAppCompatibility.Unknown -> Icons.AutoMirrored.Filled.HelpOutline
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(compatIcon, contentDescription = null, tint = compatColor, modifier = Modifier.size(16.dp))
                Text(compatibility.label, style = MaterialTheme.typography.labelMedium, color = compatColor)
            }

            Text(chosen.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (chosen.details.isNotEmpty()) {
                Text(chosen.details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // "This app can" disclosure of declared capabilities + reasons.
            val caps = MiniAppCapabilityRegistry.disclosable(chosen.permissions)
            if (caps.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        stringResource(R.string.app_this_app_can),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    caps.forEach { cap ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Icon(capIcon(cap.icon), contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(22.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(cap.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(cap.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (cap.tier == CapabilityTier.PER_USE) {
                                Text(
                                    stringResource(R.string.app_asks_each_time),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            // Installing a runnable app opens it immediately, so both states land
            // in the app (iOS parity); the label reflects whether it was already
            // installed. A hard compatibility requirement the host does not meet
            // blocks install (button disabled); already-installed apps still open.
            val blocked = !chosenInstalled && compatibility.blocksInstall
            val switching = !chosenInstalled && installedVersion != null
            Button(onClick = { onOpen(chosen) }, enabled = !blocked, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    when {
                        chosenInstalled -> Icons.AutoMirrored.Filled.Launch
                        switching -> Icons.Filled.Refresh
                        else -> Icons.Filled.Add
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(Spacing.sm))
                Text(
                    when {
                        chosenInstalled -> stringResource(R.string.app_open)
                        switching -> stringResource(R.string.app_switch_to_channel, chosen.channelLabel)
                        else -> stringResource(R.string.app_install_to_apps_tab)
                    },
                )
            }
            if (blocked && compatibility is DAppCompatibility.RecommendsNewer) {
                Text(
                    stringResource(R.string.app_requires_maknoon, compatibility.required),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaknoonColors.warning,
                )
            }
            if (blocked && compatibility is DAppCompatibility.Superseded) {
                Text(
                    stringResource(R.string.app_needs_update_superseded, compatibility.supersededAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaknoonColors.warning,
                )
            }
        }
    }
}
