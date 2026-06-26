// Multi-wallet management, ported from iOS BitcoinWalletsView. Lists every
// wallet in the store with active selection, rename, and remove. Add wallet is
// the full-screen AddBitcoinWalletScreen (ADR-0033): the "+" action routes to
// it (software + hardware Ledger / security key, with the Trezor second-factor
// selector + a Discover sweep), rather than the old software-only dialog.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinWalletsScreen(
    env: BitcoinWalletEnv,
    onAddWallet: () -> Unit,
    onStoreChanged: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var version by remember { mutableStateOf(0) }
    val wallets = remember(version) { env.store.wallets }
    val activeId = remember(version) { env.store.activeWalletId }
    var renameTarget by remember { mutableStateOf<BitcoinWalletDescriptor?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btc_bitcoin_wallets)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                    IconButton(onClick = onAddWallet) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.btc_add_wallet))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(wallets, key = { it.id }) { w ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { env.store.setActive(w.id); version++; onStoreChanged() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(w.label, style = MaterialTheme.typography.titleMedium)
                        Text("${w.kindLabel()} - ${w.network.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (w.id == activeId) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.btc_active), modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { renameTarget = w }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.btc_rename))
                    }
                    IconButton(onClick = {
                        env.store.remove(w.id)
                        version++
                        onStoreChanged()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_remove))
                    }
                }
                HorizontalDivider()
            }
        }
    }

    renameTarget?.let { target ->
        var draft by remember(target.id) { mutableStateOf(target.label) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.walletc_rename_wallet)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.btc_wallet_label_lc)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    env.store.rename(target.id, draft.trim())
                    version++
                    onStoreChanged()
                    renameTarget = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
