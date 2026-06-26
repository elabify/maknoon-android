// Software-wallet "TRON Signed Message" (TIP-191) sign + verify, ported 1:1
// from BitcoinMessageSigning / the iOS TronMessageSigning. The crypto runs in
// the shared Rust core (ledger-tron-core), so iOS, Android, and the Ledger flow
// stay byte-identical and interoperate with TronWeb / TronLink (signMessageV2 /
// verifyMessageV2) and Trust Wallet Core's TronMessageSigner.
//
// Signing derives the secp256k1 key at m/44'/195'/<account>'/0/0 via Trust
// Wallet Core's HDWallet (the same primitive the Tron send path uses) and hands
// the raw 32-byte key to the core, which produces the TIP-191 signature (0x-hex
// r||s||v) and the bound base58check T-address. Verification is keyless.

package com.elabify.musnad.wallet.tron

import uniffi.ledger_tron_core.tronSignMessage
import uniffi.ledger_tron_core.tronVerifyMessage
import wallet.core.jni.HDWallet

object TronMessageSigning {

    /**
     * Sign [message] (TIP-191) with the key at account [account]. Returns the
     * bound T-address (what [verify] checks against) and the 0x-hex signature.
     * The caller unlocks the Identity Sandwich at the UI (BiometricPrompt) and
     * passes `sandwich.recoveryWords()` + passphrase here, matching the Send
     * flow's secret handling.
     */
    fun sign(
        message: String,
        account: Long,
        mnemonicWords: List<String>,
        passphrase: String?,
    ): Pair<String, String> {
        ensureWalletCore()
        val wallet = HDWallet(mnemonicWords.joinToString(" "), passphrase ?: "")
        val key = wallet.getKey(TronDescriptors.derivationPath(account))
        val signed = tronSignMessage(key.data(), message)
        return signed.address to signed.signature
    }

    /** Verify a TIP-191 signature. Keyless: works for any T-address + message +
     *  0x-hex signature from any source. */
    fun verify(address: String, message: String, signature: String): Boolean =
        tronVerifyMessage(address = address, message = message, signature = signature)

    @Volatile private var walletCoreLoaded = false

    private fun ensureWalletCore() {
        if (!walletCoreLoaded) {
            System.loadLibrary("TrustWalletCore")
            walletCoreLoaded = true
        }
    }
}
