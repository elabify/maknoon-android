// Decide what a scanned EIP-681 token-payment QR means for THIS wallet on THIS
// chain. Pure and unit-testable: the send screen does the RPC probing, this file
// only classifies. Mirrors iOS EthereumScannedToken.swift.
//
// The case that forced this to exist: a Coinbase Arbitrum deposit QR requests
// bridged USDC.e (0xff97…5cc8) while the wallet holds native USDC
// (0xaf88…5831). Both contracts return symbol() == "USDC" on chain, so symbol
// alone cannot tell them apart and the UI must always show the contract too.
// Matching the contract strictly is correct (they really are different tokens,
// and a payee may credit only one of them), but dead-ending there is not: the
// wallet can offer the token it holds and let the user decide.
//
// Deliberately NOT a silent substitution. SameSymbolCandidates is a prompt,
// never an auto-selection.

package com.elabify.app.maknoon.ui.wallet.ethereum

import com.elabify.musnad.wallet.ethereum.EthereumToken

sealed interface EthereumScannedTokenMatch {
    /** The wallet already has this exact contract on this chain: apply the QR. */
    data class AlreadyAdded(val token: EthereumToken) : EthereumScannedTokenMatch

    /** Contract absent, but the wallet holds tokens with the same symbol. */
    data class SameSymbolCandidates(val tokens: List<EthereumToken>) : EthereumScannedTokenMatch

    /** Contract absent and nothing in the wallet resembles it. */
    data object Unknown : EthereumScannedTokenMatch
}

object EthereumScannedToken {

    /**
     * @param requestedContract the URI target (the ERC-20 contract) from the QR.
     * @param requestedSymbol `symbol()` read from that contract, or null when the
     *   probe has not run or failed. Without it there is nothing to match a
     *   same-symbol candidate against, so the result can only be AlreadyAdded or
     *   Unknown.
     * @param added every token configured for this (wallet, chain).
     */
    fun resolve(
        requestedContract: String,
        requestedSymbol: String?,
        added: List<EthereumToken>,
    ): EthereumScannedTokenMatch {
        val needle = requestedContract.trim().lowercase()
        if (needle.isEmpty()) return EthereumScannedTokenMatch.Unknown
        val exact = added.firstOrNull { it.contractAddress.lowercase() == needle }
        if (exact != null) return EthereumScannedTokenMatch.AlreadyAdded(exact)
        val symbol = requestedSymbol?.trim()
        if (symbol.isNullOrEmpty()) return EthereumScannedTokenMatch.Unknown
        val candidates = added.filter {
            it.contractAddress.lowercase() != needle && it.symbol.equals(symbol, ignoreCase = true)
        }
        return if (candidates.isEmpty()) {
            EthereumScannedTokenMatch.Unknown
        } else {
            EthereumScannedTokenMatch.SameSymbolCandidates(candidates)
        }
    }
}
