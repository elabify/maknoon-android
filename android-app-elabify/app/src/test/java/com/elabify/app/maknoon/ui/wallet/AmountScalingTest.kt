package com.elabify.app.maknoon.ui.wallet

import com.elabify.app.maknoon.ui.wallet.solana.parseSolToLamports
import com.elabify.app.maknoon.ui.wallet.solana.parseTokenToRaw
import com.elabify.app.maknoon.ui.wallet.tron.tronTokenToRaw
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-platform known-answer test for decimal -> base-unit scaling on the
 * Solana and Tron send paths. Drives the shared amount-scaling-kat.json (kept
 * byte-identical with the inline copy in iOS TokenAmountTests.swift) through
 * every scaler so both platforms produce identical base units and neither uses
 * a binary Double (the class of bug that can send the wrong amount).
 */
class AmountScalingTest {
    private data class Case(val amount: String, val decimals: Int, val expected: String?)

    private fun cases(): List<Case> {
        val text = javaClass.getResourceAsStream("/amount-scaling-kat.json")!!
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("cases")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Case(
                o.getString("amount"),
                o.getInt("decimals"),
                if (o.isNull("expected")) null else o.getString("expected"),
            )
        }
    }

    @Test
    fun tronTokenToRawMatchesKat() {
        for (c in cases()) {
            assertEquals(
                "amount=${c.amount} decimals=${c.decimals}",
                c.expected,
                tronTokenToRaw(c.amount, c.decimals),
            )
        }
    }

    @Test
    fun parseTokenToRawMatchesKat() {
        for (c in cases()) {
            assertEquals(
                "amount=${c.amount} decimals=${c.decimals}",
                c.expected,
                parseTokenToRaw(c.amount, c.decimals)?.toString(),
            )
        }
    }

    // parseSolToLamports is the 9-decimal native SOL path; confirm it agrees
    // with the table at 9 decimals.
    @Test
    fun parseSolToLamportsAgreesAtNineDecimals() {
        for (c in cases().filter { it.decimals == 9 }) {
            assertEquals(
                "amount=${c.amount}",
                c.expected,
                parseSolToLamports(c.amount)?.toString(),
            )
        }
    }
}
