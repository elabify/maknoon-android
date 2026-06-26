// Software-wallet Solana off-chain message (OCMS) sign + verify, ported 1:1
// from TronMessageSigning / the iOS SolanaMessageSigning. The crypto runs in
// the shared Rust core (ledger-sol-core), so iOS, Android, and BOTH hardware
// flows (Ledger + Trezor) stay byte-identical: all three sign the same SIMD-0048
// off-chain-message envelope the core builds.
//
// Signing derives the 32-byte SLIP-0010 ed25519 secret at m/44'/501'/<account>'/0'
// (the same primitive the Solana send path uses) and hands it to the core, which
// builds the OCMS envelope and produces the 64-byte ed25519 signature (base58)
// plus the base58 address. Verification is keyless (the address IS the pubkey).
//
// Note: OCMS is the hardware-wallet format, NOT Phantom's raw signMessage; these
// signatures verify in Maknoon + OCMS-aware tooling, not web3.js nacl.verify.

package com.elabify.musnad.wallet.solana

import uniffi.ledger_sol_core.solSignMessage
import uniffi.ledger_sol_core.solVerifyMessage

object SolanaMessageSigning {

    /**
     * Sign [message] (OCMS) with the software key at [account]. Returns the
     * base58 address (what [verify] checks against) and the base58 signature.
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
        val seed = SolanaPrimitives.privateSeed(mnemonicWords, passphrase ?: "", account)
        val signed = solSignMessage(seed, message)
        return signed.address to signed.signature
    }

    /** Verify an OCMS signature. Keyless: works for any base58 address +
     *  message + base58 signature produced by software, Ledger, or Trezor. */
    fun verify(address: String, message: String, signature: String): Boolean =
        solVerifyMessage(address = address, message = message, signature = signature)
}
