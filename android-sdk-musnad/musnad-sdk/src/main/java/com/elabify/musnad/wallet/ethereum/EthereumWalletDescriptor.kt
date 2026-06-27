// Persisted metadata for a single Ethereum wallet. 1:1 port of
// EthereumWalletDescriptor.swift.
//
// Ethereum EOAs are chain-agnostic: one private key -> the same EIP-55 address
// on every EVM chain. So we store ONE descriptor per (kind, account) and let the
// user switch which network the wallet talks to at runtime; the selected network
// is persisted on EthereumWalletStore.

package com.elabify.musnad.wallet.ethereum
import com.elabify.musnad.util.optStringOrNull

import java.util.UUID
import org.json.JSONObject

data class EthereumWalletDescriptor(
    val id: UUID = UUID.randomUUID(),
    var label: String,
    val kind: EthereumWalletKind,
    val createdAt: Long = System.currentTimeMillis(),
    var lastSyncAt: Long? = null,
    /** EIP-55 checksummed account address; cached once at creation. */
    var cachedAddress: String? = null,
    /** Trezor hidden (BIP39 passphrase) wallet binding ref; null otherwise. */
    var hidden: String? = null,
    /** Custom BIP32 derivation path for a hardware wallet at a non-standard path. */
    var derivationPath: String? = null,
) {
    /** Resolved address regardless of kind. */
    val address: String?
        get() {
            cachedAddress?.takeIf { it.isNotEmpty() }?.let { return it }
            (kind as? EthereumWalletKind.Hardware)?.let { return it.address }
            return null
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id.toString())
        .put("label", label)
        .put("kind", kind.toJson())
        .put("createdAt", createdAt)
        .put("lastSyncAt", lastSyncAt ?: JSONObject.NULL)
        .put("cachedAddress", cachedAddress ?: JSONObject.NULL)
        .put("hidden", hidden ?: JSONObject.NULL)
        .put("derivationPath", derivationPath ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): EthereumWalletDescriptor = EthereumWalletDescriptor(
            id = UUID.fromString(o.getString("id")),
            label = o.getString("label"),
            kind = EthereumWalletKind.fromJson(o.getJSONObject("kind")),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            lastSyncAt = if (o.isNull("lastSyncAt")) null else o.optLong("lastSyncAt"),
            cachedAddress = if (o.isNull("cachedAddress")) null else o.optStringOrNull("cachedAddress"),
            hidden = if (o.isNull("hidden")) null else o.optStringOrNull("hidden"),
            derivationPath = if (o.isNull("derivationPath")) null else o.optStringOrNull("derivationPath"),
        )
    }
}
