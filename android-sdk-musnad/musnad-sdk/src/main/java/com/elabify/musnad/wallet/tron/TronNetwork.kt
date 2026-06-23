// Tron network enum, ported 1:1 from iOS TronWalletStore.swift's
// `TronNetwork`. The wallet's keypair derivation is identical on every
// network (mainnet / Shasta / Nile share the same secp256k1 key); the
// only thing that changes per chip switch is which TronGrid endpoint +
// explorer + balance pool the dashboard talks to.

package com.elabify.musnad.wallet.tron

enum class TronNetwork(val rawValue: String) {
    MAINNET("mainnet"),
    SHASTA("shasta"),
    NILE("nile");

    val displayName: String
        get() = when (this) {
            MAINNET -> "Mainnet"
            SHASTA -> "Shasta"
            NILE -> "Nile"
        }

    /** Default TronGrid endpoint. User-overridable in Tron Settings. */
    val defaultRpcURL: String
        get() = when (this) {
            MAINNET -> "https://api.trongrid.io"
            SHASTA -> "https://api.shasta.trongrid.io"
            NILE -> "https://api.nileex.io"
        }

    val defaultExplorerURL: String
        get() = when (this) {
            MAINNET -> "https://tronscan.org"
            SHASTA -> "https://shasta.tronscan.org"
            NILE -> "https://nile.tronscan.org"
        }

    /** CoinGecko id for the fiat caption. Mainnet only; testnets stay
     *  silent (no real market). */
    val coinGeckoAssetId: String?
        get() = when (this) {
            MAINNET -> "tron"
            SHASTA, NILE -> null
        }

    companion object {
        fun fromRawValue(raw: String): TronNetwork? =
            entries.firstOrNull { it.rawValue == raw }
    }
}
