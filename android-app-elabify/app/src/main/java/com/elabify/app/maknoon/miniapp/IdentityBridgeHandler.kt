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
// identity.request now runs the full on-device holder -> verifier loop like iOS:
// match the holder's stored credentials (SDK MatchingEngine) against the request
// filter, gather consent + a credential pick via the approval sheet, build a
// signed Presentation (SDK PresentationBuilder), and POST it to the verifier's
// /v1/verify for the authoritative verdict, with an offline PresentationVerifier
// fallback (offline=true) when the server is unreachable. identity.collect stays
// policy/consent-based for now (cross-device scan verdict via its sheet).
// Nothing here hands key material or the raw presentation to JS: the mini-app
// only ever receives the verdict envelope.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.VerifierHistoryEntity
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.net.ChallengeContext
import com.elabify.musnad.net.VerifierClient
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.MatchingEngine
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.PresentationBuilder
import com.elabify.musnad.present.PresentationVerifier
import com.elabify.musnad.present.VerifierFilter
import com.elabify.musnad.present.VerifierFilterClause
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
    /** Loads the holder's stored credentials (Room). Injected so the handler
     *  stays free of a Context; the factory wires it to MaknoonStore. */
    private val loadCredentials: suspend () -> List<CredentialEntity>,
    /** Best-effort disclosure-history recorder (Room VerifierHistoryDao), wired
     *  by the factory. Mirrors iOS VerifierHistory.record; a no-op by default. */
    private val recordDisclosure: suspend (VerifierHistoryEntity) -> Unit = {},
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

    /** Same-device: prove a claim from THIS holder's own wallet, mirroring the
     *  iOS flow: match credentials -> consent + pick -> build a signed
     *  presentation -> server /v1/verify (authoritative), with an offline local
     *  verify fallback when the server is unreachable. */
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

        val sandwich = loadSandwich() ?: throw MiniAppBridgeError.unauthorized("wallet is locked")

        // Match the holder's credentials against the request filter. Nothing
        // matches -> DENY with no sheet (mirrors iOS).
        val filter = VerifierFilter(
            issuers = issuers.takeIf { it.isNotEmpty() }?.let { VerifierFilterClause(mode = "allow", list = it) },
            schemas = schemas.takeIf { it.isNotEmpty() }?.let { VerifierFilterClause(mode = "allow", list = it) },
            requiredClaims = requiredClaims,
        )
        val candidates = withContext(Dispatchers.IO) { loadCredentials() }
            .mapNotNull { e -> runCatching { ParsedCredential.parse(e.credentialJson) }.getOrNull()?.let { e to it } }
        val matches = candidates.filter { MatchingEngine.matches(it.second, filter) }
        if (matches.isEmpty()) {
            return JSONObject().apply {
                put("decision", "DENY")
                put("reason", "no_matching_credential")
                put("checks", JSONObject())
                put("disclosed", JSONObject())
            }.toString()
        }

        // Server-issued challenge.
        val ch = withContext(Dispatchers.IO) { verifier.challenge(requiredClaims) }

        // User consent + credential pick (default most-recent). Cancel -> 4001.
        val credArr = JSONArray()
        matches.forEach { (e, parsed) ->
            credArr.put(
                JSONObject()
                    .put("cid", e.cid)
                    .put("label", e.nickname?.takeIf { it.isNotEmpty() } ?: e.schema)
                    // Holder + issue date, matching poolAccess. Without them two
                    // un-nicknamed passports render as the same schema URI twice
                    // and the pick is a guess.
                    .put("holder", parsed.header.sub)
                    .put("issuedAt", parsed.header.iat),
            )
        }
        val payload = JSONObject().apply {
            put("appTitle", appTitle)
            putOpt("purpose", purpose)
            put("requiredClaims", JSONArray(requiredClaims))
            if (schemas.isNotEmpty()) put("schemas", JSONArray(schemas))
            if (issuers.isNotEmpty()) put("issuers", JSONArray(issuers))
            maxAgeSec?.let { put("maxAgeSec", it) }
            put("credentials", credArr)
        }
        val sheetResult = gate.request(kind = "identity", payloadJson = payload.toString(), appTitle = appTitle)
        val chosenCid = JSONObject(sheetResult).optStringOrNull("cid")
        val chosen = (chosenCid?.let { cid -> matches.firstOrNull { it.first.cid == cid } } ?: matches.first()).second

        // Sign the presentation and verify. Server verdict is authoritative; a
        // server failure falls back to a clearly-flagged offline local check.
        val presentation = withContext(Dispatchers.IO) {
            PresentationBuilder.build(
                credential = chosen,
                selectedClaims = requiredClaims.toSet(),
                challenge = ch.challenge,
                verifierDid = PresentationBuilder.OPEN_VERIFIER_DID,
                pendingRequest = null,
                sandwich = sandwich,
            )
        }
        return try {
            val resp = withContext(Dispatchers.IO) {
                verifier.verify(ChallengeContext(ch.requestId, ch.issuedAt, ch.expiresAt), presentation)
            }
            // Best-effort disclosure history (mirrors iOS VerifierHistory.record);
            // never fail the verify if the write fails.
            runCatching {
                withContext(Dispatchers.IO) {
                    recordDisclosure(
                        VerifierHistoryEntity(
                            verifierDid = PresentationBuilder.OPEN_VERIFIER_DID,
                            verifierName = appTitle,
                            label = appTitle,
                            credentialId = chosen.header.cid,
                            credentialSchema = chosen.header.schema,
                            disclosedKeysJson = JSONArray(requiredClaims).toString(),
                            lastUsedAt = System.currentTimeMillis() / 1000L,
                        ),
                    )
                }
            }
            JSONObject().apply {
                put("decision", resp.decision)
                put("reason", resp.reason)
                put("checks", mapJsonValues(resp.checks))
                put("disclosed", mapJsonValues(resp.disclosed ?: emptyMap()))
                put("requestId", ch.requestId)
                put("offline", false)
            }.toString()
        } catch (e: Exception) {
            val local = PresentationVerifier.verifyOffline(presentation)
            JSONObject().apply {
                put("decision", local.decision.name)
                put("reason", "offline_local_verify")
                put("checks", JSONObject().put("overallPass", local.checks.overallPass))
                put("disclosed", mapJsonValues(local.disclosed))
                put("requestId", ch.requestId)
                put("offline", true)
            }.toString()
        }
    }

    /** JsonValue map -> JSONObject for the verdict envelope. */
    private fun mapJsonValues(m: Map<String, JsonValue?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v?.anyValue() ?: JSONObject.NULL)
        return o
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
