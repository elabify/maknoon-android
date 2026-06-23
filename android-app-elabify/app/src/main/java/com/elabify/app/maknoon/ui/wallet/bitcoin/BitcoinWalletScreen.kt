// Top-level Bitcoin wallet screen. The Wallet tab routes here. Mirrors the
// iOS BitcoinWalletView dashboard: wallet picker, next-receive row, sync
// row, big balance card (+ fiat), Send / Receive / Addresses actions, and
// a recent-transactions list. Send / Receive / Addresses / Transactions /
// Wallets / Settings open as full-screen overlays inside this composable
// (no shared navigation graph touched).
//
// This is the ONE public entry composable for the Bitcoin chain; every
// other screen + sheet in this package is internal/private.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.components.AddressChip
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.EmptyState
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.app.maknoon.ui.wallet.common.BalanceCard
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.WalletActionsMenu
import com.elabify.app.maknoon.ui.wallet.common.WalletChainScaffold
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoindevkit.CanonicalTx

/** Which overlay screen (if any) is shown on top of the dashboard. */
private sealed interface BtcRoute {
    data object DASHBOARD : BtcRoute
    data object SEND : BtcRoute
    data object RECEIVE : BtcRoute
    data object ADDRESSES : BtcRoute
    data object TXS : BtcRoute
    data object WALLETS : BtcRoute
    data object ADD_WALLET : BtcRoute
    data object SETTINGS : BtcRoute

    /** Device-registration flow (pick vendor -> pair -> confirm -> register),
     *  launched from the Add screen's "Register a device" button so a user with
     *  no Ledger / security key registered can pair one without leaving Bitcoin.
     *  On return we go back to ADD_WALLET, where the freshly-paired device now
     *  shows in the picker. */
    data object REGISTER_DEVICE : BtcRoute
}

@Composable
fun BitcoinWalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { BitcoinWalletEnv.create(context) }

    // The wallet list + active id are mutable on the store but the store
    // does not emit; we bump this key to force re-read after store mutations
    // (add / remove / rename / setActive).
    var storeVersion by remember { mutableStateOf(0) }
    var route by remember { mutableStateOf<BtcRoute>(BtcRoute.DASHBOARD) }
    // After the inline "Register a device" flow, the just-paired device id is
    // stashed here so the Add screen reopens on the HARDWARE tab with that
    // device selected (ADR-0033). Cleared on a normal "Add wallet" entry so it
    // never goes stale.
    var pendingHwDeviceId by remember { mutableStateOf<java.util.UUID?>(null) }

    // Re-derive the active descriptor whenever the store changes or a
    // mutation bumps storeVersion.
    val active: BitcoinWalletDescriptor? = remember(storeVersion) { env.store.activeWallet }

    when (route) {
        BtcRoute.DASHBOARD -> BitcoinDashboard(
            env = env,
            storeVersion = storeVersion,
            onBack = onBack,
            onStoreChanged = { storeVersion++ },
            onOpenSend = { route = BtcRoute.SEND },
            onOpenReceive = { route = BtcRoute.RECEIVE },
            onOpenAddresses = { route = BtcRoute.ADDRESSES },
            onOpenTxs = { route = BtcRoute.TXS },
            onOpenWallets = { route = BtcRoute.WALLETS },
            onOpenSettings = { route = BtcRoute.SETTINGS },
        )
        BtcRoute.SEND -> BitcoinSendScreen(env, active, onClose = { route = BtcRoute.DASHBOARD })
        BtcRoute.RECEIVE -> BitcoinReceiveScreen(env, active, onClose = { route = BtcRoute.DASHBOARD })
        BtcRoute.ADDRESSES -> BitcoinAddressesScreen(env, active, onClose = { route = BtcRoute.DASHBOARD })
        BtcRoute.TXS -> BitcoinTransactionListScreen(env, active, onClose = { route = BtcRoute.DASHBOARD })
        BtcRoute.WALLETS -> BitcoinWalletsScreen(
            env = env,
            onAddWallet = { pendingHwDeviceId = null; route = BtcRoute.ADD_WALLET },
            onStoreChanged = { storeVersion++ },
            onClose = { route = BtcRoute.DASHBOARD },
        )
        BtcRoute.ADD_WALLET -> AddBitcoinWalletScreen(
            env = env,
            // Non-null right after an inline register: reopen on the Hardware
            // tab with this device selected.
            initialDeviceId = pendingHwDeviceId,
            onRegisterDevice = { route = BtcRoute.REGISTER_DEVICE },
            onDone = { storeVersion++; route = BtcRoute.WALLETS },
        )
        BtcRoute.REGISTER_DEVICE -> BitcoinRegisterDeviceRoute(
            // On a successful register, return to Add on the HARDWARE tab with
            // the freshly paired device selected (ADR-0033). We do NOT
            // auto-launch the discover sweep (the Add screen has its own inline
            // Discover scoped to Bitcoin).
            onRegistered = { deviceId -> pendingHwDeviceId = deviceId; route = BtcRoute.ADD_WALLET },
            onCancel = { route = BtcRoute.ADD_WALLET },
        )
        BtcRoute.SETTINGS -> BitcoinSettingsScreen(env, onClose = { route = BtcRoute.DASHBOARD })
    }
}

/**
 * Hosts the shared device-registration flow (AddHardwareDeviceFlow) inline in
 * the Bitcoin chain, so the Add screen's "Register a device" button can pair a
 * Ledger / security key without bouncing the user out to the Settings ->
 * Devices stack. Reuses the exact same flow + DeviceRegistry the Devices screen
 * uses; on finish or cancel it returns to the Add screen.
 */
@Composable
private fun BitcoinRegisterDeviceRoute(
    onRegistered: (java.util.UUID?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val registry = remember { com.elabify.musnad.devices.DeviceRegistry(context) }
    com.elabify.app.maknoon.ui.devices.AddHardwareDeviceFlow(
        registry = registry,
        // onFinished hands back the just-registered device (the discoverTarget);
        // pass its id up so the Add screen reopens with it selected.
        onFinished = { device -> onRegistered(device?.id) },
        onCancel = onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitcoinDashboard(
    env: BitcoinWalletEnv,
    storeVersion: Int,
    onBack: () -> Unit,
    onStoreChanged: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenAddresses: () -> Unit,
    onOpenTxs: () -> Unit,
    onOpenWallets: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val active = remember(storeVersion) { env.store.activeWallet }

    var engine by remember(active?.id) { mutableStateOf<BitcoinWalletEngine?>(null) }
    var balanceSat by remember(active?.id) { mutableStateOf<Long?>(null) }
    var fiat by remember(active?.id) { mutableStateOf<String?>(null) }
    var txs by remember(active?.id) { mutableStateOf<List<CanonicalTx>>(emptyList()) }
    var netSats by remember(active?.id) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var nextReceive by remember(active?.id) { mutableStateOf<String?>(null) }
    var syncing by remember(active?.id) { mutableStateOf(false) }
    var error by remember(active?.id) { mutableStateOf<String?>(null) }
    var rebuildNotice by remember(active?.id) { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // Open the engine + sync whenever the active wallet changes. Keyed on the
    // wallet id ONLY (not storeVersion): the post-sync onStoreChanged() bumps
    // storeVersion to refresh the picker, and keying on it here re-triggered
    // the sync in an infinite loop.
    LaunchedEffect(active?.id) {
        val descriptor = active ?: return@LaunchedEffect
        error = null
        val opened = withContext(Dispatchers.IO) {
            runCatching {
                val words = loadRecoveryWords(context)
                BitcoinWalletEngine.openWithResult(descriptor, env.filesDirPath, words, null)
            }
        }
        opened.onFailure { error = "Open failed: ${it.message ?: it}" }
        val result = opened.getOrNull() ?: return@LaunchedEffect
        engine = result.wallet
        // Persist the freshly-derived public-key cache so the next open is
        // biometric-free, matching iOS.
        result.updatedDescriptor?.let { upd ->
            val fp = upd.cachedAccountFingerprint
            val xpub = upd.cachedAccountXpub
            if (fp != null && xpub != null) {
                env.store.setCachedAccountKey(descriptor.id, fp, xpub)
                onStoreChanged()
            }
        }
        rebuildNotice = if (result.rebuilt) {
            env.store.clearLastSync(descriptor.id)
            onStoreChanged()
            result.rebuildReason ?: "Maknoon upgrade or schema mismatch"
        } else {
            null
        }
        refreshBitcoin(
            env = env,
            engine = result.wallet,
            descriptor = descriptor,
            setBalance = { balanceSat = it },
            setTxs = { txs = it },
            setNetSats = { netSats = it },
            setNextReceive = { nextReceive = it },
            setFiat = { fiat = it },
            setSyncing = { syncing = it },
            setError = { error = it },
            onStoreChanged = onStoreChanged,
        )
    }

    // Resync the open wallet. Shared by the BalanceCard refresh button and the
    // scaffold's pull-to-refresh.
    val doRefresh: () -> Unit = {
        val e = engine
        val descriptor = active
        if (e != null && descriptor != null) {
            scope.launch {
                refreshBitcoin(
                    env = env,
                    engine = e,
                    descriptor = descriptor,
                    setBalance = { balanceSat = it },
                    setTxs = { txs = it },
                    setNetSats = { netSats = it },
                    setNextReceive = { nextReceive = it },
                    setFiat = { fiat = it },
                    setSyncing = { syncing = it },
                    setError = { error = it },
                    onStoreChanged = onStoreChanged,
                )
            }
        }
    }

    WalletChainScaffold(
        title = stringResource(com.elabify.app.maknoon.R.string.btc_bitcoin),
        onBack = onBack,
        actions = {
            WalletActionsMenu(onManage = onOpenWallets, onSettings = onOpenSettings)
        },
        isRefreshing = syncing,
        onRefresh = doRefresh,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Locked-banner equivalent: software wallets need the seed and
            // there is no identity.
            if (active == null) {
                EmptyState(
                    icon = Icons.Filled.CurrencyBitcoin,
                    title = stringResource(com.elabify.app.maknoon.R.string.btc_no_wallet),
                    subtitle = stringResource(com.elabify.app.maknoon.R.string.btc_create_identity_first),
                )
                return@Column
            }

            // Wallet picker
            WalletPicker(
                label = active.label,
                subtitle = "${active.network.displayName} · ${active.accountSuffix()}",
                expanded = pickerOpen,
                onExpand = { pickerOpen = it },
            ) {
                env.store.wallets.forEach { w ->
                    DropdownMenuItem(
                        text = { Text("${w.label} - ${w.network.displayName}") },
                        onClick = {
                            pickerOpen = false
                            env.store.setActive(w.id)
                            onStoreChanged()
                        },
                        leadingIcon = {
                            if (w.id == active.id) {
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaknoonColors.success)
                            }
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(com.elabify.app.maknoon.R.string.btc_manage_wallets)) },
                    onClick = { pickerOpen = false; onOpenWallets() },
                )
            }

            rebuildNotice?.let { reason ->
                Banner(
                    title = stringResource(com.elabify.app.maknoon.R.string.btc_local_cache_rebuilt),
                    variant = BannerVariant.WARNING,
                    body = stringResource(com.elabify.app.maknoon.R.string.btc_local_cache_rebuilt_body, reason),
                )
            }

            // Next-receive row
            nextReceive?.let { addr ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        stringResource(com.elabify.app.maknoon.R.string.btc_next_receive),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AddressChip(text = addr, head = 12, tail = 8, modifier = Modifier.fillMaxWidth())
                }
            }

            // Balance card (elevated, brand-tinted) with the sync row inside.
            BalanceCard(
                amount = balanceSat?.let { formatBtc(it) } ?: "-",
                ticker = active.network.ticker,
                syncing = syncing,
                syncLabel = lastSyncSummary(active),
                subnote = fiat,
                onRefresh = doRefresh,
            )

            // Action buttons. Bitcoin-orange tint + the iOS icon set (up-right
            // send, down-left receive) so the row matches the network theme.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ActionTile(stringResource(com.elabify.app.maknoon.R.string.walletc_send), Icons.AutoMirrored.Filled.CallMade, Modifier.weight(1f), onOpenSend, BitcoinOrange)
                ActionTile(stringResource(com.elabify.app.maknoon.R.string.walletc_receive), Icons.AutoMirrored.Filled.CallReceived, Modifier.weight(1f), onOpenReceive, BitcoinOrange)
                ActionTile(stringResource(com.elabify.app.maknoon.R.string.btc_addresses), Icons.Filled.List, Modifier.weight(1f), onOpenAddresses, BitcoinOrange)
            }

            // Recent transactions
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(com.elabify.app.maknoon.R.string.walletc_transactions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (txs.isNotEmpty()) {
                    TextButton(onClick = onOpenTxs) { Text(stringResource(com.elabify.app.maknoon.R.string.common_see_all)) }
                }
            }
            if (txs.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(Radii.md),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EmptyState(
                        icon = Icons.Filled.Inbox,
                        title = stringResource(com.elabify.app.maknoon.R.string.btc_no_transactions),
                        subtitle = stringResource(com.elabify.app.maknoon.R.string.btc_fund_to_see_here),
                        iconSize = 40.dp,
                    )
                }
            } else {
                txs.take(5).forEach { tx ->
                    TxCell {
                        BitcoinTxRow(
                            tx = tx,
                            netSat = netSats[tx.txidHex()],
                            explorerTxUrl = env.settings.txUrl(tx.txidHex(), active.network),
                            labelStore = env.labels,
                        )
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// MARK: -- shared dashboard visuals (Bitcoin)

@Composable
private fun WalletPicker(
    label: String,
    subtitle: String,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Box {
        Surface(
            shape = RoundedCornerShape(Radii.md),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = Elevation.card,
            modifier = Modifier.fillMaxWidth().clickable { onExpand(true) },
        ) {
            Row(
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFF7931A).tint(0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(com.elabify.app.maknoon.R.drawable.ic_chain_bitcoin),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.UnfoldMore, contentDescription = stringResource(com.elabify.app.maknoon.R.string.btc_switch_wallet), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpand(false) }, content = { menuContent() })
    }
}

/** Bitcoin brand orange, used to theme the dashboard action row. */
private val BitcoinOrange = Color(0xFFF7931A)

@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit, accent: Color = MaknoonBrand.accent) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(Radii.md),
        color = accent.tint(0.12f),
    ) {
        Column(
            Modifier.padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(icon, contentDescription = null, tint = accent)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = accent, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TxCell(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radii.sm),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) { content() }
    }
}

private fun lastSyncSummary(active: BitcoinWalletDescriptor): String {
    val last = active.lastSyncAtEpochSec ?: return "Never synced"
    val ageSec = (System.currentTimeMillis() / 1000) - last
    return when {
        ageSec < 60 -> "Last sync just now"
        ageSec < 3600 -> "Last sync ${ageSec / 60}m ago"
        ageSec < 86400 -> "Last sync ${ageSec / 3600}h ago"
        else -> "Last sync ${ageSec / 86400}d ago"
    }
}

/** Show cached BDK state instantly, then run the Electrum scan and refresh.
 *  Mirrors iOS BitcoinWalletView.refresh(): read-before-sync, full scan on
 *  first sync / incremental after, address-book mirror, mark-synced. All
 *  BDK + network work runs on Dispatchers.IO. */
suspend fun refreshBitcoin(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    setBalance: (Long) -> Unit,
    setTxs: (List<CanonicalTx>) -> Unit,
    setNetSats: (Map<String, Long>) -> Unit,
    setNextReceive: (String) -> Unit,
    setFiat: (String?) -> Unit,
    setSyncing: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onStoreChanged: () -> Unit,
) {
    val url = env.settings.electrumURL(descriptor.network)

    // Instant read of persisted state.
    val cachedBal = withContext(Dispatchers.IO) {
        runCatching {
            val bal = engine.balance().total.toSat().toLong()
            val list = engine.transactions()
            setTxs(list)
            setNetSats(netSatsFor(engine, list))
            // Dashboard shows the next UNUSED address (non-advancing), matching
            // iOS; calling nextReceiveAddress() here advanced the keychain on
            // every refresh.
            setNextReceive(engine.nextUnusedReceiveAddress().address.toString())
            // Republish next-unused address into the mirror.
            val unused = engine.nextUnusedReceiveAddress().address.toString()
            env.store.updateMirrorAddress(descriptor.id, unused)
            bal
        }.getOrNull()
    }
    cachedBal?.let { setBalance(it); refreshFiat(env, descriptor, it, setFiat) }

    setSyncing(true)
    setError(null)
    val synced = withContext(Dispatchers.IO) {
        runCatching {
            if (descriptor.lastSyncAtEpochSec == null) engine.fullScan(url) else engine.sync(url)
            val bal = engine.balance().total.toSat().toLong()
            val list = engine.transactions()
            Triple(bal, list, netSatsFor(engine, list))
        }
    }
    synced.onSuccess { (bal, list, nets) ->
        setBalance(bal)
        setTxs(list)
        setNetSats(nets)
        withContext(Dispatchers.IO) {
            runCatching {
                val unused = engine.nextUnusedReceiveAddress().address.toString()
                env.store.updateMirrorAddress(descriptor.id, unused)
            }
        }
        env.store.markSynced(descriptor.id)
        onStoreChanged()
        refreshFiat(env, descriptor, bal, setFiat)
    }.onFailure {
        setError("Sync failed: ${it.message ?: it}")
    }
    setSyncing(false)
}

private fun netSatsFor(engine: BitcoinWalletEngine, txs: List<CanonicalTx>): Map<String, Long> {
    val out = HashMap<String, Long>()
    for (tx in txs) out[tx.txidHex()] = engine.netAmount(tx.transaction)
    return out
}

/** Fetch the fiat caption for the current balance (mainnet only, non-zero),
 *  matching iOS's fiatBalance. Soft-fails to null (caption hidden). */
private suspend fun refreshFiat(
    env: BitcoinWalletEnv,
    descriptor: BitcoinWalletDescriptor,
    balanceSat: Long,
    setFiat: (String?) -> Unit,
) {
    // Mainnet only (coinType 0); the shared AssetPriceCache + FiatReference gate
    // handles the currency, the showReferencePrices switch, and zero balances.
    if (descriptor.network.coinType != 0L) {
        setFiat(null)
        return
    }
    // Price-source URLs + showReferencePrices gate are handled centrally by
    // FiatReference (reads the global, overridable FiatPreferences endpoints).
    val btc = balanceSat / 100_000_000.0
    setFiat(FiatReference.caption(assetId = "bitcoin", amount = btc))
}
