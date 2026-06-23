// User's list of Tron wallets plus the per-wallet "currently viewed
// network" selection. Ported 1:1 from iOS TronWalletStore.swift: the
// descriptor is network-agnostic for keypair derivation (mainnet /
// Shasta / Nile share the same key), and the user's "I'm looking at
// mainnet right now" choice lives in a separate UUID -> TronNetwork
// map plus a chain-wide sticky selection.
//
// Persistence backs onto SharedPreferences (the iOS UserDefaults
// analogue). Keys match the iOS keys byte-for-byte so a future shared
// backup format round-trips. The wallet list carries no secrets:
// software wallets store only an account index, hardware wallets the
// cached address. Signing material always comes from IdentitySandwich.

package com.elabify.musnad.wallet.tron

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class TronWalletStore(private val prefs: SharedPreferences) {

    var wallets: List<TronWalletDescriptor> = emptyList()
        private set
    var activeWalletId: UUID? = null
        private set

    /** Per-wallet network map. Read-only at runtime now; the live picker
     *  uses the chain-wide [currentNetwork] so a wallet switch keeps the
     *  user's selection sticky. */
    var currentNetworkByWallet: Map<UUID, TronNetwork> = emptyMap()
        private set

    /** Chain-wide "current network" chip. One source of truth shared
     *  across every Tron wallet so switching wallets doesn't reset the
     *  user's selection. */
    var currentNetwork: TronNetwork = TronNetwork.MAINNET
        private set

    /** In-memory map of (wallet UUID) -> pending outbound/inbound txs the
     *  user has broadcast but TronGrid has not yet returned as confirmed.
     *  Not persisted: a relaunch reloads from TronGrid, the source of
     *  truth once a tx is in a block. */
    var pendingTxsByWallet: Map<UUID, List<PendingTronTx>> = emptyMap()
        private set

    /** Optional address-book mirror sink, wired by the app layer. */
    var addressBook: TronAddressBookSink? = null

    init {
        load()
    }

    val activeWallet: TronWalletDescriptor?
        get() {
            val id = activeWalletId ?: return wallets.firstOrNull()
            return wallets.firstOrNull { it.id == id } ?: wallets.firstOrNull()
        }

    fun activeNetwork(walletId: UUID): TronNetwork = currentNetwork

    // MARK: -- pending tx tracking

    /** Mark a freshly-broadcast tx as pending on the sender's wallet. If
     *  the recipient address belongs to another Tron wallet the user
     *  owns (resolvable hardware address), ALSO marks a pending incoming
     *  on that wallet so internal sends surface on both sides. */
    fun markPendingOutbound(
        senderWalletId: UUID,
        txID: String,
        senderAddress: String,
        recipientAddress: String,
        sunAmount: Long,
        tokenContract: String? = null,
        tokenSymbol: String? = null,
        tokenDecimals: Int? = null,
    ) {
        val outbound = PendingTronTx(
            txID = txID,
            direction = PendingTronTx.Direction.OUT,
            counterparty = recipientAddress,
            sunAmount = sunAmount,
            tokenContract = tokenContract,
            tokenSymbol = tokenSymbol,
            tokenDecimals = tokenDecimals,
        )
        appendPending(senderWalletId, outbound)

        val mirroredId = walletIdForAddress(recipientAddress)
        if (mirroredId != null && mirroredId != senderWalletId) {
            val inbound = PendingTronTx(
                txID = txID,
                direction = PendingTronTx.Direction.IN,
                counterparty = senderAddress,
                sunAmount = sunAmount,
                tokenContract = tokenContract,
                tokenSymbol = tokenSymbol,
                tokenDecimals = tokenDecimals,
            )
            appendPending(mirroredId, inbound)
        }
    }

    /** Drop any pending entries whose txID now appears in the canonical
     *  confirmed list, or that are older than 3 minutes. */
    fun dropConfirmedPending(walletId: UUID, confirmedTxIDs: Set<String>) {
        val list = pendingTxsByWallet[walletId] ?: return
        val cutoff = System.currentTimeMillis() - 3 * 60 * 1000
        val filtered = list.filterNot { tx ->
            confirmedTxIDs.contains(tx.txID) || tx.broadcastAtEpochMs < cutoff
        }
        pendingTxsByWallet = if (filtered.isEmpty()) {
            pendingTxsByWallet - walletId
        } else {
            pendingTxsByWallet + (walletId to filtered)
        }
    }

    private fun appendPending(walletId: UUID, tx: PendingTronTx) {
        val list = pendingTxsByWallet[walletId] ?: emptyList()
        if (list.any { it.txID == tx.txID }) return
        pendingTxsByWallet = pendingTxsByWallet + (walletId to (listOf(tx) + list))
    }

    /** Resolve a Tron T-prefixed address to a known wallet id. Hardware
     *  descriptors carry the address directly; software wallets do not
     *  (deriving needs the sandwich), so this only mirrors to hardware. */
    private fun walletIdForAddress(address: String): UUID? {
        for (w in wallets) {
            val k = w.kind
            if (k is TronWalletKind.Hardware && k.addressBase58Check == address) return w.id
        }
        return null
    }

    fun setActiveNetwork(walletId: UUID, network: TronNetwork) {
        currentNetwork = network
        currentNetworkByWallet = currentNetworkByWallet + (walletId to network)
        persist()
    }

    fun add(
        descriptor: TronWalletDescriptor,
        initialNetwork: TronNetwork = TronNetwork.MAINNET,
        makeActive: Boolean = true,
    ) {
        wallets = wallets + descriptor
        currentNetworkByWallet = currentNetworkByWallet + (descriptor.id to initialNetwork)
        if (makeActive) activeWalletId = descriptor.id
        persist()
        mirrorToAddressBook(descriptor)
    }

    /** Next unused software-account index. Tron descriptors are
     *  network-agnostic, so a single sequence applies across all
     *  networks. */
    fun nextSoftwareAccount(): Long {
        val used = wallets.mapNotNull { (it.kind as? TronWalletKind.Software)?.account }.toSet()
        var i = 0L
        while (used.contains(i)) i++
        return i
    }

    /** True if a software wallet already exists at this account index. */
    fun hasSoftwareWallet(account: Long): Boolean =
        wallets.any { (it.kind as? TronWalletKind.Software)?.account == account }

    fun setActive(id: UUID) {
        if (wallets.none { it.id == id }) return
        activeWalletId = id
        persist()
    }

    fun remove(id: UUID) {
        wallets = wallets.filterNot { it.id == id }
        currentNetworkByWallet = currentNetworkByWallet - id
        if (activeWalletId == id) activeWalletId = wallets.firstOrNull()?.id
        persist()
        addressBook?.removeSystemWallet(walletId = id, chainKey = "tron")
    }

    private fun mirrorToAddressBook(descriptor: TronWalletDescriptor) {
        val book = addressBook ?: return
        val kind = descriptor.kind
        val address = when (kind) {
            is TronWalletKind.Software -> return // mirrored once refresh resolves the address
            is TronWalletKind.Hardware -> kind.addressBase58Check
        }
        book.upsertSystemWallet(
            walletId = descriptor.id,
            chainKey = "tron",
            name = descriptor.label,
            address = address,
            networkRawValue = "tron",
        )
    }

    /** Externally-triggered mirror update. Called by the app after
     *  refresh resolves the software-wallet address. */
    fun updateMirrorAddress(walletId: UUID, address: String) {
        val book = addressBook ?: return
        val descriptor = wallets.firstOrNull { it.id == walletId } ?: return
        book.upsertSystemWallet(
            walletId = walletId,
            chainKey = "tron",
            name = descriptor.label,
            address = address,
            networkRawValue = "tron",
        )
    }

    fun remirrorAllToAddressBook() {
        for (descriptor in wallets) mirrorToAddressBook(descriptor)
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val mutable = wallets.toMutableList()
        if (fromIndex !in mutable.indices) return
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex.coerceIn(0, mutable.size), item)
        wallets = mutable
        persist()
    }

    fun rename(id: UUID, to: String) {
        wallets = wallets.map { if (it.id == id) it.copy(label = to) else it }
        persist()
    }

    /** Re-point a hardware wallet at a (re-registered) device record (ADR-0033
     *  device re-link recovery). Only the stale deviceId changes; the wallet,
     *  network, and balances are preserved. No-op for software/already-linked. */
    fun relinkDeviceId(walletId: UUID, deviceId: UUID, hidden: org.json.JSONObject?) {
        var changed = false
        wallets = wallets.map { w ->
            val k = w.kind
            if (w.id == walletId && k is TronWalletKind.Hardware) {
                changed = true
                w.copy(kind = k.copy(deviceId = deviceId), hidden = hidden)
            } else {
                w
            }
        }
        if (changed) persist()
    }

    fun markSynced(id: UUID, atEpochSec: Long = System.currentTimeMillis() / 1000) {
        wallets = wallets.map { if (it.id == id) it.copy(lastSyncAtEpochSec = atEpochSec) else it }
        persist()
    }

    // MARK: -- persistence

    private fun persist() {
        val arr = JSONArray()
        wallets.forEach { arr.put(it.toJson()) }
        val editor = prefs.edit()
        editor.putString(WALLETS_KEY_V2, arr.toString())
        // network map
        val map = JSONObject()
        currentNetworkByWallet.forEach { (id, net) -> map.put(id.toString(), net.rawValue) }
        editor.putString(NETWORK_MAP_KEY, map.toString())
        editor.putString(CURRENT_CHAIN_NETWORK_KEY, currentNetwork.rawValue)
        val active = activeWalletId
        if (active != null) editor.putString(ACTIVE_KEY, active.toString())
        else editor.remove(ACTIVE_KEY)
        editor.apply()
    }

    /** Reset to defaults then re-read prefs (post-restore refresh). */
    fun reload() {
        wallets = emptyList()
        activeWalletId = null
        currentNetworkByWallet = emptyMap()
        currentNetwork = TronNetwork.MAINNET
        pendingTxsByWallet = emptyMap()
        load()
    }

    private fun load() {
        val raw = prefs.getString(WALLETS_KEY_V2, null) ?: return
        val arr = try { JSONArray(raw) } catch (e: Exception) { return }
        val list = ArrayList<TronWalletDescriptor>(arr.length())
        for (i in 0 until arr.length()) {
            try { list.add(TronWalletDescriptor.fromJson(arr.getJSONObject(i))) } catch (_: Exception) {}
        }
        wallets = list

        prefs.getString(NETWORK_MAP_KEY, null)?.let { mapRaw ->
            try {
                val o = JSONObject(mapRaw)
                val out = HashMap<UUID, TronNetwork>()
                for (k in o.keys()) {
                    val id = runCatching { UUID.fromString(k) }.getOrNull() ?: continue
                    TronNetwork.fromRawValue(o.getString(k))?.let { out[id] = it }
                }
                currentNetworkByWallet = out
            } catch (_: Exception) {}
        }

        prefs.getString(ACTIVE_KEY, null)?.let { s ->
            runCatching { UUID.fromString(s) }.getOrNull()?.let { activeWalletId = it }
        }

        val chainNet = prefs.getString(CURRENT_CHAIN_NETWORK_KEY, null)
        val parsedChainNet = chainNet?.let { TronNetwork.fromRawValue(it) }
        when {
            parsedChainNet != null -> currentNetwork = parsedChainNet
            else -> activeWalletId?.let { id ->
                currentNetworkByWallet[id]?.let { currentNetwork = it }
            }
        }
    }

    companion object {
        private const val WALLETS_KEY_V2 = "networks.tron.wallets.v2"
        private const val NETWORK_MAP_KEY = "networks.tron.currentNetwork.v1"
        private const val CURRENT_CHAIN_NETWORK_KEY = "networks.tron.currentNetwork.chainwide.v1"
        private const val ACTIVE_KEY = "networks.tron.active.v1"
    }
}

/** Address-book mirror seam. The app's AddressBookStore implements this
 *  so the wallet store can surface each wallet as a read-only contact
 *  without the SDK depending on the UI layer. Mirror of iOS
 *  AddressBookStore.upsertSystemWallet / removeSystemWallet. */
interface TronAddressBookSink {
    fun upsertSystemWallet(
        walletId: UUID,
        chainKey: String,
        name: String,
        address: String,
        networkRawValue: String,
    )

    fun removeSystemWallet(walletId: UUID, chainKey: String)
}
