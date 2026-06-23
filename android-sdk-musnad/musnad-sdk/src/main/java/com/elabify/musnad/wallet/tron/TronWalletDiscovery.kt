// Software-seed Tron account discovery, the Tron twin of SolanaWalletDiscovery.
// Walks account indices 0, 1, 2, ... deriving each address from the holder seed
// (TronDescriptors), probes TronGrid for balance + tx history, and returns the
// accounts with on-chain activity so the Add screen can pre-add them. Stops
// after EMPTY_ACCOUNT_GAP_LIMIT consecutive empty accounts. Tron addresses are
// chain-agnostic, so the caller picks one network to probe.

package com.elabify.musnad.wallet.tron

import com.elabify.musnad.identity.IdentitySandwich

object TronWalletDiscovery {

    data class DiscoveredAccount(
        val network: TronNetwork,
        val account: Long,
        val address: String,
        val sun: Long,
        val txCount: Int,
    ) {
        val hasActivity: Boolean get() = sun > 0 || txCount > 0
    }

    sealed class Phase {
        object Scanning : Phase()
        data class Completed(val active: Boolean) : Phase()
        data class Failed(val message: String) : Phase()
    }

    data class Progress(val account: Long, val phase: Phase)

    /** Gap-limit at the ACCOUNT level, matching the other chains' sweeps. */
    const val EMPTY_ACCOUNT_GAP_LIMIT = 4

    /**
     * Software-seed scan. Returns hits with hasActivity == true; when
     * [includeFirstAccountAlways] is set, account 0 is always returned (a fresh
     * seed with no activity yet).
     */
    fun scanSoftware(
        sandwich: IdentitySandwich,
        network: TronNetwork,
        rpcURL: String,
        includeFirstAccountAlways: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): List<DiscoveredAccount> {
        val rpc = TronRPCClient(base = rpcURL)
        val hits = ArrayList<DiscoveredAccount>()
        val seen = HashSet<String>()
        var firstEntry: DiscoveredAccount? = null
        var account = 0L
        var consecutiveEmpty = 0
        while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT) {
            onProgress(Progress(account, Phase.Scanning))
            val address = try {
                TronDescriptors.addressFromSandwich(sandwich, account)
            } catch (e: Exception) {
                onProgress(Progress(account, Phase.Failed(e.message ?: "derivation failed")))
                account++; consecutiveEmpty++
                continue
            }
            try {
                val sun = runCatching { rpc.getBalance(address) }.getOrDefault(0L)
                val txs = runCatching { rpc.getTransactionsByAddress(address, limit = 1) }.getOrDefault(emptyList())
                val entry = DiscoveredAccount(network, account, address, sun, txs.size)
                if (account == 0L && firstEntry == null) firstEntry = entry
                if (entry.hasActivity) {
                    consecutiveEmpty = 0
                    if (seen.add(address)) hits.add(entry)
                } else {
                    consecutiveEmpty++
                }
                onProgress(Progress(account, Phase.Completed(entry.hasActivity)))
            } catch (e: Exception) {
                onProgress(Progress(account, Phase.Failed(e.message ?: "RPC error")))
                consecutiveEmpty++
            }
            account++
        }
        if (includeFirstAccountAlways) {
            val first = firstEntry
            if (first != null && hits.none { it.account == 0L }) hits.add(0, first)
        }
        return hits
    }
}
