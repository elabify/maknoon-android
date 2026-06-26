// Software-wallet "Bitcoin Signed Message" sign + verify, ported 1:1 from
// iOS BitcoinMessageSign.swift (the BitcoinMessageSigning enum). The crypto
// runs in the shared Rust core (ledger-btc-core, rust-bitcoin), so iOS,
// Android, and the Ledger/Trezor flows stay byte-identical and interoperate
// with Electrum / Bitcoin Core.
//
// Signing derives the secret key at the address path via Trust Wallet Core's
// HDWallet (the same primitive the Ethereum signer uses) and hands the raw
// 32-byte key to the core, which produces the standard "Bitcoin Signed
// Message" (Electrum-compatible) signature for the chosen script type and
// network and the address it is bound to. Native segwit by default; legacy
// and nested segwit are selected by the account path purpose. All networks
// (mainnet, testnet3, signet) are supported.
//
// Verification is keyless: it recovers the public key from the signature and
// checks it against any address + message + base64 signature from any source.

package com.elabify.musnad.wallet.bitcoin

import uniffi.ledger_btc_core.BtcMsgNetwork
import uniffi.ledger_btc_core.BtcMsgScriptType
import uniffi.ledger_btc_core.btcSignMessage
import uniffi.ledger_btc_core.btcVerifyMessage
import wallet.core.jni.HDWallet

object BitcoinMessageSigning {

    /**
     * Sign [message] with the key at [derivationPath] (a full BIP32 path, e.g.
     * "m/84'/0'/0'/0/0"), in the Electrum "Bitcoin Signed Message" format for
     * [scriptType] on [network]. Returns the address the signature binds to
     * (what [verify] checks against) and the base64 signature.
     *
     * The caller unlocks the Identity Sandwich at the UI (BiometricPrompt) and
     * passes `sandwich.recoveryWords()` + passphrase here, matching the Send
     * flow's secret handling: the seed is read once and not retained.
     */
    fun sign(
        message: String,
        derivationPath: String,
        scriptType: Bip32Path.BitcoinScriptType,
        network: BitcoinNetwork,
        mnemonicWords: List<String>,
        passphrase: String?,
    ): Pair<String, String> {
        ensureWalletCore()
        val wallet = HDWallet(mnemonicWords.joinToString(" "), passphrase ?: "")
        val key = wallet.getKey(derivationPath)
        val signed = btcSignMessage(
            secretKey = key.data(),
            message = message,
            scriptType = scriptType.coreScriptType(),
            networkKind = network.coreNetwork(),
        )
        return signed.address to signed.signature
    }

    /**
     * Verify an Electrum "Bitcoin Signed Message" signature (legacy or segwit).
     * Keyless: works for any address + message + base64 signature.
     */
    fun verify(address: String, message: String, signature: String): Boolean =
        btcVerifyMessage(address = address, message = message, signature = signature)

    private fun Bip32Path.BitcoinScriptType.coreScriptType(): BtcMsgScriptType = when (this) {
        Bip32Path.BitcoinScriptType.LEGACY -> BtcMsgScriptType.LEGACY
        Bip32Path.BitcoinScriptType.NESTED_SEGWIT -> BtcMsgScriptType.NESTED_SEGWIT
        Bip32Path.BitcoinScriptType.NATIVE_SEGWIT -> BtcMsgScriptType.NATIVE_SEGWIT
    }

    private fun BitcoinNetwork.coreNetwork(): BtcMsgNetwork = when (this) {
        BitcoinNetwork.MAINNET -> BtcMsgNetwork.MAINNET
        BitcoinNetwork.TESTNET3 -> BtcMsgNetwork.TESTNET
        BitcoinNetwork.SIGNET -> BtcMsgNetwork.SIGNET
    }

    @Volatile private var walletCoreLoaded = false

    /** Load the Trust Wallet Core native lib once (idempotent). HDWallet needs
     *  it; the BDK descriptor path does not, so the Bitcoin module otherwise
     *  never touches WalletCore. */
    private fun ensureWalletCore() {
        if (!walletCoreLoaded) {
            System.loadLibrary("TrustWalletCore")
            walletCoreLoaded = true
        }
    }
}
