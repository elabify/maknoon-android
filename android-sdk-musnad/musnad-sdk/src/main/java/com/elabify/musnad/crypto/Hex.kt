package com.elabify.musnad.crypto

private val HEX = "0123456789abcdef".toCharArray()

/** Lowercase hex, no `0x` prefix. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

/** Decode hex, tolerating a leading `0x`/`0X`. */
fun hexToBytes(input: String): ByteArray {
    val hex = if (input.startsWith("0x") || input.startsWith("0X")) input.substring(2) else input
    require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex char at ${i * 2}" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
