// Solana payment leg for Maknoon Pay (ADR-0031), the Android twin of
// CommerceSolanaPayment.swift / CommerceEVMPayment.kt. Stateless helpers the
// commerce layer uses to (a) parse a decimal amount into base units, (b) read
// the pre-broadcast settlement ref (first signature, base58) from a signed tx,
// and (c) broadcast. The actual SIGN lives in the holder context
// (RealCommerceHolderContext.signSolanaTransfer) because it needs the sandwich;
// this keeps the parse/ref/broadcast logic in one place, like the EVM leg.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.wallet.solana.SolanaPrimitives
import com.elabify.musnad.wallet.solana.SolanaRPCClient

class CommerceSolanaPaymentException(override val message: String) : Exception(message)

object CommerceSolanaPayment {

    /** Decimal amount -> integer base units (lamports / token base units),
     *  without floating point, so 0.1 SOL is exactly 100_000_000. */
    fun baseUnits(amount: String, decimals: Int): Long {
        val parts = amount.split(".", limit = 2)
        val intPart = parts.getOrElse(0) { "0" }.ifEmpty { "0" }
        var frac = parts.getOrElse(1) { "" }
        if (!intPart.all { it.isDigit() } || !frac.all { it.isDigit() }) {
            throw CommerceSolanaPaymentException("Payment amount is not a number.")
        }
        if (frac.length > decimals) frac = frac.substring(0, decimals)
        frac = frac.padEnd(decimals, '0')
        val combined = (intPart + frac).trimStart('0').ifEmpty { "0" }
        val v = combined.toLongOrNull() ?: throw CommerceSolanaPaymentException("Payment amount is not a number.")
        if (v <= 0L) throw CommerceSolanaPaymentException("Payment amount is not a positive number.")
        return v
    }

    /** The canonical Solana tx id = the first signature (base58), read from the
     *  signed wire tx BEFORE broadcast. Layout: shortvec(sigCount) then 64-byte
     *  signatures; for our single-signer txs sigCount is 1. */
    fun transactionSignature(signedBase64: String): String? {
        val data = runCatching { android.util.Base64.decode(signedBase64, android.util.Base64.NO_WRAP) }
            .getOrNull() ?: return null
        if (data.isEmpty()) return null
        var idx = 0
        var count = 0
        var shift = 0
        while (idx < data.size) {
            val b = data[idx].toInt() and 0xff
            idx += 1
            count = count or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (count < 1 || data.size < idx + 64) return null
        return SolanaPrimitives.base58Encode(data.copyOfRange(idx, idx + 64))
    }

    /** Broadcast the holder's signed transaction; returns the base58 signature. */
    fun broadcast(signedBase64: String, rpcURLString: String): String =
        SolanaRPCClient(endpoint = rpcURLString).sendTransaction(signedBase64)
}
