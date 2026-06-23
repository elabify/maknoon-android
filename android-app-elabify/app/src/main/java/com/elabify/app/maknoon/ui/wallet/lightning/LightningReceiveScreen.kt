// Create a BOLT11 invoice on the active LNDHub account, 1:1 with iOS
// LightningReceiveView.swift. User supplies amount (sats; 0 = amountless
// invoice) and optional memo; the returned payment_request is shown as a QR
// plus copyable text. addInvoice runs off the main thread.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.wallet.common.ReceiveScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LightningReceiveScreen(
    onCreated: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }
    val scope = rememberCoroutineScope()

    var amountSat by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var invoice by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }

    ReceiveScaffold(onBack = onBack, title = stringResource(R.string.ln_receive_lightning)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val pr = invoice
        if (pr == null) {
            Text(stringResource(R.string.ln_invoice), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = amountSat,
                onValueChange = { amountSat = it.filter { c -> c.isDigit() } },
                label = { Text(stringResource(R.string.ln_amount_sat)) },
                placeholder = { Text(stringResource(R.string.ln_amount_any)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text(stringResource(R.string.ln_memo_optional)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.ln_amountless_note),
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                enabled = !creating && env.activeAccount != null,
                onClick = {
                    val account = env.activeAccount ?: run { lastError = "No active account."; return@Button }
                    val sats = amountSat.trim().toLongOrNull() ?: 0L
                    creating = true
                    lastError = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val client = env.clientFor(account)
                                    ?: error("Password missing for this account. Re-import it.")
                                client.addInvoice(sats, memo)
                            }
                        }
                        result.onSuccess { invoice = it }
                            .onFailure { lastError = it.message ?: it.toString() }
                        creating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.ln_create_invoice))
                }
            }
        } else {
            Text(stringResource(R.string.walletc_scan_to_pay), style = MaterialTheme.typography.titleSmall)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCode(content = pr, modifier = Modifier.size(240.dp))
            }
            Text(stringResource(R.string.ln_bolt11), style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                Text(
                    pr,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { copyToClipboard(context, "Lightning invoice", pr) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ln_copy_invoice)) }
            Button(
                onClick = { onCreated(); onBack() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.common_done)) }
        }

            lastError?.let {
                Text(stringResource(R.string.ln_error_prefix, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
