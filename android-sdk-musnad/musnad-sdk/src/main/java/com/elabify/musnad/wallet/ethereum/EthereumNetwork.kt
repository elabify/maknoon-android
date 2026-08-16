// Curated EVM network catalog. Mainnet + the top L2s by TVL + Hyperliquid EVM.
// All entries share BIP44 coin type 60 because they are EVM-compatible;
// per-chain config (RPC, explorer, chain id) is what makes them distinct.
//
// 1:1 port of EthereumNetwork.swift. The enum raw value is the Swift `String`
// rawValue (the case name) so persisted JSON keys line up across platforms.

package com.elabify.musnad.wallet.ethereum

enum class EthereumNetwork(val rawValue: String) {
    // L1 + L2 mainnets
    MAINNET("mainnet"),
    ARBITRUM("arbitrum"),
    OPTIMISM("optimism"),
    BASE("base"),
    POLYGON("polygon"),
    BNB("bnb"),
    AVALANCHE("avalanche"),
    SCROLL("scroll"),
    LINEA("linea"),
    ZKSYNC("zksync"),
    MANTLE("mantle"),
    POLYGON_ZK_EVM("polygonZkEvm"),
    // Required regardless of TVL rank
    HYPERLIQUID("hyperliquid"),
    // Testnets
    SEPOLIA("sepolia"),
    ARBITRUM_SEPOLIA("arbitrumSepolia"),
    BASE_SEPOLIA("baseSepolia"),
    OPTIMISM_SEPOLIA("optimismSepolia"),
    ADI_TESTNET("adiTestnet"),

    /**
     * Pharos Atlantic testnet. Parallel-EVM L1; PHRS is the testnet gas token
     * (mainnet's is PROS). Musnad anchors credential batch roots here. Pharos runs
     * two testnets and only Atlantic (688689) is reachable; the older 688688
     * endpoint is not usable.
     */
    PHAROS_ATLANTIC_TESTNET("pharosAtlanticTestnet"),

    /**
     * HashKey Chain testnet. OP-Stack L2; HSK is the gas token on both its testnet
     * and its mainnet.
     */
    HASHKEY_TESTNET("hashkeyTestnet");

    enum class Classification(val rawValue: String) {
        L1("l1"),
        L1_EVM("l1Evm"),
        L2("l2"),
        SIDECHAIN("sidechain"),
        TESTNET("testnet"),
    }

    /** EIP-155 chain id. */
    val chainId: Long
        get() = when (this) {
            MAINNET -> 1
            OPTIMISM -> 10
            BNB -> 56
            POLYGON -> 137
            ZKSYNC -> 324
            HYPERLIQUID -> 999
            POLYGON_ZK_EVM -> 1101
            MANTLE -> 5000
            BASE -> 8453
            ARBITRUM -> 42161
            AVALANCHE -> 43114
            LINEA -> 59144
            SCROLL -> 534352
            SEPOLIA -> 11155111
            ARBITRUM_SEPOLIA -> 421614
            BASE_SEPOLIA -> 84532
            OPTIMISM_SEPOLIA -> 11155420
            ADI_TESTNET -> 99999
            HASHKEY_TESTNET -> 133
            PHAROS_ATLANTIC_TESTNET -> 688689
        }

    val displayName: String
        get() = when (this) {
            MAINNET -> "Ethereum"
            ARBITRUM -> "Arbitrum One"
            OPTIMISM -> "OP Mainnet"
            BASE -> "Base"
            POLYGON -> "Polygon"
            BNB -> "BNB Smart Chain"
            AVALANCHE -> "Avalanche"
            SCROLL -> "Scroll"
            LINEA -> "Linea"
            ZKSYNC -> "zkSync Era"
            MANTLE -> "Mantle"
            POLYGON_ZK_EVM -> "Polygon zkEVM"
            HYPERLIQUID -> "Hyperliquid EVM"
            SEPOLIA -> "Sepolia"
            ARBITRUM_SEPOLIA -> "Arbitrum Sepolia"
            BASE_SEPOLIA -> "Base Sepolia"
            OPTIMISM_SEPOLIA -> "OP Sepolia"
            ADI_TESTNET -> "ADI Testnet"
            HASHKEY_TESTNET -> "HashKey Testnet"
            PHAROS_ATLANTIC_TESTNET -> "Pharos Atlantic Testnet"
        }

    /** Native-coin ticker for balance display. */
    val ticker: String
        get() = when (this) {
            MAINNET, ARBITRUM, OPTIMISM, BASE, SCROLL, LINEA, ZKSYNC,
            POLYGON_ZK_EVM, SEPOLIA, ARBITRUM_SEPOLIA, BASE_SEPOLIA, OPTIMISM_SEPOLIA -> "ETH"
            POLYGON -> "MATIC"
            BNB -> "BNB"
            AVALANCHE -> "AVAX"
            MANTLE -> "MNT"
            HYPERLIQUID -> "HYPE"
            ADI_TESTNET -> "ADI"
            HASHKEY_TESTNET -> "HSK"
            PHAROS_ATLANTIC_TESTNET -> "PHRS"
        }

    /** Trust Wallet blockchains/<slug> folder for token-logo URLs; null if none. */
    val trustWalletSlug: String?
        get() = when (this) {
            MAINNET -> "ethereum"
            ARBITRUM -> "arbitrum"
            OPTIMISM -> "optimism"
            BASE -> "base"
            POLYGON -> "polygon"
            BNB -> "smartchain"
            AVALANCHE -> "avalanchec"
            SCROLL -> "scroll"
            LINEA -> "linea"
            ZKSYNC -> "zksync"
            MANTLE -> "mantle"
            POLYGON_ZK_EVM -> "polygonzkevm"
            HYPERLIQUID, SEPOLIA, ARBITRUM_SEPOLIA, BASE_SEPOLIA, OPTIMISM_SEPOLIA, ADI_TESTNET,
            PHAROS_ATLANTIC_TESTNET, HASHKEY_TESTNET -> null
        }

    /** CoinGecko asset id for the native coin; null for testnets / unsupported. */
    val coinGeckoAssetId: String?
        get() {
            if (isTestnet) return null
            return when (this) {
                MAINNET, ARBITRUM, OPTIMISM, BASE, SCROLL, LINEA, ZKSYNC, POLYGON_ZK_EVM -> "ethereum"
                POLYGON -> "polygon-ecosystem-token" // POL (ex-MATIC); the old matic-network id is dead
                BNB -> "binancecoin"
                AVALANCHE -> "avalanche-2"
                MANTLE -> "mantle"
                HYPERLIQUID -> "hyperliquid"
                else -> null
            }
        }

    val isTestnet: Boolean
        get() = when (this) {
            SEPOLIA, ARBITRUM_SEPOLIA, BASE_SEPOLIA, OPTIMISM_SEPOLIA, ADI_TESTNET,
            PHAROS_ATLANTIC_TESTNET, HASHKEY_TESTNET -> true
            else -> false
        }

    val classification: Classification
        get() = when (this) {
            MAINNET -> Classification.L1
            BNB, AVALANCHE, HYPERLIQUID -> Classification.L1_EVM
            ARBITRUM, OPTIMISM, BASE, SCROLL, LINEA, ZKSYNC, MANTLE, POLYGON_ZK_EVM -> Classification.L2
            POLYGON -> Classification.SIDECHAIN
            SEPOLIA, ARBITRUM_SEPOLIA, BASE_SEPOLIA, OPTIMISM_SEPOLIA, ADI_TESTNET,
            PHAROS_ATLANTIC_TESTNET, HASHKEY_TESTNET -> Classification.TESTNET
        }

    /** Public JSON-RPC endpoint that works without an API key. */
    val defaultRPCURL: String
        get() = when (this) {
            MAINNET -> "https://ethereum.publicnode.com"
            ARBITRUM -> "https://arb1.arbitrum.io/rpc"
            OPTIMISM -> "https://mainnet.optimism.io"
            BASE -> "https://mainnet.base.org"
            POLYGON -> "https://polygon-bor-rpc.publicnode.com"
            BNB -> "https://bsc-rpc.publicnode.com"
            AVALANCHE -> "https://api.avax.network/ext/bc/C/rpc"
            SCROLL -> "https://rpc.scroll.io"
            LINEA -> "https://rpc.linea.build"
            ZKSYNC -> "https://mainnet.era.zksync.io"
            MANTLE -> "https://rpc.mantle.xyz"
            POLYGON_ZK_EVM -> "https://zkevm-rpc.com"
            HYPERLIQUID -> "https://rpc.hyperliquid.xyz/evm"
            SEPOLIA -> "https://ethereum-sepolia.publicnode.com"
            ARBITRUM_SEPOLIA -> "https://sepolia-rollup.arbitrum.io/rpc"
            BASE_SEPOLIA -> "https://sepolia.base.org"
            OPTIMISM_SEPOLIA -> "https://sepolia.optimism.io"
            ADI_TESTNET -> "https://rpc.ab.testnet.adifoundation.ai/"
            HASHKEY_TESTNET -> "https://testnet.hsk.xyz"
            // Atlantic (688689). NOT testnet.dplabs-internal.com (688688): that
            // host serves a certificate for a different name and returns 503, so
            // OkHttp rejects it before the RPC is reached.
            PHAROS_ATLANTIC_TESTNET -> "https://atlantic.dplabs-internal.com"
        }

    /** HTML block-explorer base URL. */
    val defaultExplorerURL: String
        get() = when (this) {
            MAINNET -> "https://etherscan.io"
            ARBITRUM -> "https://arbiscan.io"
            OPTIMISM -> "https://optimistic.etherscan.io"
            BASE -> "https://basescan.org"
            POLYGON -> "https://polygonscan.com"
            BNB -> "https://bscscan.com"
            AVALANCHE -> "https://snowtrace.io"
            SCROLL -> "https://scrollscan.com"
            LINEA -> "https://lineascan.build"
            ZKSYNC -> "https://explorer.zksync.io"
            MANTLE -> "https://mantlescan.xyz"
            POLYGON_ZK_EVM -> "https://zkevm.polygonscan.com"
            HYPERLIQUID -> "https://hyperevmscan.io"
            SEPOLIA -> "https://sepolia.etherscan.io"
            ARBITRUM_SEPOLIA -> "https://sepolia.arbiscan.io"
            BASE_SEPOLIA -> "https://sepolia.basescan.org"
            OPTIMISM_SEPOLIA -> "https://sepolia-optimism.etherscan.io"
            ADI_TESTNET -> "https://explorer.ab.testnet.adifoundation.ai"
            // The redirect target, not the documented host: testnet-explorer.hsk.xyz
            // answers 301. Harmless for these GETs (OkHttp follows it) but it costs a
            // round trip, and the same stale host silently broke contract verification
            // server-side, where a 301 drops the POST body.
            HASHKEY_TESTNET -> "https://testnet-explorer.hskchain.net"
            PHAROS_ATLANTIC_TESTNET -> "https://atlantic.pharosscan.xyz"
        }

    /** Etherscan-style block-explorer API base URL for tx history; null if none. */
    val defaultExplorerAPIURL: String?
        get() = when (this) {
            MAINNET -> "https://eth.blockscout.com/api"
            ARBITRUM -> "https://arbitrum.blockscout.com/api"
            OPTIMISM -> "https://optimism.blockscout.com/api"
            BASE -> "https://base.blockscout.com/api"
            POLYGON -> "https://polygon.blockscout.com/api"
            BNB -> "https://blockscout.bnbchain.org/api"
            AVALANCHE -> "https://snowtrace.io/api"
            SCROLL -> "https://scroll.blockscout.com/api"
            LINEA -> "https://explorer.linea.build/api"
            ZKSYNC -> "https://zksync.blockscout.com/api"
            MANTLE -> "https://explorer.mantle.xyz/api"
            POLYGON_ZK_EVM -> "https://zkevm.blockscout.com/api"
            SEPOLIA -> "https://eth-sepolia.blockscout.com/api"
            ARBITRUM_SEPOLIA -> "https://sepolia-explorer.arbitrum.io/api"
            BASE_SEPOLIA -> "https://base-sepolia.blockscout.com/api"
            OPTIMISM_SEPOLIA -> "https://optimism-sepolia.blockscout.com/api"
            ADI_TESTNET -> "https://explorer-api.ab.testnet.adifoundation.ai/api"
            HASHKEY_TESTNET -> "https://testnet-explorer.hskchain.net/api"
            // Pharos Atlantic has no Etherscan-style read API. Probed 2026-08-14:
            // atlantic.pharosscan.xyz/api?module=account&action=txlist returns 404
            // with the SPA shell, and the socialscan endpoint that DOES answer for
            // this chain is contract VERIFICATION, so pointing history at it would
            // yield an empty list with no error. Null falls back to RPC-only
            // history, as Hyperliquid does.
            PHAROS_ATLANTIC_TESTNET -> null
            HYPERLIQUID -> null
        }

    companion object {
        fun fromRawValue(raw: String): EthereumNetwork? = entries.firstOrNull { it.rawValue == raw }

        fun fromChainId(chainId: Long): EthereumNetwork? = entries.firstOrNull { it.chainId == chainId }

        /**
         * Display ordering: Ethereum mainnet first, everything else sorted
         * case-insensitively by displayName.
         */
        val displayOrdered: List<EthereumNetwork>
            get() {
                val rest = entries.filter { it != MAINNET }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
                return listOf(MAINNET) + rest
            }
    }
}
