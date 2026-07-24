package com.elabify.app.maknoon.ui.wallet

/**
 * Cross-network address mismatch detection. Pure; mirrors iOS
 * AddressNetworkGuard and the shared address-network-kat.json.
 *
 * A recipient pasted or scanned into the wrong send screen (e.g. a Bitcoin
 * address into an Ethereum send) is never intentional and sends funds
 * somewhere unrecoverable if it slips through. Each send screen validates the
 * recipient against its own network; this classifier adds an explicit,
 * friendly cross-network block by recognising an address that clearly belongs
 * to a DIFFERENT network so the screen can refuse it and name it.
 *
 * Conservative on purpose: only unambiguous prefixes/shapes are recognised
 * (EVM 0x, Tron T-base58, Bitcoin bech32). A bare base58 string that could be
 * either Solana or a legacy Bitcoin address is left unclassified so the guard
 * never fires a false "wrong network" on a valid address.
 */
enum class AddressFamily(val displayName: String) {
    ETHEREUM("Ethereum"),
    TRON("Tron"),
    BITCOIN("Bitcoin"),
}

object AddressNetworkGuard {
    private val BASE58 =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toSet()

    /** Best-effort family of [address] by unambiguous prefix/shape, or null. */
    fun detect(address: String): AddressFamily? {
        val a = address.trim()
        if (a.isEmpty()) return null

        // EVM: 0x + 40 hex.
        if (a.length == 42 && (a.startsWith("0x") || a.startsWith("0X"))) {
            val hex = a.substring(2)
            if (hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return AddressFamily.ETHEREUM
            }
        }

        // Tron: base58check, 'T' + 33 base58 chars (34 total).
        if (a.length == 34 && a.startsWith("T") && a.all { it in BASE58 }) {
            return AddressFamily.TRON
        }

        // Bitcoin: bech32 / bech32m. Legacy base58 (1.../3...) is intentionally
        // not classified because it overlaps Solana base58.
        val lower = a.lowercase()
        if (lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("bcrt1")) {
            return AddressFamily.BITCOIN
        }

        return null
    }

    /**
     * The family [address] looks like when that differs from the screen's
     * [current] network family (so the send can be blocked and named), else
     * null. [current] is null for networks not covered by the classifier (e.g.
     * Solana), so any recognised family is a mismatch.
     */
    fun crossNetworkMismatch(address: String, current: AddressFamily?): AddressFamily? {
        val d = detect(address) ?: return null
        return if (d == current) null else d
    }
}
