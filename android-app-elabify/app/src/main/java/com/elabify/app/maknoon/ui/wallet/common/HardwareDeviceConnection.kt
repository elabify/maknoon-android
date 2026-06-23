// Shared, chain-agnostic BLE glue for hardware wallets (Ledger / Trezor),
// the app-side twin of HardwareSecondFactor for the read + sign paths on
// every chain. It owns one BLE connection per operation and mirrors EXACTLY
// the proven connection pattern the discover sweep + HardwareSecondFactor +
// the Ethereum signer use:
//
//   HardwareWalletFactory.make(kind) -> bindToDevice (Ledger) ->
//   beginSession() -> identifyDevice() (serial-match guard) ->
//   applyPassphraseMode (Trezor) -> setDerivationPathOverride -> the op ->
//   endSession()
//
// plus the same stale-link transport retry (HardwareWalletException.Transport
// teardown + bounded backoff) so a fresh connect right after a prior session
// (the add / read / send flows happen back to back) does not fail on a GATT
// link that is still tearing down.
//
// This is the single source of truth ADR-0033 calls for: Bitcoin consumes it
// now (add-read + PSBT sign), Ethereum delegates to it (behaviour preserved),
// and Solana / Tron reuse it next. The passphrase + derivation-path arguments
// are how hidden (BIP39 passphrase) wallets and custom / alternative-path
// wallets thread their session state through, instead of the old hard-pinned
// Standard / null the Ethereum signer used to force.

package com.elabify.app.maknoon.ui.wallet.common

import com.elabify.app.maknoon.MaknoonApplication
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.HardwareWallet
import com.elabify.musnad.hardware.HardwareWalletException
import com.elabify.musnad.hardware.HardwareWalletKind
import com.elabify.musnad.hardware.HardwareWalletFactory
import com.elabify.musnad.hardware.ledger.LedgerHardwareWallet
import com.elabify.musnad.hardware.trezor.PassphraseChoice
import com.elabify.musnad.hardware.trezor.TrezorHardwareWallet
import kotlinx.coroutines.delay

/** Bounded retries for a transient BLE connect (stale-link teardown / busy
 *  channel) on a rapid reconnect. Matches HardwareSecondFactor and the prior
 *  Ethereum connection helper. Kept at 2 so a genuinely flaky link still gets
 *  a reconnect, but a non-responsive device fails in well under a minute. */
private const val MAX_CONNECT_RETRIES = 2

/** Marker the Ledger transport embeds in the APDU-response-timeout message
 *  (see LedgerBleTransport.APDU_TIMEOUT_MARKER). A device that connected fine
 *  but never answered an APDU will NOT answer on a reconnect either, so we do
 *  not burn the full retry budget on it: one attempt, then fail fast. A
 *  stale-link / connect failure has a different message and is still retried,
 *  which is what keeps the rapid-reconnect path working for Ledger + Trezor. */
private const val APDU_TIMEOUT_MARKER = "[apdu-timeout]"

/**
 * Live connection stage for a hardware-wallet operation (ADR-0033: "Discover
 * surfaces explicit connection stages"). The UI renders these as a single
 * status line so the user knows the device is working, especially during the
 * on-device approval / passphrase wait. Chain-agnostic: the Bitcoin discover
 * sweep is the first consumer; Solana / Tron reuse the same model + labels when
 * their sweeps are built.
 *
 *   CONNECTING     - opening the BLE link + identifying the device.
 *   CONNECTED      - the serial guard passed; the right device is on the line.
 *   AWAITING_DEVICE - the device must approve (a Trezor on-device passphrase or
 *                     a button confirm); "Confirm on your <device>".
 *   SCANNING       - reading / probing accounts (carries the account index).
 *   DONE           - the operation finished.
 */
enum class HardwareStage {
    CONNECTING,
    CONNECTED,
    AWAITING_DEVICE,
    SCANNING,
    DONE,
}

/**
 * Human label for a [HardwareStage], using the device label / kind so the line
 * reads e.g. "Connecting to Ledger Nano...", "Confirm on your Trezor". Pass the
 * 0-based [account] for SCANNING so it reads "Scanning account 2...". Shared by
 * every chain's discover rendering so the copy cannot drift.
 */
fun HardwareStage.label(device: RegisteredDevice, account: Long? = null): String {
    val name = device.label.ifBlank { device.kind.displayName }
    return when (this) {
        HardwareStage.CONNECTING -> "Connecting to $name..."
        HardwareStage.CONNECTED -> "Connected"
        HardwareStage.AWAITING_DEVICE -> "Confirm on your ${device.kind.displayName}"
        HardwareStage.SCANNING ->
            if (account != null) "Scanning account $account..." else "Scanning..."
        HardwareStage.DONE -> "Done"
    }
}

private fun hardwareWalletKind(device: RegisteredDevice): HardwareWalletKind =
    when (device.kind) {
        DeviceKind.LEDGER -> HardwareWalletKind.LEDGER
        DeviceKind.TREZOR -> HardwareWalletKind.TREZOR
        else -> HardwareWalletKind.MOCK
    }

/**
 * Connect to [device] over BLE, run [op] inside one pinned session, and tear
 * down. Confirms the live serial matches the registered serial (refuses a
 * different physical device), binds a Ledger to the device's stored BLE
 * peripheral id when known, applies the requested Trezor passphrase mode
 * ([passphraseChoice]) and the requested derivation-path override
 * ([derivationPath]) so the op targets the right (possibly hidden / custom-
 * path) wallet, then runs [op]. A transient transport error is retried with a
 * backoff; a wrong device (serial guard) or a user cancel is NOT retried.
 * Suspend; call off the main thread.
 *
 * [passphraseChoice] is only meaningful for Trezor (Ledger keeps its
 * passphrase on the device and is opaque to the host); it is ignored for a
 * Ledger. Pass [PassphraseChoice.Standard] + null for the standard,
 * standard-path wallet (the byte-for-byte Ethereum behaviour).
 *
 * [onStage] is an OPTIONAL, purely advisory live-stage callback (ADR-0033):
 * CONNECTING before the connect / identify, CONNECTED after the serial guard
 * passes, AWAITING_DEVICE immediately before applying the passphrase mode +
 * invoking [op] (the point at which a Trezor may prompt on-device). It NEVER
 * affects the connection: every invocation is guarded so a throwing callback
 * cannot break or abort the session. The caller renders DONE itself after this
 * returns (the helper does not know when the higher-level sweep is finished).
 */
suspend fun <T> withHardwareDevice(
    device: RegisteredDevice,
    passphraseChoice: PassphraseChoice,
    derivationPath: String?,
    onStage: ((HardwareStage) -> Unit)? = null,
    op: suspend (HardwareWallet) -> T,
): T {
    require(device.kind == DeviceKind.LEDGER || device.kind == DeviceKind.TREZOR) {
        "Hardware signing only handles Ledger / Trezor."
    }
    // Advisory only: a throwing stage callback must never break the connection.
    fun emit(stage: HardwareStage) {
        if (onStage != null) runCatching { onStage(stage) }
    }
    var attempt = 0
    while (true) {
        emit(HardwareStage.CONNECTING)
        val wallet: HardwareWallet = HardwareWalletFactory.make(hardwareWalletKind(device))
        // Pin the live Ledger to the registered physical device by its BLE
        // peripheral id when we captured one at pair time, so a second paired
        // Ledger on the air cannot answer. Trezor targets the right device
        // through its stored reconnect credential. The serial guard below is
        // the hard backstop either way.
        if (wallet is LedgerHardwareWallet) {
            wallet.bindToDevice(device.blePeripheralId)
        }
        wallet.beginSession()
        try {
            val liveSerial = wallet.identifyDevice()
            if (liveSerial != device.serial) {
                // The Ledger "serial" is its BLE transport id, which is
                // platform-specific (an iOS CoreBluetooth peripheral UUID vs an
                // Android BLE MAC). A device record carried across platforms by
                // an encrypted backup therefore can NEVER serial-match the live
                // device, so a hard reject would lock the user out forever. If
                // this is the SOLE registered device of its kind there is no
                // ambiguity about which physical device answered, so re-bind the
                // stored serial to the live one (the device id / wallet links are
                // unchanged) and proceed. With two or more devices of the kind we
                // cannot safely disambiguate by a non-matching serial, so keep the
                // hard backstop.
                val registry = DeviceRegistry(MaknoonApplication.appContext)
                val sameKindCount = registry.devices.count { it.kind == device.kind }
                if (sameKindCount == 1) {
                    android.util.Log.w(
                        "HardwareConn",
                        "Re-binding ${device.kind.displayName} serial ${device.serial} -> $liveSerial " +
                            "(sole device of kind; likely a cross-platform backup restore).",
                    )
                    registry.rebindSerial(device.id, liveSerial)
                } else {
                    throw IllegalStateException(
                        "Connected device serial $liveSerial does not match ${device.serial}. " +
                            "Connect the correct ${device.kind.displayName}.",
                    )
                }
            }
            emit(HardwareStage.CONNECTED)
            // Tell the user to look at the device BEFORE applyPassphraseMode (a
            // Trezor on-device passphrase prompts here) and before the op (a
            // button confirm). Advisory only; the connection is unchanged.
            emit(HardwareStage.AWAITING_DEVICE)
            // Apply the chosen Trezor hidden-wallet passphrase + the custom
            // derivation-path override BEFORE the op so the address read /
            // signature derive in the right wallet. Ledger ignores the
            // passphrase mode (it is host-opaque) but honours the path
            // override.
            if (wallet is TrezorHardwareWallet) {
                wallet.applyPassphraseMode(passphraseChoice)
            }
            wallet.setDerivationPathOverride(derivationPath)
            return op(wallet)
        } catch (e: HardwareWalletException.Transport) {
            android.util.Log.w(
                "HardwareConn",
                "withHardwareDevice transport error on attempt ${attempt + 1}/${MAX_CONNECT_RETRIES + 1} for ${device.kind.displayName}: ${e.detail}",
            )
            // A device that connected but never answered an APDU will not
            // answer on a reconnect either: fail fast instead of looping the
            // full retry budget x the per-APDU timeout (~minutes of hang).
            // Stale-link / connect failures carry a different message and are
            // still retried, preserving the rapid-reconnect path for both
            // Ledger and Trezor.
            if (e.detail.contains(APDU_TIMEOUT_MARKER)) throw e
            if (attempt >= MAX_CONNECT_RETRIES) throw e
            attempt += 1
        } finally {
            runCatching { wallet.endSession() }
        }
        // Reached only on a retryable transport error: let the stale BLE
        // channel time out before reconnecting (longer each attempt).
        delay(attempt * 1500L)
    }
}
