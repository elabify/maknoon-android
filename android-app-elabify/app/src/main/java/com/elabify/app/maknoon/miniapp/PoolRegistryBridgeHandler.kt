// "pools" namespace (window.maknoon.pools.list). Android port of the iOS
// PoolRegistryBridgeHandler.swift.
//
// pools.list({ issuerUrl, caip2? }) does the one thing the mini-app sandbox
// cannot do itself: a network read. The WebView blocks fetch/XHR (connect-src
// 'none' on iOS; the Android host mirrors the no-network posture), so every
// network hop goes through this bridge. Here we GET the Access Issuer's public
// GET /v1/pools (the operator-maintained credential-gated pool registry,
// ADR-0058) and hand the parsed JSON straight back to the page, which uses it to
// populate its pool picker instead of hardcoding one pool.
//
// Public read, no user data, no key material, no consent step. To keep the
// sandbox's egress tied to a capability the app already holds, it requires the
// wallet.ethereum.read grant (parity with iOS, which only registers this handler
// for EVM-capable apps).

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PoolRegistryBridgeHandler : MiniAppNamespaceHandler {

    override val namespace = "pools"

    // Public endpoint, but tie the network egress to EVM-read access.
    override val requiredPermission: String? = "wallet.ethereum.read"

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "pools.list" -> list(argsJson)
        else -> throw MiniAppBridgeError.unsupported("pools.$method")
    }

    private suspend fun list(argsJson: String): String {
        val opts = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: throw MiniAppBridgeError.invalidParams("pools.list requires { issuerUrl }")
        val issuerUrl = opts.optStringOrNull("issuerUrl")
            ?: throw MiniAppBridgeError.invalidParams("pools.list requires { issuerUrl }")
        val base = issuerUrl.trimEnd('/')
        var url = "$base/v1/pools"
        // Optional CAIP-2 filter. Encode the colon so it stays one query value
        // (matches the server's ?caip2=eip155:NNN handling).
        val caip2 = opts.optStringOrNull("caip2")
        if (!caip2.isNullOrEmpty()) url += "?caip2=" + caip2.replace(":", "%3A")

        val body = withContext(Dispatchers.IO) {
            try {
                MaknoonHttp().getJson(url)
            } catch (e: NetworkException) {
                throw MiniAppBridgeError.internalError("pools.list failed (${e.status})")
            } catch (e: MiniAppBridgeError) {
                throw e
            } catch (e: Throwable) {
                throw MiniAppBridgeError.internalError(e.message ?: "pools.list failed")
            }
        }
        // Validate the response parses as an object, then return the { v, pools } payload.
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: throw MiniAppBridgeError.internalError("pools.list: malformed registry response")
        return obj.toString()
    }
}
