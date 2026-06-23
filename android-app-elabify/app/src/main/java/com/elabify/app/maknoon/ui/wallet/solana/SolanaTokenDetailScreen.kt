// SPL token overview, ported from iOS SolanaTokenDetailView and matching the
// Ethereum/Tron token-overview pattern (ADR-0033 Phase 2b round-2): big balance,
// Send (pre-selects this token in the shared send screen), Receive (the parent
// wallet's address), a Contract explorer link, and a metadata section
// (cluster / symbol / decimals / mint address with a copy button).
//
// Reached by tapping a token row on the Solana dashboard: the tap opens this
// overview first, never straight to Send.

package com.elabify.app.maknoon.ui.wallet.solana

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
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SolPurple = Color(0xFF9945FF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolanaTokenDetailScreen(
    descriptor: SolanaWalletDescriptor,
    mint: String,
    onSend: (mint: String) -> Unit,
    onReceive: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val env = remember { SolanaEnv.get(context) }
    val network = remember { env.walletStore.currentNetwork }
    val token = remember(mint) { env.tokenStore.tokens(network).firstOrNull { it.mint == mint } }

    var rawBalance by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(mint) {
        loading = true
        rawBalance = withContext(Dispatchers.IO) {
            runCatching {
                val w = env.openWallet(descriptor)
                w.tokenAccounts().firstOrNull { it.mint == mint }?.amount ?: 0L
            }.getOrNull()
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(token?.symbol ?: stringResource(R.string.sol_token)) },
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
                        rawBalance?.let { token.format(it) } ?: "-",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(token.symbol, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(token.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Actions: Send (pre-selects this token), Receive, Contract.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailTile(stringResource(R.string.walletc_send), Icons.AutoMirrored.Filled.CallMade, { onSend(token.mint) }, Modifier.weight(1f))
                DetailTile(stringResource(R.string.walletc_receive), Icons.AutoMirrored.Filled.CallReceived, onReceive, Modifier.weight(1f))
                DetailTile(stringResource(R.string.sol_contract), Icons.Filled.Description, {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(solanaAddressExplorerUrl(env.settings.explorerURL(network), token.mint)))) }
                }, Modifier.weight(1f))
            }

            // Metadata.
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaRow(stringResource(R.string.sol_cluster), network.displayName)
                    MetaRow(stringResource(R.string.sol_symbol), token.symbol)
                    MetaRow(stringResource(R.string.sol_decimals), token.decimals.toString())
                    MetaRow(
                        stringResource(R.string.sol_mint),
                        token.mint,
                        monospace = true,
                        onCopy = { clipboard.setText(AnnotatedString(token.mint)); copied = true },
                        copied = copied,
                    )
                }
            }
        }
    }
}

/** Solana Explorer address URL, preserving any `?cluster=` query the base
 *  carries (mirrors openSignatureInExplorer). */
private fun solanaAddressExplorerUrl(base: String, address: String): String =
    if (base.contains("?")) {
        val (root, query) = base.split("?", limit = 2)
        "${root.trimEnd('/')}/address/$address?$query"
    } else {
        "${base.trimEnd('/')}/address/$address"
    }

@Composable
private fun DetailTile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier = modifier.clickable(onClick = onClick), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(vertical = 10.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = SolPurple)
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
                    contentDescription = stringResource(R.string.sol_copy_mint_address),
                    tint = if (copied) SolPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
