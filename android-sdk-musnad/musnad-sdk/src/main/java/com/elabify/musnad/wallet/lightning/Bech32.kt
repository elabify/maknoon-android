// Minimal bech32 codec, ported 1:1 from the iOS Bech32.swift -- just what
// LNURL decoding needs. LNURLs are bech32-encoded with HRP "lnurl" and a
// checksum. The decoded data bytes are an ASCII URL (after 5->8 bit
// regrouping).
//
// Spec reference: BIP-173 (bech32) + LUD-01 (LNURL). We support arbitrarily
// long codes (LNURL doesn't enforce a limit; the 90-char BIP-173 limit is
// irrelevant here).

package com.elabify.musnad.wallet.lightning

object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = longArrayOf(
        0x3b6a57b2L, 0x26508e6dL, 0x1ea119faL, 0x3d4233ddL, 0x2a1462b3L,
    )

    data class Decoded(val hrp: String, val data: IntArray)

    /** Decode a bech32 string into (hrp, 5-bit data payload), or null if the
     *  alphabet/checksum/casing checks fail. */
    fun decode(raw: String): Decoded? {
        val lower = raw.lowercase()
        val upper = raw.uppercase()
        if (raw != lower && raw != upper) return null
        val separator = lower.lastIndexOf('1')
        if (separator < 1) return null
        val hrp = lower.substring(0, separator)
        val dataPart = lower.substring(separator + 1)
        if (dataPart.length < 6) return null

        val values = IntArray(dataPart.length)
        for (i in dataPart.indices) {
            val idx = CHARSET.indexOf(dataPart[i])
            if (idx < 0) return null
            values[i] = idx
        }
        if (!verifyChecksum(hrp, values)) return null
        // Drop the 6-character checksum suffix.
        val payload = values.copyOfRange(0, values.size - 6)
        return Decoded(hrp, payload)
    }

    /** 5-bit -> 8-bit regroup. Used after [decode] to recover the underlying
     *  byte string from a bech32 payload. Returns null on invalid padding. */
    fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray? {
        var acc = 0
        var bits = 0
        val out = ArrayList<Int>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            if (value ushr fromBits != 0) return null
            acc = (acc shl fromBits) or value
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add((acc ushr bits) and maxv)
            }
        }
        if (pad) {
            if (bits > 0) out.add((acc shl (toBits - bits)) and maxv)
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return out.toIntArray()
    }

    /** Regroup a bech32 payload (5-bit) to bytes; convenience over [convertBits]. */
    fun toBytes(data: IntArray, fromBits: Int = 5, toBits: Int = 8, pad: Boolean = false): ByteArray? {
        val regrouped = convertBits(data, fromBits, toBits, pad) ?: return null
        val out = ByteArray(regrouped.size)
        for (i in regrouped.indices) out[i] = (regrouped[i] and 0xff).toByte()
        return out
    }

    private fun polymod(values: IntArray): Long {
        var chk = 1L
        for (value in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffffL) shl 5) xor value.toLong()
            for (i in 0 until 5) {
                if ((top ushr i) and 1L != 0L) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): IntArray {
        val high = IntArray(hrp.length)
        val low = IntArray(hrp.length)
        for (i in hrp.indices) {
            val c = hrp[i].code
            high[i] = c ushr 5
            low[i] = c and 0x1f
        }
        return high + intArrayOf(0) + low
    }

    private fun verifyChecksum(hrp: String, data: IntArray): Boolean =
        polymod(hrpExpand(hrp) + data) == 1L
}
