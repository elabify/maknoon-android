package com.elabify.musnad.devices

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cross-platform parity for the deterministic device id (ADR-0033). The
 *  vectors here MUST match iOS DeviceIdentity and Python `uuid.uuid5`. */
class DeviceIdentityTest {

    @Test
    fun `trezor KAT matches the cross-platform vector`() {
        // Trezor serial = firmware device_id (stable across platforms).
        assertEquals(
            UUID.fromString("9d2002ff-fa4c-5d54-ab7a-4405728585f7"),
            DeviceIdentity.deterministicId(DeviceKind.TREZOR, "BE12AAAEFA704D6B6A9E4EC6"),
        )
    }

    @Test
    fun `ledger KAT matches the cross-platform vector`() {
        // Ledger serial here is an iOS CoreBluetooth peripheral UUID; the id is
        // deterministic for THIS serial. (A different platform's MAC yields a
        // different id, which the serial-rebind path heals.)
        assertEquals(
            UUID.fromString("cd3fa27e-b469-5dfe-8ae2-0ac0e93b7904"),
            DeviceIdentity.deterministicId(DeviceKind.LEDGER, "713DE51C-C958-9CE0-3A94-5A3761F104FA"),
        )
    }

    @Test
    fun `raw uuidV5 matches the RFC 4122 example (DNS namespace, www example org)`() {
        // RFC 4122 / Python: uuid5(NAMESPACE_DNS, "www.example.org").
        val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        assertEquals(
            UUID.fromString("74738ff5-5367-5958-9aee-98fffdcd1876"),
            DeviceIdentity.uuidV5(dns, "www.example.org"),
        )
    }

    @Test
    fun `deterministic id is stable across calls`() {
        val a = DeviceIdentity.deterministicId(DeviceKind.TREZOR, "ABC123")
        val b = DeviceIdentity.deterministicId(DeviceKind.TREZOR, "ABC123")
        assertEquals(a, b)
    }
}
