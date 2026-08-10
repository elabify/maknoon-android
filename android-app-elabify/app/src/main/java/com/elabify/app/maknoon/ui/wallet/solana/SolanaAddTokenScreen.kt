// Add a custom SPL token, 1:1 with iOS SolanaAddTokenSheet.swift. The user
// pastes a mint address; we auto-fetch decimals from the on-chain SPL Mint
// account (SolanaTokenLookup.fetch, which carries the error-prone decimals
// field directly) and also try the verified catalog for symbol/name. The
// user fills in symbol/name when the lookup can't. Persists via
// SolanaSPLTokenStore.add for the current cluster.

package com.elabify.app.maknoon.ui.wallet.solana

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.solana.SolanaSPLToken
import com.elabify.musnad.wallet.solana.SolanaTokenLookup
import com.elabify.musnad.wallet.solana.SolanaTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SolanaAddTokenScreen(
    prefillMint: String? = null,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }
    val scope = rememberCoroutineScope()
    val network = env.walletStore.currentNetwork

    var mint by remember { mutableStateOf(prefillMint ?: "") }
    var symbol by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var decimals by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(SolanaTokenSource.CUSTOM) }
    var looking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun lookup() {
        val m = mint.trim()
        if (m.isEmpty()) return
        looking = true
        note = null
        error = null
        scope.launch {
            // Catalog first (gives symbol + name + decimals if verified).
            val catalogEntry = withContext(Dispatchers.IO) {
                runCatching {
                    env.catalog.refreshIfStale(env.settings.tokenCatalogURL)
                    env.catalog.find(m)
                }.getOrNull()
            }
            if (catalogEntry != null) {
                symbol = catalogEntry.symbol
                name = catalogEntry.name
                decimals = catalogEntry.decimals.toString()
                source = SolanaTokenSource.JUPITER
                note = "Found in the verified catalog."
            } else {
                // Fall back to on-chain mint decimals.
                val meta = withContext(Dispatchers.IO) {
                    runCatching { SolanaTokenLookup.fetch(m, env.settings.rpcURL(network)) }.getOrNull()
                }
                if (meta != null) {
                    decimals = meta.decimals.toString()
                    source = SolanaTokenSource.CUSTOM
                    note = "Read decimals on-chain. Enter the symbol and name manually."
                } else {
                    error = context.getString(R.string.sol_could_not_read_mint, network.displayName)
                }
            }
            looking = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.sol_add_spl_token), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.sol_cluster_label, network.displayName), style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = mint,
            onValueChange = { mint = it },
            label = { Text(stringResource(R.string.sol_mint_address_base58)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { lookup() }, enabled = mint.isNotBlank() && !looking) { Text(stringResource(R.string.sol_look_up)) }
            if (looking) CircularProgressIndicator(modifier = Modifier.padding(8.dp))
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        OutlinedTextField(
            value = symbol,
            onValueChange = { symbol = it },
            label = { Text(stringResource(R.string.sol_symbol)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.common_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = decimals,
            onValueChange = { decimals = it.filter { c -> c.isDigit() } },
            label = { Text(stringResource(R.string.sol_decimals)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let { Text(stringResource(R.string.sol_error_prefix, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Button(
            enabled = mint.isNotBlank() && symbol.isNotBlank() && decimals.toIntOrNull() != null,
            onClick = {
                val d = decimals.toIntOrNull()
                if (d == null) { error = context.getString(R.string.sol_decimals_whole_number); return@Button }
                env.tokenStore.add(
                    SolanaSPLToken(
                        network = network,
                        mint = mint.trim(),
                        symbol = symbol.trim(),
                        name = name.trim().ifEmpty { symbol.trim() },
                        decimals = d,
                        logoURI = env.settings.tokenLogoURL(mint.trim()),
                        source = source,
                    )
                )
                onAdded()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.walletc_add_token)) }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
    }
}
