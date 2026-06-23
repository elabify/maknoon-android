// Solana cluster enum, ported 1:1 from iOS SolanaSettings.swift's
// `SolanaNetwork`. The wallet's keypair derivation is identical on
// every cluster; the only thing that changes per chip switch is which
// RPC + explorer + balance pool the dashboard talks to.

package com.elabify.musnad.wallet.solana

enum class SolanaNetwork(val rawValue: String) {
    MAINNET("mainnet"),
    DEVNET("devnet"),
    TESTNET("testnet");

    /** Display name. Solana's wire network id is "mainnet-beta", but
     *  users expect "Mainnet" in UI strings. */
    val displayName: String
        get() = when (this) {
            MAINNET -> "Mainnet"
            DEVNET -> "Devnet"
            TESTNET -> "Testnet"
        }

    /** Default JSON-RPC endpoint per cluster. User-overridable via
     *  SolanaSettings. */
    val defaultRpcURL: String
        get() = when (this) {
            MAINNET -> "https://api.mainnet-beta.solana.com"
            DEVNET -> "https://api.devnet.solana.com"
            TESTNET -> "https://api.testnet.solana.com"
        }

    /** Default block explorer per cluster. */
    val defaultExplorerURL: String
        get() = when (this) {
            MAINNET -> "https://explorer.solana.com"
            DEVNET -> "https://explorer.solana.com?cluster=devnet"
            TESTNET -> "https://explorer.solana.com?cluster=testnet"
        }

    /** CoinGecko id for spot pricing. Null on devnet/testnet (no real
     *  market). */
    val coinGeckoAssetId: String?
        get() = when (this) {
            MAINNET -> "solana"
            DEVNET, TESTNET -> null
        }

    companion object {
        fun fromRawValue(raw: String): SolanaNetwork? =
            entries.firstOrNull { it.rawValue == raw }
    }
}
