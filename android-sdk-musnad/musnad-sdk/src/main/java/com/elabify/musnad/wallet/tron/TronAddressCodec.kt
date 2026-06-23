// Tron base58check address helpers. A Tron address decodes to exactly
// 21 bytes: [0x41, 20-byte keccak-derived hash]. The on-the-wire base58
// form is that 21-byte payload plus a 4-byte double-SHA256 checksum,
// encoded with the Bitcoin base58 alphabet.
//
// We reuse WalletCore's Base58.decode (checked) for the decode path so
// the checksum is validated by the same engine that derives the
// address, and fall back to WalletCore's CoinType.TRON.validate for the
// AnyAddress-equivalent validity check the iOS build does via
// `WalletCore.AnyAddress(string:coin:.tron)`.

package com.elabify.musnad.wallet.tron

import wallet.core.jni.Base58
import wallet.core.jni.CoinType

object TronAddressCodec {

    @Volatile private var nativeLoaded = false

    private fun ensureNative() {
        if (!nativeLoaded) {
            System.loadLibrary("TrustWalletCore")
            nativeLoaded = true
        }
    }

    /** Validate a Tron base58check address. Mirror of iOS
     *  `TronDescriptors.parseAddress` / `AnyAddress(string:coin:.tron)`.
     *  Returns the same string on success, null on failure. */
    fun parseAddress(s: String): String? {
        ensureNative()
        return if (CoinType.TRON.validate(s)) s else null
    }

    fun isValid(s: String): Boolean = parseAddress(s) != null

    /** base58check decode: returns the 21-byte payload [0x41, hash20]
     *  with the checksum stripped + verified, or null on a bad
     *  checksum / malformed input. */
    fun base58CheckDecode(s: String): ByteArray? {
        ensureNative()
        return try {
            Base58.decode(s)
        } catch (e: Throwable) {
            null
        }
    }
}
