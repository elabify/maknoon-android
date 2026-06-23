// SPL token model, ported 1:1 from iOS SolanaSPLToken.swift. Persistable
// metadata for one fungible token the user tracks; the mint address is
// the canonical identity (base58 32-byte Solana pubkey). Equality is by
// (network, mint).

package com.elabify.musnad.wallet.solana

import org.json.JSONObject

/** Where an installed token's metadata originated. */
enum class SolanaTokenSource(val rawValue: String) {
    /** Auto-installed after matching wallet activity against the cached
     *  verified list. */
    JUPITER("jupiter"),
    /** From the on-chain Metaplex Token Metadata Program. */
    METAPLEX("metaplex"),
    /** User typed in decimals + symbol manually. No trust anchor. */
    CUSTOM("custom");

    companion object {
        fun fromRawValue(raw: String): SolanaTokenSource =
            entries.firstOrNull { it.rawValue == raw } ?: CUSTOM
    }
}

data class SolanaSPLToken(
    val network: SolanaNetwork,
    val mint: String,
    var symbol: String,
    var name: String,
    val decimals: Int,
    var logoURI: String? = null,
    var source: SolanaTokenSource,
) {
    val id: String get() = "${network.rawValue}:$mint"

    /** CoinGecko asset id derived from the token symbol, for fiat
     *  captions. Null = no price feed. Mirrors iOS coinGeckoId. */
    val coinGeckoId: String?
        get() = when (symbol.uppercase()) {
            "USDC" -> "usd-coin"
            "USDT" -> "tether"
            "DAI" -> "dai"
            "WBTC" -> "bitcoin"
            "WETH" -> "ethereum"
            "BONK" -> "bonk"
            "JUP" -> "jupiter-exchange-solana"
            "PYTH" -> "pyth-network"
            "JTO" -> "jito-governance-token"
            else -> null
        }

    /** Format a raw token-account amount for display, applying decimals
     *  and trimming trailing zeros. Mirrors iOS format(rawAmount:). */
    fun format(rawAmount: Long): String {
        val s = rawAmount.toString()
        val d = decimals
        if (d == 0) return s
        val padded = "0".repeat(maxOf(0, d + 1 - s.length)) + s
        val split = padded.length - d
        var whole = padded.substring(0, split)
        var frac = padded.substring(split)
        if (whole.isEmpty()) whole = "0"
        while (whole.length > 1 && whole.first() == '0') whole = whole.substring(1)
        while (frac.isNotEmpty() && frac.last() == '0') frac = frac.dropLast(1)
        return if (frac.isEmpty()) whole else "$whole.$frac"
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("network", network.rawValue)
            .put("mint", mint)
            .put("symbol", symbol)
            .put("name", name)
            .put("decimals", decimals)
            .put("source", source.rawValue)
        logoURI?.let { o.put("logoURI", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SolanaSPLToken? {
            val net = SolanaNetwork.fromRawValue(o.optString("network")) ?: return null
            return SolanaSPLToken(
                network = net,
                mint = o.getString("mint"),
                symbol = o.getString("symbol"),
                name = o.getString("name"),
                decimals = o.getInt("decimals"),
                logoURI = if (o.has("logoURI") && !o.isNull("logoURI")) o.optString("logoURI") else null,
                source = SolanaTokenSource.fromRawValue(o.optString("source")),
            )
        }
    }
}
