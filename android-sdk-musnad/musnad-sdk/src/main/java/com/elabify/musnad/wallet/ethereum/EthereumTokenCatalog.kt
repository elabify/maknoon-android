// Two views of the same per-network token data, 1:1 port of
// EthereumTokenCatalog.swift:
//   firstRunSeed(for:) - tight set auto-installed on a fresh launch (USDC only).
//   reputable(for:)    - broader verified set used by auto-discover. This list
//                        IS the offline trust anchor for "this contract is USDC".
// All addresses are lowercased by EthereumToken.create.

package com.elabify.musnad.wallet.ethereum

object EthereumTokenCatalog {

    /** First-run seed: just USDC on every chain with an official deployment. */
    fun firstRunSeed(network: EthereumNetwork): List<EthereumToken> =
        reputable(network).filter { it.symbol == "USDC" }

    /** Reputable token list used by auto-discover. */
    fun reputable(network: EthereumNetwork): List<EthereumToken> {
        fun t(c: String, sym: String, name: String, dec: Int) =
            EthereumToken.create(network, c, sym, name, dec, curated = true)
        return when (network) {
            EthereumNetwork.MAINNET -> listOf(
                t("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", "USD Coin", 6),
                t("0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", "Tether USD", 6),
                t("0x6b175474e89094c44da98b954eedeac495271d0f", "DAI", "Dai Stablecoin", 18),
                t("0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", "WETH", "Wrapped Ether", 18),
                t("0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", "WBTC", "Wrapped Bitcoin", 8),
                t("0x514910771af9ca656af840dff83e8264ecf986ca", "LINK", "Chainlink", 18),
                t("0x1f9840a85d5af5bf1d1762f925bdaddc4201f984", "UNI", "Uniswap", 18),
                t("0x7fc66500c84a76ad7e9c93437bfc5ac33e2ddae9", "AAVE", "Aave", 18),
                t("0x9f8f72aa9304c8b593d555f12ef6589cc3a579a2", "MKR", "Maker", 18),
                t("0x4d224452801aced8b2f0aebe155379bb5d594381", "APE", "ApeCoin", 18),
                t("0x95ad61b0a150d79219dcf64e1e6cc01f0b64c4ce", "SHIB", "Shiba Inu", 18),
                t("0x6982508145454ce325ddbe47a25d4ec3d2311933", "PEPE", "Pepe", 18),
            )
            EthereumNetwork.ARBITRUM -> listOf(
                t("0xaf88d065e77c8cc2239327c5edb3a432268e5831", "USDC", "USD Coin", 6),
                t("0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9", "USDT", "Tether USD", 6),
                t("0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", "DAI", "Dai Stablecoin", 18),
                t("0x82af49447d8a07e3bd95bd0d56f35241523fbab1", "WETH", "Wrapped Ether", 18),
                t("0x912ce59144191c1204e64559fe8253a0e49e6548", "ARB", "Arbitrum", 18),
                t("0xf97f4df75117a78c1a5a0dbb814af92458539fb4", "LINK", "Chainlink", 18),
            )
            EthereumNetwork.OPTIMISM -> listOf(
                t("0x0b2c639c533813f4aa9d7837caf62653d097ff85", "USDC", "USD Coin", 6),
                t("0x94b008aa00579c1307b0ef2c499ad98a8ce58e58", "USDT", "Tether USD", 6),
                t("0xda10009cbd5d07dd0cecc66161fc93d7c9000da1", "DAI", "Dai Stablecoin", 18),
                t("0x4200000000000000000000000000000000000006", "WETH", "Wrapped Ether", 18),
                t("0x4200000000000000000000000000000000000042", "OP", "Optimism", 18),
            )
            EthereumNetwork.BASE -> listOf(
                t("0x833589fcd6edb6e08f4c7c32d4f71b54bda02913", "USDC", "USD Coin", 6),
                t("0xfde4c96c8593536e31f229ea8f37b2ada2699bb2", "USDT", "Tether USD", 6),
                t("0x4200000000000000000000000000000000000006", "WETH", "Wrapped Ether", 18),
                t("0x50c5725949a6f0c72e6c4a641f24049a917db0cb", "DAI", "Dai Stablecoin", 18),
            )
            EthereumNetwork.POLYGON -> listOf(
                t("0x3c499c542cef5e3811e1192ce70d8cc03d5c3359", "USDC", "USD Coin (native)", 6),
                t("0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "USDT", "Tether USD", 6),
                t("0x8f3cf7ad23cd3cadbd9735aff958023239c6a063", "DAI", "Dai Stablecoin", 18),
                t("0x7ceb23fd6bc0add59e62ac25578270cff1b9f619", "WETH", "Wrapped Ether", 18),
                t("0x1bfd67037b42cf73acf2047067bd4f2c47d9bfd6", "WBTC", "Wrapped Bitcoin", 8),
            )
            EthereumNetwork.BNB -> listOf(
                t("0x8ac76a51cc950d9822d68b83fe1ad97b32cd580d", "USDC", "USD Coin (Binance-Peg)", 18),
                t("0x55d398326f99059ff775485246999027b3197955", "USDT", "Tether USD (Binance-Peg)", 18),
                t("0xe9e7cea3dedca5984780bafc599bd69add087d56", "BUSD", "Binance USD", 18),
            )
            EthereumNetwork.AVALANCHE -> listOf(
                t("0xb97ef9ef8734c71904d8002f8b6bc66dd9c48a6e", "USDC", "USD Coin", 6),
                t("0x9702230a8ea53601f5cd2dc00fdbc13d4df4a8c7", "USDT", "Tether USD", 6),
            )
            EthereumNetwork.SCROLL -> listOf(
                t("0x06efdbff2a14a7c8e15944d1f4a48f9f95f663a4", "USDC", "USD Coin", 6),
            )
            EthereumNetwork.LINEA -> listOf(
                t("0x176211869ca2b568f2a7d4ee941e073a821ee1ff", "USDC", "USD Coin", 6),
            )
            EthereumNetwork.ZKSYNC -> listOf(
                t("0x1d17cbcf0d6d143135ae902365d2e5e2a16538d4", "USDC", "USD Coin", 6),
            )
            EthereumNetwork.MANTLE -> listOf(
                t("0x09bc4e0d864854c6afb6eb9a9cdf58ac190d0df9", "USDC", "USD Coin", 6),
            )
            EthereumNetwork.POLYGON_ZK_EVM -> listOf(
                t("0xa8ce8aee21bc2a48a5ef670afcc9274c7bbbc035", "USDC", "USD Coin", 6),
            )
            EthereumNetwork.HYPERLIQUID -> emptyList()
            EthereumNetwork.SEPOLIA -> listOf(
                t("0x1c7d4b196cb0c7b01d743fbc6116a902379c7238", "USDC", "USD Coin (Sepolia)", 6),
                t("0x779877a7b0d9e8603169ddbd7836e478b4624789", "LINK", "Chainlink Token", 18),
            )
            EthereumNetwork.ARBITRUM_SEPOLIA,
            EthereumNetwork.BASE_SEPOLIA,
            EthereumNetwork.OPTIMISM_SEPOLIA -> emptyList()
            EthereumNetwork.ADI_TESTNET -> emptyList()
        }
    }

    /** Auto-discover lookup: case-insensitive contract match in `reputable`. */
    fun find(network: EthereumNetwork, contract: String): EthereumToken? {
        val needle = contract.lowercase()
        return reputable(network).firstOrNull { it.contractAddress == needle }
    }

    /** Legacy alias for the first-run seed. */
    fun defaults(network: EthereumNetwork): List<EthereumToken> = firstRunSeed(network)
}
