// Tron payment leg for Maknoon Pay (ADR-0031), the Android twin of the EVM /
// Solana legs. Native TRX + TRC-20. The Tron txID = sha256(raw_data) is known
// from the unsigned tx BEFORE broadcast, so the commerce flow posts identity +
// the txID ref first, then broadcasts the signed envelope. Software-only (the
// holder sandwich); Tron hardware commerce is a later add.
//
// The SIGN lives in the holder context (it needs the sandwich); this facade
// holds the decimal parse + the broadcast (TronRPCClient.broadcastWithSignature).

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.wallet.tron.TronRPCClient

class CommerceTronPaymentException(override val message: String) : Exception(message)

object CommerceTronPayment {

    /** Decimal amount -> integer base units (sun for TRX at 6 decimals, or the
     *  token's base units), without floating point. */
    fun baseUnits(amount: String, decimals: Int): Long {
        val parts = amount.split(".", limit = 2)
        val intPart = parts.getOrElse(0) { "0" }.ifEmpty { "0" }
        var frac = parts.getOrElse(1) { "" }
        if (!intPart.all { it.isDigit() } || !frac.all { it.isDigit() }) {
            throw CommerceTronPaymentException("Payment amount is not a number.")
        }
        if (frac.length > decimals) frac = frac.substring(0, decimals)
        frac = frac.padEnd(decimals, '0')
        val combined = (intPart + frac).trimStart('0').ifEmpty { "0" }
        val v = combined.toLongOrNull() ?: throw CommerceTronPaymentException("Payment amount is not a number.")
        if (v <= 0L) throw CommerceTronPaymentException("Payment amount is not a positive number.")
        return v
    }

    /** Broadcast the holder's signed envelope (+ R||S||V signature); returns the
     *  txid (hex). */
    fun broadcast(envelopeJSON: String, signatureRSV: ByteArray, rpcURLString: String): String =
        TronRPCClient(rpcURLString).broadcastWithSignature(envelopeJSON, signatureRSV)
}
