// Shared mapping + error helpers for the Devices UI.
//
// DeviceRegistry models the user's registered devices with the richer
// DeviceKind (yubikey / ledger / trezor / seedsigner, with capabilities),
// while the signing contract uses the narrower HardwareWalletKind
// (TREZOR / LEDGER / MOCK) the factory dispatches on. These helpers bridge
// the two and surface human-readable transport-error text, matching the
// iOS LocalizedError fallbacks.

package com.elabify.app.maknoon.ui.devices

import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.hardware.HardwareWalletException
import com.elabify.musnad.hardware.HardwareWalletKind

/**
 * Which signing client the factory should build for a registered device.
 * Ledger and Trezor map straight through; any other kind has no live BLE
 * signing client, so we fall back to the demo (mock) client, matching the
 * iOS simulator-fallback behaviour in RegisterDeviceSheet.identify().
 */
internal fun DeviceKind.hardwareWalletKind(): HardwareWalletKind =
    when (this) {
        DeviceKind.LEDGER -> HardwareWalletKind.LEDGER
        DeviceKind.TREZOR -> HardwareWalletKind.TREZOR
        else -> HardwareWalletKind.MOCK
    }

/** Friendly one-line message for surfacing a failed device round-trip. */
internal fun Throwable.friendlyMessage(): String =
    when (this) {
        is HardwareWalletException.UserCancelled -> "Cancelled on the device."
        is HardwareWalletException.Transport -> "Hardware transport error: $detail"
        is HardwareWalletException.NotImplemented ->
            "${kind.displayName}: on-device signing is not implemented for this device kind yet."
        else -> message ?: toString()
    }
