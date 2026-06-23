// Custom + alternative BIP32 derivation paths, ported from the iOS
// HardwareWallet/DerivationPath.swift `BIP32Path` enum, scoped here to
// the Bitcoin engine for the parts Bitcoin needs (path parse/validate,
// standard Bitcoin path, script-type-from-purpose, alternative
// templates for the discover sweep). The full cross-chain helper lives
// in the hardware-wallet layer in a later phase; this is the Bitcoin
// slice the wallet engine depends on for descriptor + script-type
// selection.

package com.elabify.musnad.wallet.bitcoin

object Bip32Path {

    class ParseException(message: String) : Exception(message)

    /** Bitcoin output script type for a BIP44/49/84 account path. */
    enum class BitcoinScriptType {
        LEGACY,        // BIP44, pkh, "1…"
        NESTED_SEGWIT, // BIP49, sh(wpkh), "3…"
        NATIVE_SEGWIT, // BIP84, wpkh, "bc1q…"
    }

    /** Parse "m/44'/501'/0'" into the BIP32 `address_n` components
     *  (hardened markers `'`/`h`/`H` set the 0x80000000 bit). Used to
     *  validate a user-entered path and to pick the script type. */
    @Throws(ParseException::class)
    fun parse(input: String): List<Long> {
        var body = input.trim()
        if (body.startsWith("m/") || body.startsWith("M/")) body = body.substring(2)
        if (body == "m" || body == "M") body = ""
        if (body.isEmpty()) throw ParseException("Enter a derivation path.")

        val out = ArrayList<Long>()
        for (raw in body.split("/")) {
            val comp = raw.trim()
            if (comp.isEmpty()) throw ParseException("Not a valid derivation path: $input")
            var digits = comp
            var hardened = false
            val last = comp.last()
            if (last == '\'' || last == 'h' || last == 'H') {
                hardened = true
                digits = comp.dropLast(1)
            }
            val idx = digits.toLongOrNull()
            if (idx == null || idx < 0 || idx >= 0x8000_0000L) {
                throw ParseException("Not a valid derivation path: $input")
            }
            out.add(if (hardened) idx or 0x8000_0000L else idx)
        }
        return out
    }

    /** True if `input` parses to a valid path. */
    fun isValid(input: String): Boolean = try {
        parse(input); true
    } catch (_: Exception) {
        false
    }

    /** Bitcoin account-level path; `purpose` selects the script type
     *  (84 native segwit by default). `coinType` 0 mainnet / 1 testnet. */
    fun standardBitcoin(account: Long, coinType: Long, purpose: Long = 84): String =
        "m/$purpose'/$coinType'/$account'"

    /** Map a parsed path's purpose (first component, hardened) to its
     *  script type. Defaults to native segwit for anything unrecognized
     *  so callers never silently mis-derive. Returns null for taproot
     *  (BIP86), which is intentionally unsupported. */
    fun bitcoinScriptType(forPath: String): BitcoinScriptType? {
        val comps = try {
            parse(forPath)
        } catch (_: Exception) {
            return BitcoinScriptType.NATIVE_SEGWIT
        }
        val first = comps.firstOrNull() ?: return BitcoinScriptType.NATIVE_SEGWIT
        return when (first and 0x8000_0000L.inv()) {
            44L -> BitcoinScriptType.LEGACY
            49L -> BitcoinScriptType.NESTED_SEGWIT
            84L -> BitcoinScriptType.NATIVE_SEGWIT
            86L -> null // taproot — not supported
            else -> BitcoinScriptType.NATIVE_SEGWIT
        }
    }

    /** Well-known Bitcoin account-path templates with `{account}` and
     *  `{coin}` placeholders, in priority order. Standard BIP84 first so
     *  the discover sweep can iterate the whole list. */
    fun bitcoinAlternativeTemplates(): List<String> = listOf(
        "m/84'/{coin}'/{account}'", // BIP84 native segwit (our default)
        "m/49'/{coin}'/{account}'", // BIP49 nested segwit
        "m/44'/{coin}'/{account}'", // BIP44 legacy
    )
}
