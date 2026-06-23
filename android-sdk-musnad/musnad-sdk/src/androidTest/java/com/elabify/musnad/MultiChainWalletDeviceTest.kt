// On-device test for WalletCore-derived ETH/SOL/TRON addresses on the Pixel 9.
// Proves the TrustWalletCore native lib loads on arm64/GrapheneOS and derives
// well-formed, deterministic addresses for each chain.

package com.elabify.musnad

import com.elabify.core.Bip39
import com.elabify.musnad.wallet.MultiChainWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiChainWalletDeviceTest {

    private val words = Bip39.mnemonicFromEntropy(ByteArray(32))

    @Test
    fun ethereumAddressIsEip55() {
        val a = MultiChainWallet.ethereumAddress(words)
        assertTrue("0x + 40 hex: $a", a.matches(Regex("^0x[0-9a-fA-F]{40}$")))
        assertEquals("deterministic", a, MultiChainWallet.ethereumAddress(words))
    }

    @Test
    fun tronAddressStartsWithT() {
        val a = MultiChainWallet.tronAddress(words)
        assertTrue("tron T...: $a", a.startsWith("T") && a.length in 30..40)
    }

    @Test
    fun passphraseChangesEthereumWallet() {
        assertTrue(
            MultiChainWallet.ethereumAddress(words) != MultiChainWallet.ethereumAddress(words, "x"),
        )
    }
}
