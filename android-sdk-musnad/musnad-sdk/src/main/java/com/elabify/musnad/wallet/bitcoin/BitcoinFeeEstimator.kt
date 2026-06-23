// Mempool.space (or Esplora-compatible) recommended-fees client. Returns
// the four standard tiers used by Sparrow's fee picker: fastest,
// half-hour, hour, economy. Numbers are sats per vByte, rounded up.
// Ported 1:1 from iOS BitcoinFeeEstimator.swift.

package com.elabify.musnad.wallet.bitcoin

import com.elabify.musnad.net.MaknoonHttp
import org.json.JSONObject

/** The fee tier the user picked in the Send view. The `CUSTOM` case
 *  resolves to 0 here so the UI substitutes the user-entered rate.
 *  Mirror of iOS `BitcoinSendView.FeeMode`. */
enum class BitcoinFeeMode { FASTEST, HALF_HOUR, HOUR, ECONOMY, CUSTOM }

/** The four recommended fee tiers from mempool.space. */
data class FeeRecommended(
    val fastestFee: Long,
    val halfHourFee: Long,
    val hourFee: Long,
    val economyFee: Long,
    val minimumFee: Long,
) {
    fun satsPerVb(mode: BitcoinFeeMode): Long = when (mode) {
        BitcoinFeeMode.FASTEST -> fastestFee
        BitcoinFeeMode.HALF_HOUR -> halfHourFee
        BitcoinFeeMode.HOUR -> hourFee
        BitcoinFeeMode.ECONOMY -> economyFee
        BitcoinFeeMode.CUSTOM -> 0
    }

    companion object {
        fun fromJson(o: JSONObject): FeeRecommended = FeeRecommended(
            fastestFee = o.getLong("fastestFee"),
            halfHourFee = o.getLong("halfHourFee"),
            hourFee = o.getLong("hourFee"),
            economyFee = o.getLong("economyFee"),
            minimumFee = o.getLong("minimumFee"),
        )
    }
}

object BitcoinFeeEstimator {

    /** Safe fallback so the Send view never blocks on the network.
     *  Identical values to iOS. */
    val fallback = FeeRecommended(
        fastestFee = 25,
        halfHourFee = 10,
        hourFee = 5,
        economyFee = 2,
        minimumFee = 1,
    )

    /** Fetch the four recommended fee tiers from
     *  `<baseURL>/api/v1/fees/recommended`. Falls back to [fallback] on
     *  any network/parse error. */
    fun fetch(baseURL: String, http: MaknoonHttp = MaknoonHttp()): FeeRecommended = try {
        val body = http.getJson("$baseURL/api/v1/fees/recommended")
        FeeRecommended.fromJson(JSONObject(body))
    } catch (_: Throwable) {
        fallback
    }
}
