// Transaction rows shared by the Ethereum dashboard's preview list and the
// full EthereumTransactionListScreen, plus the optimistic pending-tx row.
// Ported 1:1 from iOS EthereumTransactionListView: direction icon
// (sent/received/contract), relative date, counterparty, signed native
// amount, error/status badge, explorer link.

package com.elabify.app.maknoon.ui.wallet.ethereum

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
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
import com.elabify.musnad.wallet.ethereum.EthereumTx
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import com.elabify.musnad.wallet.ethereum.PendingEthereumTx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val InGreen = Color(0xFF34A853)
private val OutOrange = Color(0xFFF29900)
private val ContractBlue = Color(0xFF4285F4)
private val ErrorRed = Color(0xFFEA4335)

private enum class TxDir { IN, OUT, CONTRACT }

private fun directionOf(tx: EthereumTx, owner: String): TxDir {
    val o = owner.lowercase()
    val input = tx.input
    val isContractCall = tx.to == null || (input != null && input != "0x" && input.length > 2 && tx.value == "0")
    return when {
        tx.to?.lowercase() == o -> TxDir.IN
        tx.from.lowercase() == o && isContractCall -> TxDir.CONTRACT
        tx.from.lowercase() == o -> TxDir.OUT
        else -> TxDir.CONTRACT
    }
}

private fun iconFor(dir: TxDir): ImageVector = when (dir) {
    TxDir.IN -> Icons.Filled.ArrowDownward
    TxDir.OUT -> Icons.Filled.ArrowUpward
    TxDir.CONTRACT -> Icons.Filled.Description
}

private fun tintFor(dir: TxDir): Color = when (dir) {
    TxDir.IN -> InGreen
    TxDir.OUT -> OutOrange
    TxDir.CONTRACT -> ContractBlue
}

private fun formatDate(epochSeconds: Double): String {
    if (epochSeconds <= 0) return ""
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    return fmt.format(Date((epochSeconds * 1000).toLong()))
}

internal fun ethExplorerTxUrl(explorerBase: String, hash: String): String =
    "${explorerBase.trimEnd('/')}/tx/$hash"

@Composable
internal fun EthereumTxRow(tx: EthereumTx, ownerAddress: String, explorerBase: String) {
    val context = LocalContext.current
    val dir = directionOf(tx, ownerAddress)
    val isError = tx.isError == "1" || tx.txreceiptStatus == "0"
    val counterparty = when (dir) {
        TxDir.IN -> tx.from
        else -> tx.to ?: stringResource(R.string.eth_contract_creation)
    }
    val wei = runCatching { EthereumWeiValue.fromDecimal(java.math.BigDecimal(tx.value)) }.getOrNull()
    val signed = when (dir) {
        TxDir.IN -> "+"
        TxDir.OUT -> "-"
        TxDir.CONTRACT -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(iconFor(dir), contentDescription = null, tint = if (isError) ErrorRed else tintFor(dir), modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (dir) { TxDir.IN -> stringResource(R.string.eth_received); TxDir.OUT -> stringResource(R.string.eth_sent); TxDir.CONTRACT -> stringResource(R.string.eth_contract) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(shortHex(counterparty), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val date = formatDate(tx.timestampSeconds)
            if (date.isNotEmpty()) Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isError) Text(stringResource(R.string.eth_failed), style = MaterialTheme.typography.labelSmall, color = ErrorRed)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (wei != null && dir != TxDir.CONTRACT) {
                Text(
                    "$signed${wei.ether.setScale(6, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = tintFor(dir),
                )
            }
        }
        IconButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ethExplorerTxUrl(explorerBase, tx.hash)))) }
        }) { Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.common_view_on_explorer), modifier = Modifier.size(18.dp)) }
    }
}

@Composable
internal fun PendingEthereumTxRow(tx: PendingEthereumTx, explorerBase: String) {
    val context = LocalContext.current
    val inbound = tx.direction == PendingEthereumTx.Direction.IN
    val tint = if (inbound) InGreen else OutOrange
    val human = if (tx.tokenContract != null && tx.tokenDecimals != null) {
        "${formatUnitsDecimal(tx.weiValue, tx.tokenDecimals!!)} ${tx.tokenSymbol ?: ""}".trim()
    } else {
        runCatching { EthereumWeiValue.fromDecimal(java.math.BigDecimal(tx.weiValue)).ether.stripTrailingZeros().toPlainString() }
            .getOrDefault("0")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Schedule, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(if (inbound) stringResource(R.string.eth_receiving_pending) else stringResource(R.string.eth_sending_pending), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(shortHex(tx.counterparty), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "${if (inbound) "+" else "-"}$human",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = tint,
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ethExplorerTxUrl(explorerBase, tx.txHash)))) }
        }) { Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.common_view_on_explorer), modifier = Modifier.size(18.dp)) }
    }
}
