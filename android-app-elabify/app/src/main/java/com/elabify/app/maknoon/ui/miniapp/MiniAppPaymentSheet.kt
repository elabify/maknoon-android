// Native "receive payment" sheet for window.maknoon.payment.receive. Android
// port of MiniAppPaymentSheet.swift, plus its coordinator + PaymentWatcher.
//
// Shows a per-chain payment-request QR (with the coin ticker badged in the
// centre) plus the crypto + fiat totals, and watches the receiving address
// on-chain. When the balance rises by at least the requested amount the sale is
// auto-confirmed; the merchant can also confirm manually or cancel. The customer
// pays from their own wallet by scanning the QR; nothing is signed on this device.
//
// MECHANISM: like the commerce sheet, the live (non-serializable) request is
// stashed in MiniAppPaymentCoordinator keyed by a token carried in the
// ApprovalGate payload. The sheet is stateless with callbacks; it approves the
// gate with { txHash|null, bolt11|null, chain, network, amount, confirmedAt }.

package com.elabify.app.maknoon.ui.miniapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.MiniAppPaymentRequest

/**
 * Stateless "receive payment" sheet. The host wires [onResolve] to approve the
 * ApprovalGate and [onCancel] to cancel it. Watching/QR-rendering inputs are
 * passed in so the composable stays free of store access; the host drives the
 * balance watch via a side effect and pushes [status] / [confirmed] updates.
 */
@Composable
fun MiniAppPaymentSheet(
    request: MiniAppPaymentRequest,
    qrPayload: String?,
    status: String,
    watching: Boolean,
    bolt11: String?,
    onMarkReceived: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    qrRenderer: @Composable (payload: String, ticker: String, isLightning: Boolean) -> Unit = { _, _, _ -> },
) {
    val amountText = "${request.amount.stripTrailingZeros().toPlainString()} ${request.ticker}"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.app_scan_to_pay), style = MaterialTheme.typography.titleMedium)
        Text(
            "${request.networkDisplay} · $amountText",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        request.fiatText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (qrPayload != null) {
            qrRenderer(qrPayload, request.ticker, request.isLightning)
        } else {
            CircularProgressIndicator()
        }

        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (!request.isLightning) {
            Text(
                request.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(onClick = onMarkReceived, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.app_mark_received))
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}
