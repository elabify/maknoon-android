// Read-only "addressBook" and "fiat" bridge namespaces (Android port of the
// iOS MiniAppInfoBridgeHandlers).
//
// Both return only non-secret data and never sign or move funds:
//   * addressBook.list({chain}) -> the user's saved addresses for that chain,
//     including their own wallets, so a POS can offer a receive-address picker
//     instead of a raw text field.
//   * fiat.quote({chain, network?}) -> the configured fiat code + the native
//     coin's spot rate, so a dapp can show fiat-equivalents and offer
//     fiat-first amount entry.
//
// addressBook requires the "payment" permission (it exposes the user's address
// list); fiat is public market data and needs no grant.
//
// PLATFORM NOTE: Android does not yet have the cross-chain HolderStore address
// book / spot-price cache the iOS handlers read from. addressBook.list is
// backed by the per-chain wallet stores that exist today (ethereum via
// EthereumWalletStore); other known chains return an empty list. fiat.quote
// returns the configured fiat code + ticker + CoinGecko id with rate=null, the
// same null-rate contract iOS uses for testnets: the dapp falls back to
// crypto-only entry. Wiring a real price source is a follow-on.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.musnad.wallet.PrefsEthereumStore
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.walletPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * addressBook namespace: list the user's saved + own addresses for a chain.
 * Gated by the "wallet" permission (it reads receiving addresses; "payment" is
 * reserved for a future outbound-send handler). Returns [{name,address,network,isOwnWallet}].
 * Own wallets are surfaced first (they are the only entries Android backs today,
 * so every returned entry is isOwnWallet=true for now).
 */
class AddressBookBridgeHandler(context: Context) : MiniAppNamespaceHandler {
    override val namespace = "addressBook"
    override val requiredPermission: String? = "wallet"

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        return when (method) {
            "addressBook.list" -> {
                val chain = parseObject(argsJson)
                    ?.let { if (it.has("chain") && !it.isNull("chain")) it.optString("chain") else null }
                    ?: throw MiniAppBridgeError.invalidParams("addressBook.list requires a known `chain`")
                if (!isKnownChain(chain)) {
                    throw MiniAppBridgeError.invalidParams("addressBook.list requires a known `chain`")
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
                                .put("network", "ethereum")
                                .put("isOwnWallet", true),
                        )
                    }
                }
                out.toString()
            }
            else -> throw MiniAppBridgeError.unsupported("addressBook.$method")
        }
    }

    private fun isKnownChain(chain: String): Boolean = when (chain.lowercase()) {
        "bitcoin", "btc", "ethereum", "evm", "eth", "solana", "sol", "tron", "trx", "lightning", "ln" -> true
        else -> false
    }

    private fun isEthereum(chain: String): Boolean = when (chain.lowercase()) {
        "ethereum", "evm", "eth" -> true
        else -> false
    }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}

/**
 * fiat namespace: public market data, no grant required.
 * fiat.quote({chain, network?}) -> { fiatCode, ticker, coinId, rate }.
 * rate is null when no spot price is available (no price source wired on
 * Android yet, and always null on testnets); the dapp then falls back to
 * crypto-only entry. coinId is the CoinGecko id (null for unpriced chains).
 */
class FiatBridgeHandler(context: Context) : MiniAppNamespaceHandler {
    override val namespace = "fiat"
    override val requiredPermission: String? = null

    private val appContext = context.applicationContext

    override suspend fun handle(method: String, argsJson: String): String {
        return when (method) {
            "fiat.quote" -> {
                val p = parseObject(argsJson)
                val chain = p?.let { if (it.has("chain") && !it.isNull("chain")) it.optString("chain") else null }
                    ?: throw MiniAppBridgeError.invalidParams("fiat.quote requires `chain`")
                val network = p.let { if (it.has("network") && !it.isNull("network")) it.optString("network") else null }
                val fiatCode = fiatCode()
                val (coinId, ticker) = coin(chain, network)
                // One native unit in the user's fiat via the shared price cache.
                // Null when reference prices are disabled or the coin is a testnet
                // asset (coinId null), the dapp then falls back to crypto-only entry.
                val rate = if (coinId != null) FiatReference.unitPrice(coinId) else null
                JSONObject().apply {
                    put("fiatCode", fiatCode.uppercase())
                    put("ticker", ticker)
                    // org.json drops keys whose value is null; emit an explicit
                    // JSON null so the JS side sees the key present.
                    put("coinId", coinId ?: JSONObject.NULL)
                    put("rate", rate ?: JSONObject.NULL)
                }.toString()
            }
            else -> throw MiniAppBridgeError.unsupported("fiat.$method")
        }
    }

    private fun fiatCode(): String =
        runCatching {
            com.elabify.app.maknoon.ui.settings.FiatPreferences
                .also { it.init(appContext) }.code
        }.getOrDefault("usd")

    /** Map a chain (+ EVM network rawValue) to its CoinGecko id + ticker. */
    private fun coin(chain: String, network: String?): Pair<String?, String> = when (chain.lowercase()) {
        "bitcoin", "btc" -> "bitcoin" to "BTC"
        // Priced as BTC; the POS converts the BTC rate to sats.
        "lightning", "ln" -> "bitcoin" to "sats"
        "solana", "sol" -> "solana" to "SOL"
        "tron", "trx" -> "tron" to "TRX"
        "ethereum", "evm", "eth" -> {
            val net = network?.let { EthereumNetwork.fromRawValue(it) }
            if (net != null) net.coinGeckoAssetId to net.ticker else "ethereum" to "ETH"
        }
        else -> null to ""
    }

    private fun parseObject(argsJson: String): JSONObject? =
        runCatching { JSONObject(argsJson) }.getOrNull()
}
