// Scan the user's seed for existing Bitcoin activity. Walks account
// indices 0..maxAccount, builds an in-memory BDK wallet for each
// (network, account), runs a full Electrum scan, and reports back every
// account that had at least one transaction. Ported 1:1 from iOS
// BitcoinWalletDiscovery.swift.
//
// On-demand only (driven by a button in the wallets view). Each
// account-scan is a full Electrum scan and can take 5-30s, so we surface
// per-account progress. Run off the main thread.
//
// The caller unlocks the Identity Sandwich at the UI and passes the BIP39
// words + optional passphrase here (mirrors iOS, which pulls
// recoveryMaterial() and hands plain strings so the scan can run off the
// main actor without dragging the non-Sendable sandwich across).

package com.elabify.musnad.wallet.bitcoin

import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet

object BitcoinWalletDiscovery {

    data class DiscoveredAccount(
        val network: BitcoinNetwork,
        val account: Long,
        val txCount: Int,
        val balanceSat: Long,
    )

    /** Per-account progress callback so a multi-minute scan does not feel
     *  frozen. */
    sealed class Phase {
        object Scanning : Phase()
        data class Completed(val txCount: Int) : Phase()
    }

    data class Progress(val network: BitcoinNetwork, val account: Long, val phase: Phase)

    /** Walk accounts 0..maxAccount on each requested network. Returns the
     *  discovered accounts in scan order; the caller decides which to add
     *  to the store. */
    @Throws(Exception::class)
    fun scan(
        mnemonicWords: String,
        passphrase: String?,
        networks: List<BitcoinNetwork>,
        maxAccount: Long = 4,
        electrumURL: (BitcoinNetwork) -> String,
        onProgress: ((Progress) -> Unit)? = null,
    ): List<DiscoveredAccount> {
        val discovered = ArrayList<DiscoveredAccount>()

        val mnemonic = Mnemonic.fromString(mnemonicWords)
        val password = passphrase?.ifEmpty { null }

        for (network in networks) {
            val root = DescriptorSecretKey(network.bdk, mnemonic, password)
            for (account in 0..maxAccount) {
                onProgress?.invoke(Progress(network, account, Phase.Scanning))

                val secret: DescriptorSecretKey = if (account == 0L) {
                    root
                } else {
                    root.derive(DerivationPath("m/84'/${network.coinType}'/$account'"))
                }
                val external = Descriptor.newBip84(secret, KeychainKind.EXTERNAL, network.bdk)
                val internal = Descriptor.newBip84(secret, KeychainKind.INTERNAL, network.bdk)
                val wallet = Wallet(external, internal, network.bdk, Persister.newInMemory())

                try {
                    val client = ElectrumClient(electrumURL(network), null)
                    val req = wallet.startFullScan().build()
                    val update = client.fullScan(
                        req,
                        /* stopGap = */ 20uL,
                        /* batchSize = */ 10uL,
                        /* fetchPrevTxouts = */ false,
                    )
                    wallet.applyUpdate(update)
                } catch (_: Throwable) {
                    // Treat a per-account network failure as "no activity"
                    // so one bad endpoint cannot poison the whole run.
                    onProgress?.invoke(Progress(network, account, Phase.Completed(0)))
                    continue
                }

                val txCount = wallet.transactions().size
                val balanceSat = wallet.balance().total.toSat().toLong()
                onProgress?.invoke(Progress(network, account, Phase.Completed(txCount)))

                if (txCount > 0) {
                    discovered.add(DiscoveredAccount(network, account, txCount, balanceSat))
                }
            }
        }
        return discovered
    }
}
