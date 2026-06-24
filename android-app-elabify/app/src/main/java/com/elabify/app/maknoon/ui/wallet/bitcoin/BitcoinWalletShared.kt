// Shared state holder + helpers for the Bitcoin wallet Compose UI. One
// instance is remembered at the BitcoinWalletScreen root and threaded down
// to the dashboard, send, receive, addresses, settings and history screens
// so every sheet talks to the same engine handle + the same persisted
// stores. Ported from the iOS HolderStore wiring (bitcoinWalletStore /
// bitcoinSettings / bitcoinLabels / BitcoinWallet actor) for 1:1 parity.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import android.content.Context
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.PrefsBitcoinStore
import com.elabify.musnad.wallet.bitcoin.BitcoinLabelStore
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinSettings
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletStore
import com.elabify.musnad.wallet.walletPrefs
import org.bitcoindevkit.CanonicalTx
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Txid

/** Holds the persisted Bitcoin stores + the BTC price cache for the
 *  lifetime of the wallet screen. Constructed once via [create] and kept
 *  in a `remember` so a tab switch / recomposition does not rebuild the
 *  SharedPreferences-backed stores. Mirrors the slice of iOS HolderStore
 *  the Bitcoin views touch. */
class BitcoinWalletEnv private constructor(
    val store: BitcoinWalletStore,
    val settings: BitcoinSettings,
    val labels: BitcoinLabelStore,
    val filesDirPath: String,
) {
    companion object {
        // The wallet store is a PROCESS-WIDE SINGLETON, matching the Ethereum /
        // Solana / Tron stores. Every BitcoinWalletEnv.create() returns an env
        // backed by the same store instance, so a wallet added from any path
        // (manual add, inline discover, the generic post-register discover via
        // persistDiscoveredSelection -> persistBitcoin, which calls create()
        // again) is immediately visible to the dashboard / wallet list that
        // holds its own env. Before this, create() built a fresh store each
        // call, so a discover-added wallet hit SharedPreferences but never the
        // list's in-memory store, and only appeared after a process restart.
        @Volatile
        private var cachedStore: BitcoinWalletStore? = null

        fun create(context: Context): BitcoinWalletEnv {
            val prefs = walletPrefs(context)
            val kv = PrefsBitcoinStore(prefs)
            val store = cachedStore ?: synchronized(this) {
                cachedStore ?: BitcoinWalletStore(kv).also {
                    // No silent auto-seed: the default "Bitcoin" software wallet is
                    // created only when the user explicitly chooses it (onboarding
                    // "Create Bitcoin software wallet", or the wallet Add flow),
                    // matching iOS. Until then the wallet list stays empty.
                    cachedStore = it
                }
            }
            return BitcoinWalletEnv(
                store = store,
                settings = BitcoinSettings(kv),
                labels = BitcoinLabelStore(kv),
                filesDirPath = context.filesDir.path,
            )
        }
    }
}

/** Unlock the holder's BIP39 recovery words off the Identity Sandwich.
 *  Returns null when no identity exists yet (the dashboard then shows the
 *  "create an identity first" notice, like the first wallet slice). */
fun loadRecoveryWords(context: Context): List<String>? =
    runCatching { IdentitySandwich.load(IdentityStore(context))?.recoveryWords() }.getOrNull()

// MARK: -- formatting helpers (shared across screens)

/** 8-decimal BTC string from satoshis (no sign). */
fun formatBtc(sats: Long): String = String.format("%.8f", sats / 100_000_000.0)

/** Signed BTC delta string, "+" prefixed for inbound, used by tx rows. */
fun formatSignedBtc(sats: Long): String {
    val body = String.format("%.8f", sats / 100_000_000.0)
    return if (sats >= 0) "+$body" else body
}

/** Group-separated sats, e.g. "12,345 sats". */
fun formatSats(sats: Long): String = "%,d sats".format(sats)

/** A compact balance / address label: "12,345 sats" under 100k, else BTC. */
fun formatSatsCompact(sats: Long, ticker: String): String =
    if (sats >= 100_000) String.format("%.8f %s", sats / 100_000_000.0, ticker)
    else "$sats sats"

fun shortMiddle(s: String, head: Int = 8, tail: Int = 6): String =
    if (s.length <= head + tail + 1) s else "${s.take(head)}…${s.takeLast(tail)}"

// MARK: -- CanonicalTx accessors (BDK uses unsigned types; unwrap here)

fun CanonicalTx.txidHex(): String = transaction.computeTxid().toString()

fun CanonicalTx.isUnconfirmed(): Boolean = chainPosition is ChainPosition.Unconfirmed

/** Confirmation epoch seconds (or now for mempool), for the row date label. */
fun CanonicalTx.timestampSec(): Long = when (val pos = chainPosition) {
    is ChainPosition.Confirmed -> pos.confirmationBlockTime.confirmationTime.toLong()
    is ChainPosition.Unconfirmed -> pos.timestamp?.toLong() ?: (System.currentTimeMillis() / 1000)
}

fun CanonicalTx.blockHeightLabel(): String = when (val pos = chainPosition) {
    is ChainPosition.Confirmed -> "Block ${pos.confirmationBlockTime.blockId.height}"
    is ChainPosition.Unconfirmed -> "Mempool"
}

// MARK: -- descriptor labels

fun BitcoinWalletDescriptor.kindLabel(): String = when (val k = kind) {
    is BitcoinWalletKind.Software -> "Software (account ${k.account})"
    is BitcoinWalletKind.Hardware -> "Hardware"
}

fun BitcoinWalletDescriptor.accountSuffix(): String = when (val k = kind) {
    is BitcoinWalletKind.Software -> "Index ${k.account}"
    is BitcoinWalletKind.Hardware -> "Hardware wallet"
}

/** The software account index for the active wallet, used by signing.
 *  Hardware wallets have no software account (they route to the BLE hook). */
fun BitcoinWalletDescriptor.softwareAccountOrNull(): Long? =
    (kind as? BitcoinWalletKind.Software)?.account

// MARK: -- explorer URLs

fun BitcoinSettings.txUrl(txid: String, network: BitcoinNetwork): String =
    explorerTxURL(txid, network)

fun BitcoinSettings.addressUrl(address: String, network: BitcoinNetwork): String =
    explorerAddressURL(address, network)

// MARK: -- coin-control bridging

/** Stable key for a UTXO selection set; we hold strings, not BDK OutPoints. */
data class UtxoKey(val txid: String, val vout: Int)

/** Map the selected UTXO keys back into BDK OutPoints for the PSBT builder. */
fun Set<UtxoKey>.toOutpoints(): List<OutPoint> = mapNotNull { key ->
    runCatching { OutPoint(Txid.fromString(key.txid), key.vout.toUInt()) }.getOrNull()
}
