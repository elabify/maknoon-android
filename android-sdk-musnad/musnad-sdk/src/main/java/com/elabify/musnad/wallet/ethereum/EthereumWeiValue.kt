// Wei is a 256-bit unsigned integer. We carry it as a lowercase hex string
// (the form RPC returns) and provide view-friendly conversions to ether and to
// the chain-specific ticker. 1:1 port of EthereumWeiValue.swift.
//
// `bigEndianBytes` is used by the EIP-1559 RLP signing path (EthereumRLP /
// EthereumTxEncoder), which expects unpadded big-endian bytes for the
// chainID / nonce / gas / value fields.

package com.elabify.musnad.wallet.ethereum

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * 256-bit unsigned wei value, carried as a lowercase hex string with no `0x`
 * prefix. A pure-zero value canonicalises to "0".
 */
class EthereumWeiValue private constructor(val hex: String) {

    companion object {
        val ZERO = EthereumWeiValue("0")

        /** Parse from a hex string (with or without `0x`). Throws on bad hex. */
        fun fromHex(input: String): EthereumWeiValue {
            var s = input
            if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2)
            if (s.isEmpty()) s = "0"
            require(s.all { it.isHexDigitChar() }) { "Bad wei hex '$input'" }
            var stripped = s.lowercase()
            while (stripped.length > 1 && stripped.first() == '0') stripped = stripped.substring(1)
            return EthereumWeiValue(stripped)
        }

        /** Construct from an unsigned integer (nonce, chainID, etc.). */
        fun fromUInt64(v: Long): EthereumWeiValue = fromHex(java.lang.Long.toHexString(v))

        /** Construct from a non-negative BigInteger. */
        fun fromBigInteger(v: BigInteger): EthereumWeiValue =
            if (v.signum() <= 0) ZERO else fromHex(v.toString(16))

        /** Decimal-ether (user input) -> wei. Truncates beyond 18 dp. */
        fun fromEther(etherString: String): EthereumWeiValue? = fromUnits(etherString, 18)

        /** Decimal-gwei -> wei. Truncates beyond 9 dp. */
        fun fromGwei(gweiString: String): EthereumWeiValue? = fromUnits(gweiString, 9)

        /** Generic "human -> raw-units" parse with arbitrary decimals. */
        fun fromUnits(str: String, decimals: Int): EthereumWeiValue? {
            val trimmed = str.trim()
            val amt = try { BigDecimal(trimmed) } catch (_: NumberFormatException) { return null }
            if (amt.signum() < 0) return null
            val scaled = amt.movePointRight(decimals).setScale(0, RoundingMode.DOWN)
            return fromBigInteger(scaled.toBigInteger())
        }

        /** Decimal (integer wei) -> wei value. Negative collapses to zero. */
        fun fromDecimal(d: BigDecimal): EthereumWeiValue {
            if (d.signum() <= 0) return ZERO
            return fromBigInteger(d.setScale(0, RoundingMode.DOWN).toBigInteger())
        }

        private fun Char.isHexDigitChar(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }

    /** Integer wei as a BigInteger. */
    val bigInteger: BigInteger get() = if (hex == "0") BigInteger.ZERO else BigInteger(hex, 16)

    /** Integer wei as a BigDecimal (for fee math + display). */
    val decimal: BigDecimal get() = BigDecimal(bigInteger)

    /**
     * Big-endian byte representation with leading zeros stripped, as required
     * by the EIP-1559 RLP encoder. An exact zero is a single 0x00 byte.
     */
    val bigEndianBytes: ByteArray
        get() {
            if (hex == "0") return byteArrayOf(0)
            val raw = bigInteger.toByteArray()
            // BigInteger.toByteArray() prepends a 0x00 sign byte when the high
            // bit is set; strip it. Also strip any other leading zeros.
            var start = 0
            while (start < raw.size - 1 && raw[start].toInt() == 0) start++
            return raw.copyOfRange(start, raw.size)
        }

    operator fun plus(rhs: EthereumWeiValue): EthereumWeiValue =
        fromDecimal(this.decimal.add(rhs.decimal))

    operator fun times(rhs: EthereumWeiValue): EthereumWeiValue =
        fromDecimal(this.decimal.multiply(rhs.decimal))

    operator fun compareTo(rhs: EthereumWeiValue): Int = this.bigInteger.compareTo(rhs.bigInteger)

    /** Raw -> human-Decimal for display. */
    fun units(decimals: Int): BigDecimal =
        decimal.divide(BigDecimal.TEN.pow(decimals))

    /** Convert to ether. */
    val ether: BigDecimal get() = units(18)

    /** Convert to gwei (1 gwei = 10^9 wei). */
    val gwei: BigDecimal get() = units(9)

    /** Human-readable native display, trims trailing zeros, caps decimals. */
    fun display(ticker: String, maxDecimals: Int = 8): String =
        formatGrouped(ether, maxDecimals) + " " + ticker

    /** Human-readable token-unit display. */
    fun displayUnits(ticker: String, decimals: Int, maxDecimals: Int = 6): String =
        formatGrouped(units(decimals), maxDecimals) + " " + ticker

    fun displayGwei(maxDecimals: Int = 3): String = formatGrouped(gwei, maxDecimals, grouped = false)

    private fun formatGrouped(value: BigDecimal, maxDecimals: Int, grouped: Boolean = true): String {
        val rounded = value.setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = rounded.toPlainString()
        if (!grouped) return plain
        val negative = plain.startsWith("-")
        val body = if (negative) plain.substring(1) else plain
        val dot = body.indexOf('.')
        val intPart = if (dot >= 0) body.substring(0, dot) else body
        val fracPart = if (dot >= 0) body.substring(dot) else ""
        val withCommas = StringBuilder()
        val n = intPart.length
        for (i in intPart.indices) {
            if (i > 0 && (n - i) % 3 == 0) withCommas.append(',')
            withCommas.append(intPart[i])
        }
        return (if (negative) "-" else "") + withCommas.toString() + fracPart
    }

    override fun equals(other: Any?): Boolean = other is EthereumWeiValue && other.hex == hex
    override fun hashCode(): Int = hex.hashCode()
    override fun toString(): String = "EthereumWeiValue(0x$hex)"
}
