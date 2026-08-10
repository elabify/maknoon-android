// Full EVM network picker. Ported from iOS EthereumNetworkPickerSheet:
// the built-in catalog split into Mainnets / Testnets sections (display
// ordered, Ethereum first), then a Custom section listing user-defined
// EVM networks with edit + delete, and an "Add custom network…" entry.
// Tapping a row sets the chain-wide current network on the wallet store.

package com.elabify.app.maknoon.ui.wallet.ethereum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.ethereum.CustomEthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetworkID
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumNetworkPickerScreen(
    onAddCustom: () -> Unit,
    onEditCustom: (UUID) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val walletStore = remember { EthereumStores.walletStore(context) }
    val customs = remember { EthereumStores.customs(context) }
    var rev by remember { mutableStateOf(0) }
    val selectedId = remember(rev) { walletStore.currentNetworkID.stableId }
    val customNetworks = remember(rev) { customs.networks }
    val activeWalletId = remember { walletStore.activeWallet?.id ?: UUID.randomUUID() }

    fun select(id: EthereumNetworkID) {
        walletStore.setCurrentNetworkID(id, activeWalletId)
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.eth_select_chain)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { SectionHeader(stringResource(R.string.eth_mainnets)) }
            items(EthereumNetwork.displayOrdered.filter { !it.isTestnet }, key = { "m:${it.rawValue}" }) { net ->
                val id = EthereumNetworkID.Builtin(net)
                NetworkRow(net.displayName, isTestnet = false, selected = id.stableId == selectedId, onClick = { select(id) })
            }

            item { Spacer(Modifier.size(8.dp)); SectionHeader(stringResource(R.string.eth_testnets)) }
            items(EthereumNetwork.displayOrdered.filter { it.isTestnet }, key = { "t:${it.rawValue}" }) { net ->
                val id = EthereumNetworkID.Builtin(net)
                NetworkRow(net.displayName, isTestnet = true, selected = id.stableId == selectedId, onClick = { select(id) })
            }

            item { Spacer(Modifier.size(8.dp)); SectionHeader(stringResource(R.string.eth_custom_section)) }
            items(customNetworks, key = { it.id }) { custom ->
                CustomNetworkRow(
                    custom = custom,
                    selected = EthereumNetworkID.Custom(custom.id).stableId == selectedId,
                    onClick = { select(EthereumNetworkID.Custom(custom.id)) },
                    onEdit = { onEditCustom(custom.id) },
                    onDelete = { customs.remove(custom.id); rev++ },
                )
            }
            item {
                Spacer(Modifier.size(4.dp))
                TextButton(onClick = onAddCustom) {
                    Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.eth_add_custom_network_ellipsis))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun NetworkRow(name: String, isTestnet: Boolean, selected: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (isTestnet) TestnetPill()
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.eth_selected), tint = EthBlue)
        }
    }
}

@Composable
private fun CustomNetworkRow(
    custom: CustomEthereumNetwork,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onClick)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(custom.name, style = MaterialTheme.typography.bodyMedium)
                    if (custom.isTestnet) TestnetPill()
                    if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.eth_selected), tint = EthBlue, modifier = Modifier.size(16.dp))
                }
                Text(stringResource(R.string.eth_chain_id_ticker, custom.chainId.toString(), custom.ticker), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.eth_edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.eth_delete), tint = MaterialTheme.colorScheme.error) }
        }
    }
}
