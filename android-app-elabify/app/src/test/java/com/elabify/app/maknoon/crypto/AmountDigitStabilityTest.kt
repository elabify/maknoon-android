package com.elabify.app.maknoon.crypto

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant, mirroring MaknoonTests/AmountDigitStabilityTests.swift: a number
 * the user reconciles against a hardware wallet, a block explorer or an invoice
 * renders in Latin digits in every locale Maknoon ships.
 *
 * Android is the more dangerous platform for this. `String.format(fmt, ...)`
 * without an explicit Locale uses `Locale.getDefault()`, and
 * `LocaleSupport.wrap` calls `Locale.setDefault(locale)` when the user picks a
 * language, so the process default really does change under the app's feet.
 */
class AmountDigitStabilityTest {

    private val allLocales = listOf(
        "en", "ar", "zh-Hans", "zh-Hant", "es", "hi", "fil", "pt", "ja", "ru",
        "ur", "fr", "de", "ku", "ckb", "bn", "ml", "ta", "he", "fa", "ko", "it",
        "nl", "tr", "sw", "aa", "am", "om", "so", "ti", "vi",
    )

    private fun nonLatin(s: String): Boolean =
        s.any { it.isDigit() && it !in '0'..'9' }

    /**
     * Documents the real hazard surface on this JVM rather than trusting CLDR
     * tables. If the JDK's data shifts and a new locale joins the list, the
     * pinned formatting already covers it, but we want the failure to say so.
     */
    @Test
    fun whichLocalesWouldBreakWithADefaultLocaleFormat() {
        val offenders = allLocales.filter { tag ->
            nonLatin(String.format(Locale.forLanguageTag(tag), "%,.2f", 12345.67))
        }
        println("non-Latin digit locales on this JVM: $offenders")
        assertTrue(
            "expected at least fa and ckb to be affected, got $offenders",
            offenders.containsAll(listOf("fa", "ckb")),
        )
    }

    /** Pinning to Locale.US must beat every one of them. */
    @Test
    fun pinnedFormattingIsLatinEverywhere() {
        val original = Locale.getDefault()
        try {
            for (tag in allLocales) {
                // Simulate LocaleSupport.wrap mutating the process default.
                Locale.setDefault(Locale.forLanguageTag(tag))
                val amount = String.format(Locale.US, "%.8f", 0.05)
                val grouped = String.format(Locale.US, "%,.2f", 15833.33)
                assertEquals("under $tag", "0.05000000", amount)
                assertEquals("under $tag", "15,833.33", grouped)
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    /**
     * The regression test on the REAL wallet helpers.
     *
     * This was a live defect, not a hypothetical one: `formatBtc` used a bare
     * `String.format`, `LocaleSupport.wrap` calls `Locale.setDefault`, and `ar`
     * already ships. An Arabic Android user was seeing Arabic-Indic digits in a
     * BTC amount while their Ledger showed Latin.
     */
    @Test
    fun walletAmountHelpersStayLatinUnderEveryLocale() {
        val original = Locale.getDefault()
        try {
            for (tag in listOf("ar", "fa", "ckb", "bn", "en")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("formatBtc under $tag", "0.05000000",
                    com.elabify.app.maknoon.ui.wallet.bitcoin.formatBtc(5_000_000L))
                assertEquals("formatSignedBtc under $tag", "+0.05000000",
                    com.elabify.app.maknoon.ui.wallet.bitcoin.formatSignedBtc(5_000_000L))
                assertEquals("formatSats under $tag", "12,345 sats",
                    com.elabify.app.maknoon.ui.wallet.bitcoin.formatSats(12_345L))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    /** Identifiers must never localize either: a code point is not prose. */
    @Test
    fun codePointRenderingIsStableUnderEveryLocale() {
        val original = Locale.getDefault()
        try {
            for (tag in listOf("fa", "ckb", "ar", "en")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("under $tag", "U+00A0", PassphraseCharset.describe(listOf(0xA0)))
            }
        } finally {
            Locale.setDefault(original)
        }
    }
}
