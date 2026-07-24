package com.elabify.app.maknoon.ui.wallet

/**
 * Self-send detection: is the recipient one of this wallet's own addresses for
 * the selected network. Pure; mirrors iOS SelfSendGuard.
 *
 * Sending to your own address is not fund loss, but on account-model chains it
 * is almost always a mistake and on Tron a self-transfer is rejected on-chain
 * (burns the fee). The send screens use this to warn-and-confirm (Ethereum,
 * Solana) or hard-block (Tron). Bitcoin is intentionally not covered: a UTXO
 * wallet has many derived addresses and self-sends (consolidation / change)
 * are normal.
 */
object SelfSendGuard {
    /**
     * True when [recipient] equals one of [ownAddresses]. [caseInsensitive] is
     * used for hex (Ethereum) addresses where case is not significant; base58
     * networks (Solana, Tron) compare exactly. Empty recipient -> false.
     */
    fun isSelfSend(recipient: String, ownAddresses: List<String>, caseInsensitive: Boolean): Boolean {
        val r = recipient.trim()
        if (r.isEmpty()) return false
        val target = if (caseInsensitive) r.lowercase() else r
        return ownAddresses.any {
            val own = it.trim()
            if (caseInsensitive) own.lowercase() == target else own == target
        }
    }
}
