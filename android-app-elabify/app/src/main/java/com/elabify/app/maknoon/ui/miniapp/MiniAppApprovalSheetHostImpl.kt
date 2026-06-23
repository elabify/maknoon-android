// Concrete MiniAppApprovalSheetHost: the single seam the host uses to render the
// native confirmation sheet for whatever ApprovalRequest is pending on the
// ApprovalGate. A when over request.kind, mirroring the iOS single-sheet
// coordinators. Each branch parses request.payloadJson into the sheet's inputs
// and resolves the gate via request.approve(resultJson) / request.cancel().
//
// kind routing:
//   "identity" | "collect" | "scan"  -> IdentityMiniAppSheets (identity slice)
//   "web3"                           -> web3ApprovalSheetHost  (web3 slice)
//   "payment"                        -> payment receive sheet (MiniAppPaymentSheet)
//   "commerce"                       -> merchant Verify & Pay (MiniAppCommerceSheet)
//   "pay"                            -> payer Verify & Pay (CommercePaySheet)
//   else                             -> cancel cleanly (4001) so the JS promise
//                                       rejects rather than hanging.
//
// The identity + web3 slices already ship their own composable hosts
// (IdentityMiniAppSheets, web3ApprovalSheetHost); we forward to them so this
// file does not duplicate their biometric / camera logic. The payment +
// commerce branches resolve the live, non-serializable request from the
// per-host coordinator side-tables (token carried in the gate payload) and
// drive the blocking pipeline off the main thread.

package com.elabify.app.maknoon.ui.miniapp

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.ApprovalRequest
import com.elabify.app.maknoon.miniapp.MiniAppCommerceCoordinator
import com.elabify.app.maknoon.miniapp.MiniAppPaymentCoordinator
import com.elabify.app.maknoon.miniapp.MiniAppPaymentRequest
import com.elabify.app.maknoon.ui.components.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The host-wired [MiniAppApprovalSheetHost]. Built per host with the same
 * coordinator instances the [DefaultMiniAppHandlerFactory] handed the payment /
 * commerce handlers, so the tokens the handlers stashed resolve here.
 */
@OptIn(ExperimentalMaterial3Api::class)
class MiniAppApprovalSheetHostImpl(
    private val paymentCoordinator: MiniAppPaymentCoordinator,
    private val commerceCoordinator: MiniAppCommerceCoordinator,
) : MiniAppApprovalSheetHost {

    @Composable
    override fun Sheet(request: ApprovalRequest, onDismiss: () -> Unit) {
        when (request.kind) {
            // Identity slice owns identity / collect / scan; fall back to web3 +
            // our own payment/commerce branches for anything it does not handle.
            "identity", "collect", "scan" -> IdentityMiniAppSheets(
                request = request,
                onDismiss = onDismiss,
                fallback = { req, dismiss -> dispatchNonIdentity(req, dismiss) },
            )
            else -> dispatchNonIdentity(request, onDismiss)
        }
    }

    @Composable
    private fun dispatchNonIdentity(request: ApprovalRequest, onDismiss: () -> Unit) {
        when (request.kind) {
            "web3" -> web3ApprovalSheetHost.Sheet(request, onDismiss)
            "payment" -> PaymentReceiveModal(request, onDismiss)
            "commerce" -> CommerceMerchantModal(request, onDismiss)
            "pay" -> CommercePayModal(request, onDismiss)
            else -> LaunchedEffect(request.id) {
                // Unknown kind: reject cleanly so the JS promise gets a 4001
                // instead of hanging on the suspended handler.
                request.cancel()
                onDismiss()
            }
        }
    }

    // ---- payment.receive ("payment") ----

    @Composable
    private fun PaymentReceiveModal(request: ApprovalRequest, onDismiss: () -> Unit) {
        val token = remember(request.id) {
            runCatching { JSONObject(request.payloadJson).optString("token") }.getOrNull()
        }
        val pending: MiniAppPaymentRequest? = remember(token) { token?.let { paymentCoordinator.peek(it) } }
        if (token == null || pending == null) {
            LaunchedEffect(request.id) { request.cancel(); onDismiss() }
            return
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        // The QR payload is the on-chain payment URI the handler already built.
        // Lightning has no URI yet on Android (the BOLT11-invoice path is not
        // wired into the mini-app host), so we show the account info only.
        val qrPayload = pending.uri.takeIf { it.isNotEmpty() }
        val status = if (pending.isLightning) {
            stringResource(R.string.app_payment_status_lightning)
        } else {
            stringResource(R.string.app_payment_status_watching)
        }

        ModalBottomSheet(
            onDismissRequest = { request.cancel(); onDismiss() },
            sheetState = sheetState,
        ) {
            MiniAppPaymentSheet(
                request = pending,
                qrPayload = qrPayload,
                status = status,
                // Auto-watching the receiving address on-chain requires a per-
                // chain balance reader the mini-app host does not yet wire; the
                // merchant confirms manually via Mark received for now.
                watching = false,
                bolt11 = null,
                onMarkReceived = {
                    paymentCoordinator.take(token)
                    request.approve(receiveVerdict(pending))
                    onDismiss()
                },
                onCancel = {
                    paymentCoordinator.take(token)
                    request.cancel()
                    onDismiss()
                },
                qrRenderer = { payload, _, _ -> QrCode(content = payload) },
            )
        }
    }

    /** The { txHash, bolt11, chain, network, amount, confirmedAt } the dApp expects.
     *  txHash is null on a manual confirm (no on-chain watch ran). */
    private fun receiveVerdict(req: MiniAppPaymentRequest): String = JSONObject().apply {
        put("txHash", JSONObject.NULL)
        put("bolt11", JSONObject.NULL)
        put("chain", req.chain)
        put("network", req.networkRaw ?: JSONObject.NULL)
        put("amount", req.amount.stripTrailingZeros().toPlainString())
        put("confirmedAt", System.currentTimeMillis() / 1000)
    }.toString()

    // ---- commerce.collectAndCharge ("commerce") ----

    @Composable
    private fun CommerceMerchantModal(request: ApprovalRequest, onDismiss: () -> Unit) {
        val token = remember(request.id) {
            runCatching { JSONObject(request.payloadJson).optString("token") }.getOrNull()
        }
        val pending = remember(token) { token?.let { commerceCoordinator.peek(it) } }
        if (token == null || pending == null) {
            LaunchedEffect(request.id) { request.cancel(); onDismiss() }
            return
        }

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val preparingLabel = stringResource(R.string.app_preparing)
        var status by remember(request.id) { mutableStateOf(preparingLabel) }
        var qrPayload by remember(request.id) { mutableStateOf<String?>(null) }
        var cancelled by remember(request.id) { mutableStateOf(false) }

        // Drive the merchant host/poll/verify pipeline off the main thread. It
        // updates status + QR and returns the verdict JSON (or throws). The
        // pipeline reaches into the CommerceHolderContext; with the current stub
        // context its transport steps throw, which we surface as a clean DENY so
        // the dApp call resolves rather than hanging.
        LaunchedEffect(request.id) {
            val verdict = withContext(Dispatchers.IO) {
                runCatching {
                    commerceCoordinator.runMerchantFlow(
                        pending = pending,
                        shouldContinue = { !cancelled },
                        onStatus = { status = it },
                        onQr = { qrPayload = it },
                    )
                }.getOrElse { e ->
                    JSONObject()
                        .put("decision", "DENY")
                        .put("reason", "verification_unavailable")
                        .put("message", e.message ?: "Verify & Pay is unavailable on this device.")
                        .toString()
                }
            }
            commerceCoordinator.take(token)
            request.approve(verdict)
            onDismiss()
        }

        ModalBottomSheet(
            onDismissRequest = {
                cancelled = true
                commerceCoordinator.take(token)
                request.cancel()
                onDismiss()
            },
            sheetState = sheetState,
        ) {
            MiniAppCommerceSheet(
                request = pending.request,
                appTitle = pending.appTitle,
                qrPayload = qrPayload,
                status = status,
                onCancel = {
                    cancelled = true
                    commerceCoordinator.take(token)
                    request.cancel()
                    onDismiss()
                },
                qrRenderer = { payload -> QrCode(content = payload) },
            )
        }
    }

    // ---- payer Verify & Pay ("pay") ----

    @Composable
    private fun CommercePayModal(request: ApprovalRequest, onDismiss: () -> Unit) {
        // No mini-app handler emits kind == "pay": the payer (holder) Verify &
        // Pay flow is driven from Identity -> Scan verifier, not the mini-app
        // bridge. CommercePaySheet also needs a live CommerceRequest + driver the
        // mini-app gate payload does not carry. We therefore reject cleanly so an
        // unexpected "pay" request never hangs. Wire this if the payer flow ever
        // routes through the mini-app host.
        LaunchedEffect(request.id) {
            request.cancel()
            onDismiss()
        }
    }
}
