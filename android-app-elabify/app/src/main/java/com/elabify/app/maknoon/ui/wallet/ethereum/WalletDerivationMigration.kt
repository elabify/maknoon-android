// One-shot 0.6.7 migration for the wallet-derivation passphrase parity fix
// (ADR-0064). The identity BIP-39 passphrase is now folded into software-wallet
// derivation on every network, matching iOS. EVM software wallets cache their
// address on the descriptor, so a wallet created under the OLD (no-passphrase)
// derivation now derives a DIFFERENT address than it displays and can no longer
// sign for it (an "orphan", ADR-0063). This hard switch (early beta, no in-app
// migration) DELETES those abandoned EVM wallets on the first 0.6.7 launch.
//
// Solana, Tron and Bitcoin software wallets store only the account index and
// derive fresh from the seed on every read, so they self-heal to the new
// address with nothing to delete; their pre-0.6.7 balances sit at the old
// no-passphrase address and are silently unreachable in-app (accepted per the
// hard-switch decision). Only EVM caches a software address, so only EVM needs
// this reconcile.

package com.elabify.app.maknoon.ui.wallet.ethereum

import android.content.Context

object WalletDerivationMigration {
    private const val PREFS = "maknoon.migrations"
    private const val KEY_DONE = "derivation.parity.v1"

    /** Delete EVM software wallets abandoned by the passphrase-folding change.
     *  Idempotent + pref-gated (runs at most once, ever). Requires the seed to
     *  re-derive: if the identity is 2FA-sealed, locked, or absent, this is a
     *  no-op that does NOT mark itself done, so it retries on a later launch;
     *  the ADR-0063 orphan guard keeps any stale wallet safe (flagged, Send
     *  disabled) in the meantime. Called synchronously from MainActivity.onCreate
     *  (before the UI reads the wallet store), so there is no cross-thread race
     *  on the process-wide store. */
    fun runIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val sandwich = loadEthereumSandwich(context) ?: return // retry next launch
        runCatching {
            val store = EthereumStores.walletStore(context)
            store.wallets
                .filter { ethereumWalletOrphaned(it, sandwich) }
                .forEach { store.remove(it.id) }
            prefs.edit().putBoolean(KEY_DONE, true).apply()
        }
    }
}
