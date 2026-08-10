// The Wallet tab: digital assets organised per network, mirroring iOS
// WalletView.swift 1:1. NOT a top tab bar: the root is a LazyColumn list with
// one row per network THAT HAS at least one wallet/account (order: Bitcoin,
// Bitcoin Lightning, Ethereum, Solana, Tron). Each row shows a leading chain
// icon, the active wallet label, and a network/account subtitle, and pushes
// that chain's full wallet screen (BitcoinWalletScreen / EthereumWalletScreen /
// SolanaWalletScreen / TronWalletScreen / LightningWalletScreen) which owns its
// own state, settings, and add-wallet flow.
//
// Routing is in-screen state (the same sealed-route + BackHandler pattern as
// ui/identity/IdentityScreen.kt) so the system back button returns to the
// network list instead of exiting the app. When no network has a wallet the
// EmptyState is shown; the top-bar "+" opens a menu of the five networks and
// pushing one lets that chain's own Add-wallet flow create the first wallet.
//
// "Has wallets" is read from the SAME SDK stores the chain screens use:
//   Bitcoin   BitcoinWalletEnv.create(ctx).store.wallets       (auto-seeded #0)
//   Ethereum  EthereumStores.walletStore(ctx).wallets
//   Solana    SolanaEnv.get(ctx).walletStore.wallets
//   Tron      TronStores.walletStore(ctx).wallets
//   Lightning LightningEnv.get(ctx).accountStore.accounts
// The subtitles use only public descriptor fields (label, network, count).

package com.elabify.app.maknoon.ui.wallet

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
import com.elabify.app.maknoon.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.components.EmptyState
import com.elabify.app.maknoon.ui.settings.SettingsScreen
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletEnv
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletScreen
import com.elabify.app.maknoon.ui.wallet.bitcoin.accountSuffix
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumStores
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumWalletScreen
import com.elabify.app.maknoon.ui.wallet.ethereum.resolveCurrentNetwork
import com.elabify.app.maknoon.ui.wallet.lightning.LightningEnv
import com.elabify.app.maknoon.ui.wallet.lightning.LightningWalletScreen
import com.elabify.app.maknoon.ui.wallet.solana.SolanaEnv
import com.elabify.app.maknoon.ui.wallet.solana.SolanaWalletScreen
import com.elabify.app.maknoon.ui.wallet.tron.TronStores
import com.elabify.app.maknoon.ui.wallet.tron.TronWalletScreen
import com.elabify.musnad.devices.DeviceRegistry

/** The five chains, in the iOS section order. The two enum members below the
 *  list (icon, tint, title) are pure presentation; the per-chain wallet count
 *  and active-wallet label are read live from each chain's SDK store. */
private enum class Chain(@DrawableRes val iconRes: Int, val title: String, val accent: Color) {
    BITCOIN(R.drawable.ic_chain_bitcoin, "Bitcoin", Color(0xFFF7931A)),
    LIGHTNING(R.drawable.ic_chain_lightning, "Bitcoin Lightning", Color(0xFFFAB300)),
    ETHEREUM(R.drawable.ic_chain_ethereum, "Ethereum", Color(0xFF627EEA)),
    SOLANA(R.drawable.ic_chain_solana, "Solana", Color(0xFF9945FF)),
    TRON(R.drawable.ic_chain_tron, "Tron", Color(0xFFEF0027)),
}

/** A single network entry on the Wallet list: the chain plus the live label +
 *  subtitle read from that chain's store, computed once per [reloadKey]. */
private data class NetworkRow(
    val chain: Chain,
    val label: String,
    val subtitle: String,
)

/** In-screen routes layered above the network list. Each chain screen is its
 *  own self-contained navigator; we only need to remember which one is open so
 *  a BackHandler can pop it back to the list. */
private sealed interface WalletRoute {
    data object List : WalletRoute
    data object Settings : WalletRoute
    data class ChainView(val chain: Chain) : WalletRoute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    resetKey: Int = 0,
    /** Non-null to deep-link straight into a chain's wallet (e.g. after a
     *  Verify & Pay, "bitcoin"); consumed once via [onInitialChainConsumed]. */
    initialChain: String? = null,
    onInitialChainConsumed: () -> Unit = {},
) {
    val context = LocalContext.current

    // Bump to re-read the stores after we come back from a chain screen (where
    // the user may have added the first wallet, so a previously empty network
    // now needs a row). The stores hold their list in memory and do not emit.
    var reloadKey by remember { mutableIntStateOf(0) }
    var route by remember { mutableStateOf<WalletRoute>(WalletRoute.List) }

    // Deep-link: when the host hands us a chain (post Verify & Pay), open that
    // chain's wallet so the payer sees their pending tx, then clear the request.
    LaunchedEffect(initialChain) {
        val chain = initialChain ?: return@LaunchedEffect
        val target = when (chain.lowercase()) {
            "bitcoin" -> Chain.BITCOIN
            "lightning" -> Chain.LIGHTNING
            "ethereum" -> Chain.ETHEREUM
            "solana" -> Chain.SOLANA
            "tron" -> Chain.TRON
            else -> null
        }
        if (target != null) route = WalletRoute.ChainView(target)
        onInitialChainConsumed()
    }

    // Re-tap-to-home: when the host bumps [resetKey] (the Wallet tab was tapped
    // while already selected), pop any open chain / settings route back to the
    // network-list home and reload so the list is fresh. Guard on an ACTUAL
    // change (not the first composition): otherwise the first run would clobber
    // an [initialChain] deep-link (which sets route to ChainView) back to List.
    var lastResetKey by remember { mutableIntStateOf(resetKey) }
    LaunchedEffect(resetKey) {
        if (resetKey != lastResetKey) {
            lastResetKey = resetKey
            if (route !is WalletRoute.List) {
                route = WalletRoute.List
                reloadKey++
            }
        }
    }

    // Re-derive the populated rows whenever we (re)enter the list.
    val rows = remember(reloadKey, route) {
        if (route is WalletRoute.List) populatedRows(context) else emptyList()
    }

    when (val current = route) {
        is WalletRoute.Settings -> {
            // The gear opens the global Settings hub; system back returns here.
            BackHandler { route = WalletRoute.List }
            SettingsScreen(
                deviceRegistry = remember { DeviceRegistry(context) },
                onBack = { route = WalletRoute.List },
            )
        }

        is WalletRoute.ChainView -> {
            // System back AND the chain screen's visible back arrow both pop the
            // ChainView route back to the network list, then a reload picks up
            // any wallet the chain screen just created. The onBack we hand each
            // chain screen is the SAME pop, so the shared WalletChainScaffold
            // back arrow returns to the list rather than exiting the app.
            val popToList: () -> Unit = { route = WalletRoute.List; reloadKey++ }
            BackHandler { popToList() }
            when (current.chain) {
                Chain.BITCOIN -> BitcoinWalletScreen(onBack = popToList)
                Chain.LIGHTNING -> LightningWalletScreen(onBack = popToList)
                Chain.ETHEREUM -> EthereumWalletScreen(onBack = popToList)
                Chain.SOLANA -> SolanaWalletScreen(onBack = popToList)
                Chain.TRON -> TronWalletScreen(onBack = popToList)
            }
        }

        is WalletRoute.List -> WalletList(
            rows = rows,
            onOpenSettings = { route = WalletRoute.Settings },
            onOpen = { route = WalletRoute.ChainView(it) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletList(
    rows: List<NetworkRow>,
    onOpenSettings: () -> Unit,
    onOpen: (Chain) -> Unit,
) {
    var addMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_wallet)) },
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { addMenuOpen = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.wallet_add_wallet))
                        }
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false },
                        ) {
                            Chain.entries.forEach { chain ->
                                DropdownMenuItem(
                                    text = { Text(chain.title) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(chain.iconRes),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = { addMenuOpen = false; onOpen(chain) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(top = Spacing.xxl),
                contentAlignment = Alignment.TopCenter,
            ) {
                EmptyState(
                    icon = Icons.Outlined.AccountBalanceWallet,
                    title = stringResource(R.string.walletc_no_wallets_yet),
                    subtitle = stringResource(R.string.walletc_no_wallets_yet_body),
                    iconSize = 56.dp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = Spacing.lg),
                contentPadding = PaddingValues(top = Spacing.md, bottom = Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(rows, key = { it.chain }) { row ->
                    NetworkRowCell(row = row, onClick = { onOpen(row.chain) })
                }
            }
        }
    }
}

@Composable
private fun NetworkRowCell(row: NetworkRow, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(row.chain.accent.tint(0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(row.chain.iconRes),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Has-wallets + active-label/subtitle assembly. Mirrors iOS WalletView's
// per-network `hasXWallets` guards and `activeXLabel / activeXSubtitle`
// computed properties, reading the same SDK stores the chain screens use.
// ---------------------------------------------------------------------------

private fun populatedRows(context: android.content.Context): List<NetworkRow> {
    val rows = ArrayList<NetworkRow>(Chain.entries.size)

    // Bitcoin: BitcoinWalletEnv seeds a default mainnet software wallet (#0) on
    // a fresh identity, so this network is effectively always present.
    runCatching {
        val store = BitcoinWalletEnv.create(context).store
        if (store.wallets.isNotEmpty()) {
            val active = store.activeWallet
            rows.add(
                NetworkRow(
                    chain = Chain.BITCOIN,
                    label = active?.label ?: "Bitcoin",
                    subtitle = if (active == null) "-"
                    else "${active.network.displayName} · ${active.accountSuffix()}",
                ),
            )
        }
    }

    // Bitcoin Lightning: custodial LNDHub accounts (no auto-seed).
    runCatching {
        val store = LightningEnv.get(context).accountStore
        val accounts = store.accounts
        if (accounts.isNotEmpty()) {
            rows.add(
                NetworkRow(
                    chain = Chain.LIGHTNING,
                    label = store.activeAccount?.label ?: "Bitcoin Lightning",
                    subtitle = countLabel(accounts.size, "account", "accounts"),
                ),
            )
        }
    }

    // Ethereum: EVM software/hardware wallets on the chain-wide current network.
    runCatching {
        val store = EthereumStores.walletStore(context)
        val wallets = store.wallets
        if (wallets.isNotEmpty()) {
            val net = runCatching { resolveCurrentNetwork(context).displayName }.getOrNull()
            val count = countLabel(wallets.size, "wallet", "wallets")
            rows.add(
                NetworkRow(
                    chain = Chain.ETHEREUM,
                    label = store.activeWallet?.label ?: "Ethereum",
                    subtitle = if (net != null) "$net · $count" else count,
                ),
            )
        }
    }

    // Solana: software wallets on the chain-wide current cluster.
    runCatching {
        val store = SolanaEnv.get(context).walletStore
        val wallets = store.wallets
        if (wallets.isNotEmpty()) {
            val net = runCatching { store.currentNetwork.displayName }.getOrNull()
            val count = countLabel(wallets.size, "wallet", "wallets")
            rows.add(
                NetworkRow(
                    chain = Chain.SOLANA,
                    label = store.activeWallet?.label ?: "Solana",
                    subtitle = if (net != null) "$net · $count" else count,
                ),
            )
        }
    }

    // Tron: software/hardware wallets on the chain-wide current network.
    runCatching {
        val store = TronStores.walletStore(context)
        val wallets = store.wallets
        if (wallets.isNotEmpty()) {
            val net = runCatching { store.currentNetwork.displayName }.getOrNull()
            val count = countLabel(wallets.size, "wallet", "wallets")
            rows.add(
                NetworkRow(
                    chain = Chain.TRON,
                    label = store.activeWallet?.label ?: "Tron",
                    subtitle = if (net != null) "$net · $count" else count,
                ),
            )
        }
    }

    return rows
}

private fun countLabel(n: Int, singular: String, plural: String): String =
    if (n == 1) "1 $singular" else "$n $plural"
