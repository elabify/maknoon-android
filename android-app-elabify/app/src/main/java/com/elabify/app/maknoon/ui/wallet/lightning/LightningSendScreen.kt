// Pay a BOLT11 invoice or an LNURL-pay request via the active LNDHub account,
// 1:1 with iOS LightningSendView.swift. Auto-detects the pasted input form:
//
//   - lnbc / lntb (or lightning:lnbc...) -> BOLT11, sent straight to /payinvoice.
//   - lnurl1 / Lightning Address          -> LNURL-pay: fetch the payRequest,
//     user picks a sat amount inside the issuer's min/max, then the resolved
//     invoice is paid.
//
// LNURL-withdraw lives in its own screen (LightningWithdrawScreen). LNURL-auth
// is out of scope, matching iOS.
//
// Chrome: this screen is invoice-first, so it shares the same SendFormScaffold +
// FormSection grouping as every other chain's Send screen (inset-correct, the
// scaffold owns the status-bar inset and the leading Cancel). The invoice /
// LNURL input is a FormSection with a paste + QR-scan field; the amountless
// LNURL case adds an LNURL-details review section and an amount section; the
// sent state is a review section. The LNDHub /payinvoice and LNURL fetch logic
// is unchanged: only the chrome and section grouping moved.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.settings.AddressBookNetwork
import com.elabify.app.maknoon.ui.settings.ContactsPickerSheet
import com.elabify.app.maknoon.ui.theme.Spacing
import androidx.compose.runtime.LaunchedEffect
import com.elabify.app.maknoon.ui.wallet.common.AmountField
import com.elabify.app.maknoon.ui.wallet.common.authorizeSend
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.FormSection
import com.elabify.app.maknoon.ui.wallet.common.PrimaryActionButton
import com.elabify.app.maknoon.ui.wallet.common.RecipientField
import com.elabify.app.maknoon.ui.wallet.common.ReviewRow
import com.elabify.app.maknoon.ui.wallet.common.SendFormScaffold
import com.elabify.musnad.wallet.lightning.LNURL
import com.elabify.musnad.wallet.lightning.PaymentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class SendPhase {
    object Input : SendPhase()
    data class LnurlReady(val req: LNURL.PayRequest) : SendPhase()
    object Sending : SendPhase()
    data class Sent(val result: PaymentResult) : SendPhase()
    data class Error(val message: String) : SendPhase()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightningSendScreen(
    onPaid: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<SendPhase>(SendPhase.Input) }
    var lnurlAmountSat by remember { mutableStateOf("") }
    var lnurlComment by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    // BTC fiat unit price for the sats fiat sub-caption (Lightning is custodial
    // BTC, always mainnet). Null when reference prices are disabled / unavailable.
    var btcFiatUnit by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { btcFiatUnit = FiatReference.unitPrice("bitcoin") }

    fun fail(msg: String) { phase = SendPhase.Error(msg) }

    // Pay a resolved BOLT11 string.
    fun payBolt11(invoice: String) {
        val account = env.activeAccount ?: return fail("No active Lightning account.")
        scope.launch {
            // Fresh biometric / device-credential before sending funds (ADR-0045
            // Authorization invariant). Lightning is custodial (LNDHub), no seed.
            if (!authorizeSend(context, "Lightning")) return@launch
            phase = SendPhase.Sending
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = env.clientFor(account) ?: error("Password missing. Re-import this account.")
                    client.payInvoice(invoice)
                }
            }
            result.onSuccess { phase = SendPhase.Sent(it) }
                .onFailure { fail(it.message ?: it.toString()) }
        }
    }

    // Resolve an LNURL / Lightning Address into a payRequest.
    fun resolveLnurl(raw: String) {
        phase = SendPhase.Sending
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = LNURL.decode(raw)
                    LNURL.fetchPayRequest(url)
                }
            }
            result.onSuccess { req ->
                lnurlAmountSat = (req.minSendable / 1_000).toString()
                phase = SendPhase.LnurlReady(req)
            }.onFailure { fail(it.message ?: it.toString()) }
        }
    }

    // Fetch the LNURL invoice for the chosen amount, then pay it.
    fun payLnurl(req: LNURL.PayRequest) {
        val account = env.activeAccount ?: return fail("No active Lightning account.")
        val sat = lnurlAmountSat.trim().toLongOrNull() ?: return
        if (sat <= 0) return
        scope.launch {
            // Fresh biometric / device-credential before sending funds (ADR-0045).
            if (!authorizeSend(context, "Lightning")) return@launch
            phase = SendPhase.Sending
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val invoice = LNURL.fetchInvoice(
                        payRequest = req,
                        amountSat = sat,
                        comment = lnurlComment.ifBlank { null },
                    )
                    val client = env.clientFor(account) ?: error("Password missing. Re-import this account.")
                    client.payInvoice(invoice, amountSat = null)
                }
            }
            result.onSuccess { phase = SendPhase.Sent(it) }
                .onFailure { fail(it.message ?: it.toString()) }
        }
    }

    fun proceed() {
        var s = inputText.trim()
        if (s.lowercase().startsWith("lightning:")) s = s.substring("lightning:".length)
        val lower = s.lowercase()
        when {
            lower.startsWith("lnbc") || lower.startsWith("lntb") -> payBolt11(s)
            lower.startsWith("lnurl") || isLightningAddress(s) -> resolveLnurl(s)
            else -> fail(
                "Doesn't look like a BOLT11 invoice, LNURL, or Lightning Address. " +
                    "Expected lnbc…, lntb…, lnurl1…, or you@domain.tld.",
            )
        }
    }

    SendFormScaffold(onCancel = onBack, title = stringResource(R.string.ln_send_lightning)) {
        when (val p = phase) {
            is SendPhase.Input, is SendPhase.Error -> {
                // Invoice / LNURL input. Invoice-first: paste or scan a BOLT11
                // invoice, an LNURL, or a Lightning Address.
                FormSection(header = stringResource(R.string.ln_invoice_or_lnurl)) {
                    RecipientField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        onPaste = { clipboardText(context)?.let { inputText = it } },
                        onScanQr = { showScanner = true },
                        onPickContact = { showContacts = true },
                        placeholder = stringResource(R.string.ln_send_placeholder),
                        supporting = {
                            Text(
                                stringResource(R.string.ln_send_supporting),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }

                if (p is SendPhase.Error) {
                    FormSection {
                        Text(
                            stringResource(R.string.ln_error_prefix, p.message),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                PrimaryActionButton(
                    text = stringResource(R.string.common_continue),
                    onClick = { proceed() },
                    enabled = inputText.isNotBlank() && env.activeAccount != null,
                )
            }

            is SendPhase.LnurlReady -> {
                val req = p.req
                val minSat = req.minSendable / 1_000
                val maxSat = req.maxSendable / 1_000
                val desc = LNURL.extractDescription(req.metadata) ?: stringResource(R.string.ln_lightning_payment)

                FormSection(header = stringResource(R.string.ln_lnurl_details)) {
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                    ReviewRow(label = stringResource(R.string.ln_min), value = stringResource(R.string.ln_sat_value, minSat.toString()), mono = true)
                    ReviewRow(label = stringResource(R.string.ln_max), value = stringResource(R.string.ln_sat_value, maxSat.toString()), mono = true)
                }

                // Amount section for the amountless LNURL case: the payer picks
                // a sat amount inside the issuer's min/max.
                FormSection(header = stringResource(R.string.walletc_amount)) {
                    AmountField(
                        value = lnurlAmountSat,
                        onValueChange = { lnurlAmountSat = it.filter { c -> c.isDigit() } },
                        onMax = null,
                        balanceLabel = null,
                        unitLabel = "sat",
                        secondaryLabel = run {
                            val s = lnurlAmountSat.trim().toLongOrNull()?.takeIf { it > 0 }
                            val u = btcFiatUnit
                            if (s != null && u != null) "≈ " + FiatReference.format(s / 100_000_000.0 * u) else null
                        },
                    )
                    if ((req.commentAllowed ?: 0) > 0) {
                        OutlinedTextField(
                            value = lnurlComment,
                            onValueChange = { if (it.length <= req.commentAllowed!!) lnurlComment = it },
                            label = { Text(stringResource(R.string.ln_comment_optional_max, req.commentAllowed.toString())) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                val sat = lnurlAmountSat.trim().toLongOrNull()
                val valid = sat != null && sat > 0 && sat * 1_000 >= req.minSendable && sat * 1_000 <= req.maxSendable
                PrimaryActionButton(
                    text = stringResource(R.string.ln_pay),
                    onClick = { payLnurl(req) },
                    enabled = valid,
                )
            }

            is SendPhase.Sending -> {
                FormSection {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ln_routing_payment), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            is SendPhase.Sent -> {
                val r = p.result
                FormSection(header = stringResource(R.string.ln_paid)) {
                    Text(stringResource(R.string.ln_paid), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    r.amountSat?.let { ReviewRow(label = stringResource(R.string.walletc_amount), value = stringResource(R.string.ln_sat_value, formatSats(it)), mono = true) }
                    r.feeSat?.let { ReviewRow(label = stringResource(R.string.ln_fee), value = stringResource(R.string.ln_sat_value, formatSats(it)), mono = true) }
                    ReviewRow(label = stringResource(R.string.ln_preimage), value = stringResource(R.string.ln_preimage_value, r.preimage.take(16)), mono = true)
                }
                PrimaryActionButton(
                    text = stringResource(R.string.common_done),
                    onClick = { onPaid(); onBack() },
                )
            }
        }
    }

    if (showContacts) {
        // Pick a saved Lightning Address / LNURL from the address book (parity
        // with every other chain's Send screen, ADR-0033). Lightning has no
        // static own-wallet receive address, so no ownWallets section.
        ContactsPickerSheet(
            network = AddressBookNetwork.LIGHTNING,
            onPick = { inputText = it },
            onDismiss = { showContacts = false },
        )
    }

    if (showScanner) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showScanner = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(stringResource(R.string.ln_scan_invoice_or_lnurl), style = MaterialTheme.typography.titleMedium)
                MiniAppQrScanner(
                    continuous = false,
                    onCode = { code ->
                        inputText = code.trim()
                        showScanner = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(20.dp)),
                )
                OutlinedButton(onClick = { showScanner = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}
