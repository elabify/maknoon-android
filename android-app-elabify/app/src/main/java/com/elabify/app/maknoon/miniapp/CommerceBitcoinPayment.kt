// Bitcoin on-chain payment leg for Maknoon Pay (ADR-0031), the Android twin of
// the EVM / Solana / Tron legs. Native BTC only, software wallets only (Bitcoin
// hardware commerce is a later add). BDK builds + signs a PSBT; the finalized
// transaction's txid is deterministic and known BEFORE broadcast, so the
// commerce flow posts identity + the txid ref first, then broadcasts.
//
// The build + SIGN happen in the holder context (it owns the sandwich + the BDK
// engine); this facade adds the BTC->sats parse + a standalone Electrum
// broadcast that does not need to re-open/sync the wallet.

package com.elabify.app.maknoon.miniapp

import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.Psbt

class CommerceBitcoinPaymentException(override val message: String) : Exception(message)

object CommerceBitcoinPayment {

    /** BTC decimal amount -> integer satoshis (8 decimals), no floating point. */
    fun satsFromBTC(amount: String): Long {
        val parts = amount.split(".", limit = 2)
        val intPart = parts.getOrElse(0) { "0" }.ifEmpty { "0" }
        var frac = parts.getOrElse(1) { "" }
        if (!intPart.all { it.isDigit() } || !frac.all { it.isDigit() }) {
            throw CommerceBitcoinPaymentException("Payment amount is not a number.")
        }
        val decimals = 8
        if (frac.length > decimals) frac = frac.substring(0, decimals)
        frac = frac.padEnd(decimals, '0')
        val combined = (intPart + frac).trimStart('0').ifEmpty { "0" }
        val v = combined.toLongOrNull() ?: throw CommerceBitcoinPaymentException("Payment amount is not a number.")
        if (v <= 0L) throw CommerceBitcoinPaymentException("Payment amount is not a positive number.")
        return v
    }

    /** Finalize a signed PSBT into its transaction. Software signing finalizes
     *  in place (extractTx works directly); hardware signers (Ledger/Trezor)
     *  return partial sigs, so combine with the original unsigned PSBT and
     *  finalize (mirrors BitcoinWalletEngine.importSignedPSBTAndBroadcast). */
    private fun finalizedTx(signedPSBTBase64: String, unsignedBase64: String?): org.bitcoindevkit.Transaction {
        var psbt = Psbt(signedPSBTBase64)
        val inputs = psbt.input()
        val allFinalized = inputs.isNotEmpty() &&
            inputs.all { it.finalScriptWitness != null || it.finalScriptSig != null }
        if (allFinalized) return psbt.extractTx()
        if (unsignedBase64 != null) psbt = psbt.combine(Psbt(unsignedBase64))
        val finalized = psbt.finalize()
        if (!finalized.couldFinalize) {
            val summary = finalized.errors?.joinToString("; ") { it.toString() } ?: ""
            throw CommerceBitcoinPaymentException(
                "PSBT could not be finalized. " + summary.ifEmpty { "No reason reported by BDK." },
            )
        }
        return finalized.psbt.extractTx()
    }

    /** Deterministic txid, derivable before broadcast so the merchant gets the
     *  settlement ref first. [unsignedBase64] is needed only for hardware-signed
     *  PSBTs that still need finalizing. */
    fun txidFromSignedPSBT(signedPSBTBase64: String, unsignedBase64: String? = null): String =
        finalizedTx(signedPSBTBase64, unsignedBase64).computeTxid().toString()

    /** Broadcast a signed PSBT over Electrum; returns the txid (hex). Standalone
     *  (no engine). [unsignedBase64] lets a hardware-signed (not-yet-finalized)
     *  PSBT finalize the same way the txid was derived, so the broadcast txid
     *  matches the ref posted to the merchant. */
    fun broadcast(signedPSBTBase64: String, electrumURL: String, unsignedBase64: String? = null): String {
        val tx = finalizedTx(signedPSBTBase64, unsignedBase64)
        val client = ElectrumClient(electrumURL, null)
        return client.transactionBroadcast(tx).toString()
    }
}
