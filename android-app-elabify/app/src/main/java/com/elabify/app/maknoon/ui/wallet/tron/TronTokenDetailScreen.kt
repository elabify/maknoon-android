// TRC-20 token detail. Ported from iOS TronTokenDetailView.swift: big
// balance, Send (pre-selects this token), Receive (the parent wallet's
// T-address), a Contract explorer link, and a metadata section.

package com.elabify.app.maknoon.ui.wallet.tron

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.tron.TronTRC20Token
import com.elabify.musnad.wallet.tron.TronTRC20TransferBuilder
import com.elabify.musnad.wallet.tron.TronWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronTokenDetailScreen(
    walletId: UUID,
    token: TronTRC20Token,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val walletStore = remember { TronStores.walletStore(context) }
    val settings = remember { TronStores.settings(context) }
    val sandwich = remember { loadTronSandwich(context) }
    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    val network = remember { walletStore.currentNetwork }

    var rawBalance by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(token.id) {
        loading = true
        if (descriptor == null) { loading = false; return@LaunchedEffect }
        val rpcURL = settings.rpcURL(network)
        rawBalance = withContext(Dispatchers.IO) {
            runCatching {
                val holder = TronWallet(descriptor, network, rpcURL, sandwich).resolvedAddress()
                TronTRC20TransferBuilder.balance(holder, token.contract, rpcURL)
            }.getOrNull()
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(token.symbol) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // balance card
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text(rawBalance?.let { token.format(it) } ?: "-", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                }
                Text(token.symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(token.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // actions
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailTile(stringResource(R.string.walletc_send), Icons.Filled.Send, onSend, Modifier.weight(1f))
                DetailTile(stringResource(R.string.walletc_receive), Icons.Filled.CallReceived, onReceive, Modifier.weight(1f))
                DetailTile(stringResource(R.string.trx_contract), Icons.Filled.Description, {
                    val base = settings.explorerURL(network).trim().trimEnd('/')
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/#/contract/${token.contract}"))) }
                }, Modifier.weight(1f))
            }

            // meta
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaRow(stringResource(R.string.common_network), network.displayName)
                    MetaRow(stringResource(R.string.trx_symbol), token.symbol)
                    MetaRow(stringResource(R.string.trx_decimals), token.decimals.toString())
                    MetaRow(
                        stringResource(R.string.trx_contract),
                        token.contract,
                        monospace = true,
                        onCopy = { clipboard.setText(AnnotatedString(token.contract)); copied = true },
                        copied = copied,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailTile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = TronRed)
            Text(title, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MetaRow(
    label: String,
    value: String,
    monospace: Boolean = false,
    onCopy: (() -> Unit)? = null,
    copied: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default, modifier = Modifier.weight(1f))
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.trx_copy_contract_address),
                    tint = if (copied) TronRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
