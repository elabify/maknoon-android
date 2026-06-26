// Manage Solana wallets, 1:1 with iOS SolanaWalletsView.swift +
// AddSolanaWalletSheet.swift (software path). Mirrors the Ethereum / Tron
// Manage screens: a Scaffold + TopAppBar with a back arrow and an in-bar
// "Add wallet" action, listing the wallets with an active marker plus a
// per-row Use / rename / remove control set, and an "Add wallet" button at
// the foot of the list (per ADR-0033, Add lives inside Manage, not in the
// dashboard "+" menu).
//
// Hardware add/discover is a later phase on Android (no BLE transport yet),
// mirrored here as a disabled affordance with the same "not yet" hook the
// send screen uses; this pass is navigation chrome only and does NOT touch
// that hardware logic.

package com.elabify.app.maknoon.ui.wallet.solana

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.ManageWalletRow
import com.elabify.app.maknoon.ui.wallet.common.WalletManageList
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolanaWalletsScreen(
    onBack: () -> Unit,
    onSelect: (UUID) -> Unit,
    onAddWallet: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }
    var rev by remember { mutableIntStateOf(0) }

    val wallets = remember(rev) { env.walletStore.wallets }
    val activeId = remember(rev) { env.walletStore.activeWallet?.id }

    var renameTarget by remember { mutableStateOf<SolanaWalletDescriptor?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sol_solana_wallets)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = onAddWallet) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.sol_add_wallet))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                },
            )
        },
    ) { padding ->
        WalletManageList(
            rows = wallets.map { ManageWalletRow(it.id.toString(), it.label, walletKindLabel(it), it.id == activeId) },
            emptyTitle = stringResource(R.string.sol_no_wallet_yet),
            onActivate = { id -> env.walletStore.setActive(java.util.UUID.fromString(id)); rev++; onSelect(java.util.UUID.fromString(id)) },
            onEdit = { id -> wallets.firstOrNull { it.id.toString() == id }?.let { renameTarget = it; renameDraft = it.label } },
            onRemove = { id -> env.walletStore.remove(java.util.UUID.fromString(id)); rev++ },
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
                    if (renameDraft.isNotEmpty()) { env.walletStore.rename(target.id, renameDraft); rev++ }
                    renameTarget = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

internal fun walletKindLabel(w: SolanaWalletDescriptor): String = when (val k = w.kind) {
    is SolanaWalletKind.Software -> "Software account ${k.account}"
    is SolanaWalletKind.Hardware -> "Hardware account ${k.account} (${shortAddress(k.publicKeyBase58)})"
}

