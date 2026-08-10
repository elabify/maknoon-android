// Per-network Bitcoin settings, ported from iOS BitcoinSettingsView:
// network picker + Electrum URL (+ pinned cert SHA), mempool / fee-oracle
// URL, block-explorer URL override, CoinGecko base + fiat code. Writes
// through to the SDK BitcoinSettings store. "Use default" buttons clear an
// override by writing an empty string (the resolver then falls back to the
// public default), matching iOS.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinSettingsScreen(
    env: BitcoinWalletEnv,
    onClose: () -> Unit,
) {
    var network by remember { mutableStateOf(BitcoinNetwork.MAINNET) }
    var electrumUrl by remember { mutableStateOf("") }
    var pinnedCert by remember { mutableStateOf("") }
    var mempoolUrl by remember { mutableStateOf("") }
    var explorerUrl by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Load the resolved values for the selected network.
    LaunchedEffect(network) {
        electrumUrl = env.settings.electrumURL(network)
        mempoolUrl = env.settings.mempoolURL(network)
        explorerUrl = env.settings.explorerURL(network)
        pinnedCert = ""
        saved = false
    }

    fun save() {
        env.settings.setElectrum(BitcoinSettings.ElectrumConfig(electrumUrl.trim(), pinnedCert.trim()), network)
        env.settings.setMempool(mempoolUrl.trim(), network)
        env.settings.setExplorerURL(explorerUrl.trim(), network)
        env.settings.persist()
        saved = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btc_bitcoin_settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.btc_chain_for_settings), style = MaterialTheme.typography.titleSmall)
            Row {
                BitcoinNetwork.entries.forEach { net ->
                    FilterChip(
                        selected = network == net,
                        onClick = { network = net },
                        label = { Text(net.displayName) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.btc_electrum), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = electrumUrl,
                onValueChange = { electrumUrl = it },
                label = { Text(network.defaultElectrumURL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pinnedCert,
                onValueChange = { pinnedCert = it },
                label = { Text(stringResource(R.string.btc_pinned_cert)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { electrumUrl = ""; pinnedCert = ""; save() }) { Text(stringResource(R.string.btc_use_public_default)) }
            Text(
                stringResource(R.string.btc_electrum_default_hint, network.defaultElectrumURL),
                style = MaterialTheme.typography.labelSmall,
            )

            HorizontalDivider()
            Text(stringResource(R.string.btc_mempool_fee_oracle), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = mempoolUrl,
                onValueChange = { mempoolUrl = it },
                label = { Text(network.defaultMempoolURL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { mempoolUrl = ""; save() }) { Text(stringResource(R.string.btc_use_mempool_default)) }

            HorizontalDivider()
            Text(stringResource(R.string.btc_block_explorer), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = explorerUrl,
                onValueChange = { explorerUrl = it },
                label = { Text(stringResource(R.string.btc_same_as_mempool)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { explorerUrl = ""; save() }) { Text(stringResource(R.string.btc_use_mempool_default)) }
            Text(
                stringResource(R.string.btc_explorer_caption),
                style = MaterialTheme.typography.labelSmall,
            )

            Text(
                stringResource(R.string.btc_price_sources_caption),
                style = MaterialTheme.typography.labelSmall,
            )

            HorizontalDivider()
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btc_save_changes)) }
            OutlinedButton(onClick = {
                env.settings.resetToDefaults()
                electrumUrl = env.settings.electrumURL(network)
                mempoolUrl = env.settings.mempoolURL(network)
                explorerUrl = env.settings.explorerURL(network)
                pinnedCert = ""
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btc_use_default_settings)) }
            if (saved) Text(stringResource(R.string.btc_saved), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}
