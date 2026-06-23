// Bitcoin wallet via BDK (org.bitcoindevkit, same 2.3.1 as the iOS app, so
// descriptors + derivation line up). First slice: BIP84 (native SegWit) wallet
// derivation + receive address from a mnemonic. Network sync (balance, txs) and
// send/PSBT follow; the iOS app also supports BIP44/49 by script type.

package com.elabify.musnad.wallet

import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Network
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet

object BitcoinWallet {

    /** BIP84 (wpkh) receive address #0 for the given mnemonic + network. The
     *  optional passphrase matches the BIP-39 passphrase (BIP39 25th word). */
    fun firstReceiveAddress(
        words: List<String>,
        network: Network = Network.BITCOIN,
        passphrase: String? = null,
    ): String = buildWallet(words, network, passphrase)
        .revealNextAddress(KeychainKind.EXTERNAL)
        .address
        .toString()

    private fun buildWallet(words: List<String>, network: Network, passphrase: String?): Wallet {
        val mnemonic = Mnemonic.fromString(words.joinToString(" "))
        val root = DescriptorSecretKey(network, mnemonic, passphrase)
        val external = Descriptor.newBip84(root, KeychainKind.EXTERNAL, network)
        val change = Descriptor.newBip84(root, KeychainKind.INTERNAL, network)
        return Wallet(external, change, network, Persister.newInMemory())
    }
}
