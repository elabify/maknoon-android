// Hosts a mini app in a sandboxed WebView. Android port of the iOS
// MiniAppHostView.swift, adapted to android.webkit.WebView.
//
// Lifecycle: on first composition we ensure the bundle is downloaded and
// verified (MiniAppBundleStore), then build a WebView whose configuration:
//   * has JavaScript enabled but file access OFF (no file:// reads), DOM
//     storage on (ephemeral: WebStorage is cleared on dispose so an app's
//     localStorage never persists or leaks across apps);
//   * serves the SHA-256-pinned bundle through a WebViewAssetLoader whose
//     custom PathHandler reads verified bytes from the MiniAppBundle, so the
//     page can only load files we integrity-checked. shouldInterceptRequest
//     attaches a strict Content-Security-Policy that forbids any off-origin
//     resource (connect-src 'none' because all native I/O goes through the
//     bridge, not fetch/XHR);
//   * blocks any navigation that leaves the served origin;
//   * injects the provider shim (window.ethereum EIP-1193 + window.maknoon +
//     the window.__maknoon resolve/reject plumbing) at document start;
//   * attaches the single @JavascriptInterface bridge object.
//
// The served origin is a fixed, app-scoped https host under the reserved
// WebViewAssetLoader default domain, so localStorage / cookies are isolated
// per WebView instance and never collide with a real https origin.
//
// User-approval sheets: handlers suspend on an ApprovalGate; we observe its
// active StateFlow and render the matching sheet. The concrete sheets are
// supplied by the handler agents; MiniAppApprovalSheetHost is the seam they
// hook into (a when over ApprovalRequest.kind).

package com.elabify.app.maknoon.ui.miniapp

import android.annotation.SuppressLint
import com.elabify.app.maknoon.ui.theme.AppLanguageCatalog
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.content.Intent
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.ApprovalGate
import com.elabify.app.maknoon.miniapp.ApprovalRequest
import com.elabify.app.maknoon.miniapp.CallbackSink
import com.elabify.app.maknoon.miniapp.MiniAppBridge
import com.elabify.app.maknoon.miniapp.MiniAppBundle
import com.elabify.app.maknoon.miniapp.MiniAppBundleStore
import com.elabify.app.maknoon.miniapp.MiniAppNamespaceHandler
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject

/**
 * Minimal description of a mini app to host. The catalog/registry that
 * produces these is out of scope for this slice; a handler-wiring agent or a
 * future catalog agent supplies them. Fields mirror the iOS AppStoreEntry +
 * InstalledApp surface this screen reads.
 */
data class MiniAppLaunchSpec(
    /** Stable per-install id, "<storeId>::<appId>". */
    val installedAppId: String,
    /** Catalog app id; also the served web-origin sub-path. */
    val appId: String,
    val title: String,
    val manifestUrl: String,
    val manifestSha256: String,
    /** Capability tokens the user granted this install. */
    val grantedPermissions: Set<String>,
    /** Release channel of THIS install ("stable"/"beta"), handed to the bundle
     *  so it can badge itself. One bundle serves both channels. */
    val channel: String = "stable",
    /** Catalog version of this install, e.g. "0.1.8". */
    val version: String = "",
)

/**
 * Builds the namespace handlers a mini app is allowed to use. The handler
 * agents own the concrete handler classes; this factory is the single place
 * the host wires them together with the install context, the per-app stores,
 * and the shared [ApprovalGate]. Until the handler agents land, this returns
 * only the always-available built-ins (the bridge adds "host" itself).
 *
 * Handler agents: register your handler here, gated by the granted set the
 * same way iOS does (see makeHandlers in MiniAppHostView.swift).
 */
fun interface MiniAppHandlerFactory {
    fun build(
        spec: MiniAppLaunchSpec,
        scope: CoroutineScope,
        gate: ApprovalGate,
    ): List<MiniAppNamespaceHandler>
}

/**
 * Seam for rendering the native approval sheet for a pending [ApprovalRequest].
 * The handler agents replace this with a when over [ApprovalRequest.kind]
 * ("identity" | "collect" | "web3" | "payment" | "commerce" | "scan"),
 * calling [ApprovalRequest.approve] / [ApprovalRequest.cancel]. The default
 * is a safe no-op that cancels any request it does not recognize, so an
 * un-wired sheet rejects cleanly (4001) rather than hanging the JS promise.
 */
fun interface MiniAppApprovalSheetHost {
    @Composable
    fun Sheet(request: ApprovalRequest, onDismiss: () -> Unit)
}

private val cancelUnknownSheetHost = MiniAppApprovalSheetHost { request, onDismiss ->
    LaunchedEffect(request.id) {
        request.cancel()
        onDismiss()
    }
}

/** Reserved domain WebViewAssetLoader serves under; never resolves to real DNS. */
private const val ASSET_DOMAIN = "appassets.androidplatform.net"

private sealed interface Phase {
    data object Loading : Phase
    data class Ready(val bundle: MiniAppBundle) : Phase
    data class Failed(val message: String) : Phase
}

@Composable
fun MiniAppHostScreen(
    spec: MiniAppLaunchSpec,
    handlerFactory: MiniAppHandlerFactory = MiniAppHandlerFactory { _, _, _ -> emptyList() },
    approvalSheetHost: MiniAppApprovalSheetHost = cancelUnknownSheetHost,
    modifier: Modifier = Modifier,
) {
    val couldNotOpenAppMsg = stringResource(R.string.miniapp_could_not_open_app)
    var phase by remember(spec.installedAppId) { mutableStateOf<Phase>(Phase.Loading) }
    val gate = remember(spec.installedAppId) { ApprovalGate() }

    LaunchedEffect(spec.installedAppId, spec.manifestSha256) {
        phase = Phase.Loading
        phase = try {
            val bundle = MiniAppBundleStore.shared.ensureBundle(
                installedAppId = spec.installedAppId,
                appId = spec.appId,
                manifestUrl = spec.manifestUrl,
                manifestSha256 = spec.manifestSha256,
            )
            Phase.Ready(bundle)
        } catch (e: Exception) {
            Phase.Failed(e.message ?: couldNotOpenAppMsg)
        }
    }

    when (val p = phase) {
        is Phase.Loading -> Box(modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        is Phase.Failed -> Box(modifier.fillMaxSize(), Alignment.Center) {
            Text(
                stringResource(R.string.app_could_not_open_app, p.message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is Phase.Ready -> MiniAppWebView(
            spec = spec,
            bundle = p.bundle,
            gate = gate,
            handlerFactory = handlerFactory,
            approvalSheetHost = approvalSheetHost,
            modifier = modifier.fillMaxSize(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MiniAppWebView(
    spec: MiniAppLaunchSpec,
    bundle: MiniAppBundle,
    gate: ApprovalGate,
    handlerFactory: MiniAppHandlerFactory,
    approvalSheetHost: MiniAppApprovalSheetHost,
    modifier: Modifier,
) {
    // Bridge dispatch scope. Deliberately NOT rememberCoroutineScope(): that is
    // cancelled whenever this composable leaves composition (rotation, tab switch,
    // navigation), which cancels an in-flight bridge call (e.g. pools.list)
    // mid-flight and orphans its JS promise, so the dapp hangs with no result and
    // no error (Android-only; iOS dispatches on an unstructured Task that survives
    // view teardown). This session scope is tied to the mini-app (installedAppId)
    // and is NOT cancelled on transient disposal, so in-flight calls run to
    // completion (bounded by the HTTP client's ~30s timeout) and settle. (ADR-0062)
    val scope = remember(spec.installedAppId) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    val pendingApproval by gate.active.collectAsState()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Verified bytes come from the bundle; the loader maps the served
            // path "/app/<appId>/<file>" onto the bundle's resolve().
            val pathPrefix = "/app/${spec.appId}/"
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(ASSET_DOMAIN)
                .addPathHandler(pathPrefix, BundlePathHandler(bundle))
                .build()

            val webView = WebView(context)
            // Fill the host's allocated space. Without explicit MATCH_PARENT the
            // WebView defaults to WRAP_CONTENT and measures to the main page's
            // content height; a page's `position: fixed; inset: 0` overlay sized
            // in `vh` (e.g. the Point of Sale settings sheet, max-height: 86vh)
            // then resolves against that short box and renders as a sliver. With
            // MATCH_PARENT, `vh` + fixed positioning resolve to the full screen,
            // matching iOS WKWebView.
            webView.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            webView.settings.apply {
                javaScriptEnabled = true
                // Hard sandbox: no file:// or content:// reads, no universal
                // access. The page reaches verified files only via the loader.
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                // Ephemeral DOM storage: enabled for the session, wiped on
                // dispose so nothing persists or leaks across apps.
                domStorageEnabled = true
                // Honor the page's <meta name="viewport"> (width=device-width)
                // and lay out at the full WebView width, matching iOS WKWebView's
                // default. Without these the page renders at a desktop default
                // width and pages that lay out off the viewport (e.g. the Point
                // of Sale settings view) appear shrunken / mis-sized.
                useWideViewPort = true
                loadWithOverviewMode = true
                // Lock the viewport so the page can't pinch/double-tap zoom.
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                // No off-origin loads anyway (CSP), but be explicit.
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = true
            }

            // Callback sink over the live WebView.
            val sink = WebViewCallbackSink(webView)

            // Build handlers (granted-gated) and the bridge, then attach the
            // single @JavascriptInterface object.
            val handlers = handlerFactory.build(spec, scope, gate)
            val bridge = MiniAppBridge(
                installedAppId = spec.installedAppId,
                granted = spec.grantedPermissions,
                handlers = handlers,
                scope = scope,
                sink = sink,
                appId = spec.appId,
                appTitle = spec.title,
                appChannel = spec.channel,
                appVersion = spec.version,
            )
            webView.addJavascriptInterface(bridge, MiniAppBridge.JS_INTERFACE_NAME)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val res = assetLoader.shouldInterceptRequest(request.url) ?: return null
                    // Harden every served response with a strict CSP. connect-src
                    // 'none' because all native I/O goes through the bridge.
                    res.responseHeaders = (res.responseHeaders ?: emptyMap()).toMutableMap().apply {
                        put("Content-Security-Policy", CSP)
                        put("Cache-Control", "no-store")
                    }
                    return res
                }

                // Keep navigation inside the served origin. Anything else
                // (http link, tel:, etc.) is refused; mini apps reach the
                // outside world only through the bridge.
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url
                    val sameOrigin = url.scheme == "https" && url.host == ASSET_DOMAIN
                    if (sameOrigin) return false // let it load in the sandbox
                    // A user-TAPPED external http(s) link (e.g. a receipt's
                    // block-explorer link) opens in the system browser, never in
                    // the sandbox. Everything else stays blocked.
                    if (request.hasGesture() && (url.scheme == "https" || url.scheme == "http")) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    // Set <html lang/dir> from the chosen app language before the
                    // page renders, so RTL (Arabic) is correct with no flash and
                    // every dApp inherits the right direction even if it ignores
                    // device.info().locale. Runs before the provider + page scripts.
                    // minSdk 33: configuration.locales is always present, so the
                    // deprecated single Configuration.locale fallback is dead code.
                    val cfg = view.context.resources.configuration
                    val tag = cfg.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag() ?: "en"
                    view.evaluateJavascript(localeShim(tag), null)
                    // Inject the provider shim before the page's own scripts run.
                    // onPageStarted fires before document scripts execute, the
                    // Android analog of WKUserScript atDocumentStart.
                    view.evaluateJavascript(providerShim(), null)
                    super.onPageStarted(view, url, favicon)
                }
            }

            val entryUrl = "https://$ASSET_DOMAIN$pathPrefix${bundle.entryPath}"
            webView.loadUrl(entryUrl)
            webView
        },
        onRelease = { webView ->
            // Tear down cleanly and wipe the ephemeral web storage so nothing
            // survives this app session.
            webView.stopLoading()
            webView.removeJavascriptInterface(MiniAppBridge.JS_INTERFACE_NAME)
            webView.clearHistory()
            android.webkit.WebStorage.getInstance().deleteAllData()
            webView.destroy()
        },
    )

    // Render the native approval sheet for any pending handler request.
    pendingApproval?.let { req ->
        approvalSheetHost.Sheet(req) { /* dismissed: gate clears itself on resolve/cancel */ }
    }
}

/**
 * WebViewAssetLoader path handler that serves integrity-verified bytes from a
 * [MiniAppBundle]. Rejects traversal via the bundle's own resolve().
 */
private class BundlePathHandler(
    private val bundle: MiniAppBundle,
) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        val bytes = bundle.bytesFor(path) ?: return notFound()
        val mime = mimeType(path)
        return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(bytes))
    }

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found",
            mutableMapOf(), ByteArrayInputStream("Not found".toByteArray()),
        )

    private fun mimeType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * CallbackSink over a live WebView. Marshals async results back into the page
 * by evaluating window.__maknoon.__resolve / __reject, and pushes EIP-1193
 * events via window.__maknoonEmit. All payloads are pre-serialized JSON.
 */
private class WebViewCallbackSink(private val webView: WebView) : CallbackSink {
    private fun run(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    override fun resolve(callbackId: String, resultJson: String) {
        run("window.__maknoon.__resolve(${JSONObject.quote(callbackId)}, $resultJson);")
    }

    override fun reject(callbackId: String, errorJson: String) {
        run("window.__maknoon.__reject(${JSONObject.quote(callbackId)}, $errorJson);")
    }

    override fun emitEth(event: String, payloadJson: String) {
        run("window.__maknoonEmit && window.__maknoonEmit('eth', ${JSONObject.quote(event)}, $payloadJson);")
    }
}

private const val CSP =
    "default-src 'self'; " +
        "script-src 'self' 'unsafe-inline'; " +
        "style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data:; " +
        "font-src 'self' data:; " +
        "connect-src 'none'; " +
        "object-src 'none'; " +
        "base-uri 'none'; " +
        "frame-ancestors 'none'"

/**
 * The injected provider shim. Android analog of MiniAppProvider.js, rewritten
 * for the Android bridge mechanism: instead of a reply-handler postMessage
 * returning a Promise, JS calls the synchronous @JavascriptInterface
 * MaknoonBridgeNative.postMessage(namespace, method, argsJson, callbackId) and
 * the native side resolves/rejects the matching promise asynchronously via
 * window.__maknoon.__resolve / __reject.
 *
 * Two providers are exposed (same surface as iOS):
 *   window.ethereum (EIP-1193, pinned to Sepolia) and window.maknoon.
 */
/**
 * A document-start script that sets <html lang> + dir from the host's selected
 * app language. Mirrors the dApp's own normalization (any zh-* maps to Simplified,
 * ar -> rtl) so the host injection and the bundle agree. iOS analog: localeScript
 * in MiniAppHostView.
 */
/**
 * Inject `lang` and `dir` at document-start, before the bundle renders, so there
 * is no left-to-right flash and a mini-app gets correct direction even if it
 * ignores `device.info()` (ADR-0038: the dApp owns strings, the host owns
 * direction).
 *
 * This used to test `startsWith("ar")` for RTL and collapse every `zh-*` to
 * Simplified. Both were wrong once the roster grew: Hebrew, Urdu, Persian and
 * Sorani Kurdish would have rendered left-to-right, and Traditional readers
 * would have been shown Simplified.
 */
private fun localeShim(localeTag: String): String {
    val lang = normalizeMiniAppLocale(localeTag)
    val dir = if (lang in AppLanguageCatalog.rtlCodes) "rtl" else "ltr"
    return """
    (function () {
      var e = document.documentElement;
      e.lang = '$lang';
      e.setAttribute('dir', '$dir');
    })();
    """.trimIndent()
}

private fun providerShim(): String = """
(function () {
  "use strict";
  if (window.__maknoon && window.__maknoon.__installed) return;

  var pending = {};
  var seq = 0;

  function call(namespace, method, params) {
    return new Promise(function (resolve, reject) {
      var id = "cb_" + (++seq);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        var argsJson = JSON.stringify(params == null ? null : params);
        window.${MiniAppBridge.JS_INTERFACE_NAME}.postMessage(namespace, method, argsJson, id);
      } catch (e) {
        delete pending[id];
        reject(makeError(-32603, "Maknoon bridge unavailable"));
      }
    });
  }

  function makeError(code, message) {
    var err = new Error(message || "Request failed");
    err.code = code;
    return err;
  }

  var __maknoon = {
    __installed: true,
    __resolve: function (id, result) {
      var p = pending[id]; if (!p) return; delete pending[id]; p.resolve(result);
    },
    __reject: function (id, error) {
      var p = pending[id]; if (!p) return; delete pending[id];
      var e = error || { code: -32603, message: "Unknown bridge error" };
      p.reject(makeError(e.code, e.message));
    }
  };
  Object.defineProperty(window, "__maknoon", { value: __maknoon, configurable: false, writable: false });

  function Emitter() { this._l = {}; }
  Emitter.prototype.on = function (ev, fn) { (this._l[ev] = this._l[ev] || []).push(fn); return this; };
  Emitter.prototype.removeListener = function (ev, fn) {
    var a = this._l[ev]; if (!a) return this;
    this._l[ev] = a.filter(function (f) { return f !== fn; }); return this;
  };
  Emitter.prototype.emit = function (ev, payload) {
    (this._l[ev] || []).slice().forEach(function (fn) { try { fn(payload); } catch (e) {} });
  };

  var ethEmitter = new Emitter();
  var ethereum = {
    isMaknoon: true,
    request: function (args) {
      if (!args || typeof args.method !== "string") {
        return Promise.reject(makeError(-32602, "request requires a method"));
      }
      return call("eth", args.method, args.params);
    },
    on: function (ev, fn) { ethEmitter.on(ev, fn); return ethereum; },
    removeListener: function (ev, fn) { ethEmitter.removeListener(ev, fn); return ethereum; },
    enable: function () { return ethereum.request({ method: "eth_requestAccounts" }); }
  };

  var maknoon = {
    version: 1,
    identity: {
      request: function (options) { return call("maknoon", "identity.request", options || {}); },
      collect: function (options) { return call("maknoon", "identity.collect", options || {}); },
      getDID: function () { return call("maknoon", "identity.getDID", null); }
    },
    storage: {
      getItem: function (key) { return call("storage", "storage.get", { key: key }); },
      setItem: function (key, value) { return call("storage", "storage.set", { key: key, value: String(value) }); },
      removeItem: function (key) { return call("storage", "storage.remove", { key: key }); },
      keys: function () { return call("storage", "storage.keys", null); }
    },
    addressBook: { list: function (opts) { return call("addressBook", "addressBook.list", opts || {}); } },
    fiat: { quote: function (opts) { return call("fiat", "fiat.quote", opts || {}); } },
    payment: {
      receive: function (opts) { return call("payment", "payment.receive", opts || {}); },
      lightningAccounts: function () { return call("payment", "payment.lightningAccounts", null); }
    },
    device: {
      info: function () { return call("device", "device.info", null); },
      authenticate: function (reason) { return call("device", "device.authenticate", { reason: reason }); }
    },
    haptic: function (kind) { return call("haptic", "haptic.fire", { kind: kind }); },
    clipboard: { write: function (text) { return call("clipboard", "clipboard.write", { text: String(text) }); } },
    share: {
      text: function (text) { return call("share", "share.text", { text: String(text) }); },
      file: function (fileName, text) { return call("share", "share.file", { fileName: fileName, text: String(text) }); }
    },
    wallet: {
      getAccounts: function (opts) { return call("wallet", "wallet.getAccounts", opts || {}); },
      getAssets: function (opts) { return call("wallet", "wallet.getAssets", opts || {}); },
      getNetworks: function (opts) { return call("wallet", "wallet.getNetworks", opts || {}); }
    },
    scan: function (opts) { return call("scan", "scan.read", opts || {}); },
    commerce: { collectAndCharge: function (opts) { return call("commerce", "collectAndCharge", opts || {}); } },
    merchant: { getIdentity: function () { return call("merchant", "merchant.getIdentity", null); } },
    poolAccess: { grant: function (opts) { return call("poolAccess", "poolAccess.grant", opts || {}); } },
    // Read the Access Issuer's public pool registry (GET /v1/pools). The sandbox
    // blocks fetch/XHR, so this network read runs natively. list({ issuerUrl,
    // caip2? }) -> { v, pools:[...] }.
    pools: { list: function (opts) { return call("pools", "pools.list", opts || {}); } },
    // Leave the mini app and open the user's Ethereum wallet on the chain a tx
    // used (navigation only; no data returned). open({ chainId?, address? }).
    walletView: { open: function (opts) { return call("walletView", "walletView.open", opts || {}); } }
  };

  Object.defineProperty(window, "ethereum", { value: ethereum, configurable: false, writable: false });
  Object.defineProperty(window, "maknoon", { value: maknoon, configurable: false, writable: false });

  window.__maknoonEmit = function (kind, ev, payload) { if (kind === "eth") ethEmitter.emit(ev, payload); };

  try {
    window.dispatchEvent(new Event("ethereum#initialized"));
    window.dispatchEvent(new Event("maknoon#initialized"));
  } catch (e) {}
})();
""".trimIndent()

/**
 * Reduce any device locale tag to a code the mini-app bundles actually ship.
 * Mirror of iOS MiniAppLocale.normalize.
 *
 * Longest-prefix, not first-match: `zh-Hant-TW` must reach `zh-Hant` rather than
 * being swallowed by `zh-Hans`, and `ckb` must not be confused with `ku`.
 */
private fun normalizeMiniAppLocale(tag: String): String {
    val lower = tag.lowercase().replace('_', '-')
    val codes = AppLanguageCatalog.all.map { it.code }
    codes.firstOrNull { it.lowercase() == lower }?.let { return it }
    codes.filter { lower.startsWith(it.lowercase() + "-") }
        .maxByOrNull { it.length }?.let { return it }
    val base = lower.substringBefore('-')
    codes.firstOrNull { it.lowercase() == base }?.let { return it }
    codes.firstOrNull { it.lowercase().startsWith("$base-") }?.let { return it }
    return "en"
}
