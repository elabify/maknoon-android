// App-side BLE glue for Tron hardware message signing (Ledger only). Trezor
// firmware has no Tron message-sign protobuf, so a Trezor-backed Tron wallet
// never reaches here (the UI shows an unsupported message); the else branch is
// a defensive backstop. Mirrors EthereumHardwareConnection.signEthereumHardwareMessage.

package com.elabify.app.maknoon.ui.wallet.tron

import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.ledger.LedgerHardwareWallet
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef

/**
 * Sign [message] (TIP-191 "TRON Signed Message") on the Ledger bound to
 * [device] at [account]. Connects over the shared withHardwareDevice (serial
 * guard + retry); the device prefixes + signs and the bound T-address is
 * recovered host-side. Returns (address, 0x-hex r||s||v). Suspend; call off the
 * main thread.
 */
suspend fun signTronHardwareMessage(
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
    return withHardwareDevice(device, choice, derivationPath) { wallet ->
        when (wallet) {
            is LedgerHardwareWallet -> {
                val signed = wallet.signTronMessage(account, message)
                signed.address to signed.signature
            }
            else -> throw IllegalStateException(
                "${device.kind.displayName} firmware does not support Tron message signing.",
            )
        }
    }
}
