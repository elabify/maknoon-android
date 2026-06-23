// PBKDF2-HMAC-SHA256, hand-rolled over javax.crypto.Mac so we control the
// exact password/salt bytes (the iOS backup uses NFKC-normalized UTF-8 of the
// passphrase; SunJCE's PBEKeySpec char[] encoding is not guaranteed to match).
// Used for the encrypted-backup key (600k iterations, 32-byte output).

package com.elabify.musnad.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Pbkdf2 {
    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength // 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        var offset = 0
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (c in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, offset, hLen)
            offset += hLen
        }
        return out.copyOfRange(0, dkLen)
    }
}
