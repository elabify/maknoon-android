package com.elabify.app.maknoon.ui.wallet

/**
 * Reduce a scanned / pasted payment URI to the bare recipient address. Pure;
 * mirrors iOS PaymentURIStrip and the shared payment-uri-kat.json.
 *
 * For BIP21 (bitcoin:), Solana Pay (solana:) and Tron (tron:) the recipient is
 * the URI PATH; query parameters (amount, label, spl-token, ...) are handled
 * separately by the send screen. So the rule is: trim, drop an optional
 * case-insensitive `<scheme>:` prefix, and cut off anything from the first `?`.
 * Unlike EIP-681 there is no function call or address query parameter, so
 * returning the path is correct.
 *
 * Note the Solana Pay transaction-request form `solana:https://host/...`: the
 * path is a URL, so this returns that URL, which then fails address validation
 * loudly rather than being sent anywhere.
 */
object PaymentURIStrip {
    fun strip(raw: String, scheme: String): String {
        var out = raw.trim()
        val prefix = "$scheme:"
        if (out.lowercase().startsWith(prefix)) {
            out = out.substring(prefix.length)
        }
        val q = out.indexOf('?')
        if (q >= 0) out = out.substring(0, q)
        return out
    }

    fun bitcoin(s: String): String = strip(s, "bitcoin")
    fun solana(s: String): String = strip(s, "solana")
    fun tron(s: String): String = strip(s, "tron")
}
