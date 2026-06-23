// Convert a BIP32 / BIP49 / BIP84 extended public key string
// (xpub / ypub / zpub / Ypub / Zpub on mainnet, tpub / upub / vpub /
// Upub / Vpub on testnet/signet) into the canonical xpub-or-tpub shape
// BDK's descriptor parser accepts. Ported 1:1 from iOS
// ExtendedKeyNormalize.swift.
//
// The keys are byte-identical aside from a 4-byte version prefix; the
// slip-132 alternate prefixes are a hint about the intended derivation
// path and are not load-bearing in the descriptor itself (the
// descriptor's fragment carries it explicitly). We base58check-decode,
// swap the 4-byte version, re-encode.
//
// SeedSigner's BlueWallet export emits a zpub for BIP84 native segwit on
// mainnet (or vpub on testnet); BDK rejects those with
// "DescriptorKeyParseError: Error while parsing xkey." until normalized.

package com.elabify.musnad.wallet.bitcoin

import java.math.BigInteger
import java.security.MessageDigest

object ExtendedKeyNormalize {

    /** Mainnet BIP32 legacy version bytes (xpub). */
    private val mainnetXpub = byteArrayOf(0x04, 0x88.toByte(), 0xB2.toByte(), 0x1E)
    /** Testnet/signet/regtest BIP32 legacy version bytes (tpub). */
    private val testnetTpub = byteArrayOf(0x04, 0x35, 0x87.toByte(), 0xCF.toByte())

    /** Mainnet alternates to normalize TO xpub. */
    private val mainnetAlternates = listOf(
        byteArrayOf(0x04, 0x9D.toByte(), 0x7C, 0xB2.toByte()), // ypub  (BIP49)
        byteArrayOf(0x04, 0xB2.toByte(), 0x47, 0x46),          // zpub  (BIP84)
        byteArrayOf(0x02, 0x95.toByte(), 0xB4.toByte(), 0x3F), // Ypub  (BIP49 multisig)
        byteArrayOf(0x02, 0xAA.toByte(), 0x7E, 0xD3.toByte()), // Zpub  (BIP84 multisig)
    )

    /** Testnet alternates to normalize TO tpub. */
    private val testnetAlternates = listOf(
        byteArrayOf(0x04, 0x4A, 0x52, 0x62),                   // upub  (BIP49)
        byteArrayOf(0x04, 0x5F, 0x1C, 0xF6.toByte()),          // vpub  (BIP84)
        byteArrayOf(0x02, 0x42, 0x89.toByte(), 0xEF.toByte()), // Upub  (BIP49 multisig)
        byteArrayOf(0x02, 0x57, 0x54, 0x83.toByte()),          // Vpub  (BIP84 multisig)
    )

    /** Return `extKey` rewritten to xpub/tpub if it currently uses a
     *  slip-132 alternate prefix. xpub/tpub inputs pass through
     *  unchanged. Returns the input unchanged on any decode error so the
     *  caller hands BDK the original string and BDK produces a meaningful
     *  error message. */
    fun toXpubLegacy(extKey: String): String {
        val trimmed = extKey.trim()
        val raw = Base58Check.decode(trimmed) ?: return extKey
        if (raw.size < 4) return extKey
        val prefix = raw.copyOfRange(0, 4)
        val body = raw.copyOfRange(4, raw.size)

        val canonical: ByteArray = when {
            prefix.contentEquals(mainnetXpub) || prefix.contentEquals(testnetTpub) -> return extKey
            mainnetAlternates.any { it.contentEquals(prefix) } -> mainnetXpub
            testnetAlternates.any { it.contentEquals(prefix) } -> testnetTpub
            else -> return extKey // unknown prefix; leave untouched
        }
        return Base58Check.encode(canonical + body)
    }
}

/** Minimal Base58Check codec: just enough for the version-byte swap
 *  above. Standard Bitcoin Base58 alphabet + SHA256d[0..4] checksum.
 *  Ported 1:1 from iOS ExtendedKeyNormalize.swift's `Base58Check`. */
object Base58Check {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val indexOf: Map<Char, Int> = ALPHABET.withIndex().associate { (i, c) -> c to i }

    /** Decode a Base58Check string into the raw payload (checksum
     *  stripped). Returns null if the checksum doesn't verify or the
     *  input isn't valid Base58. */
    fun decode(s: String): ByteArray? {
        if (s.isEmpty()) return null

        var zeros = 0
        for (ch in s) if (ch == '1') zeros++ else break

        // Big-int accumulator: process each char as a base-58 digit.
        val num = ArrayList<Int>()
        for (ch in s) {
            val digit = indexOf[ch] ?: return null
            var carry = digit
            for (i in num.indices.reversed()) {
                val v = num[i] * 58 + carry
                num[i] = v and 0xFF
                carry = v shr 8
            }
            while (carry > 0) {
                num.add(0, carry and 0xFF)
                carry = carry shr 8
            }
        }
        val bytes = ByteArray(zeros) + num.map { it.toByte() }.toByteArray()
        if (bytes.size < 4) return null
        val payload = bytes.copyOfRange(0, bytes.size - 4)
        val checksum = bytes.copyOfRange(bytes.size - 4, bytes.size)
        val expected = sha256d(payload).copyOfRange(0, 4)
        return if (expected.contentEquals(checksum)) payload else null
    }

    /** Encode raw payload bytes into a Base58Check string (appending the
     *  standard checksum). */
    fun encode(payload: ByteArray): String {
        val checksum = sha256d(payload).copyOfRange(0, 4)
        val full = payload + checksum

        var zeros = 0
        for (b in full) if (b.toInt() == 0) zeros++ else break

        var num = BigInteger(1, full)
        val base = BigInteger.valueOf(58)
        val sb = StringBuilder()
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            num = q
        }
        repeat(zeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }

    private fun sha256d(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }
}
