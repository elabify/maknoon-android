// Ethereum wallet -- the single top-level entry composable the Wallet tab
// routes to. Owns the in-package navigation between the dashboard and its
// sheets/screens (send, receive, wallets, add-wallet, settings, network
// picker, custom-network editor, add-token, full transaction list),
// mirroring the set of `.sheet(...)` modifiers on iOS's EthereumWalletView.
//
// Everything below the entry composable is private/internal to this
// package. Engine + network calls run on Dispatchers.IO inside coroutines
// launched from rememberCoroutineScope / LaunchedEffect; the seed comes
// from IdentitySandwich.load, stores from EthereumStores (process-scoped so
// the pending-tx map + active-wallet + chain-wide network survive nav).

package com.elabify.app.maknoon.ui.wallet.ethereum

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
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
import com.elabify.app.maknoon.ui.wallet.common.WalletActionsMenu
import com.elabify.app.maknoon.ui.wallet.common.WalletChainScaffold
import com.elabify.app.maknoon.ui.wallet.common.WalletChipItem
import com.elabify.app.maknoon.ui.wallet.common.WalletPickerChip
import com.elabify.musnad.wallet.ethereum.EthereumToken
import com.elabify.musnad.wallet.ethereum.EthereumTx
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.PendingEthereumTx
import com.elabify.musnad.wallet.ethereum.ResolvedNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Where the Ethereum flow currently is. The dashboard is the root; the
 *  rest are pushed full-screen, mirroring the iOS sheet set. */
private sealed interface EthRoute {
    data object Dashboard : EthRoute
    data class Send(val walletId: UUID, val preselectTokenId: String?) : EthRoute
    data class Receive(val walletId: UUID) : EthRoute
    data object Wallets : EthRoute
    data object AddWallet : EthRoute
    data object RegisterDevice : EthRoute
    data object Settings : EthRoute
    data object NetworkPicker : EthRoute
    data class CustomNetwork(val editId: UUID?) : EthRoute
    data class AddToken(val prefilledContract: String?) : EthRoute
    data class TxList(val walletId: UUID, val ownerAddress: String) : EthRoute
    data class TokenDetail(val walletId: UUID, val tokenId: String) : EthRoute
}

/** THE entry composable for the Ethereum chain. The Wallet tab routes here.
 *  [onBack] pops back to the chain list; the dashboard threads it into the
 *  shared WalletChainScaffold so the screen never owns its own chrome. */
@Composable
fun EthereumWalletScreen(onBack: () -> Unit) {
    var route by remember { mutableStateOf<EthRoute>(EthRoute.Dashboard) }
    // Non-null right after an inline "Register a device": reopen Add on the
    // Hardware tab with this device selected (ADR-0033).
    var pendingHwDeviceId by remember { mutableStateOf<UUID?>(null) }

    when (val r = route) {
        is EthRoute.Dashboard -> EthereumDashboard(
            onBack = onBack,
            onSend = { id, tokenId -> route = EthRoute.Send(id, tokenId) },
            onReceive = { id -> route = EthRoute.Receive(id) },
            onWallets = { route = EthRoute.Wallets },
            onAddWallet = { pendingHwDeviceId = null; route = EthRoute.AddWallet },
            onSettings = { route = EthRoute.Settings },
            onNetworkPicker = { route = EthRoute.NetworkPicker },
            onAddToken = { prefill -> route = EthRoute.AddToken(prefill) },
            onTxList = { id, addr -> route = EthRoute.TxList(id, addr) },
            onTokenDetail = { id, tokenId -> route = EthRoute.TokenDetail(id, tokenId) },
        )
        is EthRoute.Send -> EthereumSendScreen(
            walletId = r.walletId,
            preselectTokenId = r.preselectTokenId,
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.Receive -> EthereumReceiveScreen(
            walletId = r.walletId,
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.Wallets -> EthereumWalletsScreen(
            onAddWallet = { pendingHwDeviceId = null; route = EthRoute.AddWallet },
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.AddWallet -> AddEthereumWalletScreen(
            initialDeviceId = pendingHwDeviceId,
            onRegisterDevice = { route = EthRoute.RegisterDevice },
            onDone = { route = EthRoute.Wallets },
        )
        is EthRoute.RegisterDevice -> EthereumRegisterDeviceRoute(
            onRegistered = { deviceId -> pendingHwDeviceId = deviceId; route = EthRoute.AddWallet },
            onCancel = { route = EthRoute.AddWallet },
        )
        is EthRoute.Settings -> EthereumSettingsScreen(
            onCustomNetworks = { route = EthRoute.NetworkPicker },
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.NetworkPicker -> EthereumNetworkPickerScreen(
            onAddCustom = { route = EthRoute.CustomNetwork(null) },
            onEditCustom = { id -> route = EthRoute.CustomNetwork(id) },
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.CustomNetwork -> CustomNetworkEditorScreen(
            editId = r.editId,
            onDone = { route = EthRoute.NetworkPicker },
        )
        is EthRoute.AddToken -> EthereumAddTokenScreen(
            prefilledContract = r.prefilledContract,
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.TxList -> EthereumTransactionListScreen(
            walletId = r.walletId,
            ownerAddress = r.ownerAddress,
            onDone = { route = EthRoute.Dashboard },
        )
        is EthRoute.TokenDetail -> EthereumTokenDetailScreen(
            walletId = r.walletId,
            tokenId = r.tokenId,
            onSend = { route = EthRoute.Send(r.walletId, r.tokenId) },
            onReceive = { route = EthRoute.Receive(r.walletId) },
            onDone = { route = EthRoute.Dashboard },
        )
    }
}

// MARK: -- Dashboard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EthereumDashboard(
    onBack: () -> Unit,
    onSend: (UUID, String?) -> Unit,
    onReceive: (UUID) -> Unit,
    onWallets: () -> Unit,
    onAddWallet: () -> Unit,
    onSettings: () -> Unit,
    onNetworkPicker: () -> Unit,
    onAddToken: (String?) -> Unit,
    onTxList: (UUID, String) -> Unit,
    onTokenDetail: (UUID, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walletStore = remember { EthereumStores.walletStore(context) }
    val settings = remember { EthereumStores.settings(context) }
    val tokenStore = remember { EthereumStores.tokenStore(context) }
    val customs = remember { EthereumStores.customs(context) }

    var stateRev by remember { mutableStateOf(0) }
    val activeWallet = remember(stateRev) { walletStore.activeWallet }
    val resolved = remember(stateRev) { walletStore.resolve(walletStore.currentNetworkID, customs, settings) }

    var balanceWei by remember { mutableStateOf<String?>(null) }
    var fiat by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<EthereumTx>>(emptyList()) }
    val tokenBalances = remember { mutableStateMapOf<String, String>() }
    var lastSyncAtMs by remember { mutableStateOf<Long?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    val sandwich = remember { loadEthereumSandwich(context) }
    val showsLockedBanner = sandwich == null && (activeWallet?.kind is EthereumWalletKind.Software)

    fun refresh() {
        val descriptor = activeWallet ?: return
        val addr = descriptor.address ?: return
        scope.launch {
            syncing = true
            lastError = null
            val net = resolved
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val wallet = EthereumWallet(descriptor)
                    if (descriptor.cachedAddress.isNullOrEmpty()) walletStore.setCachedAddress(descriptor.id, addr)
                    val bal = wallet.balance(net.rpcURL)
                    val txs = runCatching {
                        wallet.recentTransactions(net.explorerAPIURL, net.explorerAPIKey, net.chainId, perPage = 25)
                    }.getOrDefault(emptyList())
                    walletStore.dropConfirmedPending(descriptor.id, txs.map { it.hash }.toSet())
                    val balances = HashMap<String, String>()
                    for (token in tokenStore.tokens(net)) {
                        runCatching { wallet.tokenBalance(token, net.rpcURL) }
                            .getOrNull()?.let { balances[token.contractAddress] = it.hex }
                    }
                    walletStore.markSynced(descriptor.id)
                    Triple(bal.hex, txs, balances)
                }
            }
            result.onSuccess { (bal, txs, balances) ->
                balanceWei = bal
                fiat = FiatReference.caption(
                    net.coinGeckoAssetId,
                    com.elabify.musnad.wallet.ethereum.EthereumWeiValue.fromHex(bal).ether.toDouble(),
                )
                recent = txs
                tokenBalances.clear()
                tokenBalances.putAll(balances)
                lastSyncAtMs = System.currentTimeMillis()
            }.onFailure {
                lastError = it.message ?: it.toString()
            }
            syncing = false
        }
    }

    LaunchedEffect(activeWallet?.id, resolved.networkID.stableId) {
        balanceWei = null; fiat = null; recent = emptyList(); tokenBalances.clear()
        refresh()
    }

    WalletChainScaffold(
        title = stringResource(R.string.eth_ethereum),
        onBack = onBack,
        actions = {
            WalletActionsMenu(onManage = onWallets, onSettings = onSettings)
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
            if (showsLockedBanner) LockedBanner()

            if (activeWallet == null) {
                EmptyWalletPrompt(onAddWallet)
            } else {
                WalletPickerChip(
                    label = activeWallet.label,
                    subtitle = walletSubtitle(activeWallet),
                    accent = EthBlue,
                    iconRes = com.elabify.app.maknoon.R.drawable.ic_chain_ethereum,
                    items = walletStore.wallets.map { WalletChipItem(it.id.toString(), it.label) },
                    selectedId = activeWallet.id.toString(),
                    onPick = { id -> walletStore.setActive(java.util.UUID.fromString(id)); stateRev++ },
                    onManage = onWallets,
                )

                activeWallet.address?.let { addr ->
                    AccountAddressBadge(accountIndex = accountIndexOf(activeWallet), address = addr)
                }

                NetworkPickerChip(network = resolved, onTap = onNetworkPicker)

                BalanceCard(
                    amount = balanceWei?.let { com.elabify.musnad.wallet.ethereum.EthereumWeiValue.fromHex(it).display(ticker = "").trim() } ?: "-",
                    ticker = resolved.ticker,
                    syncing = syncing,
                    syncLabel = stringResource(R.string.eth_last_sync, ethRelativeSince(lastSyncAtMs ?: activeWallet.lastSyncAt)),
                    onRefresh = { refresh() },
                    subnote = fiat,
                )

                ActionButtons(
                    sendEnabled = activeWallet.address != null,
                    onSend = { onSend(activeWallet.id, null) },
                    onReceive = { onReceive(activeWallet.id) },
                    onExplorer = {
                        activeWallet.address?.let { addr ->
                            val base = resolved.explorerURL.trimEnd('/')
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/address/$addr"))) }
                        }
                    },
                    accent = EthBlue,
                )

                TokensSection(
                    network = resolved,
                    tokens = tokenStore.tokens(resolved),
                    balances = tokenBalances,
                    onAddToken = { onAddToken(null) },
                    onTokenTap = { token -> onTokenDetail(activeWallet.id, token.id) },
                )

                RecentTransactions(
                    pending = walletStore.pendingTxsByWallet[activeWallet.id] ?: emptyList(),
                    recent = recent,
                    ownerAddress = activeWallet.address ?: "",
                    explorerBase = resolved.explorerURL,
                    syncing = syncing,
                    onSeeAll = { activeWallet.address?.let { onTxList(activeWallet.id, it) } },
                )
            }

            lastError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun accountIndexOf(w: EthereumWalletDescriptor): Long = when (val k = w.kind) {
    is EthereumWalletKind.Software -> k.account
    is EthereumWalletKind.Hardware -> k.account
}

internal fun walletSubtitle(w: EthereumWalletDescriptor): String = when (val k = w.kind) {
    is EthereumWalletKind.Software -> "Software · Account ${k.account}"
    is EthereumWalletKind.Hardware -> "Hardware · Account ${k.account}"
}

@Composable
private fun LockedBanner() {
    Banner(
        title = stringResource(R.string.eth_identity_locked),
        variant = BannerVariant.WARNING,
        body = stringResource(R.string.eth_identity_locked_body),
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
        Text(stringResource(R.string.eth_no_wallet_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onAddWallet) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.eth_add_an_ethereum_wallet))
        }
    }
}

@Composable
private fun NetworkPickerChip(network: ResolvedNetwork, onTap: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { onTap() },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Public, contentDescription = null, tint = EthBlue)
                Text(network.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (network.isTestnet) TestnetPill()
                if (!network.isBuiltin) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(EthBlue.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text(stringResource(R.string.walletc_custom), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun TestnetPill() {
    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFF29900).copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(stringResource(R.string.walletc_testnet), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TokensSection(
    network: ResolvedNetwork,
    tokens: List<EthereumToken>,
    balances: SnapshotStateMap<String, String>,
    onAddToken: () -> Unit,
    onTokenTap: (EthereumToken) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.walletc_tokens), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (network.isBuiltin) IconButton(onClick = onAddToken) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.walletc_add_token)) }
        }
        if (!network.isBuiltin) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.eth_tokens_builtin_only), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        } else if (tokens.isEmpty()) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.eth_no_tokens_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        } else {
            tokens.forEach { token ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable { onTokenTap(token) },
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        EthereumTokenRow(token = token, rawBalanceHex = balances[token.contractAddress], modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentTransactions(
    pending: List<PendingEthereumTx>,
    recent: List<EthereumTx>,
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
                    title = if (syncing) stringResource(R.string.eth_loading) else stringResource(R.string.eth_no_transactions),
                    subtitle = stringResource(R.string.eth_fund_wallet_history),
                    iconSize = 40.dp,
                )
            }
        } else {
            pending.forEach { p ->
                Surface(shape = RoundedCornerShape(Radii.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) { PendingEthereumTxRow(p, explorerBase) }
                }
            }
            recent.take(5).forEach { tx ->
                Surface(shape = RoundedCornerShape(Radii.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) { EthereumTxRow(tx, ownerAddress, explorerBase) }
                }
            }
        }
    }
}

/**
 * Hosts the shared device-registration flow inline in the Ethereum chain, so the
 * Add screen's "Add New Device" can pair a Ledger / security key without bouncing
 * out to Settings -> Devices. On finish / cancel it returns to the Add screen;
 * the freshly-paired device id is handed back so Add reopens with it selected.
 */
@Composable
private fun EthereumRegisterDeviceRoute(
    onRegistered: (UUID?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val registry = remember { com.elabify.musnad.devices.DeviceRegistry(context) }
    com.elabify.app.maknoon.ui.devices.AddHardwareDeviceFlow(
        registry = registry,
        onFinished = { device -> onRegistered(device?.id) },
        onCancel = onCancel,
    )
}
