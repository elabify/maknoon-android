// "payment" namespace handler (window.maknoon.payment.receive). Android port of
// PaymentBridgeHandler.swift, plus the coordinator + request model the sheet reads.
//
// receive({ chain, network, address, amount, fiatText? }) opens a native sheet
// that shows a per-chain payment-request QR (PaymentURI) with the crypto + fiat
// totals, watches the receiving address on-chain, and resolves when the incoming
// transfer lands. The merchant receives funds directly to their own
// (address-book) address; nothing is signed here. Returns
// { txHash|null, bolt11|null, chain, network, amount, confirmedAt } to the dapp.
//
// payment.lightningAccounts lists the user's Lightning accounts (id + label) so
// the dApp can offer a picker, sourced through the holder context (the lightning
// store lives in the wallet slice).

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.tron.TronNetwork
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** A Lightning account as the dApp picker sees it. */
data class CommerceLightningAccount(val id: String, val label: String)

/** Reads native-coin balances per chain for balance-delta payment detection. */
fun interface PaymentBalanceReader {
    /** Whole-unit native balance for [address] on [chain]/[networkRaw], or null on error. */
    fun balance(chain: String, networkRaw: String?, address: String): BigDecimal?
}

/**
 * A ready-to-render receive request. Pure data so PaymentBridgeHandler.build is
 * unit-testable. [amount] is on-chain native units (or sats for Lightning).
 */
data class MiniAppPaymentRequest(
    val appTitle: String,
    val chain: String,
    val networkRaw: String?,
    val networkDisplay: String,
    val address: String,
    val amount: BigDecimal,
    val ticker: String,
    val uri: String,          // on-chain payment URI; empty for lightning
    val fiatText: String?,
    val isLightning: Boolean = false,
)

/**
 * Side-table for the live receive request, mirroring MiniAppCommerceCoordinator.
 * The handler stashes a request, puts the token in the gate payload, and the
 * sheet looks it up to drive QR + balance watching.
 */
class MiniAppPaymentCoordinator {
    private val pending = ConcurrentHashMap<String, MiniAppPaymentRequest>()

    fun stash(request: MiniAppPaymentRequest): String {
        val token = java.util.UUID.randomUUID().toString()
        pending[token] = request
        return token
    }

    fun peek(token: String): MiniAppPaymentRequest? = pending[token]
    fun take(token: String): MiniAppPaymentRequest? = pending.remove(token)
}

class PaymentBridgeHandler(
    private val appTitle: String,
    private val coordinator: MiniAppPaymentCoordinator,
    private val gate: ApprovalGate,
    /** Lightning accounts for the picker (wallet slice); empty when none. */
    private val lightningAccounts: () -> List<CommerceLightningAccount> = { emptyList() },
) : MiniAppNamespaceHandler {

    override val namespace = "payment"
    override val requiredPermission: String? = "payment"

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "payment.lightningAccounts" -> {
            val arr = JSONArray()
            lightningAccounts().forEach { arr.put(JSONObject().put("id", it.id).put("label", it.label)) }
            arr.toString()
        }
        "payment.receive" -> receive(argsJson)
        else -> throw MiniAppBridgeError.unsupported("payment.$method")
    }

    private suspend fun receive(argsJson: String): String {
        val p = try { JSONObject(argsJson) } catch (_: Exception) { null }
            ?: throw MiniAppBridgeError.invalidParams("payment.receive requires { chain, amount }")
        val chain = p.optStringOrNull("chain")?.lowercase()
            ?: throw MiniAppBridgeError.invalidParams("payment.receive requires { chain, amount }")

        // Lightning receives into a chosen Lightning account (its UUID passed as
        // `account`; empty falls back to the active one) and needs no address;
        // every on-chain chain requires an address.
        val isLightning = chain == "lightning" || chain == "ln"
        val address = if (isLightning) (p.optStringOrNull("account") ?: "") else (p.optStringOrNull("address") ?: "")
        if (!isLightning && address.isEmpty()) {
            throw MiniAppBridgeError.invalidParams("payment.receive requires `address`")
        }
        val networkRaw = p.optStringOrNull("network")
        val fiatText = p.optStringOrNull("fiatText")
        val amountStr = p.optStringOrNull("amount") ?: "0"
        val amount = amountStr.toBigDecimalOrNull()
        if (amount == null || amount.signum() <= 0) {
            throw MiniAppBridgeError.invalidParams("payment.receive requires a positive `amount`")
        }

        val built = build(chain, networkRaw, address, amount, fiatText, appTitle)
        val token = coordinator.stash(built)
        val payload = JSONObject().put("token", token).put("appTitle", appTitle).toString()
        // Suspends until the sheet approves with the receive verdict, or cancels.
        return gate.request(kind = "payment", payloadJson = payload, appTitle = appTitle)
    }

    companion object {
        /**
         * Resolve chain + network into a ready-to-render payment request. Pure
         * (no store access) so it's unit-testable.
         */
        fun build(
            chain: String,
            networkRaw: String?,
            address: String,
            amount: BigDecimal,
            fiatText: String?,
            appTitle: String,
        ): MiniAppPaymentRequest {
            // Lightning: no address/URI here. The sheet creates a BOLT11 invoice
            // on the active account; `amount` is in sats. `address` carries the
            // chosen Lightning account UUID (empty -> active).
            if (chain == "lightning" || chain == "ln") {
                return MiniAppPaymentRequest(
                    appTitle = appTitle, chain = "lightning", networkRaw = networkRaw,
                    networkDisplay = "Bitcoin Lightning", address = address, amount = amount,
                    ticker = "sats", uri = "", fiatText = fiatText, isLightning = true,
                )
            }

            val uri: String
            val ticker: String
            val networkDisplay: String

            when (chain) {
                "ethereum", "evm", "eth" -> {
                    val net = networkRaw?.let { EthereumNetwork.fromRawValue(it) } ?: EthereumNetwork.MAINNET
                    ticker = net.ticker
                    networkDisplay = net.displayName
                    uri = PaymentURI.Ethereum(address, net.chainId, weiString(amount)).string
                }
                "bitcoin", "btc" -> {
                    val net = networkRaw?.let { BitcoinNetwork.fromRawValue(it) } ?: BitcoinNetwork.MAINNET
                    ticker = net.ticker
                    networkDisplay = net.displayName
                    uri = PaymentURI.Bitcoin(address, amount).string
                }
                "solana", "sol" -> {
                    val net = networkRaw?.let { SolanaNetwork.fromRawValue(it) } ?: SolanaNetwork.MAINNET
                    ticker = "SOL"
                    networkDisplay = net.displayName
                    uri = PaymentURI.Solana(address, amount).string
                }
                "tron", "trx" -> {
                    val net = networkRaw?.let { TronNetwork.fromRawValue(it) } ?: TronNetwork.MAINNET
                    ticker = "TRX"
                    networkDisplay = net.displayName
                    uri = PaymentURI.Tron(address, amount).string
                }
                else -> throw MiniAppBridgeError.invalidParams("unknown chain '$chain'")
            }

            return MiniAppPaymentRequest(
                appTitle = appTitle, chain = chain, networkRaw = networkRaw,
                networkDisplay = networkDisplay, address = address, amount = amount,
                ticker = ticker, uri = uri, fiatText = fiatText,
            )
        }

        /** Decimal ETH -> integer wei string for EIP-681. */
        private fun weiString(eth: BigDecimal): String =
            eth.movePointRight(18).setScale(0, RoundingMode.DOWN).toBigInteger().toString()
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? =
    try { BigDecimal(this.trim()) } catch (_: NumberFormatException) { null }
