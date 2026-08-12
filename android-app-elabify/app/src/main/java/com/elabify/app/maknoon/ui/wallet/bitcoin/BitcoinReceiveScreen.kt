// Receive screen, ported from iOS BitcoinReceiveView: next-unused external
// address, a QR for the bitcoin: URI (with optional amount + label), a
// tap-to-copy bech32 string, and the optional label / amount form. The
// address is revealed via the engine's nextReceiveAddress() (advances the
// keychain + persists, like iOS).

package com.elabify.app.maknoon.ui.wallet.bitcoin

import com.elabify.app.maknoon.ui.common.userMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.wallet.common.ReceiveScaffold
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinReceiveScreen(
    env: BitcoinWalletEnv,
    active: BitcoinWalletDescriptor?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var address by remember { mutableStateOf<String?>(null) }
    var index by remember { mutableStateOf<Long?>(null) }
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(active?.id) {
        val descriptor = active ?: return@LaunchedEffect
        val res = withContext(Dispatchers.IO) {
            runCatching {
                val words = loadRecoveryWords(context)
                val e = BitcoinWalletEngine.open(descriptor, env.filesDirPath, words, loadBip39Passphrase(context))
                // Show the current unused address (non-advancing); opening the
                // Receive screen must not burn through keychain indices.
                val info = e.nextUnusedReceiveAddress()
                info.address.toString() to info.index.toLong()
            }
        }
        res.onSuccess { address = it.first; index = it.second }
            .onFailure { error = context.getString(R.string.btc_could_not_derive_address, it.userMessage(context)) }
    }

    val uri = remember(address, amount, label) { address?.let { bitcoinUri(it, amount, label) } }

    ReceiveScaffold(onBack = onClose) { padding ->
        val addr = address
        if (addr == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else {
                    CircularProgressIndicator()
                }
            }
            return@ReceiveScaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            QrCode(content = uri ?: addr, modifier = Modifier.size(240.dp))
            Text(
                stringResource(R.string.btc_scan_or_copy),
                style = MaterialTheme.typography.bodySmall,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        index?.let { stringResource(R.string.btc_address_index, it.toString()) }
                            ?: stringResource(R.string.walletc_address),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            addr,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(addr))
                            copied = true
                        }) {
                            Icon(
                                if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.btc_copy_address),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text(stringResource(R.string.btc_label_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text(stringResource(R.string.btc_amount_btc_optional)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun bitcoinUri(address: String, amountBtc: String, label: String): String {
    var uri = "bitcoin:$address"
    val query = ArrayList<String>()
    amountBtc.trim().toDoubleOrNull()?.takeIf { it > 0 }?.let { query.add("amount=$it") }
    if (label.trim().isNotEmpty()) {
        query.add("label=" + URLEncoder.encode(label.trim(), "UTF-8"))
    }
    if (query.isNotEmpty()) uri += "?" + query.joinToString("&")
    return uri
}
