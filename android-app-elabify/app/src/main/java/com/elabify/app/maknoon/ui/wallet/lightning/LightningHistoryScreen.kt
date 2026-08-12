// Full Lightning payment history for the active LNDHub account. Mirrors the
// iOS recent-payments list (LightningTxRow) but as a standalone full-history
// screen: combined outgoing payments + settled incoming invoices, newest
// first, fetched via LndHubClient.history(limit). Off-main via Dispatchers.IO.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.lightning.LightningTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightningHistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }

    var txs by remember { mutableStateOf<List<LightningTx>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(env.activeAccount?.id) {
        loading = true
        error = null
        val account = env.activeAccount
        if (account == null) { loading = false; return@LaunchedEffect }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val client = env.clientFor(account) ?: error("Password missing. Re-import this account.")
                client.history(limit = 100)
            }
        }
        result.onSuccess { txs = it }.onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.walletc_transactions)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.ln_no_payments_yet), style = MaterialTheme.typography.titleMedium)
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    items(txs, key = { it.id }) { tx ->
                        LightningTxRow(tx)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
internal fun LightningTxRow(tx: LightningTx) {
    val outgoing = tx.isOutgoing
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (outgoing) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = null,
            tint = if (outgoing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            Text(txDateString(tx.timestamp), style = MaterialTheme.typography.bodyMedium)
            Text(
                tx.memo ?: stringResource(if (outgoing) R.string.ln_outgoing_payment else R.string.ln_incoming_payment),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val v = tx.value ?: 0L
            val sign = if (outgoing) "-" else "+"
            Text(
                stringResource(R.string.ln_amount_sat_signed, sign, formatSats(kotlin.math.abs(v))),
                style = MaterialTheme.typography.bodyMedium,
            )
            tx.fee?.takeIf { it > 0 }?.let {
                Text(stringResource(R.string.ln_fee_value, formatSats(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun txDateString(timestamp: Long?): String {
    val ts = timestamp ?: 0L
    if (ts <= 0L) return "-"
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    return fmt.format(Date(ts * 1_000))
}
