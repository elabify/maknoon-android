// The vendor-agnostic hardware-wallet contract, ported 1:1 from the iOS
// HardwareWallet protocol. Ledger (BLE), Trezor (BLE/USB), and a Mock impl
// satisfy it; the wallet engines' signing hooks call through it. Suspend
// functions (iOS async); ByteArray for Data; accounts/coin-types are Long.

package com.elabify.musnad.hardware

enum class HardwareWalletKind(val wireId: String, val displayName: String, val requiresHardware: Boolean) {
    TREZOR("trezor-secp256k1", "Trezor", true),
    LEDGER("ledger-secp256k1", "Ledger", true),
    MOCK("mock-secp256k1", "Demo (no hardware)", false),
}

/** secp256k1 signature pieces (Ethereum / Tron style): parity v + 32-byte r,s. */
data class EcdsaSignature(val v: Int, val r: ByteArray, val s: ByteArray)

sealed class HardwareWalletException(message: String) : Exception(message) {
    class NotImplemented(val kind: HardwareWalletKind) :
        HardwareWalletException("${kind.displayName}: on-device signing not implemented for this device kind yet")
    class UserCancelled : HardwareWalletException("Cancelled on the device")
    class Transport(val detail: String) : HardwareWalletException("Hardware transport error: $detail")

    /** A DETERMINISTIC device-side rejection or invalid-input error: the
     *  device (or the protocol layer) spoke and said no, and retrying the
     *  identical request will fail identically (e.g. Trezor "Input does not
     *  match scriptPubKey", a malformed PSBT / derivation path, a rejected
     *  pairing credential). Distinct from [Transport]: the connection helper
     *  MUST NOT retry this, so the device is not re-prompted 3x for the same
     *  guaranteed failure. [detail] is a clean one-line message for the UI. */
    class DeviceRejected(val detail: String) : HardwareWalletException(detail)
}

interface HardwareWallet {
    val kind: HardwareWalletKind

    /** BLE peripheral id (Android MAC/address) most recently connected, or
     *  null for transports without one (USB, camera-only). Used to hard-filter
     *  reconnects to the specific device the user paired. */
    val currentBlePeripheralId: String?

    suspend fun identifyDevice(): String

    /** Proof-of-possession: a deterministic secp256k1 pubkey/signature used to
     *  derive the Identity-Sandwich wrap key. */
    suspend fun pair(): ByteArray

    /** Sign an arbitrary message (wrap-key derivation / unlock). */
    suspend fun signMessage(message: ByteArray): ByteArray

    // Session pinning: reference-counted so multi-account discover sweeps keep
    // one BLE connection open (Ledger drops mid-scan otherwise).
    fun beginSession()
    fun endSession()

    /** REQUIRED (not a default) so a call through the interface dispatches to
     *  the real impl, not a no-op. Sets a custom/alternative derivation path. */
    fun setDerivationPathOverride(path: String?)

    // -- Bitcoin --
    suspend fun getBitcoinAccountXpub(account: Long, networkCoinType: Long): String
    suspend fun getBitcoinMasterFingerprint(networkCoinType: Long): String
    suspend fun signPsbt(psbt: ByteArray, networkCoinType: Long): ByteArray

    // -- Ethereum / EVM --
    suspend fun getEthereumAddress(account: Long): String
    suspend fun signEthereumTransaction(
        envelope: ByteArray,
        account: Long,
        erc20Descriptor: ByteArray?,
    ): EcdsaSignature

    // -- Solana --
    suspend fun getSolanaAddress(account: Long): String
    suspend fun signSolanaTransaction(unsignedTx: ByteArray, account: Long): ByteArray

    // -- Tron (Ledger + Trezor) --
    suspend fun getTronAddress(account: Long): String
    suspend fun getTronPubkey(account: Long): ByteArray
    suspend fun signTronTransaction(rawTxProto: ByteArray, account: Long): EcdsaSignature
}
