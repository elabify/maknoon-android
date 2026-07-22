// Shared engine wiring + formatting helpers for the Ethereum wallet UI.
// This is the single place the Compose screens reach into the SDK engine
// layer (com.elabify.musnad.wallet.ethereum.*): it constructs the stores
// over SharedPreferences (walletPrefs / PrefsEthereumStore), caches them
// process-wide so the in-memory wallet list / chain-wide network chip /
// optimistic pending-tx map survive moving between the dashboard and its
// sheets, loads the holder's IdentitySandwich, and resolves the currently
// selected network (built-in or custom) for the active wallet.
//
// Mirrors the iOS HolderStore plumbing that EthereumWalletView/SendView/etc.
// read from, scoped to Ethereum only so this package stays self-contained
// and doesn't touch shared app files. Everything here is engine-side
// (blocking RPC / explorer); callers wrap in withContext(Dispatchers.IO).

package com.elabify.app.maknoon.ui.wallet.ethereum

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.PrefsEthereumStore
import com.elabify.musnad.wallet.ethereum.CustomNetworkStore
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetworkID
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumTokenRegistry
import com.elabify.musnad.wallet.ethereum.EthereumTokenStore
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.ethereum.ResolvedNetwork
import com.elabify.musnad.wallet.walletPrefs
import java.math.BigDecimal
import java.math.RoundingMode

/** Ethereum accent (Etherscan-style slate-blue), the chain tint used by
 *  the dashboard / token monograms / action tiles. */
internal val EthBlue = Color(0xFF627EEA)

/** Process-wide singletons for the Ethereum stores. The SDK stores read +
 *  write the shared `maknoon.wallets.v1` prefs file under the
 *  `networks.ethereum.*` namespace (byte-identical to iOS UserDefaults),
 *  but they also hold in-memory state that must survive navigation. Re-
 *  instantiating a store on every composition would drop the pending rows
 *  and the active-wallet selection, so we cache one instance per store. */
object EthereumStores {
    @Volatile private var walletStore: EthereumWalletStore? = null
    @Volatile private var settings: EthereumSettings? = null
    @Volatile private var tokenStore: EthereumTokenStore? = null
    @Volatile private var customs: CustomNetworkStore? = null
    @Volatile private var registry: EthereumTokenRegistry? = null

    fun walletStore(context: Context): EthereumWalletStore =
        walletStore ?: synchronized(this) {
            walletStore ?: EthereumWalletStore(PrefsEthereumStore(walletPrefs(context))).also { walletStore = it }
        }

    fun settings(context: Context): EthereumSettings =
        settings ?: synchronized(this) {
            settings ?: EthereumSettings(PrefsEthereumStore(walletPrefs(context))).also { settings = it }
        }

    fun tokenStore(context: Context): EthereumTokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: EthereumTokenStore(PrefsEthereumStore(walletPrefs(context))).also { tokenStore = it }
        }

    fun customs(context: Context): CustomNetworkStore =
        customs ?: synchronized(this) {
            customs ?: CustomNetworkStore(PrefsEthereumStore(walletPrefs(context))).also { customs = it }
        }

    fun registry(context: Context): EthereumTokenRegistry =
        registry ?: synchronized(this) {
            registry ?: EthereumTokenRegistry(PrefsEthereumStore(walletPrefs(context))).also { registry = it }
        }
}

/** Load the holder's unwrapped seed. Null when no identity exists yet or
 *  the sandwich could not be opened. Mirrors iOS `store.sandwich`. */
fun loadEthereumSandwich(context: Context): IdentitySandwich? =
    runCatching { IdentitySandwich.load(IdentityStore(context)) }.getOrNull()

/** Resolve the chain-wide current network (built-in or custom) to the flat
 *  ResolvedNetwork the RPC / explorer / signing paths consume. */
internal fun resolveCurrentNetwork(context: Context): ResolvedNetwork {
    val walletStore = EthereumStores.walletStore(context)
    val settings = EthereumStores.settings(context)
    val customs = EthereumStores.customs(context)
    return walletStore.resolve(walletStore.currentNetworkID, customs, settings)
}

// MARK: -- formatting (mirrors iOS wei / ether display helpers)

/** Short "0xAbCd…WxYz" form for an address or 0x-tx-hash. */
internal fun shortHex(s: String, head: Int = 6, tail: Int = 4): String =
    if (s.length <= head + tail + 1) s else "${s.take(head)}…${s.takeLast(tail)}"

/** Relative "3 min ago" / "just now" freshness hint from an epoch-ms ts. */
internal fun ethRelativeSince(epochMs: Long?): String {
    if (epochMs == null) return "Never synced"
    val delta = System.currentTimeMillis() - epochMs
    if (delta < 0) return "just now"
    val sec = delta / 1000
    return when {
        sec < 5 -> "just now"
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60} min ago"
        sec < 86_400 -> "${sec / 3600} h ago"
        else -> "${sec / 86_400} d ago"
    }
}

/** Format a wei decimal string into a human "value SYMBOL" string. */
internal fun formatUnitsDecimal(rawDecimal: String, decimals: Int, maxDecimals: Int = 6): String {
    val raw = runCatching { BigDecimal(rawDecimal) }.getOrNull() ?: return "0"
    val units = raw.divide(BigDecimal.TEN.pow(decimals))
    return units.setScale(maxDecimals, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
}

/** EVM display name for an account index, mirroring "Account #N". */
internal fun ethAccountLabel(account: Long): String = "Account #$account"

/**
 * ADR-0063: true when a SOFTWARE EVM wallet's cached address is not derivable
 * from the current identity seed (an "orphaned" wallet, e.g. from a mismatched
 * backup restore) -- it displays/reads balance for an address it cannot sign
 * for. Conservative: returns false for hardware wallets, when no address is
 * cached, or when the sandwich/seed is unavailable (2FA sealed), so we never
 * false-flag a wallet we simply can't check right now.
 */
internal fun ethereumWalletOrphaned(
    descriptor: EthereumWalletDescriptor,
    sandwich: IdentitySandwich?,
): Boolean {
    val sw = descriptor.kind as? EthereumWalletKind.Software ?: return false
    val cached = descriptor.cachedAddress?.takeIf { it.isNotEmpty() } ?: return false
    val s = sandwich ?: return false
    // Derive the SAME way the wallet signs (folding the identity passphrase,
    // ADR-0064); otherwise a passphrase identity false-flags every wallet.
    val derived = runCatching { EthereumDescriptors.addressFromSandwich(s, sw.account) }
        .getOrNull() ?: return false
    return !derived.equals(cached, ignoreCase = true)
}
