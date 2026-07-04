// Ethereum per-network backend settings. Ported from iOS
// EthereumSettingsView: pick a built-in network, override its JSON-RPC
// endpoint + HTML explorer URL + Etherscan-family explorer API (URL + key)
// for transaction history, set the global ENS gateway RPC, configure the
// verified-token catalog URL + logo template, and refresh the token
// catalog on demand. A "Manage networks…" entry opens the picker, which
// hosts custom-network add/edit.

package com.elabify.app.maknoon.ui.wallet.ethereum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumTokenRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EthereumSettingsScreen(onCustomNetworks: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { EthereumStores.settings(context) }
    val registry = remember { EthereumStores.registry(context) }

    var network by remember { mutableStateOf(EthereumNetwork.MAINNET) }
    var rpcDraft by remember { mutableStateOf("") }
    var explorerDraft by remember { mutableStateOf("") }
    var explorerApiDraft by remember { mutableStateOf("") }
    var explorerKeyDraft by remember { mutableStateOf("") }
    var ensDraft by remember { mutableStateOf("") }
    var catalogDraft by remember { mutableStateOf("") }
    var logoDraft by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var catalogStatus by remember { mutableStateOf<String?>(null) }

    fun loadDrafts() {
        rpcDraft = settings.rpcURLByNetwork[network] ?: ""
        explorerDraft = settings.explorerURLByNetwork[network] ?: ""
        explorerApiDraft = settings.explorerAPIURLByNetwork[network] ?: ""
        explorerKeyDraft = settings.explorerAPIKeyByNetwork[network] ?: ""
        ensDraft = settings.ensRPCURL
        catalogDraft = if (settings.tokenCatalogURL == EthereumTokenRegistry.DEFAULT_CATALOG_URL) "" else settings.tokenCatalogURL
        logoDraft = if (settings.logoTemplate == com.elabify.musnad.wallet.ethereum.EthereumSettings.DEFAULT_LOGO_TEMPLATE) "" else settings.logoTemplate
        saved = false
    }

    LaunchedEffect(network) { loadDrafts() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.eth_ethereum)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onCustomNetworks, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Public, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.eth_manage_networks))
            }

            Text(stringResource(R.string.common_network), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EthereumNetwork.displayOrdered.forEach { n ->
                    FilterChip(selected = network == n, onClick = { network = n; loadDrafts() }, label = { Text(n.displayName) })
                }
            }
            Text(
                stringResource(R.string.eth_networks_overrides_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            EthFieldSection(stringResource(R.string.eth_rpc_endpoint), stringResource(R.string.eth_json_rpc_url), rpcDraft, { rpcDraft = it }, stringResource(R.string.eth_default_value, network.defaultRPCURL))
            EthFieldSection(stringResource(R.string.eth_block_explorer), stringResource(R.string.eth_explorer_url), explorerDraft, { explorerDraft = it }, stringResource(R.string.eth_default_value, network.defaultExplorerURL))
            EthFieldSection(stringResource(R.string.eth_explorer_api_history_short), stringResource(R.string.eth_etherscan_family_url), explorerApiDraft, { explorerApiDraft = it }, stringResource(R.string.eth_default_value, network.defaultExplorerAPIURL ?: stringResource(R.string.eth_none_history_disabled)))
            EthFieldSection(stringResource(R.string.eth_explorer_api_key), stringResource(R.string.eth_api_key_optional), explorerKeyDraft, { explorerKeyDraft = it }, stringResource(R.string.eth_explorer_key_note))

            EthFieldSection(stringResource(R.string.eth_ens_gateway), stringResource(R.string.eth_mainnet_rpc_for_ens), ensDraft, { ensDraft = it }, stringResource(R.string.eth_ens_note))

            EthFieldSection(stringResource(R.string.eth_token_catalog), stringResource(R.string.eth_tokenlist_url), catalogDraft, { catalogDraft = it }, stringResource(R.string.eth_default_value, EthereumTokenRegistry.DEFAULT_CATALOG_URL))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val last = registry.lastFetched
                if (last != null) {
                    Text(stringResource(R.string.eth_catalog_last_refreshed, ethRelativeSince(last), registry.totalEntries.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.eth_not_yet_fetched), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                registry.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                catalogStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            refreshing = true
                            val url = catalogDraft.ifEmpty { EthereumTokenRegistry.DEFAULT_CATALOG_URL }
                            withContext(Dispatchers.IO) { runCatching { registry.refresh(url) } }
                            catalogStatus = registry.lastError ?: "Refreshed ${registry.totalEntries} tokens."
                            refreshing = false
                        }
                    },
                    enabled = !refreshing,
                ) {
                    if (refreshing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp))
                    Text(if (refreshing) stringResource(R.string.eth_refreshing) else stringResource(R.string.eth_refresh_now))
                }
            }

            EthFieldSection(stringResource(R.string.eth_token_logos), stringResource(R.string.eth_logo_url_template), logoDraft, { logoDraft = it }, stringResource(R.string.eth_logo_note))

            Button(
                onClick = {
                    settings.setRPC(rpcDraft, network)
                    settings.setExplorer(explorerDraft, network)
                    settings.setExplorerAPI(explorerApiDraft, explorerKeyDraft, network)
                    settings.ensRPCURL = ensDraft.trim()
                    settings.setTokenCatalogURL(catalogDraft)
                    settings.setLogoTemplate(logoDraft)
                    settings.persist()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saved) stringResource(R.string.eth_saved) else stringResource(R.string.common_save))
            }
        }
    }
}

@Composable
private fun EthFieldSection(header: String, placeholder: String, value: String, onValueChange: (String) -> Unit, footer: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(header, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
