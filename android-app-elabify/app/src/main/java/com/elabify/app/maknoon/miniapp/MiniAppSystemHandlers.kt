// Low-friction native capabilities for mini apps (Android port of the iOS
// MiniAppSystemHandlers):
//   * device  (auto)      - read host context + a biometric gate for the
//                           dApp's own sensitive screens (returns only a bool).
//   * haptic  (auto)      - success/error/impact feedback.
//   * clipboard (install) - write-only copy (never reads the clipboard).
//   * share   (install)   - system share sheet (user-mediated) + file export.
//   * wallet  (install)   - read the user's own wallet addresses per chain.
//
// Nothing here exposes raw sensors or keys: authenticate returns ok/!ok,
// device.info is non-secret context, wallet.getAccounts is public addresses.
//
// Bridge contract: handlers are string-in / string-out (JSON). argsJson is the
// JS `params` serialized ("null" when none); the returned string IS the
// resolved promise value, so it must be valid JSON (use "null" for void).
// Throw MiniAppBridgeError to reject with a specific code.

package com.elabify.app.maknoon.miniapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.ui.BiometricGate
import com.elabify.app.maknoon.ui.wallet.solana.SolanaEnv
import com.elabify.app.maknoon.ui.wallet.common.orderChainsForMenu
import com.elabify.musnad.wallet.PrefsEthereumStore
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumTokenCatalog
import com.elabify.musnad.wallet.ethereum.EthereumTokenStore
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronTRC20TokenStore
import com.elabify.musnad.wallet.walletPrefs
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * device namespace: non-secret host context + a biometric gate.
 *
 * device.info returns the host theme, locale, configured fiat code, and the
 * app version. device.authenticate runs a BiometricPrompt (strong biometric
 * with device-credential fallback) and returns only { ok: Bool } so the dApp
 * can gate its own sensitive screens without ever touching key material.
 *
 * The biometric prompt needs a FragmentActivity, supplied lazily by the host
 * (the visible activity hosting the WebView). When none is available the gate
 * reports { ok: false, reason: "unavailable" }, matching iOS.
 */
class DeviceBridgeHandler(
    private val context: Context,
    private val activityProvider: () -> FragmentActivity? = { null },
) : MiniAppNamespaceHandler {
    override val namespace = "device"
    override val requiredPermission: String? = null

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        return when (method) {
            "device.info" -> {
                val dark = (appContext.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                JSONObject().apply {
                    put("theme", if (dark) "dark" else "light")
                    put("locale", currentLocale())
                    put("fiatCode", fiatCode().uppercase())
                    put("appVersion", appVersion())
                }.toString()
            }
            "device.authenticate" -> {
                val reason = parseObject(argsJson)?.optString("reason", "Authenticate")
                    ?.ifEmpty { "Authenticate" } ?: "Authenticate"
                val activity = activityProvider()
                if (activity == null ||
                    BiometricGate.availability(activity) == BiometricGate.Availability.UNAVAILABLE
                ) {
                    JSONObject().put("ok", false).put("reason", "unavailable").toString()
                } else {
                    val ok = BiometricGate.authenticate(activity, title = "Authenticate", subtitle = reason)
                    if (ok) {
                        JSONObject().put("ok", true).toString()
                    } else {
                        JSONObject().put("ok", false).put("reason", "failed").toString()
                    }
                }
            }
            else -> throw MiniAppBridgeError.unsupported("device.$method")
        }
    }

    @Suppress("DEPRECATION")
    private fun currentLocale(): String {
        val cfg = appContext.resources.configuration
        return cfg.locales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
            ?: cfg.locale?.toLanguageTag()
            ?: "und"
    }

    private fun fiatCode(): String =
        runCatching {
            EthereumSettings(PrefsEthereumStore(walletPrefs(appContext)))
                .also { it.reload() }.fiatCode
        }.getOrDefault("usd")

    private fun appVersion(): String =
        runCatching {
            val pm = appContext.packageManager
            pm.getPackageInfo(appContext.packageName, 0).versionName ?: "0"
        }.getOrDefault("0")

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}

/**
 * haptic namespace: success/warning/error/impact feedback. Fire-and-forget;
 * returns "null". Uses VibrationEffect predefined effects where available and
 * a short one-shot fallback for plain impacts.
 */
class HapticBridgeHandler(context: Context) : MiniAppNamespaceHandler {
    override val namespace = "haptic"
    override val requiredPermission: String? = null

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        if (method != "haptic.fire") throw MiniAppBridgeError.unsupported("haptic.$method")
        val kind = parseObject(argsJson)?.optString("kind", "light")?.ifEmpty { "light" } ?: "light"
        val vibrator = vibrator() ?: return "null"
        if (!vibrator.hasVibrator()) return "null"
        val effect = when (kind) {
            "success" -> VibrationEffect.createWaveform(longArrayOf(0, 20, 60, 30), -1)
            "warning" -> VibrationEffect.createWaveform(longArrayOf(0, 30, 80, 30), -1)
            "error" -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40, 60, 40), -1)
            "heavy" -> VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)
            "medium" -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            else -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        runCatching { vibrator.vibrate(effect) }
        return "null"
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}

/**
 * clipboard namespace: write-only copy. clipboard.write({text}) puts text on
 * the system clipboard; there is intentionally no read path. Returns "null".
 */
class ClipboardBridgeHandler(context: Context) : MiniAppNamespaceHandler {
    override val namespace = "clipboard"
    override val requiredPermission: String? = "clipboard"

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        if (method != "clipboard.write") throw MiniAppBridgeError.unsupported("clipboard.$method")
        val text = parseObject(argsJson)?.let { if (it.has("text") && !it.isNull("text")) it.optString("text") else null }
            ?: throw MiniAppBridgeError.invalidParams("clipboard.write requires `text`")
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw MiniAppBridgeError.internalError("clipboard unavailable")
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
        return "null"
    }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}

/**
 * share namespace: the system share sheet (user-mediated).
 *   * share.text({text})          - share a plain string.
 *   * share.file({fileName,text}) - write text to a temp file and share it
 *                                   (e.g. a CSV receipt) via a FileProvider URI.
 * Returns "null" once the chooser is launched. The user, not the dApp, decides
 * where the content goes.
 *
 * Launching a chooser needs an Activity context to start the chooser as a new
 * task from the host. We use the application context with FLAG_ACTIVITY_NEW_TASK
 * so a handler running off the activity can still present the sheet.
 */
class ShareBridgeHandler(
    context: Context,
    /** Authority of the app's <provider> for sharing temp files. */
    private val fileProviderAuthority: String = context.applicationContext.packageName + ".fileprovider",
) : MiniAppNamespaceHandler {
    override val namespace = "share"
    override val requiredPermission: String? = "share"

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        val p = parseObject(argsJson)
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        when (method) {
            "share.text" -> {
                val text = p?.let { if (it.has("text") && !it.isNull("text")) it.optString("text") else null }
                    ?: throw MiniAppBridgeError.invalidParams("share.text requires `text`")
                send.putExtra(Intent.EXTRA_TEXT, text)
            }
            "share.file" -> {
                val name = p?.let { if (it.has("fileName") && !it.isNull("fileName")) it.optString("fileName") else null }
                val text = p?.let { if (it.has("text") && !it.isNull("text")) it.optString("text") else null }
                if (name == null || text == null) {
                    throw MiniAppBridgeError.invalidParams("share.file requires { fileName, text }")
                }
                val uri = writeTempFile(name, text)
                send.putExtra(Intent.EXTRA_STREAM, uri)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            else -> throw MiniAppBridgeError.unsupported("share.$method")
        }
        val chooser = Intent.createChooser(send, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(chooser) }
            .getOrElse { throw MiniAppBridgeError.internalError("no presenter") }
        return "null"
    }

    private fun writeTempFile(name: String, text: String): Uri {
        val safe = name.replace("/", "_")
        // Shared with a FileProvider rooted at the app cache (share_cache path).
        val dir = File(appContext.cacheDir, "miniapp-share").apply { mkdirs() }
        val file = File(dir, safe)
        runCatching { file.writeText(text, Charsets.UTF_8) }
            .getOrElse { throw MiniAppBridgeError.internalError("could not write file") }
        return runCatching { FileProvider.getUriForFile(appContext, fileProviderAuthority, file) }
            .getOrElse { throw MiniAppBridgeError.internalError("could not share file") }
    }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}

/**
 * wallet namespace: read the user's OWN wallet addresses for a chain.
 *
 * wallet.getAccounts({chain}) returns [{ name, address, network }] for the
 * given chain. On Android the multi-chain address book is not yet wired into
 * the mini-app host, so only the "ethereum"/"evm" chain is backed today (from
 * the user's EthereumWalletStore); other known chains return an empty list
 * rather than an error so a POS can degrade gracefully. signMessage is a
 * planned follow-on; EVM apps can personal_sign today via window.ethereum.
 */
class WalletBridgeHandler(context: Context) : MiniAppNamespaceHandler {
    override val namespace = "wallet"
    override val requiredPermission: String? = "wallet"

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        return when (method) {
            "wallet.getAccounts" -> {
                val chain = parseObject(argsJson)
                    ?.let { if (it.has("chain") && !it.isNull("chain")) it.optString("chain") else null }
                    ?: throw MiniAppBridgeError.invalidParams("wallet.getAccounts requires a known `chain`")
                if (!isKnownChain(chain)) {
                    throw MiniAppBridgeError.invalidParams("wallet.getAccounts requires a known `chain`")
                }
                val out = JSONArray()
                if (isEthereum(chain)) {
                    val store = EthereumWalletStore(PrefsEthereumStore(walletPrefs(appContext)))
                    runCatching { store.reload() }
                    for (w in store.wallets) {
                        val addr = w.address ?: continue
                        out.put(
                            JSONObject()
                                .put("name", w.label)
                                .put("address", addr)
                                .put("network", "ethereum"),
                        )
                    }
                }
                out.toString()
            }
            "wallet.getAssets" -> {
                // PoS 1.0.1 Milestone 1 (B): the assets a chain/network can pay
                // in. Native first, then the chain's known stablecoins (USDC then
                // USDT), then any tokens the user holds in the token store, deduped
                // by contract/mint. No live RPC here: an optional `balance` string
                // is included only when the token store already has one cached
                // (today the stores carry no balances, so it is omitted). Field
                // names + ordering match iOS exactly.
                val obj = parseObject(argsJson)
                val chain = obj
                    ?.let { if (it.has("chain") && !it.isNull("chain")) it.optString("chain") else null }
                    ?: throw MiniAppBridgeError.invalidParams("wallet.getAssets requires a known `chain`")
                if (!isKnownChain(chain)) {
                    throw MiniAppBridgeError.invalidParams("wallet.getAssets requires a known `chain`")
                }
                val networkParam = obj
                    .let { if (it.has("network") && !it.isNull("network")) it.optString("network") else null }
                    ?.ifEmpty { null }
                assetsFor(chain, networkParam).toString()
            }
            "wallet.getNetworks" -> {
                // The wallet's supported networks for a chain family, ordered
                // like the Add-wallet "Chain to scan" dropdowns (orderChainsForMenu):
                // primary mainnet first, the other mainnets alphabetically, then
                // testnets alphabetically (a consumer can split on isTestnet).
                // -> [{ id, label, isTestnet }]. Field names + ordering match iOS.
                val chain = parseObject(argsJson)
                    ?.let { if (it.has("chain") && !it.isNull("chain")) it.optString("chain") else null }
                    ?: throw MiniAppBridgeError.invalidParams("wallet.getNetworks requires a known `chain`")
                if (!isKnownChain(chain)) {
                    throw MiniAppBridgeError.invalidParams("wallet.getNetworks requires a known `chain`")
                }
                networksFor(chain).toString()
            }
            else -> throw MiniAppBridgeError.unsupported("wallet.$method")
        }
    }

    private fun isKnownChain(chain: String): Boolean = when (chain.lowercase()) {
        "bitcoin", "btc", "ethereum", "evm", "eth", "solana", "sol", "tron", "trx" -> true
        else -> false
    }

    private fun isEthereum(chain: String): Boolean = when (chain.lowercase()) {
        "ethereum", "evm", "eth" -> true
        else -> false
    }

    private fun isSolana(chain: String): Boolean = when (chain.lowercase()) {
        "solana", "sol" -> true
        else -> false
    }

    private fun isTron(chain: String): Boolean = when (chain.lowercase()) {
        "tron", "trx" -> true
        else -> false
    }

    private fun isBitcoin(chain: String): Boolean = when (chain.lowercase()) {
        "bitcoin", "btc" -> true
        else -> false
    }

    // MARK: -- wallet.getAssets

    /** One asset entry. `contract` carries the EVM/Tron contract or the Solana
     *  mint; it is JSON null for native coins. `balance` is omitted entirely
     *  unless a cached value is available (it never is today). */
    private fun asset(symbol: String, name: String, contract: String?, decimals: Int, kind: String): JSONObject =
        JSONObject()
            .put("symbol", symbol)
            .put("name", name)
            .put("contract", contract ?: JSONObject.NULL)
            .put("decimals", decimals)
            .put("kind", kind)

    private fun assetsFor(chain: String, networkParam: String?): JSONArray = when {
        isEthereum(chain) -> ethereumAssets(networkParam)
        isSolana(chain) -> solanaAssets(networkParam)
        isTron(chain) -> tronAssets(networkParam)
        // Bitcoin/Lightning: the POS hides the asset field; return native BTC
        // defensively if asked.
        isBitcoin(chain) -> JSONArray().put(asset("BTC", "Bitcoin", null, 8, "native"))
        else -> JSONArray()
    }

    /** Native asset first, then every other asset sorted alphabetically by
     *  symbol (case-insensitive). Field names + ordering match iOS exactly. */
    private fun nativeFirstSorted(native: JSONObject, rest: List<JSONObject>): JSONArray {
        val out = JSONArray()
        out.put(native)
        rest.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.optString("symbol") })
            .forEach { out.put(it) }
        return out
    }

    // MARK: -- wallet.getNetworks

    /** Build the ordered [{ id, label, isTestnet }] array for a chain family,
     *  reusing orderChainsForMenu so the order matches the Add-wallet dropdowns:
     *  primary mainnet first, other mainnets alphabetically, then testnets
     *  alphabetically. */
    private fun <T> orderedNetworks(
        all: List<T>,
        primary: T,
        isTestnet: (T) -> Boolean,
        id: (T) -> String,
        label: (T) -> String,
    ): JSONArray {
        val order = orderChainsForMenu(all = all, primary = primary, isTestnet = isTestnet, name = label)
        val out = JSONArray()
        for (n in order.mainnets + order.testnets) {
            out.put(
                JSONObject()
                    .put("id", id(n))
                    .put("label", label(n))
                    .put("isTestnet", isTestnet(n)),
            )
        }
        return out
    }

    private fun networksFor(chain: String): JSONArray = when {
        isEthereum(chain) -> orderedNetworks(
            all = EthereumNetwork.entries.toList(), primary = EthereumNetwork.MAINNET,
            isTestnet = { it.isTestnet }, id = { it.rawValue }, label = { it.displayName },
        )
        // Solana/Tron/Bitcoin enums have no isTestnet member; mainnet is the only
        // non-testnet for each, so derive it inline.
        isSolana(chain) -> orderedNetworks(
            all = SolanaNetwork.entries.toList(), primary = SolanaNetwork.MAINNET,
            isTestnet = { it != SolanaNetwork.MAINNET }, id = { it.rawValue }, label = { it.displayName },
        )
        isTron(chain) -> orderedNetworks(
            all = TronNetwork.entries.toList(), primary = TronNetwork.MAINNET,
            isTestnet = { it != TronNetwork.MAINNET }, id = { it.rawValue }, label = { it.displayName },
        )
        isBitcoin(chain) -> orderedNetworks(
            all = BitcoinNetwork.entries.toList(), primary = BitcoinNetwork.MAINNET,
            isTestnet = { it != BitcoinNetwork.MAINNET }, id = { it.rawValue }, label = { it.displayName },
        )
        else -> JSONArray()
    }

    private fun ethereumAssets(networkParam: String?): JSONArray {
        val net = networkParam?.let { p -> EthereumNetwork.entries.firstOrNull { it.rawValue == p } }
            ?: EthereumNetwork.MAINNET
        val rest = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        // Curated reputable tokens (USDC/USDT/...) then user-held tokens, deduped.
        for (t in EthereumTokenCatalog.reputable(net)) {
            val key = t.contractAddress.lowercase()
            if (!seen.add(key)) continue
            rest.add(asset(t.symbol, t.name, t.contractAddress, t.decimals, "erc20"))
        }
        val store = EthereumTokenStore(PrefsEthereumStore(walletPrefs(appContext)))
        for (t in store.tokens(net)) {
            val key = t.contractAddress.lowercase()
            if (!seen.add(key)) continue
            rest.add(asset(t.symbol, t.name, t.contractAddress, t.decimals, "erc20"))
        }
        return nativeFirstSorted(asset(net.ticker, net.displayName, null, 18, "native"), rest)
    }

    private fun solanaAssets(networkParam: String?): JSONArray {
        val net = networkParam?.let { p -> SolanaNetwork.entries.firstOrNull { it.rawValue == p } }
            ?: SolanaNetwork.MAINNET
        val rest = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        // VERIFIED stablecoin mints (Circle / Tether) then user-held SPL tokens.
        for (s in solanaStablecoins(net)) {
            if (!seen.add(s.mint)) continue
            rest.add(asset(s.symbol, s.name, s.mint, s.decimals, "spl"))
        }
        // There is no Solana token-store singleton; reuse the same store the
        // Solana wallet env builds over the shared wallet prefs.
        val store = runCatching { SolanaEnv.get(appContext).tokenStore }.getOrNull()
        if (store != null) {
            for (t in store.tokens(net)) {
                if (!seen.add(t.mint)) continue
                rest.add(asset(t.symbol, t.name, t.mint, t.decimals, "spl"))
            }
        }
        return nativeFirstSorted(asset("SOL", "Solana", null, 9, "native"), rest)
    }

    private fun tronAssets(networkParam: String?): JSONArray {
        val net = networkParam?.let { p -> TronNetwork.entries.firstOrNull { it.rawValue == p } }
            ?: TronNetwork.MAINNET
        val rest = ArrayList<JSONObject>()
        val seen = HashSet<String>()
        for (s in tronStablecoins(net)) {
            if (!seen.add(s.contract)) continue
            rest.add(asset(s.symbol, s.name, s.contract, s.decimals, "trc20"))
        }
        val store = TronTRC20TokenStore(walletPrefs(appContext))
        for (t in store.tokens(net)) {
            if (!seen.add(t.contract)) continue
            rest.add(asset(t.symbol, t.name, t.contract, t.decimals, "trc20"))
        }
        return nativeFirstSorted(asset("TRX", "Tron", null, 6, "native"), rest)
    }

    // MARK: -- baked-in stablecoin constants (verified Circle / Tether / Tronscan)

    private data class StableMint(val symbol: String, val name: String, val mint: String, val decimals: Int)
    private data class StableContract(val symbol: String, val name: String, val contract: String, val decimals: Int)

    /** Solana USDC/USDT mints. Mainnet has both; devnet has USDC only;
     *  testnet has no canonical stablecoin. */
    private fun solanaStablecoins(network: SolanaNetwork): List<StableMint> = when (network) {
        SolanaNetwork.MAINNET -> listOf(
            StableMint("USDC", "USD Coin", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6),
            StableMint("USDT", "Tether USD", "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB", 6),
        )
        SolanaNetwork.DEVNET -> listOf(
            StableMint("USDC", "USD Coin", "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU", 6),
        )
        SolanaNetwork.TESTNET -> emptyList()
    }

    /** Tron USDT (TRC-20). Mainnet only; Circle does not support USDC on Tron
     *  and the Nile/Shasta testnets have no canonical stablecoin. */
    private fun tronStablecoins(network: TronNetwork): List<StableContract> = when (network) {
        TronNetwork.MAINNET -> listOf(
            StableContract("USDT", "Tether USD", "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6),
        )
        TronNetwork.SHASTA, TronNetwork.NILE -> emptyList()
    }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}
