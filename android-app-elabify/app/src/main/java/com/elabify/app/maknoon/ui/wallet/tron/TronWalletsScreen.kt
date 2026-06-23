// Manage Tron wallets. Ported from iOS TronWalletsView.swift: list with
// active marker, rename + delete per row, and an Add-wallet entry.
// (Drag-to-reorder from iOS is folded into up/down via the store's
// move(); kept minimal here, the iOS EditButton reorder maps to the
// same TronWalletStore.move call.)

package com.elabify.app.maknoon.ui.wallet.tron

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.ManageWalletRow
import com.elabify.app.maknoon.ui.wallet.common.WalletManageList
import com.elabify.musnad.wallet.tron.TronWalletDescriptor
import com.elabify.musnad.wallet.tron.TronWalletKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronWalletsScreen(onAddWallet: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { TronStores.walletStore(context) }
    var rev by remember { mutableStateOf(0) }
    val wallets = remember(rev) { walletStore.wallets }
    val activeId = remember(rev) { walletStore.activeWallet?.id }

    var renameTarget by remember { mutableStateOf<TronWalletDescriptor?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trx_tron_wallets)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = onAddWallet) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.trx_add_wallet))
                    }
                },
            )
        },
    ) { padding ->
        WalletManageList(
            rows = wallets.map { ManageWalletRow(it.id.toString(), it.label, subtitle(it), it.id == activeId) },
            emptyTitle = stringResource(R.string.trx_no_wallet_yet),
            onActivate = { id -> walletStore.setActive(java.util.UUID.fromString(id)); rev++ },
            onEdit = { id -> wallets.firstOrNull { it.id.toString() == id }?.let { renameTarget = it; renameDraft = it.label } },
            onRemove = { id -> walletStore.remove(java.util.UUID.fromString(id)); rev++ },
            modifier = Modifier.padding(padding),
        )
    }

    val target = renameTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.walletc_rename_wallet)) },
            text = { OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it }, singleLine = true, label = { Text(stringResource(R.string.common_label)) }) },
            confirmButton = {
                TextButton(onClick = {
                    if (renameDraft.isNotEmpty()) { walletStore.rename(target.id, renameDraft); rev++ }
                    renameTarget = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun subtitle(w: TronWalletDescriptor): String = when (val k = w.kind) {
    is TronWalletKind.Software -> "Software - account ${k.account}"
    is TronWalletKind.Hardware -> "Hardware - account ${k.account}"
}
