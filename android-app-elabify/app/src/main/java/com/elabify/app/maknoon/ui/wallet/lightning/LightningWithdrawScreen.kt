// LNURL-withdraw (LUD-03), wiring the engine's LNURL.fetchWithdrawRequest +
// submitWithdraw. The user pastes/scans a withdraw voucher; we fetch its
// parameters, create a BOLT11 invoice on the active account for the chosen
// amount, then submit that invoice to the voucher callback so the customer's
// service PULLs the funds into this account.
//
// The iOS send view scoped withdraw out, but the shared LNURL engine (and the
// chain parity guidance) covers it, so it gets its own Compose entry here.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.lightning.LNURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class WithdrawPhase {
    object Input : WithdrawPhase()
    data class Ready(val req: LNURL.WithdrawRequest) : WithdrawPhase()
    object Submitting : WithdrawPhase()
    object Done : WithdrawPhase()
    data class Error(val message: String) : WithdrawPhase()
}

@Composable
internal fun LightningWithdrawScreen(
    onWithdrawn: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<WithdrawPhase>(WithdrawPhase.Input) }
    var amountSat by remember { mutableStateOf("") }

    fun fail(msg: String) { phase = WithdrawPhase.Error(msg) }

    fun resolve(raw: String) {
        phase = WithdrawPhase.Submitting
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = LNURL.decode(raw)
                    LNURL.fetchWithdrawRequest(url)
                }
            }
            result.onSuccess { req ->
                amountSat = (req.maxWithdrawable / 1_000).toString()
                phase = WithdrawPhase.Ready(req)
            }.onFailure { fail(it.message ?: it.toString()) }
        }
    }

    fun submit(req: LNURL.WithdrawRequest) {
        val account = env.activeAccount ?: return fail("No active Lightning account.")
        val sat = amountSat.trim().toLongOrNull() ?: return
        if (sat <= 0) return
        phase = WithdrawPhase.Submitting
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = env.clientFor(account) ?: error("Password missing. Re-import this account.")
                    val invoice = client.addInvoice(sat, req.defaultDescription ?: "LNURL withdraw")
                    LNURL.submitWithdraw(req, invoice)
                }
            }
            result.onSuccess { phase = WithdrawPhase.Done }
                .onFailure { fail(it.message ?: it.toString()) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.ln_withdraw_lnurl), style = MaterialTheme.typography.headlineSmall)

        when (val p = phase) {
            is WithdrawPhase.Input, is WithdrawPhase.Error -> {
                Text(stringResource(R.string.ln_withdraw_voucher), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(stringResource(R.string.ln_withdraw_voucher_label)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboardText(context)?.let { inputText = it } }) { Text(stringResource(R.string.ln_paste)) }
                }
                Text(
                    stringResource(R.string.ln_withdraw_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = inputText.isNotBlank() && env.activeAccount != null,
                    onClick = { resolve(inputText.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_continue)) }
                if (p is WithdrawPhase.Error) {
                    Text(stringResource(R.string.ln_error_prefix, p.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }

            is WithdrawPhase.Ready -> {
                val req = p.req
                val minSat = req.minWithdrawable / 1_000
                val maxSat = req.maxWithdrawable / 1_000
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        req.defaultDescription?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        Text(stringResource(R.string.ln_min_sat, minSat.toString()), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.ln_max_sat, maxSat.toString()), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = amountSat,
                    onValueChange = { amountSat = it.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.ln_amount_sat)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val sat = amountSat.trim().toLongOrNull()
                val valid = sat != null && sat > 0 &&
                    sat * 1_000 >= req.minWithdrawable && sat * 1_000 <= req.maxWithdrawable
                Button(
                    enabled = valid,
                    onClick = { submit(req) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ln_withdraw)) }
            }

            is WithdrawPhase.Submitting -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.ln_submitting), style = MaterialTheme.typography.bodyMedium)
            }

            is WithdrawPhase.Done -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.ln_withdraw_submitted), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.ln_withdraw_submitted_note),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(
                    onClick = { onWithdrawn(); onBack() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_done)) }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
    }
}
