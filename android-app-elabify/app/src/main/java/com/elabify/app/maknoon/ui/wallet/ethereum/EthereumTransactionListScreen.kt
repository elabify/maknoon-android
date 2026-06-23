// Full Ethereum transaction history. Ported from iOS
// EthereumTransactionListView: pulls the newest native transactions for
// the wallet's address from the configured Etherscan-family explorer API
// (per-network base + optional key from EthereumSettings), shows pending
// optimistic rows first, then the confirmed list. A clear empty/error
// state covers chains where the user hasn't configured an explorer API.

package com.elabify.app.maknoon.ui.wallet.ethereum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.ethereum.EthereumTx
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumTransactionListScreen(walletId: UUID, ownerAddress: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { EthereumStores.walletStore(context) }
    val resolved = remember { resolveCurrentNetwork(context) }
    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }

    var txs by remember { mutableStateOf<List<EthereumTx>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val pending = walletStore.pendingTxsByWallet[walletId] ?: emptyList()

    LaunchedEffect(walletId) {
        loading = true
        error = null
        if (descriptor == null) { error = "Wallet not found."; loading = false; return@LaunchedEffect }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                EthereumWallet(descriptor).recentTransactions(
                    resolved.explorerAPIURL, resolved.explorerAPIKey, resolved.chainId, perPage = 50,
                )
            }
        }
        result.onSuccess { txs = it }.onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.walletc_transactions)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                pending.isEmpty() && txs.isEmpty() -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(error ?: stringResource(R.string.eth_no_transactions), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (error == null) {
                        Text(stringResource(R.string.eth_history_needs_explorer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                ) {
                    items(pending, key = { "p:${it.id}" }) { p ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { PendingEthereumTxRow(p, resolved.explorerURL) }
                        }
                    }
                    items(txs, key = { it.hash }) { tx ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { EthereumTxRow(tx, ownerAddress, resolved.explorerURL) }
                        }
                    }
                }
            }
        }
    }
}
