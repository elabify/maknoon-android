// Coin-control UTXO picker, ported from iOS UTXOPickerView. Lists the
// wallet's unspent outputs (BDK listUnspent), lets the user tick the ones
// to spend, shows coverage vs the recipient amount, and supports a
// per-output label edit. Selection flows back to the Send screen's
// buildUnsignedPSBT(selectedUtxoOutpoints:).

package com.elabify.app.maknoon.ui.wallet.bitcoin

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.elabify.app.maknoon.R
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.LocalOutput
import androidx.compose.foundation.layout.windowInsetsPadding
import com.elabify.app.maknoon.ui.safeBarsInsets

private data class UtxoRow(
    val key: UtxoKey,
    val valueSat: Long,
    val confirmation: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UTXOPickerSheet(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    network: BitcoinNetwork,
    amountNeededSat: Long,
    selection: Set<UtxoKey>,
    onApply: (Set<UtxoKey>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<UtxoRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf(selection) }
    var labelTarget by remember { mutableStateOf<UtxoKey?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        rows = withContext(Dispatchers.IO) {
            runCatching {
                engine.listUnspent().filterNot { it.isSpent }.map { it.toRow() }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    val selectedSat = rows.filter { sel.contains(it.key) }.sumOf { it.valueSat }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
            // Edge-to-edge: inset from system bars (Dialog is outside the tab Scaffold).
            Column(Modifier.fillMaxSize().windowInsetsPadding(safeBarsInsets()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.btc_select_utxos_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(enabled = sel.isNotEmpty(), onClick = { onApply(sel) }) { Text(stringResource(R.string.btc_use_selection)) }
                }
                // Coverage summary
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row {
                        Text(stringResource(R.string.btc_selected), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            formatSatsCompact(context, selectedSat, network.ticker),
                            fontFamily = FontFamily.Monospace,
                            color = if (selectedSat >= amountNeededSat) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row {
                        Text(stringResource(R.string.btc_needed), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(formatSatsCompact(context, amountNeededSat, network.ticker), fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        if (selectedSat < amountNeededSat)
                            stringResource(R.string.btc_add_more_fee_excluded, formatSatsCompact(context, amountNeededSat - selectedSat, network.ticker))
                        else stringResource(R.string.btc_selection_covers),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                HorizontalDivider()
                Box(Modifier.weight(1f)) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        rows.isEmpty() -> Text(
                            stringResource(R.string.btc_no_spendable_utxos),
                            Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { "${it.key.txid}:${it.key.vout}" }) { row ->
                                val selected = sel.contains(row.key)
                                val lbl = env.labels.labelForOutput(row.key.txid, row.key.vout.toLong())
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            sel = if (selected) sel - row.key else sel + row.key
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = selected, onCheckedChange = {
                                        sel = if (selected) sel - row.key else sel + row.key
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${shortMiddle(row.key.txid, 6, 4)}:${row.key.vout}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                        if (!lbl.isNullOrEmpty()) {
                                            Text(lbl, style = MaterialTheme.typography.labelMedium)
                                        }
                                        Text(row.confirmation, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        formatSatsCompact(context, row.valueSat, network.ticker),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    IconButton(onClick = { labelTarget = row.key }) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.btc_edit_label))
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    labelTarget?.let { key ->
        BitcoinLabelEditSheet(
            scope = LabelScope.Output(key.txid, key.vout.toLong()),
            labelStore = env.labels,
            onDismiss = { labelTarget = null },
        )
    }
}

private fun LocalOutput.toRow(): UtxoRow = UtxoRow(
    key = UtxoKey(outpoint.txid.toString(), outpoint.vout.toInt()),
    valueSat = txout.value.toSat().toLong(),
    confirmation = when (val pos = chainPosition) {
        is ChainPosition.Confirmed -> "Block ${pos.confirmationBlockTime.blockId.height}"
        is ChainPosition.Unconfirmed -> "Mempool"
    },
)
