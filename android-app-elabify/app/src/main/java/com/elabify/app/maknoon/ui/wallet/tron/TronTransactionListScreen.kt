// Full transaction history for a Tron wallet. Ported from iOS
// TronTransactionListView.swift: lazy-fetched list of the 100 most
// recent transactions on the current network, reusing TronTxRow.

package com.elabify.app.maknoon.ui.wallet.tron

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.tron.TronRPCClient
import com.elabify.musnad.wallet.tron.TronWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronTransactionListScreen(walletId: UUID, ownerAddress: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { TronStores.walletStore(context) }
    val settings = remember { TronStores.settings(context) }
    val sandwich = remember { loadTronSandwich(context) }
    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    val network = remember { walletStore.currentNetwork }
    val explorerBase = remember { settings.explorerURL(network) }

    var txs by remember { mutableStateOf<List<TronRPCClient.TxRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(walletId) {
        loading = true
        lastError = null
        if (descriptor == null) { loading = false; return@LaunchedEffect }
        val rpcURL = settings.rpcURL(network)
        withContext(Dispatchers.IO) {
            runCatching { TronWallet(descriptor, network, rpcURL, sandwich).recentTransactions(100) }
        }.onSuccess { txs = it }.onFailure { lastError = it.message ?: it.toString() }
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                txs.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Inbox, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Text(stringResource(R.string.trx_no_transactions_yet), style = MaterialTheme.typography.titleMedium)
                    Text(lastError ?: stringResource(R.string.trx_fund_wallet_history), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(txs, key = { it.txID }) { tx ->
                        TronTxRow(tx, ownerAddress, explorerBase)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
