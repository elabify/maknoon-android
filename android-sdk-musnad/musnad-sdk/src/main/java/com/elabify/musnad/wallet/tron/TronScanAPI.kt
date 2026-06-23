// TronScan account-index API, ported from iOS TronScanAPI.swift.
// Independent of the TronGrid RPC the rest of the Tron stack uses.
// One job: auto-discovering the TRC-20 contracts a given address holds,
// so the dashboard can reconcile that list against the verified catalog
// without making the user paste each contract.
//
// Mainnet-only. TronScan does not publish a Shasta / Nile-equivalent API
// host, so the dashboard skips discovery on testnets and users add
// testnet tokens manually via the Add Token sheet.

package com.elabify.musnad.wallet.tron

import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException

object TronScanAPI {

    /** One held TRC-20 row from the TronScan account index. */
    data class HeldTRC20(
        val contract: String, // T-prefixed base58
        val symbol: String,
        val name: String,
        val decimals: Int,
        val amount: String, // raw on-chain integer, base-10 string
        val logoURL: String?,
    )

    /** Walk a wallet's TRC-20 holdings via TronScan's `/api/account`
     *  endpoint. Returns the parsed list with raw amounts; the caller
     *  decides whether to auto-install each against the verified catalog
     *  or surface it as Unknown. Mainnet-only. */
    fun discoverHeldTRC20(
        addressBase58: String,
        http: MaknoonHttp = MaknoonHttp(),
    ): List<HeldTRC20> {
        val url = "https://apilist.tronscanapi.com/api/account" +
            "?address=$addressBase58&showAssetList=true"
        val body = try {
            http.getJson(url)
        } catch (e: NetworkException) {
            throw TronRPCException("HTTP ${e.status} from TronScan")
        } catch (e: Exception) {
            throw TronRPCException("Tron RPC transport error: ${e.message}")
        }
        val o = try {
            org.json.JSONObject(body)
        } catch (e: Exception) {
            throw TronRPCException("TronScan account JSON: ${e.message}")
        }
        val rows = o.optJSONArray("trc20token_balances") ?: return emptyList()
        val out = ArrayList<HeldTRC20>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val contract = row.optString("tokenId", "").takeIf { it.isNotEmpty() } ?: continue
            val amount = row.optString("balance", "").takeIf { it.isNotEmpty() } ?: continue
            val symbol = row.optString("tokenAbbr", "").takeIf { it.isNotEmpty() } ?: continue
            val name = row.optString("tokenName", "").takeIf { it.isNotEmpty() } ?: continue
            val decimals = optTolerantInt(row, "tokenDecimal") ?: 0
            val logo = if (row.has("tokenLogo") && !row.isNull("tokenLogo")) row.optString("tokenLogo") else null
            out.add(
                HeldTRC20(
                    contract = contract,
                    symbol = symbol,
                    name = name,
                    decimals = decimals.coerceIn(0, 255),
                    amount = amount,
                    logoURL = logo,
                )
            )
        }
        return out
    }

    private fun optTolerantInt(o: org.json.JSONObject, key: String): Int? =
        when (val v = o.opt(key)) {
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
}
