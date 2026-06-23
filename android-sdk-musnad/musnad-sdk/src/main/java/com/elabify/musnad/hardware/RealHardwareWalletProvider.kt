// The real BLE-backed RealClientProvider for HardwareWalletFactory.
//
// iOS routes HardwareWalletFactory.make(.ledger / .trezor) to the real
// LedgerBLE / TrezorBLE clients on a physical device (and to the mock
// only under #if targetEnvironment(simulator)). Android has no compile-
// time simulator flag, so HardwareWalletFactory exposes a provider seam
// (RealClientProvider) that the app registers at startup. This is that
// provider: it builds a fresh LedgerHardwareWallet or TrezorHardwareWallet
// per call (the factory's documented "fresh client per make" contract),
// each wired to its real Android BLE transport so identifyDevice() reads
// the actual device over Bluetooth instead of the mock serial.
//
// Construct with the application Context (the BLE transports reach the
// system BluetoothManager through it; the Trezor credential store seals
// its THP host key + reconnect credential under an AndroidKeyStore key).
// The app installs this once via HardwareWalletFactory.setRealClientProvider
// in MaknoonApplication.onCreate, mirroring the iOS "real on device"
// routing 1:1.

package com.elabify.musnad.hardware

import android.content.Context
import com.elabify.musnad.hardware.ledger.LedgerHardwareWallet
import com.elabify.musnad.hardware.trezor.TrezorBleTransport
import com.elabify.musnad.hardware.trezor.TrezorCredentialStore
import com.elabify.musnad.hardware.trezor.TrezorHardwareWallet

/**
 * Builds the real BLE clients for the hardware-requiring wallet kinds.
 *
 * @param appContext the application Context; only the application context
 *   is retained (the transports + credential store call
 *   `applicationContext` on it), so this never leaks an Activity.
 */
class RealHardwareWalletProvider(
    appContext: Context,
) : HardwareWalletFactory.RealClientProvider {

    private val appContext: Context = appContext.applicationContext

    /** One process-wide credential store: the Trezor THP host key must
     *  stay stable across reconnects (the reconnect credential is bound to
     *  it), and the store is internally backed by SharedPreferences +
     *  AndroidKeyStore, so a shared instance is correct and cheap. */
    private val trezorCredentialStore: TrezorCredentialStore by lazy {
        TrezorCredentialStore(appContext)
    }

    override fun make(kind: HardwareWalletKind): HardwareWallet? =
        when (kind) {
            HardwareWalletKind.LEDGER -> LedgerHardwareWallet(appContext)
            HardwareWalletKind.TREZOR -> TrezorHardwareWallet(
                transport = TrezorBleTransport(appContext),
                credentialStore = trezorCredentialStore,
            )
            // MOCK never reaches the provider (the factory short-circuits it);
            // decline anything else so the factory falls back to the mock.
            HardwareWalletKind.MOCK -> null
        }
}
