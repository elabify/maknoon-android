// User's list of Ethereum wallets plus the chain-wide "currently viewed
// network" selection + optimistic pending-tx tracking. 1:1 port of
// EthereumWalletStore.swift. Persists under the `networks.ethereum.*` namespace.
//
// iOS wires a weak ref to AddressBookStore for system-wallet mirroring. That is
// a shared cross-chain component (owned by the address-book phase), so here the
// mirror is a pluggable callback (addressBookMirror) the app sets at wiring
// time. This keeps the engine free of a hard dependency on another agent's file.
//
// v1 -> v2 migration: v1 stored per-(network, account) descriptors; v2 dedupes
// by (kind, cachedAddress) and seeds the per-wallet network from the v1 field.

package com.elabify.musnad.wallet.ethereum
import com.elabify.musnad.util.optStringOrNull

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Optimistic in-memory pending Ethereum tx (mirrors PendingEthereumTx.swift). */
data class PendingEthereumTx(
    val txHash: String,
    val direction: Direction,
    val counterparty: String,
    /** Native wei (base-10 string), or raw token-units for ERC-20 sends. */
    val weiValue: String,
    val tokenContract: String? = null,
    val tokenSymbol: String? = null,
    val tokenDecimals: Int? = null,
    val broadcastAt: Long = System.currentTimeMillis(),
) {
    enum class Direction { IN, OUT }
    val id: String get() = txHash
}

/** Hook for mirroring system wallets into the user's address book. */
interface EthereumAddressBookMirror {
    fun upsertSystemWallet(walletId: UUID, chainKey: String, name: String, address: String)
    fun removeSystemWallet(walletId: UUID, chainKey: String)
}

class EthereumWalletStore(private val kv: EthereumKeyValueStore = EthereumKeyValueStore.InMemory()) {

    private val _wallets = mutableListOf<EthereumWalletDescriptor>()
    val wallets: List<EthereumWalletDescriptor> get() = _wallets.toList()

    var activeWalletId: UUID? = null
        private set

    /** Per-wallet "currently viewed" network (kept for migration + legacy). */
    val currentNetworkByWallet = HashMap<UUID, EthereumNetworkID>()

    /** Chain-wide "current network" chip: one source of truth across wallets. */
    var currentNetworkID: EthereumNetworkID = EthereumNetworkID.Builtin(EthereumNetwork.MAINNET)
        private set

    /** (wallet UUID) -> optimistic pending txs. */
    val pendingTxsByWallet = HashMap<UUID, MutableList<PendingEthereumTx>>()

    /** Optional address-book mirror, wired by the app. */
    var addressBookMirror: EthereumAddressBookMirror? = null

    init { load() }

    val activeWallet: EthereumWalletDescriptor?
        get() = activeWalletId?.let { id -> _wallets.firstOrNull { it.id == id } } ?: _wallets.firstOrNull()

    /** Chain-wide selection; the walletId is ignored (kept for call-site parity). */
    fun currentNetworkID(walletId: UUID): EthereumNetworkID = currentNetworkID

    fun setCurrentNetworkID(id: EthereumNetworkID, walletId: UUID) {
        currentNetworkID = id
        currentNetworkByWallet[walletId] = id
        persistNetworkMap()
        kv.putString(CURRENT_CHAIN_NETWORK_KEY, id.stableId)
    }

    fun setCurrentNetwork(network: EthereumNetwork, walletId: UUID) =
        setCurrentNetworkID(EthereumNetworkID.Builtin(network), walletId)

    // ---- pending tx tracking ----

    fun markPendingOutbound(
        senderWalletId: UUID,
        txHash: String,
        senderAddress: String,
        recipientAddress: String,
        weiValue: String,
        tokenContract: String? = null,
        tokenSymbol: String? = null,
        tokenDecimals: Int? = null,
    ) {
        val outbound = PendingEthereumTx(
            txHash, PendingEthereumTx.Direction.OUT, recipientAddress, weiValue,
            tokenContract, tokenSymbol, tokenDecimals,
        )
        appendPending(senderWalletId, outbound)

        val mirroredId = walletIdForAddress(recipientAddress)
        if (mirroredId != null && mirroredId != senderWalletId) {
            val inbound = PendingEthereumTx(
                txHash, PendingEthereumTx.Direction.IN, senderAddress, weiValue,
                tokenContract, tokenSymbol, tokenDecimals,
            )
            appendPending(mirroredId, inbound)
        }
    }

    fun dropConfirmedPending(walletId: UUID, confirmedTxHashes: Set<String>) {
        val list = pendingTxsByWallet[walletId] ?: return
        val cutoff = System.currentTimeMillis() - 15 * 60 * 1000 // allow 15 min
        val normalized = confirmedTxHashes.map { it.lowercase() }.toHashSet()
        list.removeAll { tx -> normalized.contains(tx.txHash.lowercase()) || tx.broadcastAt < cutoff }
        if (list.isEmpty()) pendingTxsByWallet.remove(walletId)
    }

    private fun appendPending(walletId: UUID, tx: PendingEthereumTx) {
        val list = pendingTxsByWallet.getOrPut(walletId) { mutableListOf() }
        if (list.any { it.txHash.lowercase() == tx.txHash.lowercase() }) return
        list.add(0, tx)
    }

    /** Public lookup of a wallet id by its 0x address, used by the mini-app
     *  "open wallet" bridge to re-activate the exact wallet a swap used. */
    fun walletId(forAddress: String): UUID? = walletIdForAddress(forAddress)

    private fun walletIdForAddress(address: String): UUID? {
        val normalized = address.lowercase()
        return _wallets.firstOrNull { (it.address ?: "").lowercase() == normalized }?.id
    }

    // ---- network resolution ----

    fun activeNetwork(customs: CustomNetworkStore, settings: EthereumSettings): ResolvedNetwork {
        val id = activeWallet?.let { currentNetworkID(it.id) } ?: EthereumNetworkID.Builtin(EthereumNetwork.MAINNET)
        return resolve(id, customs, settings)
    }

    fun resolve(id: EthereumNetworkID, customs: CustomNetworkStore, settings: EthereumSettings): ResolvedNetwork =
        when (id) {
            is EthereumNetworkID.Builtin -> {
                val net = id.network
                ResolvedNetwork(
                    networkID = id,
                    chainId = net.chainId,
                    displayName = net.displayName,
                    ticker = net.ticker,
                    isTestnet = net.isTestnet,
                    rpcURL = settings.rpcURL(net),
                    explorerURL = settings.explorerURL(net),
                    explorerAPIURL = settings.explorerAPIURL(net),
                    explorerAPIKey = settings.explorerAPIKey(net),
                )
            }
            is EthereumNetworkID.Custom -> {
                val custom = customs.find(id.id)
                    ?: return resolve(EthereumNetworkID.Builtin(EthereumNetwork.MAINNET), customs, settings)
                ResolvedNetwork(
                    networkID = id,
                    chainId = custom.chainId,
                    displayName = custom.name,
                    ticker = custom.ticker,
                    isTestnet = custom.isTestnet,
                    rpcURL = custom.rpcURL,
                    explorerURL = custom.explorerURL,
                    explorerAPIURL = custom.explorerAPIURL,
                    explorerAPIKey = custom.explorerAPIKey,
                )
            }
        }

    // ---- CRUD ----

    fun add(
        descriptor: EthereumWalletDescriptor,
        initialNetwork: EthereumNetwork = EthereumNetwork.MAINNET,
        makeActive: Boolean = true,
    ) {
        _wallets.add(descriptor)
        currentNetworkByWallet[descriptor.id] = EthereumNetworkID.Builtin(initialNetwork)
        if (makeActive) activeWalletId = descriptor.id
        persist()
        mirrorToAddressBook(descriptor)
    }

    fun remove(id: UUID) {
        _wallets.removeAll { it.id == id }
        currentNetworkByWallet.remove(id)
        if (activeWalletId == id) activeWalletId = _wallets.firstOrNull()?.id
        persist()
        addressBookMirror?.removeSystemWallet(id, "ethereum")
    }

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in _wallets.indices) return
        val item = _wallets.removeAt(fromIndex)
        _wallets.add(toIndex.coerceIn(0, _wallets.size), item)
        persist()
    }

    fun rename(id: UUID, label: String) {
        _wallets.firstOrNull { it.id == id }?.let { it.label = label; persist() }
    }

    /** Re-point a hardware wallet at a (re-registered) device record (ADR-0033
     *  device re-link recovery). Only the stale deviceId changes. No-op for
     *  software/already-linked wallets. */
    fun relinkDeviceId(walletId: UUID, deviceId: UUID, hidden: String?) {
        val idx = _wallets.indexOfFirst { it.id == walletId }
        if (idx < 0) return
        val w = _wallets[idx]
        val k = w.kind
        if (k is EthereumWalletKind.Hardware) {
            _wallets[idx] = w.copy(kind = k.copy(deviceId = deviceId), hidden = hidden)
            persist()
        }
    }

    fun markSynced(id: UUID, date: Long = System.currentTimeMillis()) {
        _wallets.firstOrNull { it.id == id }?.let { it.lastSyncAt = date; persist() }
    }

    fun setCachedAddress(id: UUID, address: String) {
        _wallets.firstOrNull { it.id == id }?.let { it.cachedAddress = address; persist() }
    }

    fun setActive(id: UUID) {
        if (_wallets.none { it.id == id }) return
        activeWalletId = id
        kv.putString(ACTIVE_KEY, id.toString())
    }

    /** Next unused software-account index across all software wallets. */
    fun nextSoftwareAccount(): Long {
        val used = _wallets.mapNotNull { (it.kind as? EthereumWalletKind.Software)?.account }.toHashSet()
        var i = 0L
        while (used.contains(i)) i++
        return i
    }

    fun hasSoftwareWallet(account: Long): Boolean =
        _wallets.any { (it.kind as? EthereumWalletKind.Software)?.account == account }

    fun hasHardwareWallet(deviceId: UUID, account: Long): Boolean =
        _wallets.any {
            val k = it.kind as? EthereumWalletKind.Hardware
            k != null && k.deviceId == deviceId && k.account == account
        }

    fun remirrorAllToAddressBook() {
        _wallets.forEach { mirrorToAddressBook(it) }
    }

    private fun mirrorToAddressBook(descriptor: EthereumWalletDescriptor) {
        val mirror = addressBookMirror ?: return
        val addr = descriptor.cachedAddress?.takeIf { it.isNotEmpty() } ?: return
        mirror.upsertSystemWallet(descriptor.id, "ethereum", descriptor.label, addr)
    }

    fun reload() {
        _wallets.clear()
        activeWalletId = null
        currentNetworkByWallet.clear()
        currentNetworkID = EthereumNetworkID.Builtin(EthereumNetwork.MAINNET)
        pendingTxsByWallet.clear()
        load()
    }

    // ---- persistence ----

    private fun load() {
        val v2 = kv.getString(WALLETS_KEY_V2)
        if (v2 != null) {
            runCatching { JSONArray(v2) }.getOrNull()?.let { arr ->
                for (i in 0 until arr.length()) {
                    runCatching { EthereumWalletDescriptor.fromJson(arr.getJSONObject(i)) }.getOrNull()
                        ?.let { _wallets.add(it) }
                }
            }
            // Network map: prefer v3 (EthereumNetworkID), fall back to v2 (raw).
            kv.getString(NETWORK_MAP_KEY_V3)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { o ->
                    o.keys().forEach { k ->
                        val id = runCatching { UUID.fromString(k) }.getOrNull()
                        val nid = EthereumNetworkID.decode(o.getString(k))
                        if (id != null && nid != null) currentNetworkByWallet[id] = nid
                    }
                }
            } ?: kv.getString(NETWORK_MAP_KEY_V2)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { o ->
                    o.keys().forEach { k ->
                        val id = runCatching { UUID.fromString(k) }.getOrNull()
                        val net = EthereumNetwork.fromRawValue(o.getString(k))
                        if (id != null && net != null) currentNetworkByWallet[id] = EthereumNetworkID.Builtin(net)
                    }
                }
            }
            kv.getString(ACTIVE_KEY)?.let { runCatching { UUID.fromString(it) }.getOrNull()?.let { activeWalletId = it } }
            // Chain-wide current network: standalone key, then active wallet's entry.
            val chainRaw = kv.getString(CURRENT_CHAIN_NETWORK_KEY)
            val decoded = chainRaw?.let { EthereumNetworkID.decode(it) }
            if (decoded != null) {
                currentNetworkID = decoded
            } else {
                activeWalletId?.let { currentNetworkByWallet[it] }?.let { currentNetworkID = it }
            }
            return
        }
        // v1 -> v2 migration.
        val v1 = kv.getString(WALLETS_KEY_V1) ?: return
        val arr = runCatching { JSONArray(v1) }.getOrNull() ?: return
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = runCatching { EthereumWalletKind.fromJson(o.getJSONObject("kind")) }.getOrNull() ?: continue
            val cachedAddress = if (o.isNull("cachedAddress")) null else o.optStringOrNull("cachedAddress")
            val net = EthereumNetwork.fromRawValue(o.optString("network")) ?: EthereumNetwork.MAINNET
            val dedupKey = "${kind.stableKey()}:${cachedAddress ?: "no-addr"}"
            if (!seen.add(dedupKey)) continue
            val d = EthereumWalletDescriptor(
                id = runCatching { UUID.fromString(o.getString("id")) }.getOrDefault(UUID.randomUUID()),
                label = o.optString("label"),
                kind = kind,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                lastSyncAt = if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"),
                cachedAddress = cachedAddress,
            )
            _wallets.add(d)
            currentNetworkByWallet[d.id] = EthereumNetworkID.Builtin(net)
        }
        kv.getString(ACTIVE_KEY)?.let { s ->
            runCatching { UUID.fromString(s) }.getOrNull()?.let { id ->
                if (_wallets.any { it.id == id }) activeWalletId = id
            }
        }
        persist()
    }

    fun persist() {
        val arr = JSONArray()
        _wallets.forEach { arr.put(it.toJson()) }
        kv.putString(WALLETS_KEY_V2, arr.toString())
        persistNetworkMap()
        kv.putString(CURRENT_CHAIN_NETWORK_KEY, currentNetworkID.stableId)
        activeWalletId?.let { kv.putString(ACTIVE_KEY, it.toString()) } ?: kv.remove(ACTIVE_KEY)
    }

    private fun persistNetworkMap() {
        val o = JSONObject()
        currentNetworkByWallet.forEach { (id, nid) -> o.put(id.toString(), nid.stableId) }
        kv.putString(NETWORK_MAP_KEY_V3, o.toString())
    }

    companion object {
        private const val WALLETS_KEY_V2 = "networks.ethereum.wallets.v2"
        private const val NETWORK_MAP_KEY_V3 = "networks.ethereum.currentNetwork.v3"
        private const val NETWORK_MAP_KEY_V2 = "networks.ethereum.currentNetwork.v2"
        private const val CURRENT_CHAIN_NETWORK_KEY = "networks.ethereum.currentNetwork.chainwide.v1"
        private const val ACTIVE_KEY = "networks.ethereum.active.v1"
        private const val WALLETS_KEY_V1 = "networks.ethereum.wallets.v1"
    }
}
