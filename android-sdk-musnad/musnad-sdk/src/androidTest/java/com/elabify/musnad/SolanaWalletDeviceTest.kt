// On-device test for Solana derivation on the Pixel 9. Verifies the SLIP-0010
// ed25519 derivation against the canonical spec test vectors (Test vector 1,
// seed 000102...0f) -- a trusted oracle proving the HD derivation is correct --
// then checks the Solana address (m/44'/501'/0'/0') is valid base58 and
// deterministic. Same scheme as WalletCore/iOS, so the address matches.

package com.elabify.musnad

import com.elabify.core.Bip39
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.wallet.SolanaWallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaWalletDeviceTest {

    @Test
    fun slip10Ed25519MatchesSpecVector1() {
        val seed = hexToBytes("000102030405060708090a0b0c0d0e0f")
        val m = SolanaWallet.slip10MasterEd25519(seed)
        assertEquals("2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7", m.key.toHex())
        assertEquals("90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb", m.chainCode.toHex())

        val m0h = SolanaWallet.slip10DeriveHardened(m, 0)
        assertEquals("68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3", m0h.key.toHex())
        assertEquals("8b59aa11380b624e81507a27fedda59fea6d0b779a778918a2fd3590e16e9c69", m0h.chainCode.toHex())
    }

    @Test
    fun solanaAddressIsValidBase58AndDeterministic() {
        val words = Bip39.mnemonicFromEntropy(ByteArray(32))
        val a = SolanaWallet.address(words)
        assertTrue("base58 32-44 chars: $a", a.matches(Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")))
        assertEquals("deterministic", a, SolanaWallet.address(words))
        assertTrue("passphrase changes wallet", a != SolanaWallet.address(words, "x"))
    }
}
