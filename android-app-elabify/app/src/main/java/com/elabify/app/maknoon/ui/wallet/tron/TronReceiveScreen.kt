// QR + address + copy for a Tron wallet. Ported from iOS
// TronReceiveView.swift: derive the wallet's single T-prefixed
// base58check address, render it as a QR (the bare address; Tron has no
// BIP21-style payment URI), copy + explorer link, network caption.

package com.elabify.app.maknoon.ui.wallet.tron

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.wallet.common.ReceiveScaffold
import com.elabify.musnad.wallet.tron.TronWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronReceiveScreen(walletId: UUID, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { TronStores.walletStore(context) }
    val settings = remember { TronStores.settings(context) }
    val sandwich = remember { loadTronSandwich(context) }

    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    val network = remember { walletStore.currentNetwork }

    var address by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(walletId) {
        loading = true
        if (descriptor == null) {
            error = "Wallet not found."
            loading = false
            return@LaunchedEffect
        }
        val rpcURL = settings.rpcURL(network)
        val result = withContext(Dispatchers.IO) {
            runCatching { TronWallet(descriptor, network, rpcURL, sandwich).resolvedAddress() }
        }
        result.onSuccess { address = it }.onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    ReceiveScaffold(onBack = onDone) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.trx_deriving_address), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                address != null -> {
                    val addr = address!!
                    Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                        QrCode(content = addr, modifier = Modifier.padding(8.dp).size(240.dp))
                    }
                    Text(
                        stringResource(R.string.trx_scan_or_copy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.walletc_address), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(addr, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, addr)
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (copied) stringResource(R.string.trx_copied) else stringResource(R.string.trx_copy_address))
                    }
                    OutlinedButton(
                        onClick = {
                            val url = tronAddressExplorerUrl(settings.explorerURL(network), addr)
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.common_view_on_explorer))
                    }
                    Text(
                        stringResource(R.string.trx_private_key_owns_address, network.displayName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clip = ClipData.newPlainText("Tron address", text)
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
