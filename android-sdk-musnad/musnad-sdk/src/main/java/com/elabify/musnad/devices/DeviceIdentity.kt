// Deterministic device identity (ADR-0033). A registered device's id is
// UUIDv5(namespace, "<kind.rawValue>:<serial>") so re-adding or restoring the
// SAME physical device always reproduces the SAME id. This prevents the
// orphaned-wallet bug: wallets link to their device by a `deviceId: UUID`, so a
// random per-registration id meant removing + re-adding a device (or a restore)
// minted a new id and broke every wallet link under it. A deterministic id makes
// a remove + re-add cycle return the same id, so the links survive.
//
// CROSS-PLATFORM CONTRACT: byte-identical to iOS DeviceIdentity. KAT:
//   trezor:BE12AAAEFA704D6B6A9E4EC6 -> 9d2002ff-fa4c-5d54-ab7a-4405728585f7
//
// CAVEAT: for a Ledger the `serial` is a platform-specific BLE transport id
// (iOS CoreBluetooth peripheral UUID vs Android BLE MAC), so the deterministic
// id is stable only WITHIN a platform; across platforms the serial (and thus the
// id) differs and the connect-time serial rebind + relink-by-key paths recover
// the link. A Trezor's serial is its firmware device_id (stable cross-platform),
// so its deterministic id is identical on both platforms.

package com.elabify.musnad.devices

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

object DeviceIdentity {
    /** RFC 4122 namespace UUID for Maknoon device ids. MUST match iOS. */
    val NAMESPACE: UUID = UUID.fromString("f9b6a1c2-3d4e-5f60-8a1b-2c3d4e5f6071")

    /** The deterministic id for a (kind, serial) pair. */
    fun deterministicId(kind: DeviceKind, serial: String): UUID =
        uuidV5(NAMESPACE, "${kind.rawValue}:$serial")

    /** RFC 4122 name-based UUID, VERSION 5 (SHA-1). Java's
     *  UUID.nameUUIDFromBytes is version 3 (MD5), so v5 is implemented by hand
     *  to match Foundation / Python uuid5 byte-for-byte. */
    fun uuidV5(namespace: UUID, name: String): UUID {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(uuidToBytes(namespace))
        md.update(name.toByteArray(Charsets.UTF_8))
        val hash = md.digest() // 20 bytes; take the first 16
        val b = hash.copyOf(16)
        b[6] = ((b[6].toInt() and 0x0F) or 0x50).toByte() // version 5
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant
        return bytesToUuid(b)
    }

    private fun uuidToBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    private fun bytesToUuid(b: ByteArray): UUID {
        val bb = ByteBuffer.wrap(b)
        val hi = bb.long
        val lo = bb.long
        return UUID(hi, lo)
    }
}
