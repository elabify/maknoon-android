// Per-chain Solana settings, 1:1 with iOS SolanaSettingsView.swift. The
// cluster (mainnet/devnet/testnet) is chain-wide and lives in the wallet
// store; the per-network RPC + explorer overrides and the token-catalog URL
// live in SolanaSettings. Empty override -> falls back to the network's
// built-in default.

package com.elabify.app.maknoon.ui.wallet.solana

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.solana.SolanaNetwork

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SolanaSettingsScreen(
    onBack: () -> Unit,
    onNetworkChanged: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }

    var network by remember { mutableStateOf(env.walletStore.currentNetwork) }
    var rpc by remember { mutableStateOf(env.settings.rpcOverridesByNetwork[network.rawValue] ?: "") }
    var explorer by remember { mutableStateOf(env.settings.explorerOverridesByNetwork[network.rawValue] ?: "") }
    var catalogUrl by remember { mutableStateOf(env.settings.tokenCatalogURL) }
    var saved by remember { mutableStateOf(false) }

    fun reloadForNetwork(n: SolanaNetwork) {
        network = n
        rpc = env.settings.rpcOverridesByNetwork[n.rawValue] ?: ""
        explorer = env.settings.explorerOverridesByNetwork[n.rawValue] ?: ""
        saved = false
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.sol_solana_settings), style = MaterialTheme.typography.headlineSmall)

        Text(stringResource(R.string.sol_cluster), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SolanaNetwork.entries.forEach { n ->
                FilterChip(
                    selected = n == network,
                    onClick = {
                        env.walletStore.activeWallet?.let { env.walletStore.setActiveNetwork(it.id, n) }
                        reloadForNetwork(n)
                        onNetworkChanged()
                    },
                    label = { Text(n.displayName) },
                )
            }
        }

        Text(
            stringResource(R.string.sol_default_rpc, network.defaultRpcURL),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = rpc,
            onValueChange = { rpc = it; saved = false },
            label = { Text(stringResource(R.string.sol_rpc_override, network.displayName)) },
            placeholder = { Text(network.defaultRpcURL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            stringResource(R.string.sol_default_explorer, network.defaultExplorerURL),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = explorer,
            onValueChange = { explorer = it; saved = false },
            label = { Text(stringResource(R.string.sol_explorer_override, network.displayName)) },
            placeholder = { Text(network.defaultExplorerURL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.sol_verified_token_catalog_url), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = catalogUrl,
            onValueChange = { catalogUrl = it; saved = false },
            label = { Text(stringResource(R.string.sol_token_list_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                env.settings.setRPCOverride(rpc.ifBlank { null }, network)
                env.settings.setExplorerOverride(explorer.ifBlank { null }, network)
                env.settings.setTokenCatalogURL(catalogUrl)
                saved = true
                onNetworkChanged()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.common_save)) }

        if (saved) Text(stringResource(R.string.sol_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
    }
}
