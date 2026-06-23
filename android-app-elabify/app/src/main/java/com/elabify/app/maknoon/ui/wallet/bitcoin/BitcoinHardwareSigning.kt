// App-side BLE glue for Bitcoin hardware (Ledger / security key) PSBT signing,
// the Bitcoin twin of EthereumDeviceSigner. Replaces the SDK's
// BitcoinSigningHelpers.signOverBLE hook (which threw BleSigningNotYetImplemented):
// the engine builds the unsigned PSBT, this routes it onto the device over the
// shared withHardwareDevice connection, and the engine finalizes + broadcasts
// the returned signed PSBT.
//
// Key facts (mirroring the proven iOS BitcoinSigningHelpers.signOverBLE):
//
//   * Both vendors' rich entry point is signBitcoinPsbt / signBitcoinPSBT
//     (unsignedBase64, fingerprintHex, accountXpub, account, coinType). The
//     bare HardwareWallet.signPsbt(psbt, coinType) throws on Ledger because it
//     lacks descriptor context, so we cast to the concrete vendor type and call
//     the rich method, exactly like iOS.
//
//   * account is passed as 0: the account-level xpub already encodes the
//     account derivation, and the PSBT's bip32 derivation paths are relative to
//     it, so the Ledger WalletPolicy origin path matches. iOS passes 0 too.
//
//   * BIP44 / 49 / 84 are supported through descriptor.derivationPath: its
//     purpose selects the script type / WalletPolicy template on the device
//     (the engine's watch-only descriptor already keys off the same field), and
//     it is applied as the derivation-path override before signing. A null
//     path is the standard BIP84 wallet.
//
//   * Hidden (Trezor passphrase) wallets re-apply HardwarePassphraseRef
//     .resolveChoice(descriptor.hidden, hostEntered) so the device re-derives
//     the matching keys; host-typed passphrases are re-entered per signing and
//     never stored.
//
//   * The unsigned PSBT must be built from a synced wallet whose prev txs are
//     present (the engine's fullScan / sync uses fetchPrevTxouts = true), so the
//     PSBT carries non_witness_utxo and modern Trezor firmware (which verifies
//     input amounts even for SegWit via the TXMETA / prev-tx stream) can sign
//     BIP44 / 49 / 84.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.ledger.LedgerHardwareWallet
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.hardware.trezor.TrezorHardwareWallet
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletException

/**
 * Sign [unsignedBase64] for the hardware wallet bound to [device] and return the
 * signed PSBT base64 (with partial_sigs merged in) for the engine to finalize +
 * broadcast. Connects over the shared withHardwareDevice (serial guard + the
 * Trezor passphrase + the derivation-path override + the stale-link retry).
 * Suspend; call off the main thread.
 */
suspend fun signBitcoinHardwarePsbt(
    device: RegisteredDevice,
    unsignedBase64: String,
    fingerprintHex: String,
    accountXpub: String,
    network: BitcoinNetwork,
    hidden: org.json.JSONObject?,
    derivationPath: String?,
    hostEnteredPassphrase: String?,
): String {
    val coinType = network.coinType
    val hiddenRef = HardwarePassphraseRef.fromJson(hidden)
    val choice = HardwarePassphraseRef.resolveChoice(hiddenRef, hostEnteredPassphrase)
    return withHardwareDevice(device, choice, derivationPath) { wallet ->
        when (wallet) {
            is LedgerHardwareWallet -> wallet.signBitcoinPsbt(
                unsignedBase64 = unsignedBase64,
                fingerprintHex = fingerprintHex,
                accountXpub = accountXpub,
                account = 0L,
                coinType = coinType,
            )
            is TrezorHardwareWallet -> wallet.signBitcoinPSBT(
                unsignedBase64 = unsignedBase64,
                fingerprintHex = fingerprintHex,
                accountXpub = accountXpub,
                account = 0L,
                coinType = coinType,
            )
            else -> throw BitcoinWalletException.SendFailed(
                "${device.kind.displayName} does not support BLE Bitcoin signing.",
            )
        }
    }
}
