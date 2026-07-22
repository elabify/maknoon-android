// Cross-platform wallet-derivation known-answer test (on-device), ADR-0064.
//
// Asserts the SAME frozen corpus iOS asserts (loaded from
// androidTest/assets/wallet-derivation-kat.json; the iOS copy is inline in
// MaknoonTests/WalletDerivationKATTests.swift and must stay byte-identical),
// so Android derives byte-identical software-wallet addresses. This is the
// gate for the identity-passphrase folding: it builds a REAL IdentitySandwich
// from the fixture entropy + passphrase and derives through the production
// addressFromSandwich paths, which is exactly where a passphrase-dropping
// derivation surfaces. The empty-passphrase vectors are a harness sanity check;
// the "TREZOR" vectors fail unless every chain folds the identity passphrase.
//
// Instrumented (WalletCore + BDK native libs + StrongBox); run:
//   ./gradlew :musnad-sdk:connectedDebugAndroidTest

package com.elabify.musnad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elabify.core.Bip39
import com.elabify.musnad.crypto.AndroidSecureStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.BitcoinWallet
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.solana.SolanaDescriptors
import com.elabify.musnad.wallet.tron.TronDescriptors
import org.bitcoindevkit.Network
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletDerivationKatDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val secure = AndroidSecureStore("maknoon.test.derivation.wrap", requireUnlockedDevice = false)
    private val prefs = "maknoon.test.derivation.prefs"
    private fun store() = IdentityStore(ctx, secure, prefs)
    private val now = 1_800_000_000L

    @After
    fun cleanup() {
        store().wipe()
    }

    private fun corpus(): JSONObject {
        val text = InstrumentationRegistry.getInstrumentation().context
            .assets.open("wallet-derivation-kat.json").bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    /** Build a sandwich carrying the fixture entropy + this vector's passphrase,
     *  using the throwaway test store (does NOT touch the real identity). */
    private fun sandwichFor(entropy: ByteArray, passphrase: String): IdentitySandwich {
        store().wipe()
        return IdentitySandwich.restoreFromMnemonic(Bip39.mnemonicFromEntropy(entropy), passphrase, now, store())
    }

    @Test
    fun walletDerivationKatCorpusMatches() {
        val c = corpus()
        val entropy = hexToBytes(c.getString("entropyHex"))
        assertEquals("fixture entropy is 32 bytes", 32, entropy.size)
        // The mnemonic must be BIP-39 of the declared entropy.
        assertEquals("mnemonic is not BIP-39(entropyHex)",
            c.getString("mnemonic"), Bip39.mnemonicFromEntropy(entropy).joinToString(" "))

        val vectors = c.getJSONArray("vectors")
        assertTrue("non-empty corpus", vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val passphrase = v.getString("passphrase")
            val account = v.getLong("account")
            val label = "pass=\"$passphrase\" account=$account"
            val sandwich = sandwichFor(entropy, passphrase)

            assertEquals("ETH mismatch $label",
                v.getString("eth").lowercase(),
                EthereumDescriptors.addressFromSandwich(sandwich, account).lowercase())

            assertEquals("SOL mismatch $label",
                v.getString("sol"),
                SolanaDescriptors.addressFromSandwich(sandwich, account))

            assertEquals("TRON mismatch $label",
                v.getString("tron"),
                TronDescriptors.addressFromSandwich(sandwich, account))

            // Bitcoin: BIP84 native-segwit mainnet receive address (account 0 only).
            if (v.has("btc")) {
                assertEquals("BTC mismatch $label",
                    v.getString("btc"),
                    BitcoinWallet.firstReceiveAddressFromSandwich(sandwich, Network.BITCOIN))
            }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) or Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }
}
