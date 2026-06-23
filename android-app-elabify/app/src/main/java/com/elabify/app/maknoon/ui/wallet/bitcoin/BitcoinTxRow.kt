// One transaction row, mirroring iOS BitcoinTxRow: status icon, date (tap
// to toggle to block height), short txid + copy, optional user label,
// signed net amount (+green / -primary), unconfirmed badge with a
// pending-age caption + Bump fee action, and an explorer link. Used by the
// dashboard recent list and the full transaction history screen.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinLabelStore
import org.bitcoindevkit.CanonicalTx
import java.text.DateFormat
import java.util.Date

@Composable
internal fun BitcoinTxRow(
    tx: CanonicalTx,
    netSat: Long?,
    explorerTxUrl: String?,
    labelStore: BitcoinLabelStore,
    onBumpFee: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val txid = remember(tx) { tx.txidHex() }
    var showHeight by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val label = remember(txid) { labelStore.labelForOutput(txid, 0L) }
    val unconfirmed = tx.isUnconfirmed()
    val canBump = onBumpFee != null && unconfirmed && (netSat ?: 0L) < 0L

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (unconfirmed) Icons.Filled.Schedule else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (unconfirmed) Color(0xFFE08A00) else Color(0xFF2E7D32),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                if (showHeight) tx.blockHeightLabel() else dateLabel(tx.timestampSec()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { showHeight = !showHeight },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortMiddle(txid, 8, 6),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(txid))
                    copied = true
                }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.btc_copy_tx_id),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (!label.isNullOrEmpty()) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canBump) {
                TextButton(onClick = { onBumpFee!!() }) { Text(stringResource(R.string.btc_bump_fee)) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                netSat?.let { formatSignedBtc(it) } ?: "-",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    netSat == null -> MaterialTheme.colorScheme.onSurface
                    netSat > 0 -> Color(0xFF2E7D32)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                if (unconfirmed) stringResource(R.string.btc_unconfirmed) else stringResource(R.string.btc_confirmed),
                style = MaterialTheme.typography.labelSmall,
                color = if (unconfirmed) Color(0xFFE08A00) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        explorerTxUrl?.let { url ->
            IconButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }) {
                Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.btc_open_in_block_explorer))
            }
        }
    }
}

private fun dateLabel(epochSec: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSec * 1000))
