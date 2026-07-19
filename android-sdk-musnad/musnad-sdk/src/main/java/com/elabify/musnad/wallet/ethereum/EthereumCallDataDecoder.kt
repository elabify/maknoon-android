// Best-effort decode of EVM calldata for the mini-app approval sheet, so a user
// sees "Approve token spend / Amount 100000000" instead of raw hex before they
// sign. Only the selectors a retail swap flow needs are decoded (ERC-20
// approve / transfer); anything else returns null and the sheet shows the raw
// calldata under a clearly-labeled "advanced" view. The wallet never
// blind-signs silently: unknown calldata is still surfaced verbatim.
//
// Kotlin mirror of iOS EthereumCallDataDecoder.swift.

package com.elabify.musnad.wallet.ethereum

object EthereumCallDataDecoder {
    /** One-line summary plus ordered labeled fields for the sheet. */
    data class Decoded(val summary: String, val fields: List<Pair<String, String>>)

    /** Decode [data] sent to [to]. Returns null for unrecognized selectors. */
    fun decode(to: String, data: ByteArray): Decoded? {
        if (data.size < 4) return null
        val selector = data.copyOfRange(0, 4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val args = data.copyOfRange(4, data.size)
        return when (selector) {
            "095ea7b3" -> addressAndUint(args)?.let { (spender, amount) ->
                Decoded("Approve token spend", listOf("Token" to to, "Spender" to spender, "Amount" to amount))
            }
            "a9059cbb" -> addressAndUint(args)?.let { (recipient, amount) ->
                Decoded("Token transfer", listOf("Token" to to, "To" to recipient, "Amount" to amount))
            }
            else -> null
        }
    }

    /** (address in first word, uint256 in second word as a decimal string). */
    private fun addressAndUint(args: ByteArray): Pair<String, String>? {
        if (args.size < 64) return null
        val address = "0x" + args.copyOfRange(12, 32).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val amount = decimalString(args.copyOfRange(32, 64))
        return address to amount
    }

    /** 32-byte big-endian unsigned integer -> base-10 string (no BigInteger dep). */
    private fun decimalString(bigEndian: ByteArray): String {
        val digits = ArrayList<Int>()
        digits.add(0)
        for (b in bigEndian) {
            var carry = b.toInt() and 0xff
            for (i in digits.indices) {
                val v = digits[i] * 256 + carry
                digits[i] = v % 10
                carry = v / 10
            }
            while (carry > 0) {
                digits.add(carry % 10)
                carry /= 10
            }
        }
        return digits.asReversed().joinToString("")
    }
}
