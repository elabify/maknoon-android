// Merchant side of server-mediated Verify & Pay (ADR-0031). Android port of
// MiniAppCommerceSheet.swift.
//
// Hosts the signed CommerceRequest, shows a SMALL URL QR for the customer to
// scan (from Identity -> Scan verifier), and polls the server for the holder's
// response. The holder has already signed + broadcast the payment and posted
// {presentation, txHash}; the merchant verifies the presentation on-device
// (CommerceMerchantPolicy) and returns the verdict + txHash to the dApp.
//
// MECHANISM: stateless composable. The host resolves the live state from
// MiniAppCommerceCoordinator (token in the ApprovalGate payload), runs the
// host/poll/decrypt/verify pipeline off the main thread, and feeds the sheet
// [status] + [qrPayload]. On a verdict the host approves the gate; the sheet
// only renders and offers Cancel.

package com.elabify.app.maknoon.ui.miniapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.CommerceRequest

/**
 * Stateless merchant "Verify & Pay" sheet. The host drives the host/poll/verify
 * pipeline and updates [status] + [qrPayload]; this composable renders.
 *
 * @param request the signed commerce request (for the headline display).
 * @param qrPayload the short commerce-request URL to render as a QR (null while preparing).
 * @param status the live progress line ("Waiting for the customer to confirm...").
 * @param onCancel cancels the ApprovalGate (rejects the dApp call 4001).
 * @param qrRenderer host-supplied QR drawing (kept out of this slice).
 */
@Composable
fun MiniAppCommerceSheet(
    request: CommerceRequest,
    appTitle: String,
    qrPayload: String?,
    status: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    qrRenderer: @Composable (payload: String) -> Unit = {},
) {
    val terms = request.paymentTerms
    val firstRail = terms.acceptedRails.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The merchant's own name (sent by the dApp), not the catalog title.
        Text(
            request.merchantName ?: appTitle,
            style = MaterialTheme.typography.titleMedium,
        )

        // Merchant-specified crypto amount + network as the headline (testnets
        // have no fiat); fiat only when provided.
        if (firstRail != null && firstRail.amount != null) {
            Text(
                "${firstRail.amount} ${firstRail.asset}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                firstRail.displayNetwork,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (terms.hasFiatValue) {
            Text(
                "= ${terms.fiatAmount} ${terms.fiatCode}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val claims = request.requiredClaims
        if (claims.isNotEmpty()) {
            Text(
                stringResource(R.string.app_requesting, claims.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        if (qrPayload != null) {
            qrRenderer(qrPayload)
            Text(
                stringResource(R.string.app_customer_scans_identity_verifier),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            CircularProgressIndicator()
        }

        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_cancel))
        }
    }
}
