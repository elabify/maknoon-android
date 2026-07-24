// Shared engine wiring + formatting helpers for the Solana wallet UI. This
// is the single place the Compose screens reach into the SDK engine layer
// (com.elabify.musnad.wallet.solana.*): it constructs the stores over
// SharedPreferences (walletPrefs / PrefsSolanaStore), loads the holder's
// IdentitySandwich, and opens a per-(descriptor, network) SolanaWallet.
//
// Mirrors the iOS HolderStore plumbing that SolanaWalletView/SendView/etc.
// read from. Everything here is engine-side (blocking RPC); callers wrap in
// withContext(Dispatchers.IO).

package com.elabify.app.maknoon.ui.wallet.solana

import android.content.Context
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.PrefsSolanaStore
import com.elabify.musnad.wallet.solana.SolanaSPLTokenStore
import com.elabify.musnad.wallet.solana.SolanaSettings
import com.elabify.musnad.wallet.solana.SolanaTokenCatalog
import com.elabify.musnad.wallet.solana.SolanaWallet
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.solana.SolanaWalletStore
import com.elabify.musnad.wallet.walletPrefs

/** Lazily-built bundle of the Solana stores, one per app session. Each
 *  store reads/writes the shared `maknoon.wallets.v1` prefs file under the
 *  `networks.solana.*` namespace, byte-identical to iOS UserDefaults. */
internal class SolanaEnv private constructor(
    val walletStore: SolanaWalletStore,
    val settings: SolanaSettings,
    val tokenStore: SolanaSPLTokenStore,
    val catalog: SolanaTokenCatalog,
    private val identityStore: IdentityStore,
) {
    /** Load the holder's identity sandwich (the software-derivation seed
     *  source). Null when no identity exists yet. Blocking. */
    fun loadSandwich(): IdentitySandwich? =
        runCatching { IdentitySandwich.load(identityStore) }.getOrNull()

    /** Open a live SolanaWallet facade for a descriptor on the chain-wide
     *  current cluster, using any per-network RPC override. Derivation folds
     *  the identity passphrase from the sandwich itself (ADR-0064). */
    fun openWallet(descriptor: SolanaWalletDescriptor): SolanaWallet {
        val network = walletStore.currentNetwork
        val rpc = settings.rpcURL(network)
        return SolanaWallet(
            descriptor = descriptor,
            network = network,
            rpcURL = rpc,
            sandwich = loadSandwich(),
        )
    }

    companion object {
        @Volatile private var instance: SolanaEnv? = null

        fun get(context: Context): SolanaEnv {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(appContext: Context): SolanaEnv {
            val prefs = walletPrefs(appContext)
            val kv = PrefsSolanaStore(prefs)
            return SolanaEnv(
                walletStore = SolanaWalletStore(kv),
                settings = SolanaSettings(kv),
                tokenStore = SolanaSPLTokenStore(kv),
                catalog = SolanaTokenCatalog(kv),
                identityStore = IdentityStore(appContext),
            )
        }
    }
}

// MARK: -- formatting (mirrors iOS lamport / SOL display helpers)

internal const val LAMPORTS_PER_SOL: Long = 1_000_000_000L

/** Format a lamport count as a SOL string, trimming trailing zeros.
 *  e.g. 1_500_000_000 -> "1.5", 890_880 -> "0.00089088". */
internal fun formatSol(lamports: Long): String {
    val negative = lamports < 0
    val abs = if (negative) -lamports else lamports
    val whole = abs / LAMPORTS_PER_SOL
    val frac = abs % LAMPORTS_PER_SOL
    val out = if (frac == 0L) {
        whole.toString()
    } else {
        var f = frac.toString().padStart(9, '0')
        while (f.isNotEmpty() && f.last() == '0') f = f.dropLast(1)
        "$whole.$f"
    }
    return if (negative) "-$out" else out
}

/** Parse a user-entered SOL amount string into lamports. Null on a
 *  malformed / negative value. */
internal fun parseSolToLamports(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(".")
    if (parts.size > 2) return null
    val whole = parts[0].ifEmpty { "0" }
    if (!whole.all { it.isDigit() }) return null
    val fracRaw = if (parts.size == 2) parts[1] else ""
    if (!fracRaw.all { it.isDigit() }) return null
    if (fracRaw.length > 9) return null
    val frac = fracRaw.padEnd(9, '0')
    return runCatching {
        whole.toLong() * LAMPORTS_PER_SOL + frac.toLong()
    }.getOrNull()
}

/** Parse a user-entered token amount (with `decimals`) into raw base units. */
internal fun parseTokenToRaw(text: String, decimals: Int): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(".")
    if (parts.size > 2) return null
    val whole = parts[0].ifEmpty { "0" }
    if (!whole.all { it.isDigit() }) return null
    val fracRaw = if (parts.size == 2) parts[1] else ""
    if (!fracRaw.all { it.isDigit() }) return null
    if (fracRaw.length > decimals) return null
    val mult = pow10(decimals)
    val frac = if (decimals == 0) "0" else fracRaw.padEnd(decimals, '0')
    return runCatching { whole.toLong() * mult + (if (decimals == 0) 0L else frac.toLong()) }.getOrNull()
}

private fun pow10(n: Int): Long {
    var r = 1L
    repeat(n) { r *= 10 }
    return r
}

/** True when a Solana send is submittable: the recipient is a valid base58
 *  address (or a resolved SNS name) AND the amount parses to positive base
 *  units. Pure; wired into SolanaSendScreen's submit gate so a malformed /
 *  wrong-network address or an unparseable amount can never reach the Send
 *  button (previously the gate only checked amount.isNotBlank()). */
internal fun solanaSendReady(
    recipientValidOrResolved: Boolean,
    amountInput: String,
    tokenDecimals: Int?,
): Boolean {
    val units = if (tokenDecimals != null) parseTokenToRaw(amountInput, tokenDecimals)
    else parseSolToLamports(amountInput)
    return recipientValidOrResolved && units != null && units > 0
}

/** Shorten a base58 address for compact rows: "AbCd…WxYz". */
internal fun shortAddress(address: String, head: Int = 4, tail: Int = 4): String =
    if (address.length <= head + tail + 1) address
    else "${address.take(head)}…${address.takeLast(tail)}"
