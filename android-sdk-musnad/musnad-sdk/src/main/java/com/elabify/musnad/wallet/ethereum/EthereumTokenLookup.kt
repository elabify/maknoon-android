// Auto-detect ERC-20 token metadata from just a contract address by calling
// name()/symbol()/decimals() via eth_call. 1:1 port of EthereumTokenLookup.swift.
// Used by the Add Token flow to skip manual entry; returns null when the
// critical fields (symbol + decimals) don't decode, so the caller falls back to
// manual entry.

package com.elabify.musnad.wallet.ethereum

data class ERC20Metadata(
    val symbol: String,
    val name: String,
    val decimals: Int,
)

object EthereumTokenLookup {

    /** Hit the RPC for all three reads and reassemble. */
    fun fetch(contract: String, rpcURL: String): ERC20Metadata? {
        val rpc = EthereumRPCClient.orNull(rpcURL) ?: return null
        val symbolHex = runCatching { rpc.ethCall(contract, EthereumABI.symbolData()) }.getOrNull()
        val decimalsHex = runCatching { rpc.ethCall(contract, EthereumABI.decimalsData()) }.getOrNull()
        val nameHex = runCatching { rpc.ethCall(contract, EthereumABI.nameData()) }.getOrNull()

        if (symbolHex == null) return null
        val symbol = EthereumABI.parseSymbol(symbolHex)?.takeIf { it.isNotEmpty() } ?: return null
        if (decimalsHex == null) return null
        val decimals = EthereumABI.parseDecimals(decimalsHex) ?: return null
        val name = nameHex
            ?.let { EthereumABI.parseSymbol(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: symbol
        return ERC20Metadata(symbol = symbol, name = name, decimals = decimals)
    }
}
