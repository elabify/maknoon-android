// Persisted metadata for a single Bitcoin wallet, ported 1:1 from iOS
// BitcoinWalletKind.swift + BitcoinWalletDescriptor.swift. This is the
// `BitcoinWalletStore` row type, NOT the BDK Descriptor (which is
// rebuilt on demand from `kind` + the Identity Sandwich seed, or from
// the cached xpub for a hardware-backed wallet).

package com.elabify.musnad.wallet.bitcoin
import com.elabify.musnad.util.optStringOrNull

import org.json.JSONObject
import java.util.UUID

/** The two flavours of Bitcoin wallet Maknoon supports.
 *
 *  Software derives BIP84 keys from the Identity Sandwich's BIP39 seed;
 *  `account` is the BIP44 account index so a user can have several
 *  independently-labelled wallets rooted in the same 24-word phrase.
 *
 *  Hardware is a watch-only descriptor built from an xpub fetched from a
 *  paired Ledger or Trezor at pairing time. The private key never leaves
 *  the device; PSBT signing routes back to the device over BLE. */
sealed class BitcoinWalletKind {
    data class Software(val account: Long) : BitcoinWalletKind()
    data class Hardware(
        val deviceId: UUID,
        val accountFingerprint: String,
        val accountXpub: String,
        /** The BIP44 account index this xpub was derived at. Carried so the
         *  Add screen can seed the next free hardware account (mirroring the
         *  Software path's account-index tracking) and show an account-index
         *  collision hint. The xpub stays the canonical dedup key; this index
         *  is the human-facing label / seed only. Null on pre-existing rows
         *  read back before this field was persisted. */
        val account: Long? = null,
    ) : BitcoinWalletKind()

    fun toJson(): JSONObject = when (this) {
        is Software -> JSONObject().put("type", "software").put("account", account)
        is Hardware -> JSONObject()
            .put("type", "hardware")
            .put("deviceId", deviceId.toString())
            .put("accountFingerprint", accountFingerprint)
            .put("accountXpub", accountXpub)
            .apply { account?.let { put("account", it) } }
    }

    companion object {
        fun fromJson(o: JSONObject): BitcoinWalletKind {
            // Native Android shape: {"type":"software"|"hardware", ...}.
            if (o.has("type")) {
                return when (o.optString("type")) {
                    "hardware" -> hardwareFrom(o)
                    else -> Software(account = o.optLong("account", 0))
                }
            }
            // iOS Swift-Codable shape (a backup from iOS): the case name is the
            // key, associated values nested under it -> {"software":{"account":N}}
            // / {"hardware":{"deviceId":..,"accountFingerprint":..,"accountXpub":..}}.
            o.optJSONObject("hardware")?.let { return hardwareFrom(it) }
            o.optJSONObject("software")?.let { return Software(account = it.optLong("account", 0)) }
            return Software(account = o.optLong("account", 0))
        }

        private fun hardwareFrom(o: JSONObject): Hardware = Hardware(
            deviceId = UUID.fromString(o.getString("deviceId")),
            accountFingerprint = o.getString("accountFingerprint"),
            accountXpub = o.getString("accountXpub"),
            account = if (o.has("account")) o.getLong("account") else null,
        )
    }
}

data class BitcoinWalletDescriptor(
    val id: UUID = UUID.randomUUID(),
    var label: String,
    val kind: BitcoinWalletKind,
    val network: BitcoinNetwork,
    val createdAtEpochSec: Long = System.currentTimeMillis() / 1000,
    var lastSyncAtEpochSec: Long? = null,

    /** Cached BIP32 master fingerprint and account-level xpub for SOFTWARE
     *  wallets. Populated once at wallet creation under one biometric
     *  prompt to read the seed, then reused on every subsequent open so we
     *  can build a watch-only BDK descriptor WITHOUT touching the seed.
     *  The seed is only fetched again at SEND time. Hardware wallets carry
     *  their own xpub/fingerprint inside `kind`. */
    var cachedAccountFingerprint: String? = null,
    var cachedAccountXpub: String? = null,

    /** Trezor hidden (BIP39 passphrase) wallet binding. Null for the
     *  standard wallet and every Ledger / software wallet. Stored as an
     *  opaque JSON object for parity with iOS HardwarePassphraseRef. */
    var hidden: JSONObject? = null,

    /** Custom BIP32 account path for a hardware wallet added at a
     *  non-standard path (null = standard BIP84 m/84'/coin'/account').
     *  Its purpose (44/49/84) selects the script type for the watch-only
     *  descriptor and signing. */
    var derivationPath: String? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id.toString())
            .put("label", label)
            .put("kind", kind.toJson())
            .put("network", network.rawValue)
            .put("createdAt", createdAtEpochSec)
        lastSyncAtEpochSec?.let { o.put("lastSyncAt", it) }
        cachedAccountFingerprint?.let { o.put("cachedAccountFingerprint", it) }
        cachedAccountXpub?.let { o.put("cachedAccountXpub", it) }
        hidden?.let { o.put("hidden", it) }
        derivationPath?.let { o.put("derivationPath", it) }
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): BitcoinWalletDescriptor = BitcoinWalletDescriptor(
            id = UUID.fromString(o.getString("id")),
            label = o.getString("label"),
            kind = BitcoinWalletKind.fromJson(o.getJSONObject("kind")),
            network = BitcoinNetwork.fromRawValue(o.optString("network", "mainnet"))
                ?: BitcoinNetwork.MAINNET,
            createdAtEpochSec = o.optLong("createdAt", System.currentTimeMillis() / 1000),
            lastSyncAtEpochSec = if (o.has("lastSyncAt")) o.getLong("lastSyncAt") else null,
            cachedAccountFingerprint = if (o.has("cachedAccountFingerprint"))
                o.optStringOrNull("cachedAccountFingerprint") else null,
            cachedAccountXpub = if (o.has("cachedAccountXpub"))
                o.optStringOrNull("cachedAccountXpub") else null,
            // Accept native object {"ref":..} AND iOS bare-string "hostEntry" so a
            // cross-device backup keeps the Trezor passphrase marker (ADR-0035).
            hidden = o.optJSONObject("hidden")
                ?: o.optString("hidden", "").takeIf { it.isNotEmpty() }?.let { JSONObject().put("ref", it) },
            derivationPath = if (o.has("derivationPath"))
                o.optStringOrNull("derivationPath") else null,
        )
    }
}

/** The on-disk SQLite path for a wallet's BDK state. One database per
 *  wallet so concurrent syncs do not contend on the lock. Mirrors iOS
 *  `Documents/networks/bitcoin/<wallet-id>/wallet.sqlite`; on Android the
 *  caller supplies the app files dir (e.g. `context.filesDir`). */
object BitcoinWalletPaths {
    /** `<filesDir>/networks/bitcoin/<wallet-id>/wallet.sqlite`. Creates
     *  the parent directory if missing. */
    fun databaseFilePath(filesDirPath: String, walletId: UUID): String {
        val dir = java.io.File(filesDirPath)
            .resolve("networks")
            .resolve("bitcoin")
            .resolve(walletId.toString())
        if (!dir.exists()) dir.mkdirs()
        return dir.resolve("wallet.sqlite").absolutePath
    }
}
