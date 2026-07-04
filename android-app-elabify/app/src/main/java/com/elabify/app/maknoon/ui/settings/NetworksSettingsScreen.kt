// Settings > Networks. Ported 1:1 from the iOS NetworksDestination.swift.
//
// iOS NetworksDestination is a grouped Form with a single Section ("Networks")
// holding five NavigationLink rows, in this exact order:
//   1. Bitcoin           - "Electrum, mempool.space, block explorer, fiat"
//   2. Bitcoin Lightning - "LNDHub-compatible custodial accounts (manual configuration)"
//   3. Ethereum          - "RPC, Etherscan-family API, explorer (mainnet plus EVM-compatible L2s)"
//   4. Solana            - "RPC endpoint, Solana Explorer"
//   5. Tron              - "TronGrid endpoint, TronScan explorer, TRC-20 catalog"
// Each row carries a tinted leading SF-symbol and (on iOS) a "Soon" pill when
// not live; all five chains are live, so no pill renders. Each row drills into
// that chain's existing backend-configuration screen.
//
// On Android we render the same single section as one rounded "section card" of
// rows under a Scaffold titled "Networks", and reuse the already-shipped
// per-chain settings screens (ui/wallet/<chain>/<Chain>SettingsScreen.kt) rather
// than duplicating them. Drill-in is in-screen state + BackHandler, matching the
// rest of the Settings hub: a row sets [route] to that chain, and system back
// pops back to the Networks list. The per-chain settings screens own their own
// sub-flows (Ethereum's network picker / custom-network editor, Lightning's
// accounts), so those are wired straight through to the existing screens too.

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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CurrencyBitcoin
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinSettingsScreen
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletEnv
import com.elabify.app.maknoon.ui.wallet.ethereum.CustomNetworkEditorScreen
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumNetworkPickerScreen
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumStores
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumSettingsScreen
import com.elabify.app.maknoon.ui.wallet.lightning.LightningAccountsScreen
import com.elabify.app.maknoon.ui.wallet.lightning.LightningSettingsScreen
import com.elabify.app.maknoon.ui.wallet.solana.SolanaSettingsScreen
import com.elabify.app.maknoon.ui.wallet.tron.TronSettingsScreen
import java.util.UUID

// In-screen routes for the Networks destination. Hub is the five-row list; each
// chain route renders that chain's existing settings screen (plus any sub-flow
// those screens drive: Ethereum's network picker + custom-network editor,
// Lightning's accounts). System back pops the chain back to the list.
private sealed interface NetworksRoute {
    data object Hub : NetworksRoute
    data object Bitcoin : NetworksRoute
    data object Lightning : NetworksRoute
    data object LightningAccounts : NetworksRoute
    data object Ethereum : NetworksRoute
    data object EthereumNetworkPicker : NetworksRoute
    data class EthereumCustomNetwork(val editId: UUID?) : NetworksRoute
    data object Solana : NetworksRoute
    data object Tron : NetworksRoute
}

@Composable
fun NetworksSettingsScreen(onBack: () -> Unit) {
    var route by remember { mutableStateOf<NetworksRoute>(NetworksRoute.Hub) }
    val context = LocalContext.current
    // Bitcoin's settings screen takes the wallet env; the other chains build
    // their env from context internally.
    val bitcoinEnv = remember { BitcoinWalletEnv.create(context) }

    when (val r = route) {
        is NetworksRoute.Hub -> {
            BackHandler { onBack() }
            NetworksHub(onOpen = { route = it }, onBack = onBack)
        }

        is NetworksRoute.Bitcoin -> {
            BackHandler { route = NetworksRoute.Hub }
            BitcoinSettingsScreen(env = bitcoinEnv, onClose = { route = NetworksRoute.Hub })
        }

        is NetworksRoute.Lightning -> {
            BackHandler { route = NetworksRoute.Hub }
            LightningSettingsScreen(
                onManageAccounts = { route = NetworksRoute.LightningAccounts },
                onBack = { route = NetworksRoute.Hub },
            )
        }

        is NetworksRoute.LightningAccounts -> {
            BackHandler { route = NetworksRoute.Lightning }
            LightningAccountsScreen(
                onBack = { route = NetworksRoute.Lightning },
                onChanged = {},
            )
        }

        is NetworksRoute.Ethereum -> {
            BackHandler { route = NetworksRoute.Hub }
            EthereumSettingsScreen(
                onCustomNetworks = { route = NetworksRoute.EthereumNetworkPicker },
                onDone = { route = NetworksRoute.Hub },
            )
        }

        is NetworksRoute.EthereumNetworkPicker -> {
            BackHandler { route = NetworksRoute.Ethereum }
            EthereumNetworkPickerScreen(
                onAddCustom = { route = NetworksRoute.EthereumCustomNetwork(null) },
                onEditCustom = { id -> route = NetworksRoute.EthereumCustomNetwork(id) },
                onDone = { route = NetworksRoute.Ethereum },
            )
        }

        is NetworksRoute.EthereumCustomNetwork -> {
            BackHandler { route = NetworksRoute.EthereumNetworkPicker }
            CustomNetworkEditorScreen(
                editId = r.editId,
                onDone = { route = NetworksRoute.EthereumNetworkPicker },
            )
        }

        is NetworksRoute.Solana -> {
            BackHandler { route = NetworksRoute.Hub }
            SolanaSettingsScreen(
                onBack = { route = NetworksRoute.Hub },
                onNetworkChanged = {},
            )
        }

        is NetworksRoute.Tron -> {
            BackHandler { route = NetworksRoute.Hub }
            TronSettingsScreen(onDone = { route = NetworksRoute.Hub })
        }
    }
}

// One drill-in row. Mirrors the iOS networkRow: tinted leading symbol, a
// semibold title, and a secondary subtitle. iOS shows a "Soon" pill only when a
// row is not live; all five chains are live, so no pill is ever drawn (matching
// iOS, which passes live: true for every row).
private data class NetworkEntry(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val tint: Color,
    val route: NetworksRoute,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworksHub(
    onOpen: (NetworksRoute) -> Unit,
    onBack: () -> Unit,
) {
    // The five rows in iOS NetworksDestination order, with Material-icon
    // equivalents of the iOS SF Symbols and the same tints:
    //   bitcoinsign.circle.fill -> CurrencyBitcoin (orange)
    //   bolt.fill               -> Bolt (yellow)
    //   diamond.fill            -> Diamond (indigo)
    //   circle.hexagongrid.fill -> Hexagon (purple)
    //   diamond.fill            -> Diamond (red)
    val entries = listOf(
        NetworkEntry(
            title = stringResource(R.string.settings_network_bitcoin),
            subtitle = stringResource(R.string.settings_network_bitcoin_subtitle),
            icon = Icons.Filled.CurrencyBitcoin,
            tint = MaknoonColors.warning,
            route = NetworksRoute.Bitcoin,
        ),
        NetworkEntry(
            title = stringResource(R.string.settings_network_lightning),
            subtitle = stringResource(R.string.settings_network_lightning_subtitle),
            icon = Icons.Filled.Bolt,
            tint = Color(0xFFE6A700),
            route = NetworksRoute.Lightning,
        ),
        NetworkEntry(
            title = stringResource(R.string.settings_network_ethereum),
            subtitle = stringResource(R.string.settings_network_ethereum_subtitle),
            icon = Icons.Filled.Diamond,
            tint = Color(0xFF5B5BD6),
            route = NetworksRoute.Ethereum,
        ),
        NetworkEntry(
            title = stringResource(R.string.settings_network_solana),
            subtitle = stringResource(R.string.settings_network_solana_subtitle),
            icon = Icons.Filled.Hexagon,
            tint = Color(0xFF9945FF),
            route = NetworksRoute.Solana,
        ),
        NetworkEntry(
            title = stringResource(R.string.settings_network_tron),
            subtitle = stringResource(R.string.settings_network_tron_subtitle),
            icon = Icons.Filled.Diamond,
            tint = MaknoonColors.error,
            route = NetworksRoute.Tron,
        ),
    )

    // Global self-hosted WalletConnect relay (EVM-only today, but a single relay
    // across ALL networks) so it lives here under Networks, not in the Ethereum
    // screen. Storage stays on EthereumStores.settings (setter normalizes host).
    val context = LocalContext.current
    val settings = remember { EthereumStores.settings(context) }
    var wcRelay by remember { mutableStateOf(settings.walletConnectRelayHost) }
    var relaySaved by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_networks)) },
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
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            SectionHeader(title = stringResource(R.string.settings_networks))
            SectionCardGroup {
                entries.forEach { entry ->
                    NetworkRow(entry = entry, onClick = { onOpen(entry.route) })
                }
            }

            SectionCardGroup {
                // Collapsed by default: just an "Advanced" row that expands to the
                // WalletConnect relay field.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.eth_advanced),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (advancedExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            stringResource(R.string.net_wc_relay_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = wcRelay,
                            onValueChange = { wcRelay = it; relaySaved = false },
                            placeholder = { Text(stringResource(R.string.eth_wc_relay_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                settings.setWalletConnectRelayHost(wcRelay)
                                settings.persist()
                                wcRelay = settings.walletConnectRelayHost
                                relaySaved = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (relaySaved) stringResource(R.string.eth_saved) else stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(
    entry: NetworkEntry,
    onClick: () -> Unit,
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
                .background(entry.tint.tint(MaknoonColors.TintCellAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(entry.icon, contentDescription = null, tint = entry.tint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                entry.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// A rounded "section card" group, the Android stand-in for an iOS Form Section.
// Local to this file so the Networks screen stands alone (the hub's SectionCard
// is private to SettingsScreen.kt).
@Composable
private fun SectionCardGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}
