// Full transaction history, newest first, ported from iOS
// BitcoinTransactionListView. Reuses BitcoinTxRow; outgoing unconfirmed
// rows surface a Bump fee action that opens the RBF sheet.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoindevkit.CanonicalTx

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinTransactionListScreen(
    env: BitcoinWalletEnv,
    active: BitcoinWalletDescriptor?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<BitcoinWalletEngine?>(null) }
    var txs by remember { mutableStateOf<List<CanonicalTx>>(emptyList()) }
    var netSats by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var bumpTarget by remember { mutableStateOf<Pair<String, Long?>?>(null) }

    LaunchedEffect(active?.id) {
        val descriptor = active ?: return@LaunchedEffect
        loading = true
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val words = loadRecoveryWords(context)
                val e = BitcoinWalletEngine.open(descriptor, env.filesDirPath, words, null)
                val list = e.transactions()
                val nets = HashMap<String, Long>()
                for (t in list) nets[t.txidHex()] = e.netAmount(t.transaction)
                Triple(e, list, nets)
            }.getOrNull()
        }
        if (loaded != null) {
            engine = loaded.first
            txs = loaded.second
            netSats = loaded.third
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.walletc_transactions)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                txs.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.btc_no_transactions), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.btc_fund_to_see_history), style = MaterialTheme.typography.bodySmall)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(txs, key = { it.txidHex() }) { tx ->
                        val net = netSats[tx.txidHex()]
                        BitcoinTxRow(
                            tx = tx,
                            netSat = net,
                            explorerTxUrl = active?.let { env.settings.txUrl(tx.txidHex(), it.network) },
                            labelStore = env.labels,
                            onBumpFee = { bumpTarget = tx.txidHex() to net },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    bumpTarget?.let { (txid, net) ->
        val e = engine
        if (e != null && active != null) {
            BumpFeeSheet(
                env = env,
                engine = e,
                descriptor = active,
                originalTxidHex = txid,
                originalFeeSat = net?.let { -it },
                onDone = {
                    bumpTarget = null
                    scope.launch {
                        val refreshed = withContext(Dispatchers.IO) {
                            runCatching {
                                val list = e.transactions()
                                val nets = HashMap<String, Long>()
                                for (t in list) nets[t.txidHex()] = e.netAmount(t.transaction)
                                list to nets
                            }.getOrNull()
                        }
                        refreshed?.let { txs = it.first; netSats = it.second }
                    }
                },
                onDismiss = { bumpTarget = null },
            )
        }
    }
}
