// Add / edit a custom EVM network. Ported from iOS CustomNetworkEditorSheet:
// Name, Chain ID (non-zero), Ticker, RPC URL (parseable http(s)), optional
// HTML explorer URL, optional Etherscan-family explorer API URL + key, and a
// Testnet toggle. Save is enabled only when name + ticker are non-empty, the
// chain ID is a positive integer, and the RPC URL is a valid http(s) URL.

package com.elabify.app.maknoon.ui.wallet.ethereum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.ethereum.CustomEthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomNetworkEditorScreen(editId: UUID?, onDone: () -> Unit) {
    val context = LocalContext.current
    val customs = remember { EthereumStores.customs(context) }
    val existing = remember(editId) { editId?.let { id -> customs.networks.firstOrNull { it.id == id } } }

    var name by remember { mutableStateOf(existing?.name ?: "") }
    var chainIdStr by remember { mutableStateOf(existing?.chainId?.toString() ?: "") }
    var ticker by remember { mutableStateOf(existing?.ticker ?: "") }
    var rpcURL by remember { mutableStateOf(existing?.rpcURL ?: "") }
    var explorerURL by remember { mutableStateOf(existing?.explorerURL ?: "") }
    var explorerAPIURL by remember { mutableStateOf(existing?.explorerAPIURL ?: "") }
    var explorerAPIKey by remember { mutableStateOf(existing?.explorerAPIKey ?: "") }
    var isTestnet by remember { mutableStateOf(existing?.isTestnet ?: false) }
    var error by remember { mutableStateOf<String?>(null) }

    val chainId = chainIdStr.trim().toLongOrNull()
    val canSave = name.trim().isNotEmpty() &&
        ticker.trim().isNotEmpty() &&
        (chainId != null && chainId > 0) &&
        EthereumSettings.isValidRPC(rpcURL)

    fun save() {
        val id = chainIdStr.trim().toLongOrNull()
        if (id == null || id <= 0) { error = context.getString(R.string.eth_chain_id_positive); return }
        if (!EthereumSettings.isValidRPC(rpcURL)) { error = context.getString(R.string.eth_rpc_url_invalid); return }
        val network = CustomEthereumNetwork(
            id = existing?.id ?: UUID.randomUUID(),
            name = name.trim(),
            chainId = id,
            ticker = ticker.trim().uppercase(),
            rpcURL = rpcURL.trim(),
            explorerURL = explorerURL.trim(),
            explorerAPIURL = explorerAPIURL.trim().ifEmpty { null },
            explorerAPIKey = explorerAPIKey.trim().ifEmpty { null },
            isTestnet = isTestnet,
        )
        if (existing != null) customs.update(network) else customs.add(network)
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing != null) stringResource(R.string.eth_edit_chain) else stringResource(R.string.eth_add_custom_chain)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.common_network), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text(stringResource(R.string.eth_custom_name_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = chainIdStr, onValueChange = { chainIdStr = it; error = null },
                label = { Text(stringResource(R.string.eth_custom_chain_id_hint)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = ticker, onValueChange = { ticker = it; error = null }, label = { Text(stringResource(R.string.eth_custom_ticker_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.walletc_testnet), modifier = Modifier.weight(1f))
                Switch(checked = isTestnet, onCheckedChange = { isTestnet = it })
            }

            Text(stringResource(R.string.eth_rpc_endpoint), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = rpcURL, onValueChange = { rpcURL = it; error = null }, label = { Text(stringResource(R.string.eth_rpc_url_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text(stringResource(R.string.eth_block_explorer_html), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = explorerURL, onValueChange = { explorerURL = it }, label = { Text(stringResource(R.string.eth_explorer_url_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.eth_explorer_links_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.eth_explorer_api_history), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = explorerAPIURL, onValueChange = { explorerAPIURL = it }, label = { Text(stringResource(R.string.eth_explorer_api_url_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = explorerAPIKey, onValueChange = { explorerAPIKey = it }, label = { Text(stringResource(R.string.eth_api_key_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(onClick = { save() }, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                Text(if (existing != null) stringResource(R.string.eth_save_changes) else stringResource(R.string.eth_add_chain))
            }
        }
    }
}
