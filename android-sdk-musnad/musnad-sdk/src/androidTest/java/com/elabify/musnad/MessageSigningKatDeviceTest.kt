// Cross-platform message-signing known-answer test (on-device).
//
// Asserts the SAME frozen corpus the Rust core and iOS assert (loaded from
// androidTest/assets/message-signing-kat.json, a copy of the canonical
// ledger-btc-rs/ledger-btc-core/test-vectors/message-signing-kat.json), so
// Android (WalletCore + the shared Rust BTC core via the UniFFI binding)
// produces byte-identical addresses + signatures. BTC = "Bitcoin Signed
// Message" (BIP-137) across legacy / nested / native-segwit x mainnet /
// testnet3 / signet; ETH = EIP-191 personal_sign.
//
// Instrumented (needs the TrustWalletCore + ledger_btc_core native libs); run:
//   ./gradlew :musnad-sdk:connectedDebugAndroidTest

package com.elabify.musnad

import androidx.test.platform.app.InstrumentationRegistry
import com.elabify.musnad.wallet.bitcoin.Bip32Path
import com.elabify.musnad.wallet.bitcoin.BitcoinMessageSigning
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.solana.SolanaMessageSigning
import com.elabify.musnad.wallet.tron.TronMessageSigning
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSigningKatDeviceTest {

    private fun corpus(): JSONObject {
        val text = InstrumentationRegistry.getInstrumentation().context
            .assets.open("message-signing-kat.json").bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    private fun scriptType(s: String): Bip32Path.BitcoinScriptType = when (s) {
        "legacy" -> Bip32Path.BitcoinScriptType.LEGACY
        "nestedSegwit" -> Bip32Path.BitcoinScriptType.NESTED_SEGWIT
        "nativeSegwit" -> Bip32Path.BitcoinScriptType.NATIVE_SEGWIT
        else -> error("unknown scriptType $s")
    }

    private fun network(s: String): BitcoinNetwork = when (s) {
        "mainnet" -> BitcoinNetwork.MAINNET
        "testnet3" -> BitcoinNetwork.TESTNET3
        "signet" -> BitcoinNetwork.SIGNET
        else -> error("unknown network $s")
    }

    @Test
    fun bitcoinKatCorpusMatches() {
        val c = corpus()
        val words = c.getString("mnemonic").split(" ")
        val vectors = c.getJSONArray("bitcoin")
        assertTrue("non-empty corpus", vectors.length() > 0)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val path = v.getString("path")
            val message = v.getString("message")
            val net = network(v.getString("network"))
            val wantAddr = v.getString("expectedAddress")
            val wantSig = v.getString("expectedSignature")

            val (addr, sig) = BitcoinMessageSigning.sign(
                message = message,
                derivationPath = path,
                scriptType = scriptType(v.getString("scriptType")),
                network = net,
                mnemonicWords = words,
                passphrase = null,
            )
            assertEquals("BTC address mismatch at $path/$net", wantAddr, addr)
            assertEquals("BTC signature mismatch at $path/$net", wantSig, sig)
            assertTrue(
                "BTC verify failed at $path/$net",
                BitcoinMessageSigning.verify(wantAddr, message, wantSig),
            )
        }
    }

    @Test
    fun ethereumKatCorpusMatches() {
        val c = corpus()
        val words = c.getString("mnemonic").split(" ")
        val eth = c.getJSONObject("ethereum")
        val message = eth.getString("message")
        val wantAddr = eth.getString("expectedAddress")
        val wantSig = eth.getString("expectedSignature")

        val sig = EthereumDescriptors.signPersonalMessage(
            words = words,
            passphrase = "",
            account = 0L,
            message = message.toByteArray(Charsets.UTF_8),
            derivationPath = eth.getString("path"),
        )
        assertEquals("ETH signature mismatch", wantSig.lowercase(), sig.lowercase())
        assertTrue(
            "ETH verify failed",
            EthereumDescriptors.verifyMessage(wantAddr, message.toByteArray(Charsets.UTF_8), wantSig),
        )
        val recovered = EthereumDescriptors.recoverAddress(message.toByteArray(Charsets.UTF_8), wantSig)
        assertEquals("ETH recover mismatch", wantAddr.lowercase(), recovered?.lowercase())
    }

    @Test
    fun tronKatCorpusMatches() {
        val c = corpus()
        val words = c.getString("mnemonic").split(" ")
        val tron = c.getJSONObject("tron")
        val message = tron.getString("message")
        val wantAddr = tron.getString("expectedAddress")
        val wantSig = tron.getString("expectedSignature")

        val (addr, sig) = TronMessageSigning.sign(
            message = message,
            account = 0L,
            mnemonicWords = words,
            passphrase = null,
        )
        assertEquals("Tron address mismatch", wantAddr, addr)
        assertEquals("Tron signature mismatch", wantSig.lowercase(), sig.lowercase())
        assertTrue("Tron verify failed", TronMessageSigning.verify(wantAddr, message, wantSig))
    }

    @Test
    fun solanaKatCorpusMatches() {
        val c = corpus()
        val words = c.getString("mnemonic").split(" ")
        val sol = c.getJSONObject("solana")
        val message = sol.getString("message")
        val wantAddr = sol.getString("expectedAddress")
        val wantSig = sol.getString("expectedSignature")

        // Solana OCMS: ed25519 (base58 address + base58 signature). This also
        // exercises the SLIP-0010 derivation (SolanaPrimitives) matching the
        // Rust core + Ledger/Trezor on-device.
        val (addr, sig) = SolanaMessageSigning.sign(
            message = message,
            account = 0L,
            mnemonicWords = words,
            passphrase = null,
        )
        assertEquals("Solana address mismatch", wantAddr, addr)
        assertEquals("Solana signature mismatch", wantSig, sig)
        assertTrue("Solana verify failed", SolanaMessageSigning.verify(wantAddr, message, wantSig))
    }
}
