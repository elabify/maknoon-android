// Ledger / Trezor deterministic-signature second factor (ADR-0032 §Resolved
// decision 3), the BLE twin of SecondFactorUnlock (YubiKey / NFC).
//
// Ledger and Trezor do not expose FIDO2 hmac-secret on their wallet transport,
// so their second-factor secret is a deterministic device signature over a fixed
// challenge (SecondFactorSignature) at a fixed account-0 derivation path,
// SHA-256'd to 32 bytes. This class owns the live BLE connection for one
// operation, mirroring exactly the discover sweep's connection pattern:
//
//   HardwareWalletFactory.make(kind) -> beginSession() -> identifyDevice()
//   (serial-match guard) -> signMessage(challenge) -> endSession()
//
// It is the app-side glue between the SDK's device-agnostic wrap layer
// (SecondFactorWrap / SecondFactorSignature / IdentitySandwich) and the live
// Ledger / Trezor BLE transport. It owns nothing at rest.
//
// User-facing strings say "second factor" / "security key"; never the internal
// sandwich name. The CRITICAL correctness requirement is signMessage
// determinism: the same device + the same challenge must return byte-identical
// output every call, or the wrappedCEK never decrypts again (lockout). Both
// vendors sign an EIP-191 personal message (RFC6979 ECDSA, deterministic) at the
// fixed account-0 path, independent of any hidden wallet / custom path.

package com.elabify.app.maknoon.yubikey

import android.content.Context
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.HardwareWallet
import com.elabify.musnad.hardware.HardwareWalletException
import com.elabify.musnad.hardware.HardwareWalletFactory
import com.elabify.musnad.hardware.HardwareWalletKind
import com.elabify.musnad.hardware.trezor.PassphraseChoice
import com.elabify.musnad.hardware.trezor.TrezorHardwareWallet
import com.elabify.musnad.identity.SecondFactorSignature
import com.elabify.musnad.identity.SecondFactorWrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connects to one registered Ledger / Trezor over BLE and produces (or
 * recovers) its 32-byte second-factor secret via a deterministic device
 * signature. Bound to one device + one operation; each call opens and closes its
 * own session.
 */
class HardwareSecondFactor(
    private val context: Context,
    private val device: RegisteredDevice,
) {
    init {
        require(device.kind == DeviceKind.LEDGER || device.kind == DeviceKind.TREZOR) {
            "HardwareSecondFactor only handles Ledger / Trezor; YubiKey uses SecondFactorUnlock."
        }
    }

    private companion object {
        /** Bounded retries for a transient BLE connect (stale-link "service
         *  discovery failed" / busy channel) on a rapid reconnect. */
        const val MAX_CONNECT_RETRIES = 3
    }

    private fun walletKind(): HardwareWalletKind =
        when (device.kind) {
            DeviceKind.LEDGER -> HardwareWalletKind.LEDGER
            DeviceKind.TREZOR -> HardwareWalletKind.TREZOR
            else -> HardwareWalletKind.MOCK
        }

    /**
     * Compute the second-factor secret for [deviceSalt] by signing the fixed
     * challenge on this device. Pins one BLE session, confirms the live serial
     * matches the registered serial (refuses a different physical device), then
     * signs. Runs off the main thread. The caller has already shown a
     * "connect and approve on your device" prompt.
     *
     * @throws HardwareWalletException on a wrong / absent / refusing device.
     */
    suspend fun computeSecret(deviceSalt: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        // A fresh BLE connect right after a prior session (the 2FA flows do rapid
        // promote -> recover -> enroll cycles) can come back as a transient
        // transport error: the GATT link from the previous op is still tearing
        // down, so service discovery fails or the channel is busy. The proven
        // pairing path (TrezorHardwareWallet.establishPairedSession) handles this
        // with a clean teardown + backoff + retry; mirror that here. A wrong
        // device (serial guard) or a user cancel is NOT retried.
        var attempt = 0
        while (true) {
            val wallet: HardwareWallet = HardwareWalletFactory.make(walletKind())
            wallet.beginSession()
            try {
                val liveSerial = wallet.identifyDevice()
                require(liveSerial == device.serial) {
                    "Connected device serial $liveSerial does not match ${device.serial}. " +
                        "Connect the correct ${device.kind.displayName}."
                }
                // The deterministic secret is keyed by the device seed at the fixed
                // account-0 path. signMessage always signs in the STANDARD wallet
                // (Trezor PassphraseSpec.Standard) regardless of any hidden-wallet
                // promotions, so pin Standard explicitly for stability.
                if (wallet is TrezorHardwareWallet) {
                    wallet.applyPassphraseMode(PassphraseChoice.Standard)
                    wallet.setDerivationPathOverride(null)
                } else {
                    wallet.setDerivationPathOverride(null)
                }
                return@withContext SecondFactorSignature.recomputeSecret(deviceSalt) { challenge ->
                    wallet.signMessage(challenge)
                }
            } catch (e: HardwareWalletException.Transport) {
                if (attempt >= MAX_CONNECT_RETRIES) throw e
                attempt += 1
            } finally {
                runCatching { wallet.endSession() }
            }
            // Reached only on a retryable transport error: let the device's stale
            // BLE channel time out before reconnecting (longer each attempt).
            kotlinx.coroutines.delay(attempt * 1500L)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * Recover this device's wrapped CEK: recompute the secret for its stored
     * deviceSalt, then unwrap. Throws a clear error if the promotion is
     * incomplete (no salt / wrappedCEK). A wrong device fails the serial guard
     * or the GCM tag; the caller surfaces "try again" or falls back to the
     * 24-word phrase / encrypted backup.
     */
    suspend fun recoverCek(): ByteArray {
        val promo = device.promotions.identity
            ?: throw HardwareWalletException.Transport(
                "This ${device.kind.displayName} is not enrolled as a second factor.",
            )
        val saltHex = promo.deviceSaltHex
        val wrappedCekHex = promo.wrappedCekHex
        if (saltHex.isNullOrEmpty() || wrappedCekHex.isNullOrEmpty()) {
            throw HardwareWalletException.Transport(
                "A second-factor record is incomplete. Re-enroll this ${device.kind.displayName}.",
            )
        }
        val salt = hexToBytes(saltHex)
        val secret = computeSecret(salt)
        return SecondFactorWrap.unwrapCek(wrappedCekHex, secret, salt)
    }
}
