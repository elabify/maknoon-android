package com.elabify.app.maknoon.crypto

import java.util.Locale

/**
 * Passphrase character-set policy. Mirror of iOS `PassphraseCharset.swift`;
 * per the project rule, iOS is the reference and Android follows it.
 *
 * ## Why this exists
 *
 * BIP-39 specifies NFKD normalization of the mnemonic and passphrase before
 * PBKDF2. Maknoon normalizes NFKC on both platforms (see `Bip39.kt`). The two
 * are IDENTICAL for ASCII, so the English wordlist plus an ASCII passphrase
 * derives exactly the seed every other BIP-39 wallet derives.
 *
 * They diverge for non-ASCII. A passphrase containing "é" has both a
 * precomposed (U+00E9) and a decomposed (U+0065 U+0301) form that hash to
 * different bytes under NFKC than NFKD, so the same 24 words and the same typed
 * passphrase would derive a DIFFERENT seed here than in a spec-conformant
 * wallet. Nothing is lost, but the funds become reachable only from Maknoon, and
 * the user finds out by restoring elsewhere and seeing an empty wallet.
 *
 * ## Create blocks, restore only warns
 *
 * That asymmetry is the point. Someone who already set a non-ASCII passphrase on
 * an older build must still be able to reach their own funds; refusing them at
 * restore would be exactly the irreversible loss this policy prevents.
 *
 * ## Scope of "ASCII"
 *
 * Printable ASCII, U+0020..U+007E: every letter, digit and special character,
 * including the space, in any position and any repetition. Control characters
 * are rejected too: invisible in a password field, and impossible to retype
 * reliably from a paper backup.
 */
object PassphraseCharset {

    private const val LOWER = 0x20  // space
    private const val UPPER = 0x7E  // tilde

    fun isAllowed(codePoint: Int): Boolean = codePoint in LOWER..UPPER

    /** Offending code points, de-duplicated, in first-seen order. */
    fun offendingCodePoints(passphrase: String): List<Int> {
        val seen = LinkedHashSet<Int>()
        var i = 0
        while (i < passphrase.length) {
            val cp = passphrase.codePointAt(i)
            if (!isAllowed(cp)) seen.add(cp)
            i += Character.charCount(cp)
        }
        return seen.toList()
    }

    /** An empty passphrase is valid BIP-39; strength is enforced separately. */
    fun isAcceptable(passphrase: String): Boolean =
        offendingCodePoints(passphrase).isEmpty()

    /**
     * Render offenders so they are visible in a message. A raw NBSP or
     * zero-width space would otherwise print as nothing, leaving the user
     * staring at an error that names an invisible problem.
     */
    fun describe(codePoints: List<Int>): String = codePoints.joinToString(", ") { cp ->
        val invisible = cp < 0x20 || cp == 0x7F ||
            Character.isSpaceChar(cp) || Character.isISOControl(cp) ||
            Character.getType(cp) == Character.FORMAT.toInt()
        if (invisible) String.format(Locale.US, "U+%04X", cp)
        else "\"" + String(Character.toChars(cp)) + "\""
    }
}
