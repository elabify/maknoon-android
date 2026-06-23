// An ERC-20 token instance pinned to a specific EVM network. 1:1 port of
// EthereumToken.swift. A single logical asset (USDC) appears once per network,
// each with its own contract address. Decimals are stored explicitly.

package com.elabify.musnad.wallet.ethereum

data class EthereumToken(
    val network: EthereumNetwork,
    /** Lowercased 0x-prefixed contract address (normalised in the factory). */
    val contractAddress: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
    /** True for curated entries shipped with the app. User-added entries false. */
    val curated: Boolean,
) {
    /** Stable id: "<network.rawValue>:<lowercase contract>". */
    val id: String get() = "${network.rawValue}:$contractAddress"

    /** CoinGecko asset id derived from symbol; null if no feed. */
    val coinGeckoId: String?
        get() = when (symbol.uppercase()) {
            "USDC" -> "usd-coin"
            "USDT" -> "tether"
            "DAI" -> "dai"
            "WBTC" -> "bitcoin"
            "WETH" -> "ethereum"
            else -> null
        }

    companion object {
        fun create(
            network: EthereumNetwork,
            contractAddress: String,
            symbol: String,
            name: String,
            decimals: Int,
            curated: Boolean,
        ) = EthereumToken(network, contractAddress.lowercase(), symbol, name, decimals, curated)
    }
}
