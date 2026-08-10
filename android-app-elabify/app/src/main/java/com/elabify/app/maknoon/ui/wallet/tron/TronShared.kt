// Shared Tron UI plumbing: a process-scoped holder for the Tron stores
// (so dashboard / send / receive / settings see the same in-memory
// wallet list + pending-tx state across sheet navigation), the seed
// loader, and small formatting helpers. Mirrors the role iOS's
// HolderStore plays for the SwiftUI views, scoped to Tron only so this
// package stays self-contained and doesn't touch shared app files.

package com.elabify.app.maknoon.ui.wallet.tron

import android.content.Context
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.tron.TronSettings
import com.elabify.musnad.wallet.tron.TronTokenCatalog
import com.elabify.musnad.wallet.tron.TronTRC20TokenStore
import com.elabify.musnad.wallet.tron.TronWalletStore
import com.elabify.musnad.wallet.walletPrefs
import java.math.BigDecimal
import java.math.RoundingMode

/** Process-wide singletons for the Tron stores. The SDK stores read +
 *  write SharedPreferences, but they also hold in-memory state (the
 *  wallet list, the chain-wide network chip, the optimistic pending-tx
 *  map) that must survive moving between the dashboard and its sheets.
 *  Re-instantiating a store on every composition would drop the pending
 *  rows and the active-wallet selection, so we cache one instance per
 *  store keyed off the app SharedPreferences. */
object TronStores {
    @Volatile private var walletStore: TronWalletStore? = null
    @Volatile private var settings: TronSettings? = null
    @Volatile private var tokenStore: TronTRC20TokenStore? = null
    @Volatile private var catalog: TronTokenCatalog? = null

    fun walletStore(context: Context): TronWalletStore =
        walletStore ?: synchronized(this) {
            walletStore ?: TronWalletStore(walletPrefs(context)).also { walletStore = it }
        }

    fun settings(context: Context): TronSettings =
        settings ?: synchronized(this) {
            settings ?: TronSettings(walletPrefs(context)).also { settings = it }
        }

    fun tokenStore(context: Context): TronTRC20TokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: TronTRC20TokenStore(walletPrefs(context)).also { tokenStore = it }
        }

    fun catalog(context: Context): TronTokenCatalog =
        catalog ?: synchronized(this) {
            catalog ?: TronTokenCatalog(walletPrefs(context)).also { catalog = it }
        }
}

/** Load the holder's unwrapped seed. Null when no identity exists yet or
 *  the sandwich could not be opened. Mirrors iOS `store.sandwich`. */
fun loadTronSandwich(context: Context): IdentitySandwich? =
    runCatching { IdentitySandwich.load(IdentityStore(context)) }.getOrNull()

// MARK: -- formatting helpers (shared across the Tron screens)

private const val SUN_PER_TRX = 1_000_000.0

/** sun -> "0.123456" TRX, six-decimal fixed like the iOS dashboard. */
fun formatTrx(sun: Long): String {
    val trx = BigDecimal(sun).divide(BigDecimal(1_000_000))
    return trx.setScale(6, RoundingMode.DOWN).toPlainString()
}

/** sun -> human TRX as a Double (for fee math / Max). */
fun sunToTrx(sun: Long): Double = sun / SUN_PER_TRX

/** TRX (Double) -> sun (Long), rounded. */
fun trxToSun(trx: Double): Long = Math.round(trx * SUN_PER_TRX)

/** Parse a user-entered amount string (whole token units) into exact integer
 *  base units as a decimal string, scaling by 10^decimals. uint256-safe
 *  (arbitrary precision, no Long overflow) and free of binary-Double rounding.
 *  Null on malformed input, negatives, more than one dot, or more fractional
 *  digits than the token supports (never silently truncates sub-unit
 *  precision). Mirrors iOS TokenAmount.baseUnits and the shared
 *  amount-scaling-kat.json. Use for both TRC-20 (pass token decimals) and
 *  native TRX (pass 6). */
fun tronTokenToRaw(text: String, decimals: Int): String? {
    if (decimals < 0) return null
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(".")
    if (parts.size > 2) return null
    val whole = parts[0].ifEmpty { "0" }
    if (!whole.all { it in '0'..'9' }) return null
    val fracRaw = if (parts.size == 2) parts[1] else ""
    if (!fracRaw.all { it in '0'..'9' }) return null
    if (fracRaw.length > decimals) return null
    val frac = if (decimals == 0) "" else fracRaw.padEnd(decimals, '0')
    val combined = (whole + frac).trimStart('0')
    return combined.ifEmpty { "0" }
}

/** Short "T9yD…mGkn" form for a base58check address or txid. */
fun shortHash(s: String): String =
    if (s.length > 12) "${s.take(6)}…${s.takeLast(4)}" else s

/** Strip an optional `tron:` URI scheme + query string from a scanned /
 *  pasted recipient, mirroring iOS `stripTronPrefix`. */
fun stripTronPrefix(s: String): String =
    com.elabify.app.maknoon.ui.wallet.PaymentURIStrip.tron(s)
