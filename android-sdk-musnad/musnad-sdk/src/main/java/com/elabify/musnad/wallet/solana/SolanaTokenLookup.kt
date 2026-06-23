// Auto-detect SPL token metadata from a mint address by reading the
// on-chain SPL Token Mint account via getAccountInfo (jsonParsed). Ported
// 1:1 from iOS SolanaTokenLookup.swift. SPL Mints carry `decimals`
// directly (the most error-prone field to get wrong by hand); symbol +
// name live in a separate Metaplex account, so we surface decimals here
// and let the user fill in symbol/name unless the catalog had them.

package com.elabify.musnad.wallet.solana

data class SPLMintMetadata(
    val decimals: Int,
    /** Total supply as a raw on-chain integer (decimal string). */
    val supplyRaw: String,
)

object SolanaTokenLookup {

    /** Hit the configured RPC for the mint's parsed account info. Returns
     *  null for: non-existent address, not an spl-token mint, or
     *  transport failure. Caller falls back to manual entry. Blocking;
     *  call on a background dispatcher. */
    fun fetch(mint: String, rpcURL: String): SPLMintMetadata? {
        return try {
            val rpc = SolanaRPCClient(endpoint = rpcURL)
            val parsed = rpc.getParsedMint(mint) ?: return null
            SPLMintMetadata(decimals = parsed.decimals, supplyRaw = parsed.supplyRaw)
        } catch (e: Exception) {
            null
        }
    }
}
