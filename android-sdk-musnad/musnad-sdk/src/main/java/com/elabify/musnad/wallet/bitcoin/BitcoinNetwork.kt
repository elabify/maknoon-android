// Maknoon's app-facing Bitcoin network enum, ported 1:1 from iOS
// BitcoinNetwork.swift. Maps onto BDK's `Network` type and to the BIP44
// coin-type used for derivation paths (m/84'/<coin>'/<account>'/<chain>/<index>).
//
// `mainnet` uses coin-type 0; every other network (testnet3, signet)
// uses coin-type 1 per BIP44.

package com.elabify.musnad.wallet.bitcoin

import org.bitcoindevkit.Network

enum class BitcoinNetwork(val rawValue: String) {
    MAINNET("mainnet"),
    TESTNET3("testnet3"),
    SIGNET("signet");

    /** BIP44 coin type for derivation. Mainnet is 0', everything else
     *  is 1' per the spec. */
    val coinType: Long
        get() = when (this) {
            MAINNET -> 0
            TESTNET3, SIGNET -> 1
        }

    /** Bridge to BDK's Network enum. */
    val bdk: Network
        get() = when (this) {
            MAINNET -> Network.BITCOIN
            TESTNET3 -> Network.TESTNET
            SIGNET -> Network.SIGNET
        }

    val displayName: String
        get() = when (this) {
            MAINNET -> "Mainnet"
            TESTNET3 -> "Testnet3"
            SIGNET -> "Signet"
        }

    /** Default Electrum server used when the user has not configured one.
     *  Public, free-to-use endpoints maintained by Blockstream
     *  (mainnet/testnet) and a community operator (signet). */
    val defaultElectrumURL: String
        get() = when (this) {
            MAINNET -> "ssl://electrum.blockstream.info:50002"
            TESTNET3 -> "ssl://electrum.blockstream.info:60002"
            SIGNET -> "ssl://mempool.space:60602"
        }

    /** Default mempool.space (or Esplora-compatible) base URL used for
     *  fee recommendations and block-target visualisation. */
    val defaultMempoolURL: String
        get() = when (this) {
            MAINNET -> "https://mempool.space"
            TESTNET3 -> "https://mempool.space/testnet"
            SIGNET -> "https://mempool.space/signet"
        }

    /** Symbol shown next to amounts (BTC on mainnet, t/sBTC on testnets). */
    val ticker: String
        get() = when (this) {
            MAINNET -> "BTC"
            TESTNET3 -> "tBTC"
            SIGNET -> "sBTC"
        }

    companion object {
        fun fromRawValue(raw: String): BitcoinNetwork? =
            entries.firstOrNull { it.rawValue == raw }
    }
}
