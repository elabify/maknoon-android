// Tron wallet -- the single top-level entry composable the Wallet tab
// routes to. Owns the in-package navigation between the dashboard and
// its sheets/screens (send, receive, wallets, add-wallet, settings,
// add-token, token-detail, full transaction list), mirroring the set of
// `.sheet(...)` modifiers on iOS's TronWalletView.
//
// Everything below the entry composable is private/internal to this
// package. Engine + network calls run on Dispatchers.IO inside coroutines
// launched from rememberCoroutineScope / LaunchedEffect; the seed comes
// from IdentitySandwich.load, stores from TronStores (process-scoped so
// the pending-tx map + active-wallet selection survive navigation).

package com.elabify.app.maknoon.ui.wallet.tron

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
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
import com.elabify.musnad.wallet.tron.PendingTronTx
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronRPCClient
import com.elabify.musnad.wallet.tron.TronScanAPI
import com.elabify.musnad.wallet.tron.TronTRC20Token
import com.elabify.musnad.wallet.tron.TronTRC20TransferBuilder
import com.elabify.musnad.wallet.tron.TronWallet
import com.elabify.musnad.wallet.tron.TronWalletDescriptor
import com.elabify.musnad.wallet.tron.TronWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.elabify.app.maknoon.ui.wallet.common.relativeSince

/** Where the Tron flow currently is. The dashboard is the root; the
 *  rest are pushed full-screen (Android sheets and pushed screens are
 *  the same animation budget here, and a full surface gives the send /
 *  settings forms room). Mirrors the iOS sheet set. */
private sealed interface TronRoute {
    data object Dashboard : TronRoute
    data class Send(val walletId: UUID, val preselectTokenId: String?) : TronRoute
    data class Receive(val walletId: UUID) : TronRoute
    data object Wallets : TronRoute
    data object AddWallet : TronRoute
    data object RegisterDevice : TronRoute
    data object Settings : TronRoute
    data object SignMessage : TronRoute
    data object VerifyMessage : TronRoute
    data class AddToken(val prefilledContract: String?) : TronRoute
    data class TokenDetail(val walletId: UUID, val tokenId: String) : TronRoute
    data class TxList(val walletId: UUID, val ownerAddress: String) : TronRoute
}

/** THE entry composable for the Tron chain. The Wallet tab routes here.
 *  [onBack] pops back to the Wallets home (WalletScreen passes popToList);
 *  it is threaded down to the dashboard's shared WalletChainScaffold. */
@Composable
fun TronWalletScreen(onBack: () -> Unit) {
    var route by remember { mutableStateOf<TronRoute>(TronRoute.Dashboard) }
    var pendingHwDeviceId by remember { mutableStateOf<UUID?>(null) }
    val context = LocalContext.current
    val tokenStore = remember { TronStores.tokenStore(context) }

    when (val r = route) {
        is TronRoute.Dashboard -> TronDashboard(
            onBack = onBack,
            onSend = { id, tokenId -> route = TronRoute.Send(id, tokenId) },
            onReceive = { id -> route = TronRoute.Receive(id) },
            onWallets = { route = TronRoute.Wallets },
            onAddWallet = { pendingHwDeviceId = null; route = TronRoute.AddWallet },
            onSettings = { route = TronRoute.Settings },
            onSignMessage = { route = TronRoute.SignMessage },
            onVerifyMessage = { route = TronRoute.VerifyMessage },
            onAddToken = { prefill -> route = TronRoute.AddToken(prefill) },
            onTokenDetail = { id, tokenId -> route = TronRoute.TokenDetail(id, tokenId) },
            onTxList = { id, addr -> route = TronRoute.TxList(id, addr) },
        )
        is TronRoute.Send -> TronSendScreen(
            walletId = r.walletId,
            preselectTokenId = r.preselectTokenId,
            onDone = { route = TronRoute.Dashboard },
        )
        is TronRoute.Receive -> TronReceiveScreen(
            walletId = r.walletId,
            onDone = { route = TronRoute.Dashboard },
        )
        is TronRoute.Wallets -> TronWalletsScreen(
            onAddWallet = { pendingHwDeviceId = null; route = TronRoute.AddWallet },
            onOpenSettings = { route = TronRoute.Settings },
            onDone = { route = TronRoute.Dashboard },
        )
        is TronRoute.SignMessage -> TronSignMessageScreen(
            active = TronStores.walletStore(context).activeWallet,
            onClose = { route = TronRoute.Dashboard },
        )
        is TronRoute.VerifyMessage -> TronVerifyMessageScreen(onClose = { route = TronRoute.Dashboard })
        is TronRoute.AddWallet -> AddTronWalletScreen(
            initialDeviceId = pendingHwDeviceId,
            onRegisterDevice = { route = TronRoute.RegisterDevice },
            onDone = { route = TronRoute.Wallets },
        )
        is TronRoute.RegisterDevice -> TronRegisterDeviceRoute(
            onRegistered = { deviceId -> pendingHwDeviceId = deviceId; route = TronRoute.AddWallet },
            onCancel = { route = TronRoute.AddWallet },
        )
        is TronRoute.Settings -> TronSettingsScreen(
            onDone = { route = TronRoute.Dashboard },
        )
        is TronRoute.AddToken -> TronAddTokenScreen(
            prefilledContract = r.prefilledContract,
            onDone = { route = TronRoute.Dashboard },
        )
        is TronRoute.TokenDetail -> {
            val token = tokenStore.allTokens.firstOrNull { it.id == r.tokenId }
            if (token == null) {
                route = TronRoute.Dashboard
            } else {
                TronTokenDetailScreen(
                    walletId = r.walletId,
                    token = token,
                    onSend = { route = TronRoute.Send(r.walletId, r.tokenId) },
                    onReceive = { route = TronRoute.Receive(r.walletId) },
                    onDone = { route = TronRoute.Dashboard },
                )
            }
        }
        is TronRoute.TxList -> TronTransactionListScreen(
            walletId = r.walletId,
            ownerAddress = r.ownerAddress,
            onDone = { route = TronRoute.Dashboard },
        )
    }
}

// MARK: -- Dashboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TronDashboard(
    onBack: () -> Unit,
    onSend: (UUID, String?) -> Unit,
    onReceive: (UUID) -> Unit,
    onWallets: () -> Unit,
    onAddWallet: () -> Unit,
    onSettings: () -> Unit,
    onSignMessage: () -> Unit,
    onVerifyMessage: () -> Unit,
    onAddToken: (String?) -> Unit,
    onTokenDetail: (UUID, String) -> Unit,
    onTxList: (UUID, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walletStore = remember { TronStores.walletStore(context) }
    val settings = remember { TronStores.settings(context) }
    val tokenStore = remember { TronStores.tokenStore(context) }
    val catalog = remember { TronStores.catalog(context) }

    // re-render trigger: bumped whenever store state changes underneath us
    var stateRev by remember { mutableStateOf(0) }
    val activeWallet = remember(stateRev) { walletStore.activeWallet }
    val activeNetwork = remember(stateRev) { walletStore.currentNetwork }

    var address by remember { mutableStateOf<String?>(null) }
    var sun by remember { mutableStateOf<Long?>(null) }
    var fiat by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<TronRPCClient.TxRecord>>(emptyList()) }
    val tokenBalances = remember { mutableStateMapOf<String, String>() }
    var lastSyncAtMs by remember { mutableStateOf<Long?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val sandwich = remember { loadTronSandwich(context) }
    val showsLockedBanner = sandwich == null && (activeWallet?.kind is TronWalletKind.Software)

    fun refresh() {
        val descriptor = activeWallet ?: return
        if (sandwich == null && descriptor.kind is TronWalletKind.Software) return
        scope.launch {
            syncing = true
            lastError = null
            val net = walletStore.currentNetwork
            val rpcURL = settings.rpcURL(net)
            withContext(Dispatchers.IO) {
                runCatching { catalog.refreshIfStale(settings.tokenCatalogURL) }
            }
            // The Tron address is a LOCAL, network-agnostic derivation (same
            // T-address on mainnet / shasta / nile). Resolve + publish it FIRST,
            // independent of the balance / tx RPC below, so Send + Explorer stay
            // enabled even when a given network's RPC fails or the account is
            // unactivated. Previously address was only set on full-refresh
            // success, so a failed mainnet/shasta sync greyed those buttons while
            // Nile (which happened to succeed) showed them correctly.
            val resolvedAddr = withContext(Dispatchers.IO) {
                runCatching { TronWallet(descriptor, net, rpcURL, sandwich).resolvedAddress() }.getOrNull()
            }
            if (resolvedAddr != null) {
                address = resolvedAddr
                walletStore.updateMirrorAddress(descriptor.id, resolvedAddr)
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wallet = TronWallet(descriptor, net, rpcURL, sandwich)
                    val a = resolvedAddr ?: wallet.resolvedAddress()
                    val bal = wallet.refreshBalance()
                    val txs = runCatching { wallet.recentTransactions(10) }.getOrDefault(emptyList())
                    // confirmed -> drop matching pending entries
                    walletStore.dropConfirmedPending(descriptor.id, txs.map { it.txID }.toSet())
                    // auto-discover held TRC-20s (mainnet only)
                    if (net == TronNetwork.MAINNET) {
                        val held = runCatching { TronScanAPI.discoverHeldTRC20(a) }.getOrDefault(emptyList())
                        tokenStore.reconcile(held.map { it.contract }, net, catalog)
                    }
                    val balances = HashMap<String, String>()
                    for (token in tokenStore.tokens(net)) {
                        runCatching {
                            TronTRC20TransferBuilder.balance(a, token.contract, rpcURL)
                        }.getOrNull()?.let { balances[token.contract] = it }
                    }
                    walletStore.markSynced(descriptor.id)
                    Triple(a, bal, txs) to balances
                }
            }
            result.onSuccess { (triple, balances) ->
                address = triple.first
                sun = triple.second
                fiat = FiatReference.caption(net.coinGeckoAssetId, triple.second / 1_000_000.0)
                recent = triple.third
                tokenBalances.clear()
                tokenBalances.putAll(balances)
                lastSyncAtMs = System.currentTimeMillis()
            }.onFailure {
                lastError = it.message ?: it.toString()
            }
            syncing = false
        }
    }

    LaunchedEffect(activeWallet?.id, activeNetwork) {
        // reset per-wallet/network display while the new sync lands
        sun = null; fiat = null; recent = emptyList(); tokenBalances.clear(); address = null
        refresh()
    }

    WalletChainScaffold(
        title = stringResource(R.string.trx_tron),
        onBack = onBack,
        actions = {
            // Settings lives behind a gear on Manage Wallets (matching iOS / BTC /
            // ETH); canonical order Add / Manage / divider / Sign / Verify (ADR-0033).
            WalletActionsMenu(
                onManage = onWallets,
                onAddWallet = onAddWallet,
                onSignMessage = onSignMessage,
                onVerifyMessage = onVerifyMessage,
            )
        },
        isRefreshing = syncing,
        onRefresh = { refresh() },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (showsLockedBanner) {
                LockedBanner()
            }

            // wallet picker / empty state
            if (activeWallet == null) {
                EmptyWalletPrompt(onAddWallet)
            } else {
                WalletPickerChip(
                    label = activeWallet.label,
                    subtitle = walletSubtitle(activeWallet),
                    accent = TronRed,
                    iconRes = com.elabify.app.maknoon.R.drawable.ic_chain_tron,
                    items = walletStore.wallets.map { WalletChipItem(it.id.toString(), it.label) },
                    selectedId = activeWallet.id.toString(),
                    onPick = { id -> walletStore.setActive(UUID.fromString(id)); stateRev++ },
                    onManage = onWallets,
                )

                address?.let { addr ->
                    AccountAddressBadge(accountIndex = accountIndexOf(activeWallet), address = addr)
                }

                NetworkPickerChip(
                    options = TronNetwork.entries.map { NetworkOption(it.name, it.displayName, it != TronNetwork.MAINNET) },
                    selectedId = activeNetwork.name,
                    onSelect = { opt -> walletStore.setActiveNetwork(activeWallet.id, TronNetwork.valueOf(opt.id)); stateRev++ },
                )

                BalanceCard(
                    amount = sun?.let { formatTrx(it) } ?: "-",
                    ticker = "TRX",
                    syncing = syncing,
                    syncLabel = stringResource(R.string.trx_last_sync, relativeSince(lastSyncAtMs ?: activeWallet.lastSyncAtEpochSec?.times(1000))),
                    onRefresh = { refresh() },
                    subnote = fiat,
                )

                ActionButtons(
                    sendEnabled = address != null,
                    onSend = { activeWallet.id.let { onSend(it, null) } },
                    onReceive = { onReceive(activeWallet.id) },
                    onExplorer = {
                        address?.let { addr ->
                            val url = tronAddressExplorerUrl(settings.explorerURL(activeNetwork), addr)
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    },
                    accent = TronRed,
                )

                TokensSection(
                    tokens = tokenStore.tokens(activeNetwork),
                    balances = tokenBalances,
                    onAddToken = { onAddToken(null) },
                    onTokenTap = { token -> onTokenDetail(activeWallet.id, token.id) },
                    // Network-wide remove (TRC-20 tokens are not wallet-scoped),
                    // mirroring iOS; bump stateRev to recompute the in-memory list.
                    onRemoveToken = { token ->
                        tokenStore.remove(token)
                        tokenBalances.remove(token.contract)
                        stateRev++
                    },
                )

                val unknown = tokenStore.unknownContracts(activeNetwork)
                if (unknown.isNotEmpty()) {
                    UnknownTokensBanner(
                        contracts = unknown,
                        onAddCustom = { onAddToken(it) },
                        onIgnore = { tokenStore.dismissUnknown(it, activeNetwork); stateRev++ },
                    )
                }

                RecentTransactions(
                    pending = walletStore.pendingTxsByWallet[activeWallet.id] ?: emptyList(),
                    recent = recent,
                    ownerAddress = address ?: "",
                    explorerBase = settings.explorerURL(activeNetwork),
                    syncing = syncing,
                    onSeeAll = { address?.let { onTxList(activeWallet.id, it) } },
                )
            }

            lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun accountIndexOf(w: TronWalletDescriptor): Long = when (val k = w.kind) {
    is TronWalletKind.Software -> k.account
    is TronWalletKind.Hardware -> k.account
}

private fun walletSubtitle(w: TronWalletDescriptor): String = when (val k = w.kind) {
    is TronWalletKind.Software -> "Software · Account ${k.account}"
    is TronWalletKind.Hardware -> "Hardware · Account ${k.account}"
}

@Composable
private fun LockedBanner() {
    Banner(
        title = stringResource(R.string.trx_identity_locked),
        variant = BannerVariant.WARNING,
        body = stringResource(R.string.trx_identity_locked_body),
        icon = Icons.Filled.Lock,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
}

@Composable
private fun EmptyWalletPrompt(onAddWallet: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.trx_no_wallet_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAddWallet) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trx_add_a_wallet))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TokensSection(
    tokens: List<TronTRC20Token>,
    balances: SnapshotStateMap<String, String>,
    onAddToken: () -> Unit,
    onTokenTap: (TronTRC20Token) -> Unit,
    onRemoveToken: (TronTRC20Token) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menuTokenId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.walletc_tokens), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddToken) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.walletc_add_token)) }
        }
        if (tokens.isEmpty()) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trx_no_trc20_tokens), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        } else {
            tokens.forEach { token ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onTokenTap(token) },
                        onLongClick = { menuTokenId = token.id },
                    ),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TronTRC20TokenRow(token = token, rawBalance = balances[token.contract], modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        // Long-press the row for a context menu (parity with iOS).
                        DropdownMenu(expanded = menuTokenId == token.id, onDismissRequest = { menuTokenId = null }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.eth_copy_contract_address)) },
                                onClick = {
                                    clipboard.setText(AnnotatedString(token.contract))
                                    menuTokenId = null
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_remove)) },
                                onClick = {
                                    onRemoveToken(token)
                                    menuTokenId = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnknownTokensBanner(contracts: List<String>, onAddCustom: (String) -> Unit, onIgnore: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFF29900))
                Text(stringResource(R.string.trx_unknown_tokens_detected), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                stringResource(R.string.trx_unknown_tokens_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            contracts.forEach { contract ->
                Text(contract, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { onAddCustom(contract) }) { Text(stringResource(R.string.trx_add_as_custom)) }
                    TextButton(onClick = { onIgnore(contract) }) { Text(stringResource(R.string.trx_ignore)) }
                }
            }
        }
    }
}

@Composable
private fun RecentTransactions(
    pending: List<PendingTronTx>,
    recent: List<TronRPCClient.TxRecord>,
    ownerAddress: String,
    explorerBase: String,
    syncing: Boolean,
    onSeeAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.walletc_transactions), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (recent.isNotEmpty()) TextButton(onClick = onSeeAll) { Text(stringResource(R.string.common_see_all)) }
        }
        if (pending.isEmpty() && recent.isEmpty()) {
            Surface(shape = RoundedCornerShape(Radii.md), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Filled.Inbox,
                    title = if (syncing) stringResource(R.string.trx_loading) else stringResource(R.string.trx_no_transactions_yet),
                    subtitle = stringResource(R.string.trx_fund_wallet_history),
                    iconSize = 40.dp,
                )
            }
        } else {
            pending.forEach { p ->
                Surface(shape = RoundedCornerShape(Radii.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) { PendingTronTxRow(p, explorerBase) }
                }
            }
            recent.take(5).forEach { rec ->
                Surface(shape = RoundedCornerShape(Radii.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) { TronTxRow(rec, ownerAddress, explorerBase) }
                }
            }
        }
    }
}

/**
 * Hosts the shared device-registration flow inline in the Tron chain, so the Add
 * screen's "Add New Device" can pair a Ledger / security key without bouncing out
 * to Settings -> Devices. On finish / cancel it returns to Add; the paired device
 * id is handed back so Add reopens with it selected.
 */
@Composable
private fun TronRegisterDeviceRoute(
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
