// window.maknoon.merchant, lets a merchant dApp (the POS) render its OWN
// self-contained settings: its verifier identity + verification status. Android
// port of MerchantBridgeHandler.swift. Everything is scoped to this installation
// (installedAppId), so one dApp can never read another's merchant data.
//
//   merchant.getIdentity() -> { did, publicKey, verified }
//       Provisions the per-install verifier key on first call. `verified` is a
//       live lookup against the curated verifier registry (true once Elabify has
//       registered this DID). The dApp shows the DID + key for the merchant to
//       send to sales@elabify.com.
//
// Receipts + the rest of the merchant's settings are the dApp's own, kept via
// window.maknoon.storage; only the verifier key is native. No grant required
// (scoped per-install).

package com.elabify.app.maknoon.miniapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MerchantBridgeHandler(
    private val ctx: CommerceHolderContext,
    private val installedAppId: String,
) : MiniAppNamespaceHandler {

    override val namespace = "merchant"
    override val requiredPermission: String? = null   // scoped per-install; no extra grant

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "merchant.getIdentity" -> getIdentity()
        else -> throw MiniAppBridgeError.unsupported("merchant.$method")
    }

    private suspend fun getIdentity(): String = withContext(Dispatchers.IO) {
        val did = ctx.merchantIdentity.ensureProvisioned(installedAppId)
        val pub = ctx.merchantIdentity.publicKeyHex(installedAppId) ?: ""
        val verified = ctx.registryLookup(did) != null
        JSONObject()
            .put("did", did)
            .put("publicKey", pub)
            .put("verified", verified)
            .toString()
    }
}
