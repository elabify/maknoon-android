// Solana wallet metadata + kind discriminator + optimistic pending-tx
// model, ported 1:1 from iOS SolanaWalletStore.swift's `SolanaWalletKind`,
// `SolanaWalletDescriptor`, and `PendingSolanaTx`.
//
// The descriptor is cluster-agnostic in v2: the same Ed25519 keypair
// works on mainnet, devnet, and testnet, so the "currently viewed
// cluster" lives in SolanaWalletStore, not here.

package com.elabify.musnad.wallet.solana

import java.util.UUID
import org.json.JSONObject

/** Discriminator for how a Solana wallet was created. Software wallets
 *  derive from the master seed at BIP44 m/44'/501'/account'/0'
 *  (Solana / Ed25519 / SLIP-0010). Hardware wallets cache the public
 *  key captured at pair time. */
sealed class SolanaWalletKind {
    data class Software(val account: Long) : SolanaWalletKind()
    data class Hardware(
        val deviceId: UUID,
        val account: Long,
        val publicKeyBase58: String,
    ) : SolanaWalletKind()

    fun toJson(): JSONObject = when (this) {
        is Software -> JSONObject().put("type", "software").put("account", account)
        is Hardware -> JSONObject()
            .put("type", "hardware")
            .put("deviceId", deviceId.toString())
            .put("account", account)
            .put("publicKeyBase58", publicKeyBase58)
    }

    companion object {
        fun fromJson(o: JSONObject): SolanaWalletKind {
            // Native Android shape {"type":..}; iOS Swift-Codable backup shape
            // {"software":{..}} / {"hardware":{..}}.
            if (o.has("type")) {
                return if (o.optString("type") == "hardware") hardwareFrom(o)
                else Software(account = o.optLong("account", 0))
            }
            o.optJSONObject("hardware")?.let { return hardwareFrom(it) }
            o.optJSONObject("software")?.let { return Software(account = it.optLong("account", 0)) }
            return Software(account = o.optLong("account", 0))
        }

        private fun hardwareFrom(o: JSONObject): Hardware = Hardware(
            deviceId = UUID.fromString(o.getString("deviceId")),
            account = o.optLong("account", 0),
            publicKeyBase58 = o.getString("publicKeyBase58"),
        )
    }
}

/** Persisted metadata for one Solana wallet. */
data class SolanaWalletDescriptor(
    val id: UUID = UUID.randomUUID(),
    var label: String,
    val kind: SolanaWalletKind,
    val createdAtEpochSec: Long = System.currentTimeMillis() / 1000,
    var lastSyncAtEpochSec: Long? = null,
    /** Trezor hidden (BIP39 passphrase) wallet binding. Null for the
     *  standard wallet and every Ledger / software wallet. Stored as an
     *  opaque JSON object for parity with iOS HardwarePassphraseRef. */
    var hidden: JSONObject? = null,
    /** Custom BIP32 derivation path for a hardware wallet added at a
     *  non-standard path (null = standard from `account`). */
    var derivationPath: String? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id.toString())
            .put("label", label)
            .put("kind", kind.toJson())
            .put("createdAt", createdAtEpochSec)
        lastSyncAtEpochSec?.let { o.put("lastSyncAt", it) }
        hidden?.let { o.put("hidden", it) }
        derivationPath?.let { o.put("derivationPath", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SolanaWalletDescriptor = SolanaWalletDescriptor(
            id = UUID.fromString(o.getString("id")),
            label = o.getString("label"),
            kind = SolanaWalletKind.fromJson(o.getJSONObject("kind")),
            createdAtEpochSec = o.optLong("createdAt", System.currentTimeMillis() / 1000),
            lastSyncAtEpochSec = if (o.has("lastSyncAt")) o.getLong("lastSyncAt") else null,
            // Accept BOTH the native object form {"ref":"hostEntry"} AND the iOS
            // bare-string form "hostEntry" so a cross-device (iOS) backup keeps the
            // Trezor passphrase marker on restore (ADR-0035).
            hidden = o.optJSONObject("hidden")
                ?: o.optString("hidden", "").takeIf { it.isNotEmpty() }?.let { JSONObject().put("ref", it) },
            derivationPath = if (o.has("derivationPath")) o.optString("derivationPath", null) else null,
        )
    }
}

/** Optimistic in-memory representation of a Solana tx we know about
 *  but RPC has not yet returned as confirmed. Surfaced as a "Pending"
 *  row at the top of the wallet's transaction list immediately after
 *  broadcast. Mirror of iOS `PendingSolanaTx`. */
data class PendingSolanaTx(
    val signature: String,
    val direction: Direction,
    /** Sender (for inbound) or recipient (for outbound). */
    val counterparty: String,
    /** Native lamports delta (signed). Used when `tokenMint` is null.
     *  For SPL transfers, reused as the raw on-chain token amount. */
    val lamports: Long,
    val tokenMint: String? = null,
    val tokenSymbol: String? = null,
    val tokenDecimals: Int? = null,
    val broadcastAtEpochMs: Long = System.currentTimeMillis(),
) {
    enum class Direction { IN, OUT }

    val id: String get() = signature
}
