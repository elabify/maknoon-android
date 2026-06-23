// User's list of Solana wallets plus the chain-wide "currently viewed
// cluster" selection plus optimistic pending-tx tracking. Ported 1:1
// from iOS SolanaWalletStore.swift.
//
// The descriptor is cluster-agnostic (the same Ed25519 keypair works on
// mainnet/devnet/testnet); the user's "I'm looking at mainnet right now"
// choice lives in a chain-wide `currentNetwork` (one source of truth, so
// switching wallets doesn't reset it). Persists to the KV store under the
// `networks.solana.*` namespace, matching the iOS UserDefaults keys.
//
// Address-book mirroring is routed through a small SolanaAddressBookSink
// hook so this engine doesn't hard-depend on the shared AddressBookStore
// (owned by another component). The app wires a concrete sink in the UI
// phase; until then it's null and mirroring is a no-op.

package com.elabify.musnad.wallet.solana

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Mirror seam for the user's address book. The app implements this over
 *  the shared AddressBookStore; the engine only calls it. Matches the
 *  iOS calls to AddressBookStore.upsertSystemWallet / removeSystemWallet
 *  with chainKey "solana". */
interface SolanaAddressBookSink {
    fun upsertSystemWallet(walletId: UUID, chainKey: String, name: String, address: String)
    fun removeSystemWallet(walletId: UUID, chainKey: String)
}

class SolanaWalletStore(private val kv: SolanaKeyValueStore = SolanaKeyValueStore.InMemory()) {

    var wallets: List<SolanaWalletDescriptor> = emptyList()
        private set
    var activeWalletId: UUID? = null
        private set
    /** Per-wallet "currently viewing this cluster" selection. Kept for
     *  back-compat with v2 storage; the live UI reads `currentNetwork`. */
    var currentNetworkByWallet: MutableMap<UUID, SolanaNetwork> = mutableMapOf()
        private set
    /** Chain-wide current cluster. One source of truth for the picker. */
    var currentNetwork: SolanaNetwork = SolanaNetwork.MAINNET
        private set

    /** In-memory (not persisted) optimistic pending txs per wallet. */
    var pendingTxsByWallet: MutableMap<UUID, List<PendingSolanaTx>> = mutableMapOf()
        private set

    /** Wired by the app after both stores are constructed. */
    var addressBook: SolanaAddressBookSink? = null

    init { load() }

    val activeWallet: SolanaWalletDescriptor?
        get() = activeWalletId?.let { id -> wallets.firstOrNull { it.id == id } } ?: wallets.firstOrNull()

    /** Cluster the user is currently viewing (chain-wide). */
    fun activeNetwork(walletId: UUID): SolanaNetwork = currentNetwork

    /** Update the chain-wide cluster selection. Persists immediately. */
    fun setActiveNetwork(walletId: UUID, network: SolanaNetwork) {
        currentNetwork = network
        currentNetworkByWallet[walletId] = network
        persist()
    }

    // MARK: -- pending tx tracking

    /** Mark a freshly-broadcast tx as pending on the sender's wallet. If
     *  the recipient is another hardware Solana wallet on this device,
     *  also mirrors as a pending inbound on that wallet. */
    fun markPendingOutbound(
        senderWalletId: UUID,
        signature: String,
        senderAddress: String,
        recipientAddress: String,
        lamports: Long,
        tokenMint: String? = null,
        tokenSymbol: String? = null,
        tokenDecimals: Int? = null,
    ) {
        val outbound = PendingSolanaTx(
            signature = signature,
            direction = PendingSolanaTx.Direction.OUT,
            counterparty = recipientAddress,
            lamports = lamports,
            tokenMint = tokenMint,
            tokenSymbol = tokenSymbol,
            tokenDecimals = tokenDecimals,
        )
        appendPending(senderWalletId, outbound)

        val mirroredId = walletIdForAddress(recipientAddress)
        if (mirroredId != null && mirroredId != senderWalletId) {
            val inbound = PendingSolanaTx(
                signature = signature,
                direction = PendingSolanaTx.Direction.IN,
                counterparty = senderAddress,
                lamports = lamports,
                tokenMint = tokenMint,
                tokenSymbol = tokenSymbol,
                tokenDecimals = tokenDecimals,
            )
            appendPending(mirroredId, inbound)
        }
    }

    /** Drop pending entries whose signature now appears confirmed, plus
     *  pendings older than 3 minutes (presumed orphaned). */
    fun dropConfirmedPending(walletId: UUID, confirmedSignatures: Set<String>) {
        val list = pendingTxsByWallet[walletId] ?: return
        val cutoff = System.currentTimeMillis() - 3L * 60 * 1000
        val kept = list.filterNot { tx ->
            confirmedSignatures.contains(tx.signature) || tx.broadcastAtEpochMs < cutoff
        }
        if (kept.isEmpty()) pendingTxsByWallet.remove(walletId) else pendingTxsByWallet[walletId] = kept
    }

    private fun appendPending(walletId: UUID, tx: PendingSolanaTx) {
        val list = pendingTxsByWallet[walletId] ?: emptyList()
        if (list.any { it.signature == tx.signature }) return
        pendingTxsByWallet[walletId] = listOf(tx) + list
    }

    /** Resolve a base58 pubkey to a hardware wallet id we know about.
     *  Software wallets don't carry the address (deriving needs the
     *  sandwich), so this only auto-mirrors to hardware wallets. */
    private fun walletIdForAddress(address: String): UUID? {
        for (w in wallets) {
            val k = w.kind
            if (k is SolanaWalletKind.Hardware && k.publicKeyBase58 == address) return w.id
        }
        return null
    }

    // MARK: -- wallet lifecycle

    fun add(
        descriptor: SolanaWalletDescriptor,
        initialNetwork: SolanaNetwork = SolanaNetwork.MAINNET,
        makeActive: Boolean = true,
    ) {
        wallets = wallets + descriptor
        currentNetworkByWallet[descriptor.id] = initialNetwork
        if (makeActive) activeWalletId = descriptor.id
        persist()
        mirrorToAddressBook(descriptor)
    }

    /** Next unused software-account index (single sequence across all
     *  clusters). */
    fun nextSoftwareAccount(): Long {
        val used = wallets.mapNotNull { (it.kind as? SolanaWalletKind.Software)?.account }.toSet()
        var i = 0L
        while (used.contains(i)) i++
        return i
    }

    /** True if a software wallet already exists at this account index. */
    fun hasSoftwareWallet(account: Long): Boolean =
        wallets.any { (it.kind as? SolanaWalletKind.Software)?.account == account }

    fun setActive(id: UUID) {
        if (wallets.none { it.id == id }) return
        activeWalletId = id
        persist()
    }

    fun remove(id: UUID) {
        wallets = wallets.filterNot { it.id == id }
        currentNetworkByWallet.remove(id)
        if (activeWalletId == id) activeWalletId = wallets.firstOrNull()?.id
        persist()
        addressBook?.removeSystemWallet(id, "solana")
    }

    /** User-driven reorder from the wallet-list edit mode. */
    fun move(from: Int, to: Int) {
        val list = wallets.toMutableList()
        if (from !in list.indices) return
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        wallets = list
        persist()
    }

    fun rename(id: UUID, label: String) {
        wallets = wallets.map { if (it.id == id) it.copy(label = label) else it }
        persist()
    }

    fun markSynced(id: UUID, atEpochSec: Long = System.currentTimeMillis() / 1000) {
        wallets = wallets.map { if (it.id == id) it.copy(lastSyncAtEpochSec = atEpochSec) else it }
        persist()
    }

    /** Re-point a hardware wallet at a (re-registered) device record without
     *  losing the wallet, its network mapping, or cached balances. Used by the
     *  device re-link recovery (ADR-0033): the wallet's account public key
     *  proves it is the same key the connected device derives, so only the
     *  stale deviceId changes. No-op for software wallets or if already linked. */
    fun relinkDeviceId(walletId: UUID, deviceId: UUID, hidden: org.json.JSONObject?) {
        var changed = false
        wallets = wallets.map { w ->
            val k = w.kind
            if (w.id == walletId && k is SolanaWalletKind.Hardware) {
                changed = true
                // Repoint the device AND refresh the hidden (passphrase) marker
                // to the config this account was just discovered under, so a
                // host-typed hidden wallet re-prompts on send.
                w.copy(kind = k.copy(deviceId = deviceId), hidden = hidden)
            } else {
                w
            }
        }
        if (changed) persist()
    }

    // MARK: -- address-book mirroring

    private fun mirrorToAddressBook(descriptor: SolanaWalletDescriptor) {
        val ab = addressBook ?: return
        val address = when (val k = descriptor.kind) {
            is SolanaWalletKind.Software -> return // software addr resolved later
            is SolanaWalletKind.Hardware -> k.publicKeyBase58
        }
        ab.upsertSystemWallet(descriptor.id, "solana", descriptor.label, address)
    }

    /** Externally-triggered mirror update (called by the wallet view
     *  after refresh resolves a software-wallet address). */
    fun updateMirrorAddress(walletId: UUID, address: String) {
        val ab = addressBook ?: return
        val descriptor = wallets.firstOrNull { it.id == walletId } ?: return
        ab.upsertSystemWallet(walletId, "solana", descriptor.label, address)
    }

    fun remirrorAllToAddressBook() {
        wallets.forEach { mirrorToAddressBook(it) }
    }

    // MARK: -- persistence

    private fun persist() {
        val arr = JSONArray()
        wallets.forEach { arr.put(it.toJson()) }
        kv.putString(WALLETS_KEY_V2, arr.toString())
        persistNetworkMap()
        kv.putString(CURRENT_CHAIN_NETWORK_KEY, currentNetwork.rawValue)
        activeWalletId?.let { kv.putString(ACTIVE_KEY, it.toString()) } ?: kv.remove(ACTIVE_KEY)
    }

    private fun persistNetworkMap() {
        val o = JSONObject()
        currentNetworkByWallet.forEach { (id, net) -> o.put(id.toString(), net.rawValue) }
        kv.putString(NETWORK_MAP_KEY, o.toString())
    }

    fun reload() {
        wallets = emptyList()
        activeWalletId = null
        currentNetworkByWallet = mutableMapOf()
        currentNetwork = SolanaNetwork.MAINNET
        pendingTxsByWallet = mutableMapOf()
        load()
    }

    private fun load() {
        // Preferred path: v2 descriptor list + separate cluster map.
        kv.getString(WALLETS_KEY_V2)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { arr ->
                val list = ArrayList<SolanaWalletDescriptor>(arr.length())
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { list.add(SolanaWalletDescriptor.fromJson(it)) }
                }
                wallets = list
                kv.getString(NETWORK_MAP_KEY)?.let { mapRaw ->
                    runCatching { JSONObject(mapRaw) }.getOrNull()?.let { o ->
                        val out = HashMap<UUID, SolanaNetwork>()
                        o.keys().forEach { k ->
                            runCatching { UUID.fromString(k) }.getOrNull()?.let { id ->
                                SolanaNetwork.fromRawValue(o.getString(k))?.let { out[id] = it }
                            }
                        }
                        currentNetworkByWallet = out
                    }
                }
                kv.getString(ACTIVE_KEY)?.let { s ->
                    runCatching { UUID.fromString(s) }.getOrNull()?.let { activeWalletId = it }
                }
                val chainRaw = kv.getString(CURRENT_CHAIN_NETWORK_KEY)
                val chainNet = chainRaw?.let { SolanaNetwork.fromRawValue(it) }
                if (chainNet != null) {
                    currentNetwork = chainNet
                } else {
                    activeWalletId?.let { id -> currentNetworkByWallet[id]?.let { currentNetwork = it } }
                }
                return
            }
        }

        // v1 migration: descriptor carried a `network` field; peel it off
        // into the map and re-persist under v2.
        kv.getString(WALLETS_KEY_V1)?.let { raw ->
            runCatching { JSONArray(raw) }.getOrNull()?.let { arr ->
                val migrated = ArrayList<SolanaWalletDescriptor>(arr.length())
                val networkMap = HashMap<UUID, SolanaNetwork>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val d = SolanaWalletDescriptor.fromJson(o)
                    migrated.add(d)
                    SolanaNetwork.fromRawValue(o.optString("network"))?.let { networkMap[d.id] = it }
                }
                wallets = migrated
                currentNetworkByWallet = networkMap
                kv.getString(ACTIVE_KEY)?.let { s ->
                    runCatching { UUID.fromString(s) }.getOrNull()?.let { id ->
                        if (wallets.any { it.id == id }) activeWalletId = id
                    }
                }
                persist()
            }
        }
    }

    companion object {
        private const val WALLETS_KEY_V1 = "networks.solana.wallets.v1"
        private const val WALLETS_KEY_V2 = "networks.solana.wallets.v2"
        private const val NETWORK_MAP_KEY = "networks.solana.currentNetwork.v1"
        private const val CURRENT_CHAIN_NETWORK_KEY = "networks.solana.currentNetwork.chainwide.v1"
        private const val ACTIVE_KEY = "networks.solana.active.v1"
    }
}
