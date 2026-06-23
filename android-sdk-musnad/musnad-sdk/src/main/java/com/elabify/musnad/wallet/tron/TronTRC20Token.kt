// TRC-20 token model, ported 1:1 from iOS TronTRC20Token.swift:
// per-(network, contract) persistable metadata, decimals-aware
// formatter, source provenance.

package com.elabify.musnad.wallet.tron

import org.json.JSONObject

enum class TronTokenSource(val rawValue: String) {
    /** Auto-installed after matching the user's wallet activity against
     *  the cached verified list. */
    TRONSCAN("tronscan"),
    /** User typed in decimals + symbol manually. No trust anchor;
     *  surface clearly in the UI ("Custom"). */
    CUSTOM("custom");

    companion object {
        fun fromRawValue(raw: String): TronTokenSource =
            entries.firstOrNull { it.rawValue == raw } ?: CUSTOM
    }
}

data class TronTRC20Token(
    val network: TronNetwork,
    /** Base58 T-prefixed contract address. */
    val contract: String,
    var symbol: String,
    var name: String,
    val decimals: Int,
    var logoURI: String? = null,
    var source: TronTokenSource,
) {
    val id: String get() = "${network.rawValue}:$contract"

    /** CoinGecko asset id derived from the token symbol. Null when no
     *  price feed; the send view hides the fiat picker for those. */
    val coinGeckoId: String?
        get() = when (symbol.uppercase()) {
            "USDT" -> "tether"
            "USDC" -> "usd-coin"
            "USDD" -> "usdd"
            "TUSD" -> "true-usd"
            "BTT" -> "bittorrent"
            "WIN" -> "wink"
            "JST" -> "just"
            "SUN" -> "sun-token"
            else -> null
        }

    /** Format a raw amount string (on-chain integer as base-10 decimal)
     *  for display. TRC-20 amounts can exceed 64 bits so the input is a
     *  string, processed digit-by-digit. Mirror of iOS
     *  `format(rawAmountDecimal:)`. */
    fun format(rawAmountDecimal: String): String {
        val s = rawAmountDecimal.trim()
        if (s.isEmpty() || !s.all { it.isDigit() }) return "0"
        val d = decimals
        if (d == 0) return s
        val padded = "0".repeat(maxOf(0, d + 1 - s.length)) + s
        val split = padded.length - d
        var whole = padded.substring(0, split)
        var frac = padded.substring(split)
        if (whole.isEmpty()) whole = "0"
        while (whole.length > 1 && whole.first() == '0') whole = whole.substring(1)
        while (frac.isNotEmpty() && frac.last() == '0') frac = frac.substring(0, frac.length - 1)
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("network", network.rawValue)
            .put("contract", contract)
            .put("symbol", symbol)
            .put("name", name)
            .put("decimals", decimals)
            .put("source", source.rawValue)
        logoURI?.let { o.put("logoURI", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): TronTRC20Token? {
            val net = TronNetwork.fromRawValue(o.optString("network")) ?: return null
            return TronTRC20Token(
                network = net,
                contract = o.getString("contract"),
                symbol = o.getString("symbol"),
                name = o.getString("name"),
                decimals = o.optInt("decimals", 0),
                logoURI = if (o.has("logoURI") && !o.isNull("logoURI")) o.optString("logoURI") else null,
                source = TronTokenSource.fromRawValue(o.optString("source", "custom")),
            )
        }
    }
}
