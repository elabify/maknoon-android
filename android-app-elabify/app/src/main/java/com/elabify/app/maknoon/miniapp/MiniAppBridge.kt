// Native side of the mini-app JS bridge. Android port of the iOS
// MiniAppBridge.swift, adapted to the Android WebView bridge mechanism.
//
// MECHANISM (differs from iOS WKScriptMessageHandlerWithReply):
//   * The WebView exposes ONE @JavascriptInterface object under the JS name
//     "MaknoonBridgeNative". The injected shim (see MiniAppHostScreen) calls
//       MaknoonBridgeNative.postMessage(namespace, method, argsJson, callbackId)
//     synchronously. That call returns immediately (void).
//   * This class validates the message, enforces the installed app's declared
//     permissions, routes to the matching namespace handler on a coroutine,
//     and replies asynchronously by invoking the [CallbackSink]:
//       sink.resolve(callbackId, resultJson)  ->  window.__maknoon.__resolve(...)
//       sink.reject(callbackId, errorJson)     ->  window.__maknoon.__reject(...)
//
// All handlers are string-in / string-out (JSON), so the marshalling layer
// never needs to know a handler's shape. A handler receives the raw argsJson
// string (the JS `params` serialized) and returns a JSON string that becomes
// the resolved promise value. To reject, a handler throws MiniAppBridgeError
// (mapped to an EIP-1193-style { code, message }) or any other Throwable
// (mapped to -32603 internal).
//
// USER-APPROVAL SHEETS: a handler that needs a native confirmation (identity
// disclosure, payment, signing, scan) does NOT block. It suspends on an
// ApprovalGate the host wired in (see [ApprovalGate] below): the gate posts a
// request into a StateFlow the Compose host observes, shows the sheet, and
// resumes the suspended coroutine with the user's decision. A cancelled sheet
// resumes with MiniAppBridgeError.userRejected(), which the dispatcher turns
// into a 4001 rejection. Handlers therefore stay plain suspend functions.
//
// Trust boundary: handlers run native code, show their own approval UI, and
// gate sensitive actions on device auth. Nothing here ever hands key material
// back to JS.

package com.elabify.app.maknoon.miniapp

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * EIP-1193 / bridge error with a numeric code the shim maps onto a rejected
 * promise. Codes follow EIP-1193 where applicable (4001 user rejected, 4100
 * unauthorized, 4200 unsupported, -32602 invalid params, -32603 internal).
 */
class MiniAppBridgeError(
    val code: Int,
    override val message: String,
) : Exception(message) {
    companion object {
        fun unsupported(what: String) = MiniAppBridgeError(4200, "Unsupported: $what")
        fun unauthorized(what: String) = MiniAppBridgeError(4100, "Not authorized: $what")
        fun userRejected() = MiniAppBridgeError(4001, "User rejected the request")
        fun invalidParams(what: String) = MiniAppBridgeError(-32602, "Invalid parameters: $what")
        fun internalError(what: String) = MiniAppBridgeError(-32603, what)
    }
}

/**
 * One JS namespace (e.g. "eth", "maknoon", "storage"). A handler is a plain
 * suspend function from the raw argsJson string to a result JSON string.
 *
 * CONTRACT for handler agents:
 *   * [namespace] is the JS routing key the shim sends (see the shim's call()).
 *   * [requiredPermission] is the capability token the install must hold to
 *     use this namespace (see MiniAppCapabilityRegistry). null = always
 *     allowed (host, storage, fiat, device, haptic). The dispatcher enforces
 *     this BEFORE calling [handle], so a handler may assume the grant is held.
 *   * [handle] receives `method` (e.g. "identity.request") and the params as a
 *     JSON string ("null" when the shim sent no params), and returns a JSON
 *     string that becomes the resolved JS promise value. Return "null" for a
 *     void result. Throw [MiniAppBridgeError] to reject with a specific code,
 *     or any other Throwable for a -32603 internal error.
 *   * To require a user-approval sheet, suspend on the [ApprovalGate] the
 *     host injected into the handler at construction time (see [ApprovalGate]).
 */
interface MiniAppNamespaceHandler {
    val namespace: String
    val requiredPermission: String?

    /**
     * Permission the app must hold for a SPECIFIC method. Lets one handler
     * require different tokens per method (e.g. Web3BridgeHandler gates reads,
     * writes, and signing separately). Defaults to [requiredPermission].
     */
    fun requiredPermissionFor(method: String): String? = requiredPermission

    suspend fun handle(method: String, argsJson: String): String
}

/**
 * Sink the dispatcher uses to deliver async results back to JS. The host
 * (MiniAppHostScreen) implements this over the live WebView by calling
 * evaluateJavascript("window.__maknoon.__resolve|__reject(...)"). Both
 * payloads are already-serialized JSON strings.
 */
interface CallbackSink {
    /** Resolve the JS promise for [callbackId] with [resultJson] (a JSON value). */
    fun resolve(callbackId: String, resultJson: String)

    /** Reject the JS promise for [callbackId] with [errorJson] = {code,message}. */
    fun reject(callbackId: String, errorJson: String)

    /**
     * Push an EIP-1193 event to the page (chainChanged / accountsChanged).
     * [payloadJson] is a JSON value. Routed to window.__maknoonEmit("eth", ...).
     */
    fun emitEth(event: String, payloadJson: String)
}

/**
 * Central dispatcher and the single @JavascriptInterface object bound to the
 * WebView. Construct it with the install's id, its granted capability set, the
 * namespace handlers, the dispatch scope, and the callback sink. Handler
 * agents do not construct this; the host wires it up (see MiniAppHostScreen).
 */
class MiniAppBridge(
    private val installedAppId: String,
    private val granted: Set<String>,
    handlers: List<MiniAppNamespaceHandler>,
    private val scope: CoroutineScope,
    private val sink: CallbackSink,
    /** App metadata for the built-in "host" namespace. */
    private val appId: String,
    private val appTitle: String,
) {
    private val handlers: MutableMap<String, MiniAppNamespaceHandler> = mutableMapOf()

    init {
        for (h in handlers) this.handlers[h.namespace] = h
        // Built-in, always-available namespace.
        val host = HostNamespaceHandler(appId, appTitle, granted)
        this.handlers[host.namespace] = host
    }

    /**
     * JS entry point. Called synchronously from the page on the WebView's JS
     * thread; we hop onto [scope] immediately and reply via [sink]. Never
     * throws across the JNI boundary.
     */
    @JavascriptInterface
    fun postMessage(namespace: String?, method: String?, argsJson: String?, callbackId: String?) {
        val cb = callbackId ?: return
        val ns = namespace
        val m = method
        if (ns == null || m == null) {
            sink.reject(cb, errorEnvelope(MiniAppBridgeError.invalidParams("malformed bridge message")))
            return
        }
        val handler = handlers[ns]
        if (handler == null) {
            sink.reject(cb, errorEnvelope(MiniAppBridgeError.unsupported("namespace $ns")))
            return
        }
        val needed = handler.requiredPermissionFor(m)
        if (needed != null && !granted.contains(needed)) {
            sink.reject(cb, errorEnvelope(MiniAppBridgeError.unauthorized("app lacks '$needed' permission")))
            return
        }
        val args = argsJson ?: "null"
        scope.launch {
            try {
                val result = handler.handle(m, args)
                sink.resolve(cb, result)
            } catch (e: MiniAppBridgeError) {
                sink.reject(cb, errorEnvelope(e))
            } catch (e: Throwable) {
                sink.reject(cb, errorEnvelope(MiniAppBridgeError.internalError(e.message ?: "internal error")))
            }
        }
    }

    companion object {
        /** JS name of the injected @JavascriptInterface object. */
        const val JS_INTERFACE_NAME = "MaknoonBridgeNative"

        /** Serialize a bridge error as the {code,message} JSON the shim expects. */
        fun errorEnvelope(e: MiniAppBridgeError): String =
            JSONObject().put("code", e.code).put("message", e.message).toString()
    }
}

/**
 * A native confirmation a handler needs before it can complete (identity
 * disclosure, payment, signing, scan). The host (MiniAppHostScreen) observes
 * [ApprovalGate.active], renders the matching Compose sheet, and resumes the
 * suspended handler through [ApprovalGate.resolve] / [ApprovalGate.cancel].
 *
 * [kind] selects which sheet to show ("identity", "collect", "web3",
 * "payment", "commerce", "scan"). [payloadJson] is the request the sheet
 * renders (handler-defined). [appTitle] is the merchant/app display name to
 * show the user. The resolved value [R] is a JSON string the handler
 * interprets (e.g. a tx hash, a disclosure, a scanned code); a cancel rejects
 * the handler's await with MiniAppBridgeError.userRejected().
 */
class ApprovalRequest internal constructor(
    val id: Long,
    val kind: String,
    val payloadJson: String,
    val appTitle: String,
    private val deferred: CompletableDeferred<String>,
) {
    /** Approve the request, resuming the handler with [resultJson]. */
    fun approve(resultJson: String) {
        deferred.complete(resultJson)
    }

    /** Cancel the request, rejecting the handler with a 4001. */
    fun cancel() {
        deferred.completeExceptionally(MiniAppBridgeError.userRejected())
    }
}

/**
 * The seam a handler uses to demand a native approval sheet without blocking.
 * One gate per mini-app host. A handler calls [request] and suspends until the
 * user approves (returns the sheet's JSON result) or cancels (throws
 * MiniAppBridgeError.userRejected()). The host observes [active] and drives the
 * sheet UI, calling the request's approve/cancel.
 *
 * Only one approval is active at a time; a new [request] while one is pending
 * cancels the pending one (a fresh user intent supersedes the old prompt),
 * matching the iOS single-sheet coordinators.
 */
class ApprovalGate {
    private val _active = MutableStateFlow<ApprovalRequest?>(null)

    /** The pending approval request, or null. Observe this from the host. */
    val active: StateFlow<ApprovalRequest?> = _active.asStateFlow()

    private var counter = 0L

    /**
     * Post an approval request and suspend until the user decides.
     * @param kind sheet selector ("identity"|"collect"|"web3"|"payment"|"commerce"|"scan").
     * @param payloadJson the request body the sheet renders.
     * @param appTitle merchant/app display name shown to the user.
     * @return the sheet's JSON result on approval.
     * @throws MiniAppBridgeError 4001 when the user cancels.
     */
    suspend fun request(kind: String, payloadJson: String, appTitle: String): String {
        // Supersede any pending prompt.
        _active.value?.cancel()
        val deferred = CompletableDeferred<String>()
        val req = ApprovalRequest(++counter, kind, payloadJson, appTitle, deferred)
        _active.value = req
        try {
            return deferred.await()
        } finally {
            // Clear only if this request is still the active one.
            if (_active.value === req) _active.value = null
        }
    }
}

/**
 * Built-in "host" namespace: metadata plus a ping for hello-world bring-up.
 * No permission required, no sensitive surface.
 */
private class HostNamespaceHandler(
    private val appId: String,
    private val appTitle: String,
    private val granted: Set<String>,
) : MiniAppNamespaceHandler {
    override val namespace = "host"
    override val requiredPermission: String? = null

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "ping" -> JSONObject.quote("pong")
        "appInfo" -> JSONObject().apply {
            put("id", appId)
            put("title", appTitle)
            put("permissions", org.json.JSONArray(granted.toList()))
            put("bridgeVersion", 1)
        }.toString()
        else -> throw MiniAppBridgeError.unsupported("host.$method")
    }
}
