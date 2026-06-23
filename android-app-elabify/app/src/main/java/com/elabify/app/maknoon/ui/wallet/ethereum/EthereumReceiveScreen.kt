// QR + address + copy for an Ethereum wallet. Ported from iOS
// EthereumReceiveView: render the wallet's EIP-55 address as a QR (the
// bare 0x address; the same address works on every EVM chain), copy +
// explorer link, network caption. The address is already cached on the
// descriptor at creation, so no RPC round-trip is needed.

package com.elabify.app.maknoon.ui.wallet.ethereum

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.wallet.common.ReceiveScaffold
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumReceiveScreen(walletId: UUID, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { EthereumStores.walletStore(context) }
    val resolved = remember { resolveCurrentNetwork(context) }

    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    val address = descriptor?.address
    var copied by remember { mutableStateOf(false) }

    ReceiveScaffold(onBack = onDone) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (address == null) {
                Text(stringResource(R.string.eth_no_derived_address), color = MaterialTheme.colorScheme.error)
            } else {
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    QrCode(content = address, modifier = Modifier.padding(8.dp).size(240.dp))
                }
                Text(
                    stringResource(R.string.eth_scan_or_copy),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.walletc_address), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedButton(
                    onClick = { copyToClipboard(context, address); copied = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) stringResource(R.string.eth_copied) else stringResource(R.string.eth_copy_address))
                }
                OutlinedButton(
                    onClick = {
                        val url = "${resolved.explorerURL.trimEnd('/')}/address/$address"
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.common_view_on_explorer))
                }
                Text(
                    stringResource(R.string.eth_private_key_owns_address, resolved.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clip = ClipData.newPlainText("Ethereum address", text)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
