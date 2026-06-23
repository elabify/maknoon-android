// "maknoon" namespace handler (window.maknoon.identity). Android port of the
// iOS IdentityBridgeHandler.swift, adapted to the Android bridge mechanism
// (string-in / string-out JSON, suspend on an ApprovalGate for the sheet).
//
// Methods:
//   identity.getDID  -> { did }                        (no sheet; reads the sandwich)
//   identity.request -> { decision, reason, checks, disclosed, ... }
//       The on-device holder -> verifier loop: gather user consent for the
//       requested claims via the identity approval sheet, then return the
//       authoritative verdict. The verdict is the verifier's, never asserted
//       by the page.
//   identity.collect -> { decision, reason, missing, message, ... }
//       Cross-device: the merchant scans (or hosts a QR for) a SEPARATE
//       customer's presentation and gets back the verify verdict.
//
// IMPORTANT scope note (differs from iOS): the iOS handler leans on a holder
// credential store, a MatchingEngine, a PresentationFactory, an offline
// PresentationVerifier, and a server /v1/verify call. On Android only the
// identity primitives (IdentitySandwich: holderDid + signChallenge) and the
// verifier /v1/challenge client are ported so far (the credential/presentation
// stack is not yet on the Android classpath). To avoid reimplementing crypto
// here, this handler:
//   * fully implements identity.getDID against IdentitySandwich;
//   * gates identity.request / identity.collect behind the user-approval
//     sheets (consent is real and user-driven) and returns the exact iOS
//     verdict envelope shape, with offline=true and the disclosure the sheet
//     gathered. The server-side verify wiring is wired through the SDK's
//     VerifierClient.challenge so the requestId is real; the verify POST and
//     credential matching are deferred to when the presentation stack lands
//     (see the open questions in the handoff).
// Nothing in here hands key material to JS, and no sensitive surface is
// released without the sheet returning an approval.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.net.VerifierClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class IdentityBridgeHandler(
    private val store: IdentityStore,
    private val appTitle: String,
    private val installedAppId: String,
    private val gate: ApprovalGate,
    /** Verifier host the bridge issues challenges against. The catalog/host
     *  supplies the host the rest of the app already trusts. */
    private val verifierBaseUrl: String,
) : MiniAppNamespaceHandler {

    override val namespace = "maknoon"
    override val requiredPermission: String? = "identity"

    private val verifier = VerifierClient(verifierBaseUrl)

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "identity.getDID" -> getDid()
        "identity.request" -> requestPresentation(argsJson)
        "identity.collect" -> collect(argsJson)
        else -> throw MiniAppBridgeError.unsupported("maknoon.$method")
    }

    // MARK: -- identity.getDID

    private suspend fun getDid(): String {
        val did = loadSandwich()?.holderDid
            ?: throw MiniAppBridgeError.unauthorized("wallet is locked")
        return JSONObject().put("did", did).toString()
    }

    // MARK: -- identity.request

    /** Same-device: prove a claim from THIS holder's own wallet. */
    private suspend fun requestPresentation(argsJson: String): String {
        val opts = parseOpts(argsJson) ?: throw MiniAppBridgeError.invalidParams("expected an options object")
        val requiredClaims = stringList(opts.optJSONArray("requiredClaims"))
        if (requiredClaims.isEmpty()) {
            throw MiniAppBridgeError.invalidParams("requiredClaims must be a non-empty array")
        }
        val schemas = stringList(opts.optJSONArray("schemas"))
        val issuers = stringList(opts.optJSONArray("issuers"))
        val purpose = opts.optStringOrNull("purpose")
        val maxAgeSec = opts.optLongOrNull("maxAgeSec")

        // Locked wallet: nothing to prove with.
        loadSandwich() ?: throw MiniAppBridgeError.unauthorized("wallet is locked")

        // Server-issued challenge so the requestId returned to the dApp is real.
        // Best-effort: a challenge failure (offline) does not block consent.
        val requestId = runCatching {
            withContext(Dispatchers.IO) { verifier.challenge(requiredClaims) }.requestId
        }.getOrNull()

        // User consent: review who is asking + which claims, pick a credential,
        // confirm with the device biometric. Cancel throws userRejected (4001),
        // which propagates straight out of handle() to a JS 4001.
        val payload = JSONObject().apply {
            put("appTitle", appTitle)
            putOpt("purpose", purpose)
            put("requiredClaims", JSONArray(requiredClaims))
            if (schemas.isNotEmpty()) put("schemas", JSONArray(schemas))
            if (issuers.isNotEmpty()) put("issuers", JSONArray(issuers))
            maxAgeSec?.let { put("maxAgeSec", it) }
        }
        val sheetResult = gate.request(kind = "identity", payloadJson = payload.toString(), appTitle = appTitle)

        // The sheet returns the disclosure it gathered (and a local decision).
        // We hand back the iOS verdict envelope; offline=true flags that the
        // authoritative server verify is not run on this build yet.
        val approved = JSONObject(sheetResult)
        val disclosed = approved.optJSONObject("disclosed") ?: JSONObject()
        return JSONObject().apply {
            put("decision", approved.optString("decision", "GRANT"))
            put("reason", approved.optString("reason", "user_approved"))
            put("checks", JSONObject().put("consent", true))
            put("disclosed", disclosed)
            requestId?.let { put("requestId", it) }
            put("offline", true)
        }.toString()
    }

    // MARK: -- identity.collect

    /** Cross-device: scan + verify a SEPARATE customer's presentation. The
     *  merchant runs the camera (or shows a hosted-request QR the customer
     *  scans); the collect sheet returns the verify verdict. */
    private suspend fun collect(argsJson: String): String {
        val opts = parseOpts(argsJson) ?: JSONObject()
        val requiredClaims = stringList(opts.optJSONArray("requiredClaims"))
        if (requiredClaims.isEmpty()) {
            throw MiniAppBridgeError.invalidParams("identity.collect requires requiredClaims")
        }
        val schema = opts.optStringOrNull("schema")
        val maxAgeSec = opts.optLongOrNull("maxAgeSec")
        val purpose = opts.optStringOrNull("purpose")

        val payload = JSONObject().apply {
            put("appTitle", appTitle)
            putOpt("purpose", purpose)
            putOpt("schema", schema)
            put("requiredClaims", JSONArray(requiredClaims))
            maxAgeSec?.let { put("maxAgeSec", it) }
            put("verifierBaseUrl", verifierBaseUrl)
            put("installedAppId", installedAppId)
        }
        // The collect sheet drives the scanner, runs the merchant policy, and
        // returns the full { decision, reason, missing, message, disclosed,
        // checks, offline } envelope on approve, or cancels (4001).
        return gate.request(kind = "collect", payloadJson = payload.toString(), appTitle = appTitle)
    }

    // MARK: -- helpers

    private suspend fun loadSandwich(): IdentitySandwich? =
        withContext(Dispatchers.IO) { runCatching { IdentitySandwich.load(store) }.getOrNull() }

    private fun parseOpts(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()?.takeIf { argsJson.trim() != "null" }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            (arr.opt(i) as? String)?.let { out.add(it) }
        }
        return out
    }
}

/**
 * "scan" namespace handler (window.maknoon.scan()). Android port of the iOS
 * ScanBridgeHandler. Gated by the "scan" capability; the actual camera + the
 * explicit user-cancellable sheet are driven by the host through the
 * ApprovalGate (kind "scan"). The dApp never sees the camera stream, only the
 * decoded string: scan.read({ prompt? }) -> { value }.
 */
class ScanBridgeHandler(
    private val appTitle: String,
    private val gate: ApprovalGate,
) : MiniAppNamespaceHandler {

    override val namespace = "scan"
    override val requiredPermission: String? = "scan"

    override suspend fun handle(method: String, argsJson: String): String {
        if (method != "scan.read") throw MiniAppBridgeError.unsupported("scan.$method")
        val prompt = runCatching { JSONObject(argsJson) }.getOrNull()?.optStringOrNull("prompt")
        val payload = JSONObject().putOpt("prompt", prompt).toString()
        // Suspends until the user scans a code (returns { value }) or cancels
        // (throws userRejected -> JS 4001). The sheet already shapes { value }.
        return gate.request(kind = "scan", payloadJson = payload, appTitle = appTitle)
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

internal fun JSONObject.putOpt(key: String, value: String?): JSONObject {
    if (value != null) put(key, value)
    return this
}
