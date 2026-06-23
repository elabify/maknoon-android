// Lightning settings, 1:1 with iOS LightningSettingsView.swift. Lightning has
// no chain-wide "network" the way the on-chain coins do: each LNDHub account IS
// its own server, so "settings" is the account roster (with the per-account TLS
// flag and credential rotation, reached through the shared accounts screen)
// plus a short security note. The RPC/explorer-override slot the on-chain
// settings screens carry maps here to the per-account server URL + TLS toggle,
// which live on the Edit account view.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.WalletChainScaffold

@Composable
internal fun LightningSettingsScreen(
    onManageAccounts: () -> Unit,
    onBack: () -> Unit,
) {
    WalletChainScaffold(title = stringResource(R.string.ln_lightning_settings), onBack = onBack) { padding ->
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ln_lndhub_accounts), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ln_settings_accounts_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.Button(
                    onClick = onManageAccounts,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ln_manage_accounts)) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ln_security), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.ln_security_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    }
}
