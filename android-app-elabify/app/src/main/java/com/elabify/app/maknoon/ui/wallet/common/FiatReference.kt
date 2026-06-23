// App-side glue over the SDK AssetPriceCache. Applies the one global gate the
// SDK cache deliberately does not know about (FiatPreferences.showReferencePrices
// + the selected currency) and centralizes the two operations every wallet needs:
//
//   - caption(assetId, amount):  the dashboard "≈ <fiat>" line under a balance.
//   - unitPrice(assetId):        one native unit in the selected fiat, for the
//                                send-screen fiat <-> native live conversion.
//
// [assetId] is the CoinGecko id for the active network (EthereumNetwork/
// SolanaNetwork/TronNetwork.coinGeckoAssetId, "bitcoin" for BTC + Lightning), and
// is null on testnets, which is how fiat is suppressed there. All work runs off
// the main thread; both functions soft-fail to null (caption/option hidden).

package com.elabify.app.maknoon.ui.wallet.common

import com.elabify.app.maknoon.ui.settings.FiatPreferences
import com.elabify.musnad.wallet.pricing.AssetPriceCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FiatReference {

    /** Whether fiat references should be offered at all for [assetId]. False when
     *  the user disabled reference prices or the asset is null (testnet). */
    fun enabledFor(assetId: String?): Boolean =
        assetId != null && FiatPreferences.showReferencePrices

    /** Push the user's (overridable) price-source URLs into the shared cache so a
     *  Settings change applies live. The single place these third-party hosts are
     *  configured; gating on showReferencePrices happens in [enabledFor]. */
    private fun applyEndpointOverrides() {
        AssetPriceCache.baseURL = FiatPreferences.coinGeckoBaseURL
        AssetPriceCache.fxBaseURL = FiatPreferences.fxBaseURL
    }

    /** Dashboard caption, e.g. "≈ $5,234.12", for [amount] native units. Null when
     *  disabled, testnet (assetId null), zero, or no rate is available. */
    suspend fun caption(assetId: String?, amount: Double): String? {
        if (!enabledFor(assetId) || amount <= 0.0) return null
        applyEndpointOverrides()
        val fiat = FiatPreferences.code
        return withContext(Dispatchers.IO) {
            AssetPriceCache.refreshPrice(assetId!!, fiat)
            AssetPriceCache.fiatCaption(amount, assetId, fiat)
        }
    }

    /** Price of ONE native unit of [assetId] in the selected fiat, for send-screen
     *  conversion. Null when disabled / testnet / no rate. */
    suspend fun unitPrice(assetId: String?): Double? {
        if (!enabledFor(assetId)) return null
        applyEndpointOverrides()
        val fiat = FiatPreferences.code
        return withContext(Dispatchers.IO) { AssetPriceCache.refreshPrice(assetId!!, fiat) }
    }

    /** Format an already-computed fiat [value] with the selected currency symbol,
     *  e.g. "$12.34". For send-screen captions that compute the value locally. */
    fun format(value: Double): String =
        AssetPriceCache.formatCurrency(value, FiatPreferences.code)

    /** The selected fiat code, uppercased, for denomination labels (e.g. "USD"). */
    fun fiatLabel(): String = FiatPreferences.code.uppercase()
}
