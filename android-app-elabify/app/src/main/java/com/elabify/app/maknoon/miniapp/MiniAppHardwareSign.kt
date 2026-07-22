// Pre-sign "Prepare Device" gate for mini-app hardware signing. The Android twin
// of iOS MiniAppHardwareSignCoordinator.
//
// A bridge handler (pool-access grant, web3 personal_sign / signTypedData /
// sendTransaction) calls this before opening BLE to a Ledger / Trezor. It posts
// a kind="hwsign" ApprovalRequest whose payload carries the bound device's kind
// / label / serial and whether a host-typed hidden-wallet passphrase is needed;
// MiniAppApprovalSheetHostImpl renders the shared HardwareSignReadySheet and
// resolves with the typed passphrase (or none). The handler then threads that
// passphrase into the SDK sign call.
//
// Without this step a hidden Trezor wallet either throws immediately
// ("Enter this hidden wallet's passphrase to sign.", from resolveChoice with a
// null passphrase) or, for an on-device-passphrase wallet, blocks forever on a
// device response the user was never told to give ("verifying and granting
// access on-chain" hangs). The passphrase is returned to the handler and never
// stored.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import org.json.JSONObject

/**
 * Present the "Prepare Device" sheet for [device] and await the user. Returns
 * the host-typed hidden-wallet passphrase they entered (null when the wallet
 * needs none). [hiddenWireId] is the descriptor's hidden ref wire id (drives
 * whether the passphrase field is shown). Throws
 * MiniAppBridgeError.userRejected() on Cancel / dismiss.
 */
suspend fun ApprovalGate.requestHardwareSign(
    device: RegisteredDevice,
    hiddenWireId: String?,
    appTitle: String,
): String? {
    val requiresHostPassphrase =
        HardwarePassphraseRef.fromWireId(hiddenWireId)?.needsHostPassphrase == true
    val payload = JSONObject()
        .put("deviceKind", device.kind.name)
        .put("deviceLabel", device.label)
        .put("deviceSerial", device.serialDisplay)
        .put("requiresHostPassphrase", requiresHostPassphrase)
        .toString()
    val result = request(kind = "hwsign", payloadJson = payload, appTitle = appTitle)
    val obj = JSONObject(result)
    if (obj.isNull("passphrase")) return null
    return obj.optString("passphrase").takeIf { it.isNotEmpty() }
}
