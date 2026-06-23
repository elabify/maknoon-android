// Tron per-network backend settings. Ported from iOS
// TronSettingsView.swift: pick a network, override its TronGrid
// endpoint + TronScan explorer URL, configure the TRC-20 catalog URL +
// logo base URL, and refresh the verified-token catalog on demand.

package com.elabify.app.maknoon.ui.wallet.tron

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronSettings
import com.elabify.musnad.wallet.tron.TronTokenCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronSettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { TronStores.settings(context) }
    val catalog = remember { TronStores.catalog(context) }

    var network by remember { mutableStateOf(TronNetwork.MAINNET) }
    var rpcDraft by remember { mutableStateOf("") }
    var explorerDraft by remember { mutableStateOf("") }
    var catalogDraft by remember { mutableStateOf("") }
    var logoDraft by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var catalogStatus by remember { mutableStateOf<String?>(null) }

    fun loadDrafts() {
        rpcDraft = settings.rpcOverridesByNetwork[network.rawValue] ?: ""
        explorerDraft = settings.explorerOverridesByNetwork[network.rawValue] ?: ""
        catalogDraft = if (settings.tokenCatalogURL == TronTokenCatalog.DEFAULT_CATALOG_URL) "" else settings.tokenCatalogURL
        logoDraft = if (settings.logoBaseURL == TronSettings.DEFAULT_LOGO_BASE_URL) "" else settings.logoBaseURL
        saved = false
    }

    // initial + on network switch
    androidx.compose.runtime.LaunchedEffect(network) { loadDrafts() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trx_tron)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.common_network), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TronNetwork.entries.forEach { n ->
                    FilterChip(selected = network == n, onClick = { network = n; loadDrafts() }, label = { Text(n.displayName) })
                }
            }
            Text(
                stringResource(R.string.trx_network_settings_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FieldSection(stringResource(R.string.trx_rpc_endpoint), stringResource(R.string.trx_trongrid_url), rpcDraft, { rpcDraft = it }, stringResource(R.string.trx_default_value, network.defaultRpcURL))
            FieldSection(stringResource(R.string.trx_block_explorer), stringResource(R.string.trx_explorer_url), explorerDraft, { explorerDraft = it }, stringResource(R.string.trx_default_value, network.defaultExplorerURL))
            FieldSection(stringResource(R.string.trx_token_catalog), stringResource(R.string.trx_catalog_url), catalogDraft, { catalogDraft = it }, stringResource(R.string.trx_default_value, TronTokenCatalog.DEFAULT_CATALOG_URL))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val last = catalog.lastFetchedEpochMs
                if (last != null) {
                    Text(stringResource(R.string.trx_last_refreshed, relativeSince(last), catalog.entriesByContract.size.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.trx_not_yet_fetched), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                catalog.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                catalogStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            refreshing = true
                            val url = catalogDraft.ifEmpty { TronTokenCatalog.DEFAULT_CATALOG_URL }
                            withContext(Dispatchers.IO) { runCatching { catalog.refresh(url) } }
                            catalogStatus = catalog.lastError ?: "Refreshed ${catalog.entriesByContract.size} tokens."
                            refreshing = false
                        }
                    },
                    enabled = !refreshing,
                ) {
                    if (refreshing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp))
                    Text(if (refreshing) stringResource(R.string.trx_refreshing) else stringResource(R.string.trx_refresh_now))
                }
            }

            FieldSection(stringResource(R.string.trx_token_logos), stringResource(R.string.trx_logo_base_url), logoDraft, { logoDraft = it }, stringResource(R.string.trx_default_value, TronSettings.DEFAULT_LOGO_BASE_URL))

            Button(
                onClick = {
                    settings.setRPCOverride(rpcDraft.ifEmpty { null }, network)
                    settings.setExplorerOverride(explorerDraft.ifEmpty { null }, network)
                    settings.setTokenCatalogURL(catalogDraft)
                    settings.setLogoBaseURL(logoDraft)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saved) stringResource(R.string.trx_saved) else stringResource(R.string.common_save))
            }
        }
    }
}

@Composable
private fun FieldSection(header: String, placeholder: String, value: String, onValueChange: (String) -> Unit, footer: String) {
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
