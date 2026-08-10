// Add a TRC-20 token to the dashboard. Ported from iOS
// TronAddTokenSheet.swift. Lookup order: cached catalog -> on-chain
// probe (name/symbol/decimals via triggerConstantContract) -> manual
// entry. The contract field is debounced; a hit pre-fills the editable
// fields and surfaces a Verified / Detected / Not-in-catalog badge.

package com.elabify.app.maknoon.ui.wallet.tron

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.tron.TRC20Metadata
import com.elabify.musnad.wallet.tron.TronAddressCodec
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronTRC20Token
import com.elabify.musnad.wallet.tron.TronTokenCatalog
import com.elabify.musnad.wallet.tron.TronTokenLookup
import com.elabify.musnad.wallet.tron.TronTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TronAddTokenScreen(prefilledContract: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { TronStores.walletStore(context) }
    val settings = remember { TronStores.settings(context) }
    val tokenStore = remember { TronStores.tokenStore(context) }
    val catalog = remember { TronStores.catalog(context) }
    val network = remember { walletStore.currentNetwork }

    var contractInput by remember { mutableStateOf(prefilledContract ?: "") }
    var symbolInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var decimalsInput by remember { mutableStateOf("") }
    var catalogHit by remember { mutableStateOf<TronTokenCatalog.Entry?>(null) }
    var probedMeta by remember { mutableStateOf<TRC20Metadata?>(null) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmed = contractInput.trim()
    val contractValid = trimmed.isNotEmpty() && TronAddressCodec.isValid(trimmed)

    // catalog hit + debounced on-chain probe
    LaunchedEffect(contractInput) {
        catalogHit = if (contractValid) catalog.find(trimmed) else null
        probedMeta = null
        if (!contractValid || catalogHit != null) { probing = false; return@LaunchedEffect }
        probing = true
        delay(350)
        val rpcURL = settings.rpcURL(network)
        val meta = withContext(Dispatchers.IO) {
            runCatching { TronTokenLookup.fetch(trimmed, rpcURL) }.getOrNull()
        }
        probing = false
        if (meta != null) {
            probedMeta = meta
            if (symbolInput.isBlank()) symbolInput = meta.symbol
            if (nameInput.isBlank()) nameInput = meta.name
            if (decimalsInput.isBlank()) decimalsInput = meta.decimals.toString()
        }
    }

    val decimalsValid = decimalsInput.toIntOrNull()?.let { it in 0..18 } == true
    val canAdd = contractValid && (catalogHit != null || (symbolInput.isNotBlank() && decimalsValid))

    fun addToken() {
        if (!contractValid) { error = context.getString(R.string.trx_contract_address_invalid); return }
        val hit = catalogHit
        val token = if (hit != null) {
            TronTRC20Token(network, trimmed, hit.symbol, hit.name, hit.decimals, hit.logoURI, TronTokenSource.TRONSCAN)
        } else {
            val d = decimalsInput.toIntOrNull() ?: run { error = context.getString(R.string.trx_decimals_range); return }
            val sym = symbolInput.trim()
            TronTRC20Token(network, trimmed, sym, nameInput.trim().ifEmpty { sym }, d, null, TronTokenSource.CUSTOM)
        }
        tokenStore.add(token)
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trx_add_trc20_token)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = contractInput,
                onValueChange = { contractInput = it; error = null },
                label = { Text(stringResource(R.string.trx_contract_address_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.trx_network_label, network.displayName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (network != TronNetwork.MAINNET) {
                Text(
                    stringResource(R.string.trx_testnet_catalog_help, network.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF29900),
                )
            }

            if (probing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.trx_reading_onchain), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val hit = catalogHit
            when {
                hit != null -> {
                    BadgeRow(stringResource(R.string.trx_verified), Icons.Filled.CheckCircle, Color(0xFF34A853))
                    ReadonlyRow(stringResource(R.string.trx_symbol), hit.symbol)
                    ReadonlyRow(stringResource(R.string.common_name), hit.name)
                    ReadonlyRow(stringResource(R.string.trx_decimals), hit.decimals.toString())
                }
                probedMeta != null -> {
                    BadgeRow(stringResource(R.string.trx_detected_on_chain), Icons.Filled.Sensors, Color(0xFF4285F4))
                    EditFields(symbolInput, { symbolInput = it }, nameInput, { nameInput = it }, decimalsInput, { decimalsInput = it })
                }
                contractValid && !probing -> {
                    BadgeRow(stringResource(R.string.trx_not_in_catalog), Icons.Filled.Warning, Color(0xFFF29900))
                    EditFields(symbolInput, { symbolInput = it }, nameInput, { nameInput = it }, decimalsInput, { decimalsInput = it })
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(onClick = { addToken() }, enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.walletc_add_token))
            }
            Text(
                stringResource(R.string.trx_add_token_footer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(text, style = MaterialTheme.typography.titleSmall, color = tint)
    }
}

@Composable
private fun ReadonlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditFields(
    symbol: String, onSymbol: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    decimals: String, onDecimals: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = symbol, onValueChange = onSymbol, label = { Text(stringResource(R.string.trx_symbol_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = name, onValueChange = onName, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = decimals,
            onValueChange = onDecimals,
            label = { Text(stringResource(R.string.trx_decimals_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
