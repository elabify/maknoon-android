// Cross-vendor model for "a hardware security device the user has
// registered with Maknoon." Ported 1:1 from iOS Devices/RegisteredDevice.swift.
//
// Registration is a lightweight handshake: connect to the device just long
// enough to read a stable identifier ("serial") so we can recognise it next
// time. We do NOT enroll the device into anything at this stage.
//
// Promotion of a registered device into either:
//   - the Identity Sandwich (the device wraps the BIP39 entropy, so it
//     becomes a second factor for unlock), or
//   - a network (Bitcoin / Ethereum / ... wallet creation)
// is a separate, explicit action the user performs from the device-detail
// screen or the per-network settings page.
//
// SEAM (intentionally NOT implemented here): the Identity-Sandwich hardware-
// wrap promote/demote logic lives in another component. This file carries
// only the persisted promotion RECORDS (data: a credential id + version, or
// a list of wallet ids per chain). DeviceRegistry mutates those records;
// the actual wrap/unwrap of seed entropy is wired elsewhere.

package com.elabify.musnad.devices

import com.elabify.musnad.hardware.HardwareWalletKind
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Vendor discriminator for a registered device. Broader than
 *  HardwareWalletKind: it also covers identity-only (YubiKey) and air-gapped
 *  (SeedSigner) devices that aren't live-transport secp256k1 signers.
 *  `rawValue` is the persisted string. */
enum class DeviceKind(val rawValue: String) {
    YUBIKEY("yubikey"),
    LEDGER("ledger"),
    TREZOR("trezor"),

    /** Air-gapped Bitcoin-only signer. No live transport; all data crosses
     *  the air gap as QR codes via the phone's camera. Registration captures
     *  the xpub + master fingerprint as the device's stable serial. */
    SEEDSIGNER("seedsigner");

    val displayName: String
        get() = when (this) {
            YUBIKEY -> "YubiKey"
            LEDGER -> "Ledger"
            TREZOR -> "Trezor"
            SEEDSIGNER -> "SeedSigner"
        }

    /** What this device can be promoted into. YubiKey wraps the Identity
     *  Sandwich; Ledger / Trezor sign on chain-specific networks (and CAN
     *  also wrap identity via FIDO2 "Security Key"); SeedSigner is Bitcoin
     *  only. */
    val capabilities: Set<Capability>
        get() = when (this) {
            YUBIKEY -> setOf(Capability.IDENTITY)
            LEDGER -> setOf(
                Capability.IDENTITY, Capability.BITCOIN, Capability.ETHEREUM,
                Capability.SOLANA, Capability.TRON,
            )
            TREZOR -> setOf(
                Capability.IDENTITY, Capability.BITCOIN, Capability.ETHEREUM,
                Capability.SOLANA, Capability.TRON,
            )
            SEEDSIGNER -> setOf(Capability.BITCOIN)
        }

    /** How this device signs a Bitcoin transaction. Drives whether the Send
     *  button runs a live BLE sign or the air-gapped PSBT QR round-trip. */
    val bitcoinSigningMechanism: BitcoinSigningMechanism
        get() = when (this) {
            LEDGER, TREZOR -> BitcoinSigningMechanism.HARDWARE_BLE
            SEEDSIGNER -> BitcoinSigningMechanism.AIRGAPPED_PSBT
            YUBIKEY -> BitcoinSigningMechanism.AIRGAPPED_PSBT // never used; can't bitcoin
        }

    /** The live-transport secp256k1 signer kind for this device, if any.
     *  Ledger / Trezor map onto HardwareWalletKind; identity-only and
     *  air-gapped kinds return null. Lets the device layer hand a registered
     *  device straight to HardwareWalletFactory.make(...). */
    val hardwareWalletKind: HardwareWalletKind?
        get() = when (this) {
            LEDGER -> HardwareWalletKind.LEDGER
            TREZOR -> HardwareWalletKind.TREZOR
            YUBIKEY, SEEDSIGNER -> null
        }

    companion object {
        fun fromRawValue(raw: String): DeviceKind? = entries.firstOrNull { it.rawValue == raw }

        /** Device kinds the user can register today, sorted alphabetically by
         *  display name for the registration picker. */
        val registrableCases: List<DeviceKind>
            get() = entries.sortedBy { it.displayName.lowercase() }

        /** Registrable kinds that can hold value (sign for at least one
         *  network), excluding identity-only devices like YubiKey. Used by
         *  the onboarding "first wallet" hardware picker. */
        val walletCapableRegistrableCases: List<DeviceKind>
            get() {
                val walletCaps = setOf(
                    Capability.BITCOIN, Capability.ETHEREUM,
                    Capability.SOLANA, Capability.TRON,
                )
                return registrableCases.filter { it.capabilities.any { c -> c in walletCaps } }
            }
    }
}

/** What a device can be promoted into. (iOS models this as an OptionSet;
 *  Kotlin uses a Set of these flags.) */
enum class Capability { IDENTITY, BITCOIN, ETHEREUM, SOLANA, TRON }

/** What kind of signing flow to run for a given Bitcoin wallet's
 *  transactions. Lives at the device level so a wallet's signing UX is
 *  determined by the device it was created from. */
enum class BitcoinSigningMechanism {
    /** On-phone software signing. Software wallets only, never hardware. */
    SOFTWARE,

    /** Hardware wallet over a live transport (BLE for Ledger / Trezor).
     *  Connect, send the PSBT, get the signed PSBT back, finalise and
     *  broadcast. No QR round-trip. */
    HARDWARE_BLE,

    /** Air-gapped hardware wallet. Build the unsigned PSBT, the user moves
     *  it to the device via QR (or microSD on SeedSigner), the device signs
     *  offline, the signed PSBT comes back via QR. */
    AIRGAPPED_PSBT,
}

/** A device the user has registered with Maknoon. Identified by `serial`, a
 *  stable per-device string sourced from the vendor (YubiKey serial number,
 *  Ledger BLE peripheral identifier, Trezor device_id from Features). */
data class RegisteredDevice(
    val id: UUID = UUID.randomUUID(),
    val kind: DeviceKind,

    /** Stable identifier reported by the vendor. Used to confirm "this is
     *  the same physical device" on every reconnect. Display in the UI is
     *  truncated; the full string is shown in the device detail view. */
    val serial: String,
    val label: String,

    /** Epoch milliseconds at registration. (iOS persists a Date; we store
     *  epoch ms so the JSON is portable and ordering is trivial.) */
    val registeredAtEpochMs: Long = System.currentTimeMillis(),

    /** Android-stable BLE peripheral identifier (MAC / address) captured at
     *  pair time. Used as a hard filter on subsequent connect attempts so
     *  the app cannot accidentally talk to a different physical Ledger /
     *  Trezor the user also paired with this phone. Null only for devices
     *  registered before this field existed, or transports without one; the
     *  next successful connect upgrades them in place. */
    val blePeripheralId: String? = null,

    /** Hex-encoded secp256k1 attestation pubkey the device returned at pair
     *  time (HardwareWallet.pair()). Persisted so the device row can show
     *  the bound key without reconnecting, and so the Identity-Sandwich wrap
     *  seam (implemented elsewhere) can recover it. Null until first pair. */
    val attestationPubkeyHex: String? = null,

    /** Per-capability promotion record. Each entry is a snapshot of what the
     *  user opted in to. Removing an entry is an explicit "remove this device
     *  from Identity / Bitcoin / etc." action; the device stays registered. */
    val promotions: Promotions = Promotions.empty(),
) {

    /** Per-capability promotion snapshots. DATA ONLY: the hardware-wrap
     *  promote/demote logic for the Identity Sandwich is a documented seam
     *  owned by another component. */
    data class Promotions(
        val identity: IdentityPromotion? = null,
        val bitcoinWalletIds: List<UUID> = emptyList(),
        val ethereumWalletIds: List<UUID> = emptyList(),
        val solanaWalletIds: List<UUID> = emptyList(),
        val tronWalletIds: List<UUID> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            identity?.let { put("identity", it.toJson()) }
            put("bitcoinWalletIds", uuidArray(bitcoinWalletIds))
            put("ethereumWalletIds", uuidArray(ethereumWalletIds))
            put("solanaWalletIds", uuidArray(solanaWalletIds))
            put("tronWalletIds", uuidArray(tronWalletIds))
        }

        companion object {
            fun empty() = Promotions()

            // Tolerant decoder so on-disk registry JSON written before the
            // chain fields existed still loads: missing keys decode to [].
            fun fromJson(o: JSONObject): Promotions = Promotions(
                identity = o.optJSONObject("identity")?.let { IdentityPromotion.fromJson(it) },
                bitcoinWalletIds = uuidList(o.optJSONArray("bitcoinWalletIds")),
                ethereumWalletIds = uuidList(o.optJSONArray("ethereumWalletIds")),
                solanaWalletIds = uuidList(o.optJSONArray("solanaWalletIds")),
                tronWalletIds = uuidList(o.optJSONArray("tronWalletIds")),
            )
        }
    }

    /** Identity-Sandwich enrollment record + the per-device second-factor wrap
     *  envelope (ADR-0032). DATA ONLY: the wrap/unwrap crypto lives in
     *  [com.elabify.musnad.identity.SecondFactorWrap]; this record only carries
     *  what that layer reads back at unlock. */
    data class IdentityPromotion(
        /** FIDO2 credential id (hex-encoded) the device returned when we
         *  enrolled it for the hmac-secret wrap. Picks the right credential
         *  at unlock time if the device holds more than one. */
        val credentialIdHex: String,
        val enrolledAtEpochMs: Long,
        /** Wrap-derivation protocol version. 1 = raw-signature (broken: FIDO2
         *  signature counter drifts). 2 = FIDO2 hmac-secret extension
         *  (deterministic). Null on records written before this field
         *  existed; the wrap path treats null as v1 and re-enrolls. */
        val wrapProtocolVersion: Int? = 2,
        /** The 32-byte hmac-secret salt (hex) that produced this device's
         *  wrap key. Also the HKDF salt. Null on records written before the
         *  second-factor wrap existed (credential-only promotions); such a
         *  device must be re-enrolled to gate the entropy. */
        val deviceSaltHex: String? = null,
        /** The CEK sealed under this device's wrap key (framed
         *  nonce||ct||tag, hex). Together with [deviceSaltHex] this is the full
         *  per-device envelope: recompute the secret, derive the wrap key,
         *  decrypt this to the CEK, decrypt sealedEntropy. Null = no wrap yet. */
        val wrappedCekHex: String? = null,
    ) {
        /** True once this device actually wraps the CEK (a complete v2
         *  envelope), not just a legacy credential-only promotion. */
        val hasSecondFactorWrap: Boolean
            get() = wrapProtocolVersion == 2 && !deviceSaltHex.isNullOrEmpty() && !wrappedCekHex.isNullOrEmpty()

        fun toJson(): JSONObject = JSONObject().apply {
            put("credentialIdHex", credentialIdHex)
            put("enrolledAtEpochMs", enrolledAtEpochMs)
            wrapProtocolVersion?.let { put("wrapProtocolVersion", it) }
            deviceSaltHex?.let { put("deviceSaltHex", it) }
            wrappedCekHex?.let { put("wrappedCekHex", it) }
        }

        companion object {
            fun fromJson(o: JSONObject): IdentityPromotion = IdentityPromotion(
                credentialIdHex = o.optString("credentialIdHex"),
                enrolledAtEpochMs = o.optLong("enrolledAtEpochMs"),
                wrapProtocolVersion = if (o.has("wrapProtocolVersion")) o.getInt("wrapProtocolVersion") else null,
                deviceSaltHex = o.optString("deviceSaltHex", "").ifEmpty { null },
                wrappedCekHex = o.optString("wrappedCekHex", "").ifEmpty { null },
            )
        }
    }

    /** Short display of the serial for list rows. */
    val serialDisplay: String
        get() = if (serial.length <= 12) serial else "${serial.take(6)}…${serial.takeLast(4)}"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id.toString())
        put("kind", kind.rawValue)
        put("serial", serial)
        put("label", label)
        put("registeredAtEpochMs", registeredAtEpochMs)
        blePeripheralId?.let { put("blePeripheralId", it) }
        attestationPubkeyHex?.let { put("attestationPubkeyHex", it) }
        put("promotions", promotions.toJson())
    }

    companion object {
        /** Tolerant decoder; returns null if the row is unrecognisable
         *  (unknown kind, missing serial) so a single bad entry doesn't
         *  poison the whole list load. */
        fun fromJson(o: JSONObject): RegisteredDevice? {
            val kind = DeviceKind.fromRawValue(o.optString("kind")) ?: return null
            val serial = o.optString("serial").ifEmpty { return null }
            val id = runCatching { UUID.fromString(o.optString("id")) }.getOrNull() ?: UUID.randomUUID()
            return RegisteredDevice(
                id = id,
                kind = kind,
                serial = serial,
                label = o.optString("label", kind.displayName),
                registeredAtEpochMs = o.optLong("registeredAtEpochMs", System.currentTimeMillis()),
                blePeripheralId = o.optString("blePeripheralId", "").ifEmpty { null },
                attestationPubkeyHex = o.optString("attestationPubkeyHex", "").ifEmpty { null },
                promotions = o.optJSONObject("promotions")?.let { Promotions.fromJson(it) }
                    ?: Promotions.empty(),
            )
        }
    }
}

// -- shared UUID-array JSON helpers --

private fun uuidArray(ids: List<UUID>): JSONArray = JSONArray().apply { ids.forEach { put(it.toString()) } }

private fun uuidList(arr: JSONArray?): List<UUID> {
    if (arr == null) return emptyList()
    val out = ArrayList<UUID>(arr.length())
    for (i in 0 until arr.length()) {
        runCatching { UUID.fromString(arr.optString(i)) }.getOrNull()?.let { out.add(it) }
    }
    return out
}
