// SolanaWalletScreen: the single top-level entry composable the Wallet tab
// routes to for the Solana chain. Mirrors iOS SolanaWalletView.swift: a
// dashboard (native balance + fiat caption, cluster selector, send/receive
// buttons, SPL token list, recent activity) plus internal navigation to the
// receive / send / history / settings / wallets / add-token screens.
//
// All engine + RPC work runs off the main thread (Dispatchers.IO). The first
// software wallet is auto-created on a fresh identity so the tab is never
// empty (mirrors iOS's "ensure account 0" behaviour).

package com.elabify.app.maknoon.ui.wallet.solana

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.AddressChip
import com.elabify.app.maknoon.ui.components.EmptyState
import com.elabify.app.maknoon.ui.wallet.common.AccountAddressBadge
import com.elabify.app.maknoon.ui.wallet.common.ActionButtons
import com.elabify.app.maknoon.ui.wallet.common.BalanceCard
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.NetworkOption
import com.elabify.app.maknoon.ui.wallet.common.NetworkPickerChip
import com.elabify.app.maknoon.ui.wallet.common.WalletActionsMenu
import com.elabify.app.maknoon.ui.wallet.common.WalletChainScaffold
import com.elabify.app.maknoon.ui.wallet.common.WalletChipItem
import com.elabify.app.maknoon.ui.wallet.common.WalletPickerChip
import com.elabify.app.maknoon.ui.wallet.common.relativeSince
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.solana.SolanaRPCClient
import com.elabify.musnad.wallet.solana.SolanaSPLToken
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class SolRoute { DASHBOARD, RECEIVE, SEND, TOKEN_DETAIL, HISTORY, SETTINGS, WALLETS, ADD_WALLET, REGISTER_DEVICE, ADD_TOKEN }

@Composable
fun SolanaWalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }

    var route by remember { mutableStateOf(SolRoute.DASHBOARD) }
    var reloadKey by remember { mutableIntStateOf(0) }
    // Non-null right after an inline "Register a device": reopen Add on the
    // Hardware tab with this device selected (ADR-0033).
    var pendingHwDeviceId by remember { mutableStateOf<UUID?>(null) }
    // For the send screen: optional preselected SPL mint.
    var sendPreselectMint by remember { mutableStateOf<String?>(null) }
    // The SPL mint whose overview screen is open (TOKEN_DETAIL route).
    var tokenDetailMint by remember { mutableStateOf<String?>(null) }

    val active: SolanaWalletDescriptor? = env.walletStore.activeWallet

    when (route) {
        SolRoute.DASHBOARD -> SolanaDashboard(
            reloadKey = reloadKey,
            onBack = onBack,
            onReceive = { route = SolRoute.RECEIVE },
            onSend = { mint -> sendPreselectMint = mint; route = SolRoute.SEND },
            onTokenDetail = { mint -> tokenDetailMint = mint; route = SolRoute.TOKEN_DETAIL },
            onHistory = { route = SolRoute.HISTORY },
            onSettings = { route = SolRoute.SETTINGS },
            onWallets = { route = SolRoute.WALLETS },
            onAddToken = { route = SolRoute.ADD_TOKEN },
            onChanged = { reloadKey++ },
        )

        SolRoute.RECEIVE -> active?.let {
            SolanaReceiveScreen(descriptor = it, onBack = { route = SolRoute.DASHBOARD })
        } ?: BackToDashboard { route = SolRoute.DASHBOARD }

        SolRoute.SEND -> active?.let {
            SolanaSendScreen(
                descriptor = it,
                preselectMint = sendPreselectMint,
                onBack = { sendPreselectMint = null; reloadKey++; route = SolRoute.DASHBOARD },
            )
        } ?: BackToDashboard { route = SolRoute.DASHBOARD }

        SolRoute.TOKEN_DETAIL -> active?.let { d ->
            SolanaTokenDetailScreen(
                descriptor = d,
                mint = tokenDetailMint.orEmpty(),
                onSend = { mint -> sendPreselectMint = mint; route = SolRoute.SEND },
                onReceive = { route = SolRoute.RECEIVE },
                onDone = { tokenDetailMint = null; route = SolRoute.DASHBOARD },
            )
        } ?: BackToDashboard { route = SolRoute.DASHBOARD }

        SolRoute.HISTORY -> active?.let { d ->
            SolanaHistoryScreen(
                descriptor = d,
                onBack = { route = SolRoute.DASHBOARD },
                onOpenExplorer = { sig -> openSignatureInExplorer(context, env, sig) },
            )
        } ?: BackToDashboard { route = SolRoute.DASHBOARD }

        SolRoute.SETTINGS -> SolanaSettingsScreen(
            onBack = { route = SolRoute.DASHBOARD },
            onNetworkChanged = { reloadKey++ },
        )

        SolRoute.WALLETS -> SolanaWalletsScreen(
            onBack = { reloadKey++; route = SolRoute.DASHBOARD },
            onSelect = { _: UUID -> reloadKey++; route = SolRoute.DASHBOARD },
            onAddWallet = { pendingHwDeviceId = null; route = SolRoute.ADD_WALLET },
        )

        SolRoute.ADD_WALLET -> AddSolanaWalletScreen(
            initialDeviceId = pendingHwDeviceId,
            onRegisterDevice = { route = SolRoute.REGISTER_DEVICE },
            onDone = { reloadKey++; route = SolRoute.WALLETS },
        )

        SolRoute.REGISTER_DEVICE -> SolanaRegisterDeviceRoute(
            onRegistered = { deviceId -> pendingHwDeviceId = deviceId; route = SolRoute.ADD_WALLET },
            onCancel = { route = SolRoute.ADD_WALLET },
        )

        SolRoute.ADD_TOKEN -> SolanaAddTokenScreen(
            onBack = { route = SolRoute.DASHBOARD },
            onAdded = { reloadKey++; route = SolRoute.DASHBOARD },
        )
    }
}

@Composable
private fun BackToDashboard(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.sol_no_active_wallet), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
    }
}

private data class DashboardData(
    val descriptor: SolanaWalletDescriptor,
    val address: String,
    val lamports: Long,
    val fiat: String?,
    val tokens: List<Pair<SolanaSPLToken, Long>>,
    val unknownMints: List<String>,
    val recent: List<SolanaRPCClient.SignatureRecord>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SolanaDashboard(
    reloadKey: Int,
    onBack: () -> Unit,
    onReceive: () -> Unit,
    onSend: (mint: String?) -> Unit,
    onTokenDetail: (mint: String) -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onWallets: () -> Unit,
    onAddToken: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }

    var data by remember { mutableStateOf<DashboardData?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var noIdentity by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf(env.walletStore.currentNetwork) }

    LaunchedEffect(reloadKey, network) {
        busy = true
        error = null
        noIdentity = false

        val sandwich = withContext(Dispatchers.IO) { env.loadSandwich() }
        if (sandwich == null) {
            noIdentity = true
            busy = false
            return@LaunchedEffect
        }

        // Ensure at least account 0 exists (fresh identity).
        if (env.walletStore.wallets.isEmpty()) {
            env.walletStore.add(
                SolanaWalletDescriptor(label = "Solana 0", kind = SolanaWalletKind.Software(account = 0L)),
                initialNetwork = network,
                makeActive = true,
            )
        }
        val descriptor = env.walletStore.activeWallet
        if (descriptor == null) {
            error = "No active wallet."
            busy = false
            return@LaunchedEffect
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val w = env.openWallet(descriptor)
                val address = w.resolvedAddress()
                env.walletStore.updateMirrorAddress(descriptor.id, address)
                val lamports = w.refreshBalance()
                env.walletStore.markSynced(descriptor.id)

                // SPL token accounts + auto-discover against the catalog.
                val accounts = runCatching { w.tokenAccounts() }.getOrDefault(emptyList())
                runCatching { env.catalog.refreshIfStale(env.settings.tokenCatalogURL) }
                env.tokenStore.reconcile(accounts.map { it.mint }, network, env.catalog)

                val balancesByMint = accounts.associate { it.mint to it.amount }
                val tracked = env.tokenStore.tokens(network).map { t ->
                    t to (balancesByMint[t.mint] ?: 0L)
                }
                val recent = runCatching { w.recentSignatures(limit = 5) }.getOrDefault(emptyList())

                val fiat = FiatReference.caption(network.coinGeckoAssetId, lamports / 1_000_000_000.0)

                DashboardData(
                    descriptor = descriptor,
                    address = address,
                    lamports = lamports,
                    fiat = fiat,
                    tokens = tracked,
                    unknownMints = env.tokenStore.unknownMints(network),
                    recent = recent,
                )
            }
        }
        result.onSuccess { data = it }.onFailure { error = it.message ?: it.toString() }
        busy = false
    }

    WalletChainScaffold(
        title = stringResource(R.string.sol_solana),
        onBack = onBack,
        actions = {
            WalletActionsMenu(onManage = onWallets, onSettings = onSettings)
        },
        isRefreshing = busy,
        onRefresh = onChanged,
    ) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Persistent chrome: the wallet picker + cluster switcher render whenever a
        // wallet exists, INDEPENDENT of the data load, so a failed/throttled RPC
        // never traps the user here, they can switch cluster (e.g. to a testnet) or
        // open Settings to set a custom RPC. (ADR-0033 failed-endpoint rule.)
        val activeDescriptor = data?.descriptor ?: env.walletStore.activeWallet
        if (!noIdentity && activeDescriptor != null) {
            val acct = (activeDescriptor.kind as? SolanaWalletKind.Software)?.account
            WalletPickerChip(
                label = activeDescriptor.label,
                subtitle = stringResource(R.string.sol_software_account, (acct ?: 0L).toString()),
                accent = androidx.compose.ui.graphics.Color(0xFF9945FF),
                iconRes = com.elabify.app.maknoon.R.drawable.ic_chain_solana,
                items = env.walletStore.wallets.map { WalletChipItem(it.id.toString(), it.label) },
                selectedId = activeDescriptor.id.toString(),
                onPick = { id -> env.walletStore.setActive(java.util.UUID.fromString(id)); onChanged() },
                onManage = onWallets,
            )
            data?.address?.let { AccountAddressBadge(accountIndex = acct, address = it) }
            NetworkPickerChip(
                options = SolanaNetwork.entries.map {
                    NetworkOption(id = it.name, displayName = it.displayName, isTestnet = it.coinGeckoAssetId == null)
                },
                selectedId = network.name,
                onSelect = { opt ->
                    val n = SolanaNetwork.valueOf(opt.id)
                    env.walletStore.setActiveNetwork(activeDescriptor.id, n)
                    network = n
                },
            )
        }

        when {
            noIdentity -> Text(
                stringResource(R.string.sol_create_identity_first),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )

            busy && data == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            error != null && data == null -> Column(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    stringResource(R.string.sol_rpc_unreachable),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    OutlinedButton(onClick = onChanged) { Text(stringResource(R.string.common_retry)) }
                    OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.common_settings)) }
                }
            }

            data != null -> {
                val d = data!!

                BalanceCard(
                    amount = formatSol(d.lamports),
                    ticker = stringResource(R.string.sol_sol_ticker),
                    syncing = busy,
                    syncLabel = stringResource(R.string.sol_last_sync, relativeSince(d.descriptor.lastSyncAtEpochSec?.times(1000))),
                    onRefresh = onChanged,
                    subnote = d.fiat,
                )

                ActionButtons(
                    sendEnabled = true,
                    onSend = { onSend(null) },
                    onReceive = onReceive,
                    accent = androidx.compose.ui.graphics.Color(0xFF9945FF),
                )

                // Tokens + activity (non-shared content), uniformly inset.
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {

                // SPL tokens.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.walletc_tokens), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onAddToken) { Text(stringResource(R.string.walletc_add_token)) }
                }
                if (d.tokens.isEmpty()) {
                    Surface(shape = RoundedCornerShape(Radii.md), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.sol_no_spl_tokens, network.displayName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.md),
                        )
                    }
                } else {
                    d.tokens.forEach { (token, raw) ->
                        Surface(
                            shape = RoundedCornerShape(Radii.md),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().clickable { onTokenDetail(token.mint) },
                        ) {
                            Row(Modifier.fillMaxWidth().padding(Spacing.lg), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(token.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(token.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(token.format(raw), style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                if (d.unknownMints.isNotEmpty()) {
                    Text(
                        stringResource(R.string.sol_unrecognized_tokens, d.unknownMints.size.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Recent activity.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sol_recent_activity), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onHistory) { Text(stringResource(R.string.common_see_all)) }
                }
                if (d.recent.isEmpty()) {
                    Surface(shape = RoundedCornerShape(Radii.md), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            icon = Icons.Filled.Inbox,
                            title = stringResource(R.string.sol_no_recent_transactions),
                            subtitle = stringResource(R.string.sol_fund_wallet_to_see_activity),
                            iconSize = 40.dp,
                        )
                    }
                } else {
                    d.recent.forEach { rec ->
                        val failed = rec.err != null
                        Surface(shape = RoundedCornerShape(Radii.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable { openSignatureInExplorer(context, env, rec.signature) }.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                Icon(
                                    if (failed) Icons.Filled.Error else Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = if (failed) MaknoonColors.error else MaknoonColors.success,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    AddressChip(text = rec.signature, head = 10, tail = 10)
                                    Text(
                                        if (failed) stringResource(R.string.sol_status_failed) else (rec.confirmationStatus ?: stringResource(R.string.sol_status_confirmed)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (failed) MaknoonColors.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
    }
}

// MARK: -- shared dashboard visuals (Solana)

@Composable
private fun SolBalanceCard(
    label: String,
    amount: String,
    showTestNote: Boolean,
    address: String,
    busy: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radii.card),
        color = MaknoonBrand.deepPurple.tint(0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(Elevation.cardRaised, RoundedCornerShape(Radii.card), clip = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(amount, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = MaknoonBrand.accent)
            if (showTestNote) {
                Text(stringResource(R.string.sol_test_cluster_no_value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                shortAddress(address, head = 6, tail = 6),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaknoonBrand.accent)
                    Text(stringResource(R.string.sol_refreshing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaknoonColors.success, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.sol_synced), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.sol_refresh), modifier = Modifier.size(18.dp), tint = MaknoonBrand.accent)
                }
            }
        }
    }
}

@Composable
private fun SolActionTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(Radii.md),
        color = MaknoonBrand.accent.tint(0.12f),
    ) {
        Column(
            Modifier.padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(icon, contentDescription = null, tint = MaknoonBrand.accent)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaknoonBrand.accent)
        }
    }
}

/** Open a signature on the configured explorer (override or cluster
 *  default), appending the path the way Solana Explorer expects. */
private fun openSignatureInExplorer(
    context: android.content.Context,
    env: SolanaEnv,
    signature: String,
) {
    val base = env.settings.explorerURL(env.walletStore.currentNetwork)
    // explorer.solana.com/tx/<sig>?cluster=devnet  (defaults carry ?cluster=)
    val url = if (base.contains("?")) {
        val (root, query) = base.split("?", limit = 2)
        "${root.trimEnd('/')}/tx/$signature?$query"
    } else {
        "${base.trimEnd('/')}/tx/$signature"
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Hosts the shared device-registration flow inline in the Solana chain, so the
 * Add screen's "Add New Device" can pair a Ledger / security key without
 * bouncing out to Settings -> Devices. On finish / cancel it returns to Add; the
 * paired device id is handed back so Add reopens with it selected.
 */
@Composable
private fun SolanaRegisterDeviceRoute(
    onRegistered: (UUID?) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val registry = remember { com.elabify.musnad.devices.DeviceRegistry(ctx) }
    com.elabify.app.maknoon.ui.devices.AddHardwareDeviceFlow(
        registry = registry,
        onFinished = { device -> onRegistered(device?.id) },
        onCancel = onCancel,
    )
}
