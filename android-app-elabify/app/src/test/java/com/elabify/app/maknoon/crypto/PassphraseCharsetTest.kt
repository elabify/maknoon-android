package com.elabify.app.maknoon.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors MaknoonTests/PassphraseCharsetTests.swift. The contract is: every
 * printable ASCII character, including spaces and specials, in any position and
 * any repetition, is acceptable; nothing else is, because NFKC and NFKD agree
 * only on ASCII.
 */
class PassphraseCharsetTest {

    @Test
    fun everyPrintableAsciiCharacterIsAccepted() {
        // Exhaustive rather than sampled: "all ASCII special characters" is the
        // actual contract, so assert all 95 of them.
        for (cp in 0x20..0x7E) {
            assertTrue(
                "U+%04X should be allowed".format(cp),
                PassphraseCharset.isAllowed(cp),
            )
        }
    }

    @Test
    fun spacesAnywhereAndRepeatedSpecialsAreAccepted() {
        listOf(
            " leading space",
            "trailing space ",
            "   ",
            "a b  c   d",
            "!!!!@@@@####",
            "~`!@#\$%^&*()_+-=[]{}|;':\",./<>?\\",
            "correct horse battery staple",
            "x".repeat(512),
        ).forEach {
            assertTrue("should accept ${it.take(24)}", PassphraseCharset.isAcceptable(it))
        }
    }

    @Test
    fun emptyPassphraseIsAcceptable() {
        assertTrue(PassphraseCharset.isAcceptable(""))
    }

    @Test
    fun nonAsciiIsRejected() {
        listOf(
            "café",             // precomposed U+00E9
            "café",       // decomposed: the exact NFKC/NFKD divergence
            "naïve",
            "пароль",
            "密码",
            "كلمة السر",
            "pass🔑word", // emoji, a surrogate pair
            "pass word",   // NBSP, indistinguishable from a space
            "pass​word",   // zero-width space, completely invisible
        ).forEach {
            assertFalse("should reject $it", PassphraseCharset.isAcceptable(it))
        }
    }

    @Test
    fun controlCharactersAreRejected() {
        listOf(0x00, 0x09, 0x0A, 0x0D, 0x1F, 0x7F).forEach { cp ->
            val s = "abc${String(Character.toChars(cp))}def"
            assertFalse("U+%04X should be rejected".format(cp), PassphraseCharset.isAcceptable(s))
        }
    }

    @Test
    fun surrogatePairIsCountedAsOneCodePoint() {
        // Iterating by Char rather than code point would report two offenders
        // for one emoji and could mis-handle the halves.
        assertEquals(1, PassphraseCharset.offendingCodePoints("a🔑b").size)
    }

    @Test
    fun offendersAreDeduplicatedInFirstSeenOrder() {
        val bad = PassphraseCharset.offendingCodePoints("aéiöué")
        assertEquals(listOf(0xE9, 0xF6), bad)
    }

    @Test
    fun invisibleOffendersAreNamedByCodepoint() {
        // "remove ''" helps nobody; NBSP and ZWSP must show as U+XXXX.
        assertEquals("U+00A0", PassphraseCharset.describe(listOf(0xA0)))
        assertEquals("U+200B", PassphraseCharset.describe(listOf(0x200B)))
    }

    @Test
    fun visibleOffendersAreQuotedNotCodepointed() {
        assertEquals("\"é\"", PassphraseCharset.describe(listOf(0xE9)))
    }
}
