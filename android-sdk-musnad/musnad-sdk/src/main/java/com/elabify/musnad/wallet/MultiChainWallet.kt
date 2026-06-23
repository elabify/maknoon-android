// EVM (Ethereum) and Tron wallets via Trust WalletCore -- the same engine the
// iOS app uses. Addresses derive from the holder's BIP-39 mnemonic + optional
// passphrase via each coin's standard path (HDWallet.getKeyForCoin ->
// CoinType.deriveAddress). Signing / balance / tokens follow.
//
// NOTE: the Maven WalletCore binding (0.12.8) wraps an older core WITHOUT a
// SOLANA coin type, so Solana lives in SolanaWallet (SLIP-0010 ed25519,
// verified against the spec vectors) rather than here.

package com.elabify.musnad.wallet

import wallet.core.jni.CoinType
import wallet.core.jni.HDWallet

object MultiChainWallet {

    @Volatile private var nativeLoaded = false

    private fun ensureNative() {
        if (!nativeLoaded) {
            System.loadLibrary("TrustWalletCore")
            nativeLoaded = true
        }
    }

    private fun deriveAddress(words: List<String>, passphrase: String, coin: CoinType): String {
        ensureNative()
        val wallet = HDWallet(words.joinToString(" "), passphrase)
        val key = wallet.getKeyForCoin(coin)
        return coin.deriveAddress(key)
    }

    /** EIP-55 Ethereum address (0x...), shared by all EVM chains. */
    fun ethereumAddress(words: List<String>, passphrase: String = ""): String =
        deriveAddress(words, passphrase, CoinType.ETHEREUM)

    /** Tron address (base58check, T...). */
    fun tronAddress(words: List<String>, passphrase: String = ""): String =
        deriveAddress(words, passphrase, CoinType.TRON)
}
