package com.elabify.app.maknoon.ui.wallet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drives the shared address-network-kat.json (byte-identical with the inline
 * copy in iOS AddressNetworkGuardTests.swift) so both platforms classify
 * recipient addresses identically, plus the cross-network mismatch rule.
 */
class AddressNetworkGuardTest {
    private data class Case(val address: String, val family: String?)

    private fun cases(): List<Case> {
        val text = javaClass.getResourceAsStream("/address-network-kat.json")!!
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("cases")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Case(o.getString("address"), if (o.isNull("family")) null else o.getString("family"))
        }
    }

    @Test
    fun detectMatchesKat() {
        for (c in cases()) {
            assertEquals(
                "address=${c.address}",
                c.family,
                AddressNetworkGuard.detect(c.address)?.name?.lowercase(),
            )
        }
    }

    @Test
    fun crossNetworkMismatchRule() {
        assertEquals(
            AddressFamily.BITCOIN,
            AddressNetworkGuard.crossNetworkMismatch(
                "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq", AddressFamily.ETHEREUM,
            ),
        )
        assertNull(
            AddressNetworkGuard.crossNetworkMismatch(
                "0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f", AddressFamily.ETHEREUM,
            ),
        )
        // Solana screen (current = null): any recognised family is a mismatch.
        assertEquals(
            AddressFamily.TRON,
            AddressNetworkGuard.crossNetworkMismatch("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", null),
        )
        assertNull(
            AddressNetworkGuard.crossNetworkMismatch("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", null),
        )
    }
}
