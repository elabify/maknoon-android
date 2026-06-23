// Bridges the commerce bridge handler to the native "Verify & Pay" sheet and
// suspends the dApp's commerce.collectAndCharge call until it resolves
// (ADR-0031). Android port of MiniAppCommerceCoordinator.swift.
//
// MECHANISM DIVERGENCE FROM iOS: iOS used a CheckedContinuation owned by an
// @Observable coordinator. On Android the suspend/resume is the host's
// ApprovalGate. But the ApprovalGate payload is a JSON STRING and the sheet
// needs the LIVE request (the CommerceRequest plus the non-serializable
// ephemeral TransportHolder + the CommerceHolderContext). So this coordinator is
// a small side-table: the handler stashes a Pending keyed by an opaque token,
// puts only the token + display fields in the gate payload, and the sheet looks
// the Pending back up by token. The merchant sheet does the hosting/polling/
// verification work and approves the gate with the final verdict JSON.

package com.elabify.app.maknoon.miniapp

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

class MiniAppCommerceCoordinator(
    /** Builds an HTTP transport (shared OkHttp by default in the impl). */
    val transport: CommerceTransport = CommerceTransport(),
) {
    /** The live, non-serializable state a commerce sheet needs, keyed by token. */
    data class Pending(
        val token: String,
        val appTitle: String,
        val request: CommerceRequest,
        // Ephemeral keypair (merchant) the holder seals its response to; held
        // only on this device to decrypt the polled response (server-blind).
        val responseKeypair: TransportHolder,
        val ctx: CommerceHolderContext,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Stash a live request and return its lookup token (goes in the gate payload). */
    fun stash(
        appTitle: String,
        request: CommerceRequest,
        responseKeypair: TransportHolder,
        ctx: CommerceHolderContext,
    ): String {
        val token = java.util.UUID.randomUUID().toString()
        pending[token] = Pending(token, appTitle, request, responseKeypair, ctx)
        return token
    }

    /** Resolve and consume a stashed request by token. */
    fun take(token: String): Pending? = pending.remove(token)

    /** Peek without consuming (the sheet reads display fields before finishing). */
    fun peek(token: String): Pending? = pending[token]

    /**
     * The merchant-side pipeline the sheet runs (host -> render QR -> poll ->
     * decrypt -> verify), driven by the host so the sheet stays stateless. All
     * steps are blocking; run on Dispatchers.IO. Updates [onStatus]/[onQr] for
     * the progress UI; returns the final verdict JSON string on success, or
     * throws CommerceTransportException on a fatal transport error.
     *
     * The returned string is exactly what the dApp's collectAndCharge promise
     * resolves to (it goes straight into ApprovalGate.approve):
     *   { decision: "GRANT"|"DENY", reason, missing[], message, txHash, disclosed{} }
     */
    fun runMerchantFlow(
        pending: Pending,
        pollIntervalMs: Long = 2_000,
        shouldContinue: () -> Boolean = { true },
        onStatus: (String) -> Unit,
        onQr: (payload: String) -> Unit,
    ): String {
        val base = pending.ctx.dropHost
        // 1. Host the signed request; surface its short URL as a small QR.
        val id = transport.hostRequest(base, pending.request)
        onQr(base.trimEnd('/') + "/v1/commerce-request/$id")
        onStatus("Waiting for the customer to confirm...")

        // 2. Poll for the holder's SEALED response.
        while (shouldContinue()) {
            Thread.sleep(pollIntervalMs)
            if (!shouldContinue()) break
            val env = try { transport.pollResult(base, id) } catch (_: Exception) { null } ?: continue
            val resp = try {
                val opened = CommerceSeal.open(env, pending.responseKeypair)
                CommerceServerResponse.fromJson(opened)
            } catch (_: Exception) {
                onStatus("Received a response but couldn't decrypt it. Ask the customer to try again.")
                continue
            }
            return finish(resp, pending)
        }
        // Cancelled before a response arrived; the gate cancel path handles 4001.
        throw CommerceTransportException("Verify & Pay was cancelled before the customer responded.")
    }

    /**
     * The holder already broadcast; verify identity on-device + record txHash.
     * requestId keying + the presentation's echoed verifierRequest bind the
     * response, so reuse the merchant's own nonce for the policy check.
     */
    private fun finish(resp: CommerceServerResponse, pending: Pending): String {
        val terms = pending.request.paymentTerms
        val cr = CommerceResponse(
            v = 1,
            presentation = resp.presentation,
            payment = CommercePayment(rail = resp.rail, signedTx = null, settlementRef = resp.txHash),
            nonce = terms.nonce,
        )
        val verdict = CommerceMerchantPolicy.evaluate(cr, pending.request, pending.ctx)

        val disclosed = JSONObject()
        resp.presentation.optJSONArray("disclosed")?.let { arr ->
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val key = item.optStringOrNull("key") ?: continue
                disclosed.put(key, if (item.isNull("value")) JSONObject.NULL else item.opt("value"))
            }
        }

        return JSONObject().apply {
            put("decision", if (verdict.granted) "GRANT" else "DENY")
            put("reason", verdict.reason)
            put("missing", JSONArray(verdict.missing))
            put("message", verdict.message ?: "")
            put("txHash", resp.txHash)
            put("disclosed", disclosed)
        }.toString()
    }
}
