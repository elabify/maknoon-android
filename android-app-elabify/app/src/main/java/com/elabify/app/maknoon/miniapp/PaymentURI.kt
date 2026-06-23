// Per-chain payment-request URIs for the POS "receive" QR. Android port of
// PaymentURI.swift.
//
// The customer scans this with their own wallet and pays from there, so we emit
// the de-facto standard each chain's wallets understand:
//   EVM      EIP-681    ethereum:<addr>@<chainId>?value=<wei>
//   Bitcoin  BIP21      bitcoin:<addr>?amount=<btc>[&label=]
//   Solana   Solana Pay solana:<addr>?amount=<sol>[&label=]
//   Tron     (de-facto) tron:<addr>?amount=<trx>   (best-effort; no formal std)
//
// Amounts are carried in each chain's human unit except EVM, where EIP-681 puts
// the value in wei. The native-coin value never includes token logic (POS
// receive is native-coin only for now).

package com.elabify.app.maknoon.miniapp

import java.math.BigDecimal
import java.math.RoundingMode

sealed interface PaymentURI {
    /** EVM: [weiValue] is the amount in wei (decimal integer string). */
    data class Ethereum(val address: String, val chainId: Long, val weiValue: String?) : PaymentURI
    /** Bitcoin: amount in BTC. */
    data class Bitcoin(val address: String, val btc: BigDecimal?) : PaymentURI
    /** Solana: amount in SOL. */
    data class Solana(val address: String, val sol: BigDecimal?) : PaymentURI
    /** Tron: amount in TRX. */
    data class Tron(val address: String, val trx: BigDecimal?) : PaymentURI

    val string: String
        get() = when (this) {
            is Ethereum -> {
                var uri = "ethereum:$address@$chainId"
                val wei = weiValue
                if (!wei.isNullOrEmpty() && wei != "0") uri += "?value=$wei"
                uri
            }
            is Bitcoin -> {
                var uri = "bitcoin:$address"
                positiveAmount(btc)?.let { uri += "?amount=$it" }
                uri
            }
            is Solana -> {
                var uri = "solana:$address"
                positiveAmount(sol)?.let { uri += "?amount=$it" }
                uri
            }
            is Tron -> {
                var uri = "tron:$address"
                positiveAmount(trx)?.let { uri += "?amount=$it" }
                uri
            }
        }

    private fun positiveAmount(d: BigDecimal?): String? {
        if (d == null || d.signum() <= 0) return null
        // Round to 18 dp (the finest unit we emit), strip trailing zeros, no
        // exponent. stripTrailingZeros can leave scientific notation for whole
        // numbers, so go through toPlainString.
        val rounded = d.setScale(18, RoundingMode.HALF_UP).stripTrailingZeros()
        return rounded.toPlainString()
    }
}
