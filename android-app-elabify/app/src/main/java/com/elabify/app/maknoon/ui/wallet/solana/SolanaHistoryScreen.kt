// Solana transaction history, 1:1 with iOS SolanaTransactionListView.swift.
// Pulls recent signatures via SolanaWallet.recentSignatures, then resolves
// each one's SOL delta (getTransactionDelta) so the row can show a signed
// amount. Optimistic pending txs from the store sort to the top.

package com.elabify.app.maknoon.ui.wallet.solana

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.solana.PendingSolanaTx
import com.elabify.musnad.wallet.solana.SolanaRPCClient
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HistoryRow(
    val signature: String,
    val blockTime: Long?,
    val confirmationStatus: String?,
    val isError: Boolean,
    val lamportsDelta: Long?,
    val pending: Boolean,
)

@Composable
internal fun SolanaHistoryScreen(
    descriptor: SolanaWalletDescriptor,
    onBack: () -> Unit,
    onOpenExplorer: (signature: String) -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }

    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(descriptor.id, env.walletStore.currentNetwork) {
        busy = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val w = env.openWallet(descriptor)
                val sigs: List<SolanaRPCClient.SignatureRecord> = w.recentSignatures(limit = 25)
                // Drop confirmed pendings now that we have fresh signatures.
                env.walletStore.dropConfirmedPending(descriptor.id, sigs.map { it.signature }.toSet())
                val pending: List<PendingSolanaTx> = env.walletStore.pendingTxsByWallet[descriptor.id] ?: emptyList()

                val confirmed = sigs.map { rec ->
                    val delta = runCatching { w.transactionDelta(rec.signature) }.getOrNull()
                    HistoryRow(
                        signature = rec.signature,
                        blockTime = rec.blockTime,
                        confirmationStatus = rec.confirmationStatus,
                        isError = rec.err != null,
                        lamportsDelta = delta?.lamports,
                        pending = false,
                    )
                }
                val pendingRows = pending.map { p ->
                    HistoryRow(
                        signature = p.signature,
                        blockTime = p.broadcastAtEpochMs / 1000,
                        confirmationStatus = "pending",
                        isError = false,
                        lamportsDelta = if (p.tokenMint == null) {
                            if (p.direction == PendingSolanaTx.Direction.OUT) -p.lamports else p.lamports
                        } else null,
                        pending = true,
                    )
                }
                val confirmedSigs = confirmed.map { it.signature }.toSet()
                pendingRows.filterNot { confirmedSigs.contains(it.signature) } + confirmed
            }
        }
        result.onSuccess { rows = it }.onFailure { error = it.message ?: it.toString() }
        busy = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sol_activity), style = MaterialTheme.typography.headlineSmall)

        when {
            busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            error != null -> {
                Text(stringResource(R.string.sol_error_prefix, error ?: ""), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            }

            rows.isEmpty() -> {
                Text(stringResource(R.string.sol_no_transactions_cluster), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_back)) }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.signature }) { row ->
                        HistoryRowCard(row) { onOpenExplorer(row.signature) }
                    }
                }
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_back)) }
            }
        }
    }
}

@Composable
private fun HistoryRowCard(row: HistoryRow, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val amount = row.lamportsDelta?.let { d ->
                    val sign = if (d > 0) "+" else ""
                    "$sign${formatSol(d)} SOL"
                } ?: "-"
                Text(
                    amount,
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        row.isError -> MaterialTheme.colorScheme.error
                        (row.lamportsDelta ?: 0) > 0 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                val status = when {
                    row.isError -> stringResource(R.string.sol_status_failed)
                    row.pending -> stringResource(R.string.sol_status_pending)
                    else -> row.confirmationStatus ?: stringResource(R.string.sol_status_confirmed)
                }
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider()
            Text(
                shortAddress(row.signature, head = 8, tail = 8),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            row.blockTime?.let {
                Text(formatTimestamp(it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatTimestamp(epochSec: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))
