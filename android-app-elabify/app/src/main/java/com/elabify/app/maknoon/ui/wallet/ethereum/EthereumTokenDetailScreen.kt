// ERC-20 token overview, ported from iOS EthereumTokenDetailView. Big balance,
// Send (pre-selects this token in the shared send screen), Receive (the parent
// wallet's address), a Contract explorer link, and a metadata section
// (network / symbol / decimals / contract address with a copy button).
//
// Reached by tapping a token row on the Ethereum dashboard (ADR-0033 Phase 2b
// round-2): tapping a token opens this overview first, never straight to Send.

package com.elabify.app.maknoon.ui.wallet.ethereum

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
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
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
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumTokenDetailScreen(
    walletId: UUID,
    tokenId: String,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val walletStore = remember { EthereumStores.walletStore(context) }
    val tokenStore = remember { EthereumStores.tokenStore(context) }
    val resolved = remember { resolveCurrentNetwork(context) }
    val descriptor = remember(walletId) { walletStore.wallets.firstOrNull { it.id == walletId } }
    // Wallet-scoped lookup (ADR-0060) so a wallet's own added token resolves here.
    val token = remember(tokenId) { tokenStore.tokens(resolved, walletId).firstOrNull { it.id == tokenId } }

    var rawBalanceHex by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(tokenId) {
        loading = true
        if (descriptor == null || token == null) { loading = false; return@LaunchedEffect }
        rawBalanceHex = withContext(Dispatchers.IO) {
            runCatching { EthereumWallet(descriptor).tokenBalance(token, resolved.rpcURL).hex }.getOrNull()
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(token?.symbol ?: stringResource(R.string.eth_token)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        if (token == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text(stringResource(R.string.walletc_token_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Balance card.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        rawBalanceHex?.let { formatTokenBalanceHex(it, token.decimals) } ?: "-",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(token.symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(token.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Actions: Send (pre-selects this token), Receive, Contract.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailTile(stringResource(R.string.walletc_send), Icons.AutoMirrored.Filled.CallMade, onSend, Modifier.weight(1f))
                DetailTile(stringResource(R.string.walletc_receive), Icons.AutoMirrored.Filled.CallReceived, onReceive, Modifier.weight(1f))
                DetailTile(stringResource(R.string.eth_contract), Icons.Filled.Description, {
                    val base = resolved.explorerURL.trim().trimEnd('/')
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/token/${token.contractAddress}"))) }
                }, Modifier.weight(1f))
            }

            // Metadata.
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaRow(stringResource(R.string.common_network), resolved.displayName)
                    MetaRow(stringResource(R.string.eth_symbol), token.symbol)
                    MetaRow(stringResource(R.string.eth_decimals), token.decimals.toString())
                    MetaRow(
                        stringResource(R.string.eth_contract),
                        token.contractAddress,
                        monospace = true,
                        onCopy = {
                            clipboard.setText(AnnotatedString(token.contractAddress))
                            copied = true
                        },
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
            Icon(icon, contentDescription = null, tint = EthBlue)
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
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.eth_copy_contract_address),
                    tint = if (copied) EthBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
