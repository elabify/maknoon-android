package com.elabify.maknoon.impl

import android.content.Context
import com.elabify.maknoon.DeviceKind
import com.elabify.maknoon.DeviceRef
import com.elabify.maknoon.DiscoveredDevice
import com.elabify.maknoon.EnrollOptions
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MaknoonHardware
import com.elabify.maknoon.SignedTx
import com.elabify.maknoon.Transport
import com.elabify.maknoon.UnsignedTx
import com.elabify.musnad.devices.DeviceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [MaknoonHardware] over the device registry + hardware transports. Identity-agnostic device
 * plumbing (enrolling a device as an Identity Sandwich second factor is
 * `MusnadIdentity.enrollSecondFactor`, not here).
 *
 * Wired: `enrolledDevices()` reads the registry (backed by the SDK-owned device prefs, the
 * same one the app writes). Discovery, enrollment, and signing are transport-coupled: they
 * need BLE / USB-OTG / NFC scanning with host-granted runtime permissions and the app-set
 * real hardware provider (`HardwareWalletFactory.setRealClientProvider`, wiring the Ledger /
 * Trezor UniFFI cores). Those land with the transport-wiring step of the migration; device
 * signing then unblocks `MaknoonWallet.signAndBroadcast(Signer.Device)` across all chains.
 */
internal class MaknoonHardwareImpl(
    private val appContext: Context,
) : MaknoonHardware {

    override suspend fun enrolledDevices(): List<DeviceRef> = withContext(Dispatchers.IO) {
        DeviceRegistry(appContext).also { it.reload() }.devices.mapNotNull { d ->
            facadeKind(d.kind)?.let { k -> DeviceRef(id = d.id, kind = k, label = d.label, serial = d.serial) }
        }
    }

    override fun discover(kind: DeviceKind, transport: Transport): Flow<DiscoveredDevice> = transportPending()

    override suspend fun enroll(device: DiscoveredDevice, options: EnrollOptions): DeviceRef = transportPending()

    override suspend fun sign(tx: UnsignedTx, device: DeviceRef): SignedTx = transportPending()

    private fun transportPending(): Nothing = throw MaknoonError.Configuration(
        "Device discovery / enrollment / signing land with the transport-wiring step (BLE/USB/NFC " +
            "scanning + the app-set hardware provider); enrolledDevices() reads the registry today",
    )

    /** Map the internal device kind to the facade kind by name; drop unknowns. */
    private fun facadeKind(k: com.elabify.musnad.devices.DeviceKind): DeviceKind? = when (k.name) {
        "LEDGER" -> DeviceKind.LEDGER
        "TREZOR" -> DeviceKind.TREZOR
        "YUBIKEY" -> DeviceKind.YUBIKEY
        "SEEDSIGNER" -> DeviceKind.SEEDSIGNER
        else -> null
    }
}
