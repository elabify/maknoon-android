// The user's list of Bitcoin wallets. Backed by a BitcoinKeyValueStore
// JSON blob (iOS: UserDefaults) so the list survives app restarts. The
// actual BDK Wallet handle for each row is rebuilt on demand from the
// descriptor + sandwich seed (or cached xpub for hardware wallets) via
// BitcoinWalletEngine.open.
//
// Tracks the "active" wallet id used by the Wallet tab, and mirrors each
// wallet's next-unused receive address as a read-only address-book
// contact. Ported 1:1 from iOS BitcoinWalletStore.swift.
//
// The shared AddressBookStore is a separate engine (another package); to
// avoid a hard dependency the store talks to it through the small
// [BitcoinAddressBookMirror] seam, which the app wires to the real
// address book. Mirrors iOS `addressBook.upsertSystemWallet(...)`.

package com.elabify.musnad.wallet.bitcoin

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The address-book side of wallet mirroring, matching the methods iOS
 *  calls on AddressBookStore. The app provides the implementation. */
interface BitcoinAddressBookMirror {
    fun upsertSystemWallet(walletId: UUID, chainKey: String, name: String, address: String)
    fun removeSystemWallet(walletId: UUID, chainKey: String)
}

class BitcoinWalletStore(private val store: BitcoinKeyValueStore = InMemoryKeyValueStore()) {

    var wallets: List<BitcoinWalletDescriptor> = emptyList()
        private set
    var activeWalletId: UUID? = null
        private set

    /** Address book seam so each wallet can mirror its next-unused
     *  receive address as a read-only "Your wallets" contact. Wired by the
     *  app at launch (iOS: HolderStore). */
    var addressBook: BitcoinAddressBookMirror? = null

    /** Cached mirror address per wallet so we can re-publish on launch
     *  without rebuilding a BDK wallet. */
    private val mirrorAddressByWallet = HashMap<UUID, String>()

    init {
        load()
    }

    val activeWallet: BitcoinWalletDescriptor?
        get() {
            val id = activeWalletId ?: return wallets.firstOrNull()
            return wallets.firstOrNull { it.id == id } ?: wallets.firstOrNull()
        }

    /** First-run seeding: if empty, drop in a single default Mainnet
     *  software wallet at account 0 labelled "Bitcoin". */
    fun seedDefaultIfNeeded() {
        if (wallets.isNotEmpty()) return
        val initial = BitcoinWalletDescriptor(
            label = "Bitcoin",
            kind = BitcoinWalletKind.Software(account = 0),
            network = BitcoinNetwork.MAINNET,
        )
        wallets = listOf(initial)
        activeWalletId = initial.id
        persist()
    }

    fun add(descriptor: BitcoinWalletDescriptor, makeActive: Boolean = true) {
        wallets = wallets + descriptor
        if (makeActive) activeWalletId = descriptor.id
        persist()
    }

    fun remove(id: UUID) {
        wallets = wallets.filterNot { it.id == id }
        if (activeWalletId == id) activeWalletId = wallets.firstOrNull()?.id
        mirrorAddressByWallet.remove(id)
        persistMirrorCache()
        persist()
        addressBook?.removeSystemWallet(walletId = id, chainKey = "bitcoin")
    }

    /** User-driven reorder from the wallet-list edit mode. */
    fun move(fromIndex: Int, toIndex: Int) {
        val mutable = wallets.toMutableList()
        if (fromIndex !in mutable.indices) return
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex.coerceIn(0, mutable.size), item)
        wallets = mutable
        persist()
    }

    fun rename(id: UUID, to: String) {
        val idx = wallets.indexOfFirst { it.id == id }
        if (idx < 0) return
        val mutable = wallets.toMutableList()
        mutable[idx] = mutable[idx].copy(label = to)
        wallets = mutable
        persist()
        // Re-publish the mirror so the contact name reflects the new label.
        mirrorAddressByWallet[id]?.let { publishMirror(id, it) }
    }

    /** Re-point a hardware wallet at a (re-registered) device record (ADR-0033
     *  device re-link recovery). Only the stale deviceId changes; wallet,
     *  network, labels, balances preserved. No-op for software/already-linked. */
    fun relinkDeviceId(walletId: UUID, deviceId: UUID, hidden: org.json.JSONObject?) {
        val idx = wallets.indexOfFirst { it.id == walletId }
        if (idx < 0) return
        val w = wallets[idx]
        val k = w.kind
        if (k is BitcoinWalletKind.Hardware) {
            val mutable = wallets.toMutableList()
            mutable[idx] = w.copy(kind = k.copy(deviceId = deviceId), hidden = hidden)
            wallets = mutable
            persist()
        }
    }

    /** Called after BDK resolves the next-unused receive address: caches
     *  it locally + pushes it into the address book as a system contact. */
    fun updateMirrorAddress(walletId: UUID, address: String) {
        if (address.isEmpty()) return
        if (mirrorAddressByWallet[walletId] != address) {
            mirrorAddressByWallet[walletId] = address
            persistMirrorCache()
        }
        publishMirror(walletId, address)
    }

    /** Re-publish every cached mirror. Called once at launch. */
    fun remirrorAllToAddressBook() {
        for (descriptor in wallets) {
            val address = mirrorAddressByWallet[descriptor.id] ?: continue
            publishMirror(descriptor.id, address)
        }
    }

    private fun publishMirror(walletId: UUID, address: String) {
        val book = addressBook ?: return
        val descriptor = wallets.firstOrNull { it.id == walletId } ?: return
        book.upsertSystemWallet(
            walletId = walletId,
            chainKey = "bitcoin",
            name = descriptor.label,
            address = address,
        )
    }

    fun markSynced(id: UUID, atEpochSec: Long = System.currentTimeMillis() / 1000) {
        val idx = wallets.indexOfFirst { it.id == id }
        if (idx < 0) return
        val mutable = wallets.toMutableList()
        mutable[idx] = mutable[idx].copy(lastSyncAtEpochSec = atEpochSec)
        wallets = mutable
        persist()
    }

    /** Force the next refresh to run a full scan instead of an
     *  incremental sync. Called after BDK rebuilt the local SQLite cache. */
    fun clearLastSync(id: UUID) {
        val idx = wallets.indexOfFirst { it.id == id }
        if (idx < 0) return
        val mutable = wallets.toMutableList()
        mutable[idx] = mutable[idx].copy(lastSyncAtEpochSec = null)
        wallets = mutable
        persist()
    }

    /** Populate the cached account-level public key after the first
     *  derive-from-seed so future opens skip the seed unlock. */
    fun setCachedAccountKey(id: UUID, fingerprint: String, xpub: String) {
        val idx = wallets.indexOfFirst { it.id == id }
        if (idx < 0) return
        val mutable = wallets.toMutableList()
        mutable[idx] = mutable[idx].copy(
            cachedAccountFingerprint = fingerprint,
            cachedAccountXpub = xpub,
        )
        wallets = mutable
        persist()
    }

    fun setActive(id: UUID) {
        if (wallets.none { it.id == id }) return
        activeWalletId = id
        store.putString(ACTIVE_KEY, id.toString())
    }

    /** Next unused account index for software wallets on a network. Used
     *  by "Add software wallet" so we never collide. */
    fun nextSoftwareAccount(network: BitcoinNetwork): Long {
        val used = wallets.mapNotNull { w ->
            val k = w.kind
            if (w.network == network && k is BitcoinWalletKind.Software) k.account else null
        }.toSet()
        var i = 0L
        while (used.contains(i)) i++
        return i
    }

    /** True if a software wallet already exists at this account index on
     *  the given network. The Add sheet uses it to block duplicates. */
    fun hasSoftwareWallet(account: Long, network: BitcoinNetwork): Boolean =
        wallets.any { w ->
            val k = w.kind
            w.network == network && k is BitcoinWalletKind.Software && k.account == account
        }

    /** Next unused account index for HARDWARE wallets on a (device, network).
     *  Mirrors [nextSoftwareAccount] for the Add screen's Hardware path so a
     *  freshly-added hardware wallet seeds to the lowest free account index for
     *  that device on that network. Hardware rows persisted before the account
     *  index was recorded (account == null) are ignored here; the post-read
     *  xpub dedup is the final collision guard either way. */
    fun nextHardwareAccount(deviceId: java.util.UUID, network: BitcoinNetwork): Long {
        val used = wallets.mapNotNull { w ->
            val k = w.kind
            if (w.network == network && k is BitcoinWalletKind.Hardware && k.deviceId == deviceId) {
                k.account
            } else {
                null
            }
        }.toSet()
        var i = 0L
        while (used.contains(i)) i++
        return i
    }

    /** True if a hardware wallet already exists at this account index for the
     *  given device on the given network. Drives the Add screen's inline
     *  account-index collision hint, mirroring [hasSoftwareWallet]. */
    fun hasHardwareWalletAtAccount(deviceId: java.util.UUID, account: Long, network: BitcoinNetwork): Boolean =
        wallets.any { w ->
            val k = w.kind
            w.network == network && k is BitcoinWalletKind.Hardware &&
                k.deviceId == deviceId && k.account == account
        }

    // MARK: -- persistence

    /** Reset to defaults then re-read storage (post-restore refresh). */
    fun reload() {
        wallets = emptyList()
        activeWalletId = null
        mirrorAddressByWallet.clear()
        load()
    }

    private fun load() {
        store.getString(WALLETS_KEY)?.let { raw ->
            // Per-descriptor resilient: a single unparseable row is skipped, not
            // fatal. A restored backup must never crash the app.
            runCatching {
                val arr = JSONArray(raw)
                wallets = (0 until arr.length()).mapNotNull { i ->
                    runCatching { BitcoinWalletDescriptor.fromJson(arr.getJSONObject(i)) }.getOrNull()
                }
            }
        }
        store.getString(ACTIVE_KEY)?.let { s ->
            runCatching { activeWalletId = UUID.fromString(s) }
        }
        store.getString(MIRROR_KEY)?.let { raw ->
            val o = JSONObject(raw)
            for (k in o.keys()) {
                runCatching { mirrorAddressByWallet[UUID.fromString(k)] = o.getString(k) }
            }
        }
    }

    private fun persist() {
        val arr = JSONArray()
        wallets.forEach { arr.put(it.toJson()) }
        store.putString(WALLETS_KEY, arr.toString())
        store.putString(ACTIVE_KEY, activeWalletId?.toString())
    }

    private fun persistMirrorCache() {
        val o = JSONObject()
        mirrorAddressByWallet.forEach { (id, addr) -> o.put(id.toString(), addr) }
        store.putString(MIRROR_KEY, o.toString())
    }

    companion object {
        // Persistence root under "networks.bitcoin.*", matching iOS.
        private const val WALLETS_KEY = "networks.bitcoin.wallets.v1"
        private const val ACTIVE_KEY = "networks.bitcoin.active.v1"
        private const val MIRROR_KEY = "networks.bitcoin.addressBook.mirror.v1"
    }
}
