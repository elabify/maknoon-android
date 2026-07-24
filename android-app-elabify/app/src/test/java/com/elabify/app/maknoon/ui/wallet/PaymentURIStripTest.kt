package com.elabify.app.maknoon.ui.wallet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives the shared payment-uri-kat.json (byte-identical with the inline copy
 * in iOS PaymentURIStripTests.swift) so both platforms reduce BIP21 / Solana
 * Pay / Tron URIs to the identical recipient, incl. the Solana Pay
 * transaction-request URL edge.
 */
class PaymentURIStripTest {
    private data class Case(val input: String, val scheme: String, val recipient: String)

    private fun cases(): List<Case> {
        val text = javaClass.getResourceAsStream("/payment-uri-kat.json")!!
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("cases")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Case(o.getString("input"), o.getString("scheme"), o.getString("recipient"))
        }
    }

    @Test
    fun stripMatchesKat() {
        for (c in cases()) {
            assertEquals("input=${c.input}", c.recipient, PaymentURIStrip.strip(c.input, c.scheme))
        }
    }
}
