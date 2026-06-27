// Tron wallet metadata + kind discriminator + optimistic pending-tx
// model, ported 1:1 from iOS TronWalletStore.swift's `TronWalletKind`,
// `TronWalletDescriptor`, and `PendingTronTx`.
//
// The descriptor is network-agnostic in v2: the same secp256k1 keypair
// works on mainnet / Shasta / Nile, so the "currently viewed network"
// lives in TronWalletStore, not here.

package com.elabify.musnad.wallet.tron
import com.elabify.musnad.util.optStringOrNull

import java.util.UUID
import org.json.JSONObject

/** Discriminator for how a Tron wallet was created. Software wallets
 *  derive from the master seed at BIP44 m/44'/195'/account'/0/0
 *  (Tron / secp256k1). Hardware wallets (Ledger only; Trezor firmware
 *  does not implement Tron) cache the T-prefixed base58check address
 *  captured at pair time. */
sealed class TronWalletKind {
    data class Software(val account: Long) : TronWalletKind()
    data class Hardware(
        val deviceId: UUID,
        val account: Long,
        val addressBase58Check: String,
    ) : TronWalletKind()

    fun toJson(): JSONObject = when (this) {
        is Software -> JSONObject().put("type", "software").put("account", account)
        is Hardware -> JSONObject()
            .put("type", "hardware")
            .put("deviceId", deviceId.toString())
            .put("account", account)
            .put("addressBase58Check", addressBase58Check)
    }

    companion object {
        fun fromJson(o: JSONObject): TronWalletKind {
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
            addressBase58Check = o.getString("addressBase58Check"),
        )
    }
}

/** Persisted metadata for one Tron wallet. */
data class TronWalletDescriptor(
    val id: UUID = UUID.randomUUID(),
    var label: String,
    val kind: TronWalletKind,
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
        fun fromJson(o: JSONObject): TronWalletDescriptor = TronWalletDescriptor(
            id = UUID.fromString(o.getString("id")),
            label = o.getString("label"),
            kind = TronWalletKind.fromJson(o.getJSONObject("kind")),
            createdAtEpochSec = o.optLong("createdAt", System.currentTimeMillis() / 1000),
            lastSyncAtEpochSec = if (o.has("lastSyncAt")) o.getLong("lastSyncAt") else null,
            // Accept native object {"ref":..} AND iOS bare-string "hostEntry" so a
            // cross-device backup keeps the Trezor passphrase marker (ADR-0035).
            hidden = o.optJSONObject("hidden")
                ?: o.optString("hidden", "").takeIf { it.isNotEmpty() }?.let { JSONObject().put("ref", it) },
            derivationPath = if (o.has("derivationPath")) o.optStringOrNull("derivationPath") else null,
        )
    }
}

/** Optimistic in-memory representation of a transaction we know about
 *  but TronGrid has not yet returned as confirmed. Surfaced as a
 *  "Pending" row at the top of the wallet's transaction list
 *  immediately after broadcast. Mirror of iOS `PendingTronTx`. */
data class PendingTronTx(
    val txID: String,
    val direction: Direction,
    /** Sender (for inbound) or recipient (for outbound). */
    val counterparty: String,
    /** Native TRX amount in sun. Used when `tokenContract` is null. */
    val sunAmount: Long,
    /** Non-null for TRC-20 sends; the raw on-chain contract address. */
    val tokenContract: String? = null,
    /** TRC-20 symbol for display. */
    val tokenSymbol: String? = null,
    /** TRC-20 decimals. */
    val tokenDecimals: Int? = null,
    val broadcastAtEpochMs: Long = System.currentTimeMillis(),
) {
    enum class Direction { IN, OUT }

    val id: String get() = txID
}
