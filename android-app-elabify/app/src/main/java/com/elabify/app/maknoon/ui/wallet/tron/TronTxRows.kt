// Transaction rows shared by the Tron dashboard's preview list and the
// full TronTransactionListScreen, plus the optimistic pending-tx row.
// Ported 1:1 from iOS TronTransactionListView.swift (TronTxRow +
// PendingTronTxRow): direction icon, relative date, counterparty,
// signed amount, status, explorer link.

package com.elabify.app.maknoon.ui.wallet.tron

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.tron.PendingTronTx
import com.elabify.musnad.wallet.tron.TronRPCClient
import java.math.BigDecimal
import java.math.RoundingMode

private val InGreen = Color(0xFF34A853)
private val OutOrange = Color(0xFFF29900)
private val ContractBlue = Color(0xFF4285F4)

private enum class TxDir { IN, OUT, CONTRACT, OTHER }

private fun directionOf(tx: TronRPCClient.TxRecord, owner: String): TxDir {
    if (tx.contractType == "TransferContract") {
        if (tx.toAddress == owner) return TxDir.IN
        if (tx.ownerAddress == owner) return TxDir.OUT
        return TxDir.OTHER
    }
    return TxDir.CONTRACT
}

private fun iconFor(dir: TxDir): ImageVector = when (dir) {
    TxDir.IN -> Icons.Filled.ArrowDownward
    TxDir.OUT -> Icons.Filled.ArrowUpward
    TxDir.CONTRACT -> Icons.Filled.Description
    TxDir.OTHER -> Icons.Filled.Description
}

private fun tintFor(dir: TxDir): Color = when (dir) {
    TxDir.IN -> InGreen
    TxDir.OUT -> OutOrange
    TxDir.CONTRACT -> ContractBlue
    TxDir.OTHER -> Color.Gray
}

private fun explorerTxUrl(explorerBase: String, txid: String): String {
    val base = explorerBase.trim().trimEnd('/')
    return "$base/#/transaction/$txid"
}

private fun explorerAddrUrl(explorerBase: String, addr: String): String {
    val base = explorerBase.trim().trimEnd('/')
    return "$base/#/address/$addr"
}

@Composable
internal fun TronTxRow(
    tx: TronRPCClient.TxRecord,
    ownerAddress: String,
    explorerBaseURL: String,
) {
    val context = LocalContext.current
    val dir = directionOf(tx, ownerAddress)
    val failed = tx.contractStatus != null && tx.contractStatus != "SUCCESS"

    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(iconFor(dir), contentDescription = null, tint = tintFor(dir), modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            val ts = tx.blockTimestamp
            if (ts != null) {
                Text(relativeSince(ts), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            } else {
                Text(stringResource(R.string.trx_pending), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                counterpartyShort(tx, dir),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(amountString(tx, dir), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            when {
                failed -> Text(stringResource(R.string.trx_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                tx.contractStatus != null -> Text(
                    tx.contractStatus!!.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = {
            val url = explorerTxUrl(explorerBaseURL, tx.txID)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }) {
            Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.trx_open_in_tronscan), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun counterpartyShort(tx: TronRPCClient.TxRecord, dir: TxDir): String {
    val other = when (dir) {
        TxDir.IN -> tx.ownerAddress
        TxDir.OUT -> tx.toAddress
        TxDir.CONTRACT -> tx.contractAddress
        TxDir.OTHER -> tx.toAddress ?: tx.ownerAddress
    }
    if (!other.isNullOrEmpty()) return shortHash(other)
    return shortHash(tx.txID)
}

private fun amountString(tx: TronRPCClient.TxRecord, dir: TxDir): String {
    val sun = tx.nativeSunAmount
    if (sun == null || sun <= 0) {
        return if (tx.contractType == "TriggerSmartContract") "TRC-20" else "-"
    }
    val trx = BigDecimal(sun).divide(BigDecimal(1_000_000)).setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    val sign = when (dir) {
        TxDir.IN -> "+"
        TxDir.OUT -> "−"
        else -> ""
    }
    return "$sign$trx TRX"
}

@Composable
internal fun PendingTronTxRow(
    pending: PendingTronTx,
    explorerBaseURL: String,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Schedule, contentDescription = null, tint = OutOrange, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.trx_broadcast_relative, relativeSince(pending.broadcastAtEpochMs)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                shortHash(pending.counterparty),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(pendingAmountString(pending), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.trx_pending), style = MaterialTheme.typography.labelSmall, color = OutOrange)
        }
        IconButton(onClick = {
            val url = explorerTxUrl(explorerBaseURL, pending.txID)
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }) {
            Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.trx_open_in_tronscan), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun pendingAmountString(p: PendingTronTx): String {
    val symbol = p.tokenSymbol
    val decimals = p.tokenDecimals
    val sign = if (p.direction == PendingTronTx.Direction.OUT) "−" else "+"
    if (symbol != null && decimals != null) {
        val v = BigDecimal(p.sunAmount)
            .divide(BigDecimal.TEN.pow(decimals))
            .setScale(decimals, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
        return "$sign$v $symbol"
    }
    val trx = BigDecimal(p.sunAmount).divide(BigDecimal(1_000_000)).setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    return "$sign$trx TRX"
}

// re-exported for screens that want the address explorer link
internal fun tronAddressExplorerUrl(explorerBase: String, addr: String): String = explorerAddrUrl(explorerBase, addr)
