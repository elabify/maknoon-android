// Walks BIP44 account indices on the holder's seed (software) or a hardware
// device, hits RPC + explorer for each derived address to find on-chain
// activity, returns the hits so the UI can pre-add wallets. 1:1 port of
// EthereumWalletDiscovery.swift.
//
// Two key facts carried over from iOS:
//   * Ethereum addresses are chain-agnostic; we probe one chain at a time.
//   * Alternative-path sweeps dedup by resolved path AND by address (a device
//     may ignore the override, or two templates fill to the same path).
//
// The hardware route is expressed via the EthereumDiscoveryDevice hook so this
// engine doesn't depend on the (separately-owned) hardware-wallet layer.

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.identity.IdentitySandwich
import wallet.core.jni.CoinType
import wallet.core.jni.HDWallet

/** A hardware device that can produce an Ethereum address per account/path. */
interface EthereumDiscoveryDevice {
    fun beginSession()
    fun endSession()
    fun setDerivationPathOverride(path: String?)
    fun ethereumAddress(account: Long): String
}

object EthereumWalletDiscovery {

    data class DiscoveredAccount(
        val network: EthereumNetwork,
        val account: Long,
        val address: String,
        val hasBalance: Boolean,
        val txCount: Int,
        /** Non-standard path this was derived at; null for the standard path. */
        val derivationPath: String? = null,
    ) {
        val id: String get() = "${network.rawValue}:$account:${derivationPath ?: "std"}"
        val hasActivity: Boolean get() = hasBalance || txCount > 0
    }

    data class Progress(
        val network: EthereumNetwork,
        val account: Long,
        val phase: Phase,
    ) {
        sealed interface Phase {
            object Scanning : Phase
            data class Completed(val active: Boolean) : Phase
            data class Failed(val message: String) : Phase
        }
    }

    sealed interface Source {
        /** Software: derive each candidate via the sandwich seed. */
        data class Software(val sandwich: IdentitySandwich, val passphrase: String = "") : Source

        /** Hardware: ask the device for each address. */
        data class Hardware(val device: EthereumDiscoveryDevice) : Source
    }

    /**
     * Sweep account indices 0 until [maxAccount] on [network], returning only the
     * accounts where hasActivity is true. [onProgress] fires once per candidate.
     */
    fun scan(
        source: Source,
        network: EthereumNetwork,
        rpcURL: String,
        explorerAPIURL: String?,
        apiKey: String?,
        maxAccount: Long = 5,
        includeFirstAccountAlways: Boolean = false,
        pathTemplates: List<String>? = null,
        onProgress: (Progress) -> Unit = {},
    ): List<DiscoveredAccount> {

        data class Candidate(val account: Long, val address: String, val path: String?)
        val candidates = ArrayList<Candidate>()
        val seenPaths = HashSet<String>()

        when (source) {
            is Source.Software -> {
                MultiChainNative.ensure()
                val words = source.sandwich.recoveryWords().joinToString(" ")
                val hd = HDWallet(words, source.passphrase)
                for (account in 0 until maxAccount) {
                    if (pathTemplates != null) {
                        for (tmpl in pathTemplates) {
                            val path = BIP32Path.fill(tmpl, account)
                            if (!seenPaths.add(path)) continue
                            val key = hd.getKey(path)
                            val addr = CoinType.ETHEREUM.deriveAddress(key)
                            candidates.add(Candidate(account, addr, path))
                        }
                    } else {
                        val key = hd.getKey(EthereumDescriptors.standardPath(account))
                        val addr = CoinType.ETHEREUM.deriveAddress(key)
                        candidates.add(Candidate(account, addr, null))
                    }
                }
            }
            is Source.Hardware -> {
                val client = source.device
                client.beginSession()
                try {
                    for (account in 0 until maxAccount) {
                        if (pathTemplates != null) {
                            for (tmpl in pathTemplates) {
                                val path = BIP32Path.fill(tmpl, account)
                                if (!seenPaths.add(path)) continue
                                client.setDerivationPathOverride(path)
                                try {
                                    val addr = client.ethereumAddress(account)
                                    candidates.add(Candidate(account, addr, path))
                                } catch (_: Exception) {
                                    continue
                                }
                            }
                        } else {
                            client.setDerivationPathOverride(null)
                            val addr = client.ethereumAddress(account)
                            candidates.add(Candidate(account, addr, null))
                        }
                    }
                } finally {
                    client.setDerivationPathOverride(null)
                    client.endSession()
                }
            }
        }

        val hits = ArrayList<DiscoveredAccount>()
        val seenAddresses = HashSet<String>()
        var firstAccountEntry: DiscoveredAccount? = null

        for (cand in candidates) {
            onProgress(Progress(network, cand.account, Progress.Phase.Scanning))
            try {
                val (hasBalance, txCount) = EthereumWallet.probeActivity(
                    address = cand.address,
                    rpcURL = rpcURL,
                    explorerAPIURL = explorerAPIURL,
                    apiKey = apiKey,
                    chainId = network.chainId,
                )
                val entry = DiscoveredAccount(network, cand.account, cand.address, hasBalance, txCount, cand.path)
                if (cand.account == 0L && firstAccountEntry == null) firstAccountEntry = entry
                if (entry.hasActivity && seenAddresses.add(cand.address.lowercase())) {
                    hits.add(entry)
                }
                onProgress(Progress(network, cand.account, Progress.Phase.Completed(entry.hasActivity)))
            } catch (e: Exception) {
                if (cand.account == 0L && firstAccountEntry == null) {
                    firstAccountEntry = DiscoveredAccount(network, cand.account, cand.address, false, 0, cand.path)
                }
                onProgress(Progress(network, cand.account, Progress.Phase.Failed(e.message ?: "$e")))
            }
        }

        if (includeFirstAccountAlways && firstAccountEntry != null && hits.none { it.account == 0L }) {
            hits.add(0, firstAccountEntry!!)
        }
        return hits
    }
}

/** Fills a BIP32 path template's account placeholder. Minimal port of BIP32Path.fill. */
object BIP32Path {
    /**
     * Replace the account field in a template. Templates use `{account}` or
     * `<account>` placeholders, e.g. "m/44'/60'/{account}'/0/0".
     */
    fun fill(template: String, account: Long): String =
        template.replace("{account}", account.toString()).replace("<account>", account.toString())
}
