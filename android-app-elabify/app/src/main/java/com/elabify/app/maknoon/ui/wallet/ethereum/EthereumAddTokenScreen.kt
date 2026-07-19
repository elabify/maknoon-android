// Add an ERC-20 token to the dashboard. Ported from iOS
// EthereumAddTokenSheet. Lookup order: verified registry (TokenList
// catalog) -> on-chain probe (name/symbol/decimals via eth_call) ->
// manual entry. The contract field is debounced; a hit pre-fills the
// editable fields and surfaces a Verified / Detected / Not-in-catalog
// badge. Tokens are scoped to the chain-wide current built-in network.

package com.elabify.app.maknoon.ui.wallet.ethereum

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.ethereum.ERC20Metadata
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetworkID
import com.elabify.musnad.wallet.ethereum.EthereumToken
import com.elabify.musnad.wallet.ethereum.EthereumTokenLookup
import com.elabify.musnad.wallet.ethereum.EthereumTokenRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun isErc20Contract(s: String): Boolean {
    val t = s.trim()
    if (!t.startsWith("0x") && !t.startsWith("0X")) return false
    val body = t.substring(2)
    return body.length == 40 && body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumAddTokenScreen(prefilledContract: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val walletStore = remember { EthereumStores.walletStore(context) }
    val settings = remember { EthereumStores.settings(context) }
    val tokenStore = remember { EthereumStores.tokenStore(context) }
    val registry = remember { EthereumStores.registry(context) }
    val resolved = remember { resolveCurrentNetwork(context) }
    val builtinNetwork = remember {
        (walletStore.currentNetworkID as? EthereumNetworkID.Builtin)?.network
    }

    var contractInput by remember { mutableStateOf(prefilledContract ?: "") }
    var symbolInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var decimalsInput by remember { mutableStateOf("") }
    var registryHit by remember { mutableStateOf<EthereumTokenRegistry.Entry?>(null) }
    var probedMeta by remember { mutableStateOf<ERC20Metadata?>(null) }
    var probing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val trimmed = contractInput.trim()
    val contractValid = isErc20Contract(trimmed)

    LaunchedEffect(contractInput) {
        val net = builtinNetwork
        registryHit = if (contractValid && net != null) registry.find(net, trimmed) else null
        probedMeta = null
        if (!contractValid || registryHit != null) { probing = false; return@LaunchedEffect }
        probing = true
        delay(350)
        val meta = withContext(Dispatchers.IO) {
            runCatching { EthereumTokenLookup.fetch(trimmed, resolved.rpcURL) }.getOrNull()
        }
        probing = false
        if (meta != null) {
            probedMeta = meta
            if (symbolInput.isBlank()) symbolInput = meta.symbol
            if (nameInput.isBlank()) nameInput = meta.name
            if (decimalsInput.isBlank()) decimalsInput = meta.decimals.toString()
        }
    }

    val decimalsValid = decimalsInput.toIntOrNull()?.let { it in 0..36 } == true
    val canAdd = builtinNetwork != null && contractValid && (registryHit != null || (symbolInput.isNotBlank() && decimalsValid))

    fun addToken() {
        val net = builtinNetwork ?: run { error = "Tokens are only supported on built-in networks."; return }
        if (!contractValid) { error = "Contract address is not a valid 0x-prefixed 20-byte address."; return }
        val hit = registryHit
        val token = if (hit != null) {
            EthereumToken.create(net, trimmed, hit.symbol, hit.name, hit.decimals, curated = true)
        } else {
            val d = decimalsInput.toIntOrNull() ?: run { error = "Decimals must be 0 to 36."; return }
            val sym = symbolInput.trim()
            EthereumToken.create(net, trimmed, sym, nameInput.trim().ifEmpty { sym }, d, curated = false)
        }
        // Scoped to the active wallet on this chain (ADR-0060): a custom token
        // added here does not appear in the user's other wallets. Falls back to a
        // chain-wide add only when there is no active wallet to attribute it to.
        val walletId = walletStore.activeWallet?.id
        if (walletId != null) tokenStore.add(token, walletId) else tokenStore.add(token)
        // The dashboard re-runs its refresh on return (route swap re-composes it),
        // so the new token's balance appears without a manual full sync (ADR-0060).
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.eth_add_erc20_token)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (builtinNetwork == null) {
                Text(stringResource(R.string.eth_tokens_builtin_only_long), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF29900))
            }

            OutlinedTextField(
                value = contractInput,
                onValueChange = { contractInput = it; error = null },
                label = { Text(stringResource(R.string.eth_contract_address_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.eth_network_caption, resolved.displayName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (builtinNetwork?.isTestnet == true) {
                Text(
                    stringResource(R.string.eth_testnet_catalog_note, resolved.displayName),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF29900),
                )
            }

            if (probing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.eth_reading_metadata), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            val hit = registryHit
            when {
                hit != null -> {
                    BadgeRow(stringResource(R.string.eth_badge_verified), Icons.Filled.CheckCircle, Color(0xFF34A853))
                    ReadonlyRow(stringResource(R.string.eth_symbol), hit.symbol)
                    ReadonlyRow(stringResource(R.string.common_name), hit.name)
                    ReadonlyRow(stringResource(R.string.eth_decimals), hit.decimals.toString())
                }
                probedMeta != null -> {
                    BadgeRow(stringResource(R.string.eth_badge_detected), Icons.Filled.Sensors, Color(0xFF4285F4))
                    EditFields(symbolInput, { symbolInput = it }, nameInput, { nameInput = it }, decimalsInput, { decimalsInput = it })
                }
                contractValid && !probing -> {
                    BadgeRow(stringResource(R.string.eth_badge_not_in_catalog), Icons.Filled.Warning, Color(0xFFF29900))
                    EditFields(symbolInput, { symbolInput = it }, nameInput, { nameInput = it }, decimalsInput, { decimalsInput = it })
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(onClick = { addToken() }, enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.walletc_add_token))
            }
            Text(
                stringResource(R.string.eth_custom_token_warning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeRow(text: String, icon: ImageVector, tint: Color) {
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
        OutlinedTextField(value = symbol, onValueChange = onSymbol, label = { Text(stringResource(R.string.eth_symbol_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = name, onValueChange = onName, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = decimals,
            onValueChange = onDecimals,
            label = { Text(stringResource(R.string.eth_decimals_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
