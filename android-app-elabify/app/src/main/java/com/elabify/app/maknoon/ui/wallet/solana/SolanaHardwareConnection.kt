// Live BLE glue for Solana hardware wallets (Ledger / Trezor), the Solana twin
// of EthereumDeviceSigner. The SDK's SolanaWallet.sendHardware /
// prepareHardwareNative take a SolanaHardwareSigner callback; this implements it
// by opening one withHardwareDevice session (serial guard + stale-link retry)
// and asking the device to sign the unsigned message, re-applying the wallet's
// hidden (Trezor passphrase) mode + custom derivation path so the device derives
// the SAME wallet that was added.
//
// signSolanaTransaction is a plain (non-suspend) SDK callback; the device op is
// suspend, and the send screen already runs sendHardware on Dispatchers.IO, so
// we bridge with runBlocking exactly as the Ethereum signer does. The host-typed
// passphrase (for a hidden wallet) is captured at construction from the pre-sign
// dialog and used once; only the hidden CONFIG lives on the descriptor.

package com.elabify.app.maknoon.ui.wallet.solana

import com.elabify.app.maknoon.MaknoonApplication
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.wallet.solana.SolanaHardwareSigner
import kotlinx.coroutines.runBlocking

class SolanaDeviceSigner(
    private val device: RegisteredDevice,
    /** The wallet's hidden (Trezor passphrase) CONFIG, from descriptor.hidden;
     *  null for a standard / Ledger wallet. */
    private val hidden: HardwarePassphraseRef?,
    private val derivationPath: String?,
    /** Host-typed hidden-wallet passphrase for THIS signing (never stored). */
    private val hostPassphrase: String?,
) : SolanaHardwareSigner {

    override fun signSolanaTransaction(unsignedMessage: ByteArray, account: Long): ByteArray = runBlocking {
        val choice = HardwarePassphraseRef.resolveChoice(hidden, hostPassphrase)
        withHardwareDevice(device, choice, derivationPath) { wallet ->
            wallet.signSolanaTransaction(unsignedMessage, account)
        }
    }
}

/**
 * Sign [message] (Solana OCMS off-chain message) on the device bound to
 * [device] at [account]. Works on BOTH Ledger and Trezor: the wrapper fetches
 * the device pubkey, builds the SIMD-0048 envelope (ledger-sol-core), has the
 * device sign it raw, and returns (base58 address, base58 64-byte signature) so
 * software + Ledger + Trezor are byte-identical. Suspend; call off the main
 * thread. Re-applies the wallet's hidden (Trezor passphrase) mode + custom
 * derivation path so the device derives the SAME wallet that was added.
 */
suspend fun signSolanaHardwareMessage(
    device: RegisteredDevice,
    account: Long,
    message: String,
    hidden: org.json.JSONObject?,
    derivationPath: String?,
    hostEnteredPassphrase: String?,
): Pair<String, String> {
    val choice = HardwarePassphraseRef.resolveChoice(
        HardwarePassphraseRef.fromJson(hidden),
        hostEnteredPassphrase,
    )
    return try {
        withHardwareDevice(device, choice, derivationPath) { wallet ->
            val signed = wallet.signSolanaMessage(message, account)
            signed.address to signed.signature
        }
    } catch (e: Throwable) {
        // Trezor firmware predating Solana OCMS support (trezor-firmware PR #6759)
        // has no SolanaSignMessage (906) handler and returns Failure_UnexpectedMessage.
        // Surface an update-firmware hint instead of the raw protocol error.
        if ((e.message ?: "").contains("unexpected message", ignoreCase = true)) {
            throw IllegalStateException(
                MaknoonApplication.appContext.getString(R.string.sol_trezor_firmware_no_ocms),
            )
        }
        throw e
    }
}
