// Two flavours of Ethereum wallet, mirroring EthereumWalletKind.swift.
//
//   Software(account)
//     Derives the BIP44 EOA address from the holder's BIP-39 seed at
//     m/44'/60'/<account>'/0/0. Multiple accounts coexist from one seed.
//
//   Hardware(deviceId, account, address)
//     Address fetched from a paired Ledger / Trezor at pairing time; the
//     private key never leaves the device, signing routes over BLE.

package com.elabify.musnad.wallet.ethereum

import java.util.UUID
import org.json.JSONObject

sealed interface EthereumWalletKind {
    data class Software(val account: Long) : EthereumWalletKind
    data class Hardware(val deviceId: UUID, val account: Long, val address: String) : EthereumWalletKind

    fun toJson(): JSONObject = when (this) {
        is Software -> JSONObject().put("type", "software").put("account", account)
        is Hardware -> JSONObject()
            .put("type", "hardware")
            .put("deviceId", deviceId.toString())
            .put("account", account)
            .put("address", address)
    }

    /** Stable string used for dedupe keys (mirrors the Swift `\(kind)` interp). */
    fun stableKey(): String = when (this) {
        is Software -> "software($account)"
        is Hardware -> "hardware($deviceId,$account,$address)"
    }

    companion object {
        fun fromJson(o: JSONObject): EthereumWalletKind {
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
            address = o.getString("address"),
        )
    }
}
