// Persistence + in-memory model for the user's registered hardware devices.
// Ported 1:1 from iOS Devices/DeviceRegistry.swift.
//
// iOS backs this with UserDefaults under "devices.registered.v1"; here it is
// SharedPreferences under the same key so the wire is identical. The iOS
// type is @Observable (SwiftUI); on Android the host observes via whatever
// state holder it wires this into, so this class just exposes the current
// list + mutators and persists on every change.
//
// The promotion mutators here only touch the persisted promotion RECORDS.
// The Identity-Sandwich hardware-wrap promote/demote (sealing seed entropy
// behind the device) is a documented seam owned by another component; this
// registry never performs that wrap, it only records that it happened.

package com.elabify.musnad.devices

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray

class DeviceRegistry(private val prefs: SharedPreferences) {

    /** Convenience: open the registry's own SharedPreferences file. */
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    var devices: List<RegisteredDevice> = emptyList()
        private set

    init { load() }

    /** Drop the in-memory cache and re-read from storage. Used by the wallet-
     *  wide reset path so a wipe surfaces immediately. */
    fun reload() {
        devices = emptyList()
        load()
    }

    fun find(serial: String): RegisteredDevice? = devices.firstOrNull { it.serial == serial }

    fun find(id: UUID): RegisteredDevice? = devices.firstOrNull { it.id == id }

    /** Register a new device, OR return the existing record if a device with
     *  the same (kind, serial) was previously registered. Idempotent so re-
     *  running "Register device" on the same physical device does not create
     *  duplicates. Upgrades the existing record's blePeripheralId / attestation
     *  pubkey in place if newly supplied (we never overwrite a stable value
     *  with null). */
    fun register(
        kind: DeviceKind,
        serial: String,
        label: String,
        blePeripheralId: String? = null,
        attestationPubkeyHex: String? = null,
    ): RegisteredDevice {
        val existing = find(serial)
        if (existing != null && existing.kind == kind) {
            val newPeripheral = blePeripheralId?.takeIf { it != existing.blePeripheralId }
            val newAttestation = attestationPubkeyHex?.takeIf { it != existing.attestationPubkeyHex }
            if (newPeripheral != null || newAttestation != null) {
                val updated = existing.copy(
                    blePeripheralId = newPeripheral ?: existing.blePeripheralId,
                    attestationPubkeyHex = newAttestation ?: existing.attestationPubkeyHex,
                )
                replace(updated)
                return updated
            }
            return existing
        }
        val record = RegisteredDevice(
            // Deterministic id (ADR-0033) so a later remove + re-add of the SAME
            // physical device reproduces the SAME id and keeps every wallet link
            // intact, instead of a random id that orphaned them.
            id = DeviceIdentity.deterministicId(kind, serial),
            kind = kind,
            serial = serial,
            label = label,
            blePeripheralId = blePeripheralId,
            attestationPubkeyHex = attestationPubkeyHex,
        )
        devices = devices + record
        persist()
        return record
    }

    /** Upgrade a device's persisted BLE peripheral id in place after a
     *  successful connect, without going through full register(...). */
    fun setBlePeripheralId(deviceId: UUID, blePeripheralId: String) {
        val existing = find(deviceId) ?: return
        if (existing.blePeripheralId == blePeripheralId) return
        replace(existing.copy(blePeripheralId = blePeripheralId))
    }

    /** Re-bind a device's persisted serial to the value it reports on THIS
     *  platform. The Ledger "serial" is the BLE transport id, which is
     *  platform-specific (an iOS CoreBluetooth peripheral UUID vs an Android
     *  BLE MAC), so a device record carried across platforms by an encrypted
     *  backup can never serial-match the live device and the connect guard
     *  would reject it forever. When the caller has confirmed this is the SOLE
     *  registered device of its kind (so there is no ambiguity about which
     *  physical device answered), re-point the stored serial to the live one
     *  so future connects match directly. The device id (UUID) is unchanged,
     *  so every wallet linked by deviceId stays linked. */
    fun rebindSerial(deviceId: UUID, serial: String) {
        val existing = find(deviceId) ?: return
        if (existing.serial == serial) return
        replace(existing.copy(serial = serial))
    }

    /** Record / update the device's attestation pubkey (hex) after a pair. */
    fun setAttestationPubkeyHex(deviceId: UUID, attestationPubkeyHex: String) {
        val existing = find(deviceId) ?: return
        if (existing.attestationPubkeyHex == attestationPubkeyHex) return
        replace(existing.copy(attestationPubkeyHex = attestationPubkeyHex))
    }

    fun remove(id: UUID) {
        val before = devices.size
        devices = devices.filterNot { it.id == id }
        if (devices.size != before) persist()
    }

    fun rename(id: UUID, label: String) {
        val existing = find(id) ?: return
        replace(existing.copy(label = label))
    }

    // -- promotion records (data only; see seam note at top) --

    fun setIdentityPromotion(deviceId: UUID, promotion: RegisteredDevice.IdentityPromotion?) {
        val existing = find(deviceId) ?: return
        replace(existing.copy(promotions = existing.promotions.copy(identity = promotion)))
    }

    fun addBitcoinWallet(deviceId: UUID, walletId: UUID) = addWallet(deviceId, walletId, Capability.BITCOIN)
    fun removeBitcoinWallet(deviceId: UUID, walletId: UUID) = removeWallet(deviceId, walletId, Capability.BITCOIN)
    fun addEthereumWallet(deviceId: UUID, walletId: UUID) = addWallet(deviceId, walletId, Capability.ETHEREUM)
    fun removeEthereumWallet(deviceId: UUID, walletId: UUID) = removeWallet(deviceId, walletId, Capability.ETHEREUM)
    fun addSolanaWallet(deviceId: UUID, walletId: UUID) = addWallet(deviceId, walletId, Capability.SOLANA)
    fun removeSolanaWallet(deviceId: UUID, walletId: UUID) = removeWallet(deviceId, walletId, Capability.SOLANA)
    fun addTronWallet(deviceId: UUID, walletId: UUID) = addWallet(deviceId, walletId, Capability.TRON)
    fun removeTronWallet(deviceId: UUID, walletId: UUID) = removeWallet(deviceId, walletId, Capability.TRON)

    private fun addWallet(deviceId: UUID, walletId: UUID, chain: Capability) {
        val existing = find(deviceId) ?: return
        val p = existing.promotions
        val updated = when (chain) {
            Capability.BITCOIN -> if (walletId in p.bitcoinWalletIds) return else p.copy(bitcoinWalletIds = p.bitcoinWalletIds + walletId)
            Capability.ETHEREUM -> if (walletId in p.ethereumWalletIds) return else p.copy(ethereumWalletIds = p.ethereumWalletIds + walletId)
            Capability.SOLANA -> if (walletId in p.solanaWalletIds) return else p.copy(solanaWalletIds = p.solanaWalletIds + walletId)
            Capability.TRON -> if (walletId in p.tronWalletIds) return else p.copy(tronWalletIds = p.tronWalletIds + walletId)
            Capability.IDENTITY -> return
        }
        replace(existing.copy(promotions = updated))
    }

    private fun removeWallet(deviceId: UUID, walletId: UUID, chain: Capability) {
        val existing = find(deviceId) ?: return
        val p = existing.promotions
        val updated = when (chain) {
            Capability.BITCOIN -> p.copy(bitcoinWalletIds = p.bitcoinWalletIds - walletId)
            Capability.ETHEREUM -> p.copy(ethereumWalletIds = p.ethereumWalletIds - walletId)
            Capability.SOLANA -> p.copy(solanaWalletIds = p.solanaWalletIds - walletId)
            Capability.TRON -> p.copy(tronWalletIds = p.tronWalletIds - walletId)
            Capability.IDENTITY -> return
        }
        replace(existing.copy(promotions = updated))
    }

    /** Drop a wallet id from every device's promotion list. Cleanup pass when
     *  a wallet is removed from its store so the chain badges on Settings >
     *  Devices reflect what's currently linked. */
    fun scrubWalletId(walletId: UUID) {
        var dirty = false
        devices = devices.map { d ->
            val p = d.promotions
            val np = p.copy(
                bitcoinWalletIds = p.bitcoinWalletIds - walletId,
                ethereumWalletIds = p.ethereumWalletIds - walletId,
                solanaWalletIds = p.solanaWalletIds - walletId,
                tronWalletIds = p.tronWalletIds - walletId,
            )
            if (np != p) { dirty = true; d.copy(promotions = np) } else d
        }
        if (dirty) persist()
    }

    /** Backup-restore-only: replace the device list wholesale, preserving the
     *  original UUIDs + promotions so wallets that reference a deviceId still
     *  resolve after the restore. The user-facing register(...) path always
     *  mints a fresh UUID, which would orphan the wallet to device linkage. */
    fun replaceAll(replacement: List<RegisteredDevice>) {
        devices = replacement
        persist()
    }

    // -- queries used by per-network settings views --

    fun devicesSupporting(capability: Capability): List<RegisteredDevice> =
        devices.filter { capability in it.kind.capabilities }

    // -- internals --

    private fun replace(updated: RegisteredDevice) {
        devices = devices.map { if (it.id == updated.id) updated else it }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        devices.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(STORE_KEY, arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(STORE_KEY, null) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val list = ArrayList<RegisteredDevice>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { RegisteredDevice.fromJson(it)?.let(list::add) }
        }
        devices = list
    }

    companion object {
        /** Matches the iOS UserDefaults key verbatim. */
        private const val STORE_KEY = "devices.registered.v1"

        /** SharedPreferences file for the Context convenience constructor. */
        private const val PREFS_NAME = "maknoon.devices.v1"
    }
}
