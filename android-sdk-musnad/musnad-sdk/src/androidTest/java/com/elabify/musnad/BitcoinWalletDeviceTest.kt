// On-device test for BDK Bitcoin wallet derivation on the Pixel 9: a BIP84
// receive address derives from a mnemonic, is a valid mainnet bech32 (bc1q),
// deterministic, and testnet derives tb1q. Proves the BDK native lib works on
// arm64/GrapheneOS.

package com.elabify.musnad

import com.elabify.core.Bip39
import com.elabify.musnad.wallet.BitcoinWallet
import org.bitcoindevkit.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitcoinWalletDeviceTest {

    // 24 words from all-zero 256-bit entropy ("abandon ... art"): a valid BIP39 phrase.
    private val words = Bip39.mnemonicFromEntropy(ByteArray(32))

    @Test
    fun bip84MainnetAddressIsValidAndDeterministic() {
        val addr = BitcoinWallet.firstReceiveAddress(words, Network.BITCOIN)
        assertTrue("mainnet wpkh address: $addr", addr.startsWith("bc1q"))
        assertEquals("deterministic", addr, BitcoinWallet.firstReceiveAddress(words, Network.BITCOIN))
    }

    @Test
    fun bip84TestnetAddress() {
        val addr = BitcoinWallet.firstReceiveAddress(words, Network.TESTNET)
        assertTrue("testnet wpkh address: $addr", addr.startsWith("tb1q"))
    }

    @Test
    fun passphraseChangesTheWallet() {
        val none = BitcoinWallet.firstReceiveAddress(words, Network.BITCOIN, passphrase = null)
        val withPass = BitcoinWallet.firstReceiveAddress(words, Network.BITCOIN, passphrase = "x")
        assertTrue("passphrase yields a different wallet", none != withPass)
    }
}
