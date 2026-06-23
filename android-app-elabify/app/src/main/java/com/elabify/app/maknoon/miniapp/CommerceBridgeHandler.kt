// window.maknoon.commerce.collectAndCharge, the unified verify-and-pay bridge
// (ADR-0031). Android port of CommerceBridgeHandler.swift.
//
// One call replaces the POS's separate identity.collect + payment.receive: it
// builds + signs a CommerceRequest from the dApp's params, opens the native
// merchant sheet (engage holder, verify offline, broadcast), and returns a
// single verdict { decision, reason, missing, message, disclosed, txHash }.
//
// The handler follows the foundation contract: string-in (argsJson) /
// string-out (resultJson). The native sheet is requested through the
// ApprovalGate (kind = "commerce"); the gate suspends this coroutine until the
// sheet approves with the verdict JSON (or cancels -> 4001). The live,
// non-serializable request state travels through the coordinator side-table.

package com.elabify.app.maknoon.miniapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CommerceBridgeHandler(
    private val ctx: CommerceHolderContext,
    private val appTitle: String,
    private val installedAppId: String,
    private val coordinator: MiniAppCommerceCoordinator,
    private val gate: ApprovalGate,
) : MiniAppNamespaceHandler {

    override val namespace = "commerce"
    override val requiredPermission: String? = "payment"

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "collectAndCharge" -> collectAndCharge(argsJson)
        else -> throw MiniAppBridgeError.unsupported("commerce.$method")
    }

    private suspend fun collectAndCharge(argsJson: String): String {
        val p = parseObject(argsJson)
            ?: throw MiniAppBridgeError.invalidParams("commerce.collectAndCharge expects an object")
        val identity = p.optJSONObject("identity") ?: JSONObject()
        val payment = p.optJSONObject("payment") ?: JSONObject()
        val rails = parseRails(payment.optJSONArray("acceptedRails"))
        if (rails.isEmpty()) {
            throw MiniAppBridgeError.invalidParams("payment.acceptedRails is required")
        }
        val lane = CommerceLane.fromRaw(p.optStringOrNull("lane"))
        val merchantName = p.optStringOrNull("merchantName") ?: appTitle

        // Build + sign the request off the main thread (registry + crypto).
        val built = withContext(Dispatchers.IO) {
            // Lightning is invoice-based: a "lightning" rail arrives carrying the
            // merchant's Lightning ACCOUNT id (not a payable destination). Mint a
            // fresh BOLT11 for the amount on that account here, on the merchant
            // device, so the holder pays a real invoice. Other chains pass through.
            val resolvedRails = rails.map { rail ->
                if (rail.chain != "lightning") {
                    rail
                } else {
                    // Lightning rail amounts are in SATOSHIS (integer), not BTC,
                    // the PoS sends sats for the Lightning leg. (Treating it as BTC
                    // would 100,000,000x the invoice and the provider rejects it.)
                    val sats = rail.amount?.toDoubleOrNull()?.toLong()
                        ?.takeIf { it > 0 }
                        ?: throw MiniAppBridgeError.invalidParams("Lightning rail needs a positive satoshi amount.")
                    val bolt11 = ctx.mintLightningInvoice(rail.address, sats, merchantName)
                    rail.copy(address = bolt11)
                }
            }
            CommerceRequestFactory.build(
                ctx = ctx,
                installedAppId = installedAppId,
                merchantName = merchantName,
                schema = identity.optStringOrNull("schema"),
                requiredClaims = stringList(identity.optJSONArray("requiredClaims")),
                issuers = identity.optJSONArray("issuers")?.let { stringList(it) },
                identityMaxAgeSec = optLongOrNull(identity, "maxAgeSec"),
                fiatAmount = payment.optStringOrNull("fiatAmount") ?: "0",
                fiatCode = payment.optStringOrNull("fiatCode") ?: "USD",
                acceptedRails = resolvedRails,
                reference = payment.optStringOrNull("reference"),
                floorMinor = optLongOrNull(payment, "floorMinor"),
                lane = lane,
            )
        }

        // Stash the live state (non-serializable keypair) and request the sheet.
        val token = coordinator.stash(merchantName, built.request, built.responseKeypair, ctx)
        val payload = JSONObject()
            .put("token", token)
            .put("merchantName", merchantName)
            .toString()
        // Suspends here until the merchant sheet approves (verdict JSON) or
        // cancels (throws userRejected -> 4001). The returned string is already
        // a JSON object; it becomes the resolved promise value verbatim.
        return gate.request(kind = "commerce", payloadJson = payload, appTitle = merchantName)
    }

    private fun parseRails(arr: JSONArray?): List<PaymentRail> = PaymentRail.listFromJson(arr)

    private fun parseObject(argsJson: String): JSONObject? =
        try { JSONObject(argsJson) } catch (_: Exception) { null }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun optLongOrNull(o: JSONObject, key: String): Long? =
        if (o.has(key) && !o.isNull(key)) o.optLong(key) else null
}
