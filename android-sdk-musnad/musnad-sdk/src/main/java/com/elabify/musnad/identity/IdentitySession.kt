// Process-wide in-memory cache of the routine IdentitySandwich.
//
// IdentitySandwich.load derives the ML-DSA-65 ephemeral public key from the
// seed (mldsa65PublicKey, a UniFFI/Rust lattice keygen) on every call. The
// Identity tab is fully disposed when the user switches to Wallet / Apps (the
// MaknoonRoot `when (selected)` swaps composables), so returning to it re-ran
// that derivation each time, which the user sees as a visible pause + spinner.
//
// This caches the loaded sandwich keyed on IdentityStore.materialFingerprint
// (cheap plain-prefs reads, no Keystore / no crypto) so a tab switch reuses the
// already-derived sandwich instantly, while still invalidating automatically
// when the identity is created/reset, the ephemeral key rotates, or the second
// factor is toggled. It is cleared on app lock (AppLockManager.lockNow) and on
// identity wipe (IdentityStore.wipe) so a locked / reset device holds no
// decrypted identity in memory.
//
// Scope: only the ROUTINE sandwich (2FA-off with entropy, or 2FA-on with
// entropy null). The second-factor unlock path (IdentitySandwich
// .loadWithSecondFactor) produces a transient entropy-bearing sandwich after a
// key tap and intentionally bypasses this cache so that recovered entropy is
// not held longer than the operation that needed it.

package com.elabify.musnad.identity

object IdentitySession {
    @Volatile private var cachedFingerprint: String? = null
    @Volatile private var cached: IdentitySandwich? = null

    /**
     * The cached sandwich without touching the store, for synchronous UI init
     * (e.g. seeding initial state so the Identity tab does not flash a spinner
     * on re-entry). May be momentarily stale right after an identity change;
     * the next [loadCached] reconciles it. Never triggers a load.
     */
    fun peek(): IdentitySandwich? = cached

    /**
     * Cached load: returns the in-memory sandwich when the persisted material
     * is unchanged, otherwise loads it via [IdentitySandwich.load] and caches
     * the result keyed on the current fingerprint. Same return contract as
     * [IdentitySandwich.load] (null when there is no resolvable identity, which
     * also clears any stale cache). Call off the main thread on a miss: the
     * underlying load does the ML-DSA derivation.
     */
    @Synchronized
    fun loadCached(store: IdentityStore): IdentitySandwich? {
        val fingerprint = store.materialFingerprint()
        val hit = cached
        if (hit != null && fingerprint == cachedFingerprint) return hit
        val loaded = IdentitySandwich.load(store) ?: run {
            clear()
            return null
        }
        cached = loaded
        cachedFingerprint = fingerprint
        return loaded
    }

    /** Drop the cached identity (app lock / identity reset). */
    @Synchronized
    fun clear() {
        cached = null
        cachedFingerprint = null
    }
}
