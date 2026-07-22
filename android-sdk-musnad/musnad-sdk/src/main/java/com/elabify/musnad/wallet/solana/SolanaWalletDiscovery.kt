// Walk BIP44 Solana account indices on the holder's seed, probe each
// derived address for on-chain activity, return the hits. Ported 1:1
// from iOS SolanaWalletDiscovery.swift.
//
//   * Path is m/44'/501'/<account>'/0' and each account's primary key IS
//     the address; we don't walk receive/change inside an account.
//   * Addresses are cluster-agnostic at the keypair level, but on-chain
//     activity is per-cluster, so the scan is parameterised by cluster.
//   * Activity heuristic: non-zero lamport balance OR at least one
//     signature.
//
// Software derivation reads the seed once and walks host-side. The
// hardware path (Ledger/Trezor per-account address) is a later phase; the
// SolanaHardwareSigner seam plus a getSolanaAddress hook will slot in
// there. Blocking RPC; call on a background dispatcher.

package com.elabify.musnad.wallet.solana

import com.elabify.musnad.identity.IdentitySandwich

object SolanaWalletDiscovery {

    data class DiscoveredAccount(
        val network: SolanaNetwork,
        val account: Long,
        val address: String,
        val lamports: Long,
        val signatureCount: Int,
        /** Non-standard path when sweeping alternatives; null = standard. */
        val derivationPath: String? = null,
    ) {
        val id: String get() = "${network.rawValue}:$account:${derivationPath ?: "std"}"
        val hasActivity: Boolean get() = lamports > 0 || signatureCount > 0
    }

    sealed class Phase {
        object Scanning : Phase()
        data class Completed(val active: Boolean, val lamports: Long) : Phase()
        data class Failed(val message: String) : Phase()
    }

    data class Progress(val account: Long, val phase: Phase)

    /** BIP44-style gap-limit at the ACCOUNT level. Same value as the
     *  Bitcoin hardware-discover sweep for cross-chain consistency. */
    const val EMPTY_ACCOUNT_GAP_LIMIT = 4

    /** Software-seed scan. Walks accounts 0, 1, 2, ... until
     *  EMPTY_ACCOUNT_GAP_LIMIT consecutive empty accounts, then stops.
     *  Returns hits with hasActivity == true. When
     *  `includeFirstAccountAlways` is true, account 0's address is always
     *  returned (for a fresh seed with no activity yet). */
    fun scanSoftware(
        sandwich: IdentitySandwich,
        network: SolanaNetwork,
        rpcURL: String,
        includeFirstAccountAlways: Boolean = false,
        onProgress: (Progress) -> Unit = {},
    ): List<DiscoveredAccount> {
        val rpc = SolanaRPCClient(endpoint = rpcURL)
        val words = sandwich.recoveryWords()
        // Fold the identity passphrase into derivation, matching iOS (ADR-0064),
        // so discovery scans the SAME addresses the wallet will derive.
        val passphrase = sandwich.bip39Passphrase()

        val hits = ArrayList<DiscoveredAccount>()
        val seenAddresses = HashSet<String>()
        var firstAccountEntry: DiscoveredAccount? = null
        var account = 0L
        var consecutiveEmpty = 0
        while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT) {
            onProgress(Progress(account, Phase.Scanning))
            val address = try {
                SolanaPrimitives.addressFor(words, passphrase, account)
            } catch (e: Exception) {
                onProgress(Progress(account, Phase.Failed(e.message ?: "derivation failed")))
                account++
                consecutiveEmpty++
                continue
            }
            try {
                val lamports = runCatching { rpc.getBalance(address) }.getOrDefault(0L)
                val sigs = runCatching { rpc.getSignaturesForAddress(address, limit = 1) }.getOrDefault(emptyList())
                val entry = DiscoveredAccount(
                    network = network,
                    account = account,
                    address = address,
                    lamports = lamports,
                    signatureCount = sigs.size,
                )
                if (account == 0L && firstAccountEntry == null) firstAccountEntry = entry
                if (entry.hasActivity) {
                    consecutiveEmpty = 0
                    if (seenAddresses.add(address)) hits.add(entry)
                } else {
                    consecutiveEmpty++
                }
                onProgress(Progress(account, Phase.Completed(entry.hasActivity, lamports)))
            } catch (e: Exception) {
                onProgress(Progress(account, Phase.Failed(e.message ?: "RPC error")))
                consecutiveEmpty++
            }
            account++
        }
        if (includeFirstAccountAlways) {
            val first = firstAccountEntry
            if (first != null && hits.none { it.account == 0L }) hits.add(0, first)
        }
        return hits
    }
}
