// Single-confirm "Verify & Pay" (ADR-0031). Android port of CommercePaySheet.swift.
//
// The payer scans the merchant's short URL (from Identity -> Scan verifier), and
// this one sheet shows: the merchant (a Point of Sale), the requested identity
// fields + the matching VC, the amount/asset/network, and a proposal of the
// payer's wallets with balances that can pay. One Confirm discloses the identity
// AND signs + broadcasts the payment, then posts the (sealed) response for the
// merchant to poll.
//
// P1: EVM software wallets are payable. Hardware + non-EVM wallets are shown but
// flagged "not yet payable on this device" by the wallet slice's signEvmTransfer.
//
// STRUCTURE: a plain (non-Compose) CommercePayDriver holds the orchestration
// (validate, match, enumerate, balances, confirm, seal, broadcast); the
// composable is stateless and renders driver state through callbacks, matching
// the foundation rule "keep sheets stateless with callbacks".

package com.elabify.app.maknoon.ui.miniapp

import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.CommerceBitcoinPayment
import com.elabify.app.maknoon.miniapp.CommerceEVMPayment
import com.elabify.app.maknoon.miniapp.CommerceSolanaPayment
import com.elabify.app.maknoon.miniapp.CommerceTronPayment
import com.elabify.app.maknoon.miniapp.CommerceHolderContext
import com.elabify.app.maknoon.miniapp.CommerceRequest
import com.elabify.app.maknoon.miniapp.CommerceRequestValidator
import com.elabify.app.maknoon.miniapp.CommerceSeal
import com.elabify.app.maknoon.miniapp.CommerceServerResponse
import com.elabify.app.maknoon.miniapp.CommerceTransport
import com.elabify.app.maknoon.miniapp.CommerceTransportException
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumToken
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.musnad.wallet.lightning.LightningAccount
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronWalletDescriptor
import com.elabify.musnad.wallet.tron.TronWalletKind
import java.util.UUID
import org.json.JSONObject

/** A payer wallet, tagged by chain. EVM carries its Ethereum descriptor +
 *  network + asset; Solana carries its Solana descriptor + network + mint. */
sealed interface PayWallet {
    data class Eth(
        val descriptor: EthereumWalletDescriptor,
        val network: EthereumNetwork,
        val asset: CommerceEVMPayment.Asset,
    ) : PayWallet
    data class Sol(
        val descriptor: com.elabify.musnad.wallet.solana.SolanaWalletDescriptor,
        val network: com.elabify.musnad.wallet.solana.SolanaNetwork,
        val mint: String?,
        val decimals: Int,
    ) : PayWallet
    data class Trx(
        val descriptor: TronWalletDescriptor,
        val network: TronNetwork,
        val tokenContract: String?,
        val decimals: Int,
    ) : PayWallet
    data class Btc(
        val descriptor: BitcoinWalletDescriptor,
        val network: BitcoinNetwork,
    ) : PayWallet
    data class Lnx(
        val account: LightningAccount,
    ) : PayWallet
}

/** A payer wallet that could fund a published rail. */
data class CommercePayCandidate(
    val id: UUID,
    val wallet: PayWallet,
    val rail: com.elabify.app.maknoon.miniapp.PaymentRail,
    val label: String,
    val networkLabel: String,
    val assetSymbol: String,
    /** false => also show a separate gas line (paying a token). */
    val assetIsNative: Boolean,
    var balanceText: String = "...",
    var gasBalanceText: String = "...",
    var sufficient: Boolean = false,
    var payable: Boolean = true,
    var note: String? = null,
)

/** Trust styling for the merchant header. */
data class CommercePayTrust(val label: String, val tier: CommerceRequestValidator.Tier, val blocked: String?)

/**
 * Orchestration for the payer "Verify & Pay" flow. Blocking methods run on
 * Dispatchers.IO from the host; the composable reads the resulting state.
 */
class CommercePayDriver(
    private val ctx: CommerceHolderContext,
    private val request: CommerceRequest,
    private val responseBaseURL: String,
    private val transport: CommerceTransport = CommerceTransport(),
) {
    private val requiredClaims: List<String> get() = request.requiredClaims
    private val terms get() = request.paymentTerms

    /** Authenticate the request + the merchant's signature over the terms.
     *  [res] resolves the localized tier label / block reason. */
    fun authenticate(res: android.content.Context): CommercePayTrust {
        val v = CommerceRequestValidator.validate(request, ctx, res)
        return CommercePayTrust(v.tierLabel, v.tier, if (v.ok) null else v.reason)
    }

    /** The matched disclosable credential JSON, or null when none qualifies. */
    fun matchCredential(): JSONObject? = ctx.matchCredential(request.verifierRequest)

    /** Display text for a required claim of [credential] (handles sdnScreen). */
    fun claimText(credential: JSONObject?, key: String): String =
        if (credential == null) "" else ctx.claimDisplayText(credential, key)

    /** Build candidates: each accepted rail x each holder wallet on that chain. */
    fun buildCandidates(): List<CommercePayCandidate> {
        val built = mutableListOf<CommercePayCandidate>()
        for (rail in terms.acceptedRails) {
            when (rail.chain) {
                "ethereum" -> {
                    val net = EthereumNetwork.fromRawValue(rail.network ?: "") ?: continue
                    val asset = CommerceEVMPayment.Asset(
                        symbol = rail.asset, contract = rail.assetContract, decimals = rail.assetDecimals ?: 18,
                    )
                    for (desc in ctx.ethereumWallets()) {
                        if (desc.address.isNullOrEmpty()) continue
                        val note = (desc.kind as? EthereumWalletKind.Hardware)?.let { "Signs on your hardware device" }
                        built.add(
                            CommercePayCandidate(
                                id = desc.id, wallet = PayWallet.Eth(desc, net, asset), rail = rail,
                                label = desc.label, networkLabel = net.displayName, assetSymbol = rail.asset,
                                assetIsNative = asset.contract == null, payable = true, note = note,
                            ),
                        )
                    }
                }
                "solana" -> {
                    val net = com.elabify.musnad.wallet.solana.SolanaNetwork.fromRawValue(rail.network ?: "") ?: continue
                    val decimals = rail.assetDecimals ?: (if (rail.assetContract == null) 9 else 6)
                    for (desc in ctx.solanaWallets()) {
                        // Software + hardware (Ledger/Trezor) both pay: hardware
                        // signs on-device over BLE (see confirmAndPay).
                        val isHardware = desc.kind is com.elabify.musnad.wallet.solana.SolanaWalletKind.Hardware
                        built.add(
                            CommercePayCandidate(
                                id = desc.id, wallet = PayWallet.Sol(desc, net, rail.assetContract, decimals),
                                rail = rail, label = desc.label, networkLabel = net.displayName,
                                assetSymbol = rail.asset, assetIsNative = rail.assetContract == null,
                                payable = true,
                                note = if (isHardware) "Signs on your hardware device" else null,
                            ),
                        )
                    }
                }
                "tron" -> {
                    val net = TronNetwork.fromRawValue(rail.network ?: "") ?: continue
                    val decimals = rail.assetDecimals ?: 6
                    for (desc in ctx.tronWallets()) {
                        // Software + hardware (Ledger/Trezor) both pay: hardware
                        // signs raw_data on-device over BLE (see confirmAndPay).
                        val isHardware = desc.kind is TronWalletKind.Hardware
                        built.add(
                            CommercePayCandidate(
                                id = desc.id, wallet = PayWallet.Trx(desc, net, rail.assetContract, decimals),
                                rail = rail, label = desc.label, networkLabel = net.displayName,
                                assetSymbol = rail.asset, assetIsNative = rail.assetContract == null,
                                payable = true,
                                note = if (isHardware) "Signs on your hardware device" else null,
                            ),
                        )
                    }
                }
                "bitcoin" -> {
                    val net = BitcoinNetwork.fromRawValue(rail.network ?: "") ?: continue
                    for (desc in ctx.bitcoinWallets()) {
                        // Only wallets on the rail's network can pay.
                        if (desc.network != net) continue
                        // Software + hardware (Ledger/Trezor) both pay: hardware
                        // signs the PSBT on-device over BLE (see confirmAndPay).
                        val isHardware = desc.kind is BitcoinWalletKind.Hardware
                        built.add(
                            CommercePayCandidate(
                                id = desc.id, wallet = PayWallet.Btc(desc, net), rail = rail,
                                label = desc.label, networkLabel = net.displayName,
                                assetSymbol = rail.asset, assetIsNative = true,
                                payable = true,
                                note = if (isHardware) "Signs on your hardware device" else null,
                            ),
                        )
                    }
                }
                "lightning" -> {
                    // Custodial LNDHub: any holder Lightning account can pay the
                    // merchant-minted BOLT11 carried in rail.address.
                    for (acct in ctx.lightningAccounts()) {
                        built.add(
                            CommercePayCandidate(
                                id = acct.id, wallet = PayWallet.Lnx(acct), rail = rail,
                                label = acct.label, networkLabel = "Lightning",
                                assetSymbol = rail.asset.ifEmpty { "sats" }, assetIsNative = true,
                                payable = true, note = null,
                            ),
                        )
                    }
                }
                else -> continue
            }
        }
        return built
    }

    /** Read each candidate's balance + sufficiency. Mutates the list in place. */
    fun fetchBalances(candidates: List<CommercePayCandidate>) {
        for (c in candidates) fetchBalance(c)
    }

    /** Read ONE candidate's balance + sufficiency, mutating it in place. Split
     *  out so the host can fetch candidates concurrently and update the UI
     *  incrementally (per-wallet) instead of all-at-once after a slow serial
     *  sweep. Blocking; call off the main thread. */
    fun fetchBalance(c: CommercePayCandidate) {
        try {
            when (val w = c.wallet) {
                is PayWallet.Eth -> {
                    val rpc = ctx.ethereumRpcURL(w.network)
                    val wallet = EthereumWallet(w.descriptor)
                    // Native (gas) balance always read so the payer can see they
                    // can cover fees, even when paying an ERC-20.
                    val native = wallet.balance(rpc)
                    c.gasBalanceText = native.displayUnits(w.network.ticker, 18, maxDecimals = 6)
                    val bal: EthereumWeiValue = if (w.asset.contract != null) {
                        val token = EthereumToken(
                            network = w.network, contractAddress = w.asset.contract,
                            symbol = w.asset.symbol, name = w.asset.symbol,
                            decimals = w.asset.decimals, curated = false,
                        )
                        wallet.tokenBalance(token, rpc)
                    } else {
                        native
                    }
                    c.balanceText = bal.displayUnits(w.asset.symbol, w.asset.decimals, maxDecimals = 6)
                    val need = c.rail.amount?.let { EthereumWeiValue.fromUnits(it, w.asset.decimals) }
                    c.sufficient = need != null && !(bal < need)
                }
                is PayWallet.Sol -> {
                    val rpc = ctx.solanaRpcURL(w.network)
                    val bals = ctx.solanaBalance(w.descriptor, w.network, rpc, w.mint)
                    c.gasBalanceText = fmtSol(bals.lamports.toDouble() / 1_000_000_000.0, "SOL")
                    if (w.mint != null) {
                        val raw = bals.tokenRaw ?: 0L
                        c.balanceText = fmtSol(raw.toDouble() / Math.pow(10.0, w.decimals.toDouble()), c.assetSymbol)
                        val need = c.rail.amount?.let { runCatching { CommerceSolanaPayment.baseUnits(it, w.decimals) }.getOrNull() }
                        c.sufficient = need != null && raw >= need
                    } else {
                        c.balanceText = fmtSol(bals.lamports.toDouble() / 1_000_000_000.0, "SOL")
                        val need = c.rail.amount?.let { runCatching { CommerceSolanaPayment.baseUnits(it, 9) }.getOrNull() }
                        c.sufficient = need != null && bals.lamports >= need
                    }
                }
                is PayWallet.Trx -> {
                    val rpc = ctx.tronRpcURL(w.network)
                    val bals = ctx.tronBalance(w.descriptor, w.network, rpc, w.tokenContract)
                    c.gasBalanceText = fmtSol(bals.sun.toDouble() / 1_000_000.0, "TRX")
                    if (w.tokenContract != null) {
                        val raw = bals.tokenRaw ?: 0L
                        c.balanceText = fmtSol(raw.toDouble() / Math.pow(10.0, w.decimals.toDouble()), c.assetSymbol)
                        val need = c.rail.amount?.let { runCatching { CommerceTronPayment.baseUnits(it, w.decimals) }.getOrNull() }
                        c.sufficient = need != null && raw >= need
                    } else {
                        c.balanceText = fmtSol(bals.sun.toDouble() / 1_000_000.0, "TRX")
                        val need = c.rail.amount?.let { runCatching { CommerceTronPayment.baseUnits(it, 6) }.getOrNull() }
                        c.sufficient = need != null && bals.sun >= need
                    }
                }
                is PayWallet.Btc -> {
                    val sats = ctx.bitcoinBalanceSats(w.descriptor)
                    c.balanceText = fmtSol(sats.toDouble() / 100_000_000.0, "BTC")
                    c.gasBalanceText = c.balanceText
                    val need = c.rail.amount?.let { runCatching { CommerceBitcoinPayment.satsFromBTC(it) }.getOrNull() }
                    c.sufficient = need != null && sats >= need
                }
                is PayWallet.Lnx -> {
                    val sats = ctx.lightningBalanceSat(w.account)
                    c.balanceText = "$sats sats"
                    c.gasBalanceText = c.balanceText
                    // The merchant-minted BOLT11 carries the amount; compare the
                    // holder's sat balance to the ask (Lightning rail amounts are
                    // in satoshis, not BTC).
                    val need = c.rail.amount?.toDoubleOrNull()?.toLong()
                    c.sufficient = need == null || sats >= need
                }
            }
        } catch (_: Exception) {
            c.balanceText = "-"
            c.gasBalanceText = "-"
            c.sufficient = false
        }
    }

    /** Trim a Solana decimal amount to <=6 places + ticker (EVM uses displayUnits). */
    private fun fmtSol(x: Double, ticker: String): String {
        var s = String.format(Locale.US, "%.6f", x)
        if (s.contains(".")) {
            s = s.trimEnd('0').trimEnd('.')
        }
        return "$s $ticker"
    }

    /**
     * A signed-but-unsent payment: the identity disclosure + the deferred
     * on-chain broadcast + the pre-broadcast settlement ref, so the merchant
     * gets identity BEFORE money moves and the UI can show a Broadcast step
     * (hardware) between signing and sending.
     */
    class Prepared(
        val presentation: JSONObject,
        val rail: com.elabify.app.maknoon.miniapp.PaymentRail,
        val settlementRef: String,
        /** Chain key for post-pay navigation back to the wallet (rail.chain). */
        val chain: String,
        val broadcastFn: () -> String,
    )

    /**
     * Phase 1: build the presentation + sign the payment WITHOUT broadcasting
     * (so a hardware wallet's signature lands before the user taps Broadcast).
     * Blocking; the device prompts here for a hardware wallet.
     */
    fun prepare(
        candidate: CommercePayCandidate,
        matched: JSONObject,
        hostPassphrase: String? = null,
    ): Prepared {
        val amount = candidate.rail.amount
            ?: throw CommerceTransportException("Merchant did not specify an amount.")

        // 1. Identity disclosure always uses the holder's consumer sandwich.
        val presentation = ctx.buildPresentation(matched, requiredClaims.toSet(), request.verifierRequest)

        // 2-3. Sign WITHOUT broadcasting + capture the pre-broadcast settlement
        //      ref (EVM: keccak txHash; Solana: first signature), so the merchant
        //      gets identity + ref before money moves. broadcastFn defers the
        //      on-chain send to step 5.
        val settlementRef: String
        val broadcastFn: () -> String
        when (val w = candidate.wallet) {
            is PayWallet.Eth -> {
                val rpc = ctx.ethereumRpcURL(w.network)
                val rawTx = ctx.signEvmTransfer(
                    descriptor = w.descriptor, rpcURLString = rpc,
                    recipient = candidate.rail.address, amount = amount, asset = w.asset,
                    biometricReason = "Authorize $amount ${w.asset.symbol} payment",
                    hostPassphrase = hostPassphrase,
                )
                settlementRef = "0x" + ctx.keccak256(hexToBytes(rawTx.removePrefix("0x"))).toHex()
                broadcastFn = { CommerceEVMPayment.broadcast(rawTx, rpc) }
            }
            is PayWallet.Sol -> {
                val rpc = ctx.solanaRpcURL(w.network)
                val signed = ctx.signSolanaTransfer(
                    descriptor = w.descriptor, network = w.network, rpcURLString = rpc,
                    recipient = candidate.rail.address, amount = amount, mint = w.mint,
                    decimals = w.decimals,
                    biometricReason = "Authorize $amount ${candidate.assetSymbol} payment",
                    hostPassphrase = hostPassphrase,
                )
                settlementRef = signed.signatureRef
                broadcastFn = { CommerceSolanaPayment.broadcast(signed.signedBase64, rpc) }
            }
            is PayWallet.Trx -> {
                val rpc = ctx.tronRpcURL(w.network)
                val signed = ctx.signTronTransfer(
                    descriptor = w.descriptor, network = w.network, rpcURLString = rpc,
                    recipient = candidate.rail.address, amount = amount,
                    tokenContract = w.tokenContract, tokenDecimals = w.decimals,
                    biometricReason = "Authorize $amount ${candidate.assetSymbol} payment",
                    hostPassphrase = hostPassphrase,
                )
                settlementRef = signed.txID
                broadcastFn = { CommerceTronPayment.broadcast(signed.envelopeJSON, signed.signatureRSV, rpc) }
            }
            is PayWallet.Btc -> {
                val electrum = ctx.bitcoinElectrumURL(w.network)
                val signed = ctx.signBitcoinTransfer(
                    descriptor = w.descriptor, recipient = candidate.rail.address,
                    amountSat = CommerceBitcoinPayment.satsFromBTC(amount),
                    biometricReason = "Authorize $amount BTC payment",
                    hostPassphrase = hostPassphrase,
                )
                settlementRef = signed.txid
                broadcastFn = {
                    CommerceBitcoinPayment.broadcast(
                        signed.signedPSBTBase64, electrum, unsignedBase64 = signed.unsignedPSBTBase64,
                    )
                }
            }
            is PayWallet.Lnx -> {
                // No local signing. The ref is the merchant-minted BOLT11
                // (rail.address); the pay is an LNDHub call AFTER the identity
                // post. The merchant matches the invoice it issued.
                val bolt11 = candidate.rail.address
                if (bolt11.isEmpty()) {
                    throw CommerceTransportException("Merchant did not provide a Lightning invoice.")
                }
                settlementRef = bolt11
                broadcastFn = { ctx.payLightningBolt11(w.account, bolt11) }
            }
        }
        return Prepared(
            presentation = presentation, rail = candidate.rail,
            settlementRef = settlementRef, chain = candidate.rail.chain, broadcastFn = broadcastFn,
        )
    }

    /**
     * Phase 2: seal the presentation + ref to the merchant's published key and
     * POST it FIRST, then broadcast on-chain only if the identity post
     * succeeded (so the payer never pays into a void). Returns the on-chain tx
     * hash / ref. Blocking.
     */
    fun finalizeAndBroadcast(prepared: Prepared): String {
        val pub = terms.responseKey
            ?: throw CommerceTransportException("Merchant did not provide an encryption key.")
        val serverResponse = CommerceServerResponse(
            requestId = request.requestId, presentation = prepared.presentation,
            rail = prepared.rail, txHash = prepared.settlementRef,
        )
        val sealed = CommerceSeal.seal(
            json = serverResponse.toJson(), toPublicKeyBase64 = pub,
            requestId = request.requestId, senderFactory = ctx.transportSenderFactory,
        )
        transport.postResponse(responseBaseURL, sealed)
        return prepared.broadcastFn()
    }

    /** One-shot prepare + broadcast (the software one-tap path). */
    fun confirmAndPay(
        candidate: CommercePayCandidate,
        matched: JSONObject,
        hostPassphrase: String? = null,
    ): String = finalizeAndBroadcast(prepare(candidate, matched, hostPassphrase))
}

/**
 * Stateless payer sheet. The host owns a CommercePayDriver, runs its blocking
 * steps off the main thread, and feeds the rendered state in. [onConfirm] is
 * fired with the selected candidate id; [onClose] dismisses (cancel before
 * confirm rejects the originating call).
 */
@Composable
fun CommercePaySheet(
    merchantName: String,
    trust: CommercePayTrust?,
    requiredClaims: List<String>,
    claimValue: (String) -> String,
    hasMatch: Boolean,
    amountLine: String?,
    networkLine: String?,
    fiatLine: String?,
    candidates: List<CommercePayCandidate>,
    selectedId: UUID?,
    phaseLabel: String?,
    errorText: String?,
    confirmEnabled: Boolean,
    /** Hardware only: signed on-device, awaiting the explicit Broadcast tap. */
    signedAwaitingBroadcast: Boolean = false,
    /** Non-null once the payment settled: the on-chain tx ref to confirm. */
    doneTxid: String? = null,
    onSelect: (UUID) -> Unit,
    onConfirm: () -> Unit,
    onBroadcast: () -> Unit = {},
    onViewInWallet: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_verify_and_pay), style = MaterialTheme.typography.titleMedium)
        Text(merchantName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        trust?.let {
            Text(
                it.blocked ?: it.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.blocked != null) MaterialTheme.colorScheme.error
                else if (it.tier == CommerceRequestValidator.Tier.REGISTERED) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.tertiary,
            )
        }

        // Payment
        Text(stringResource(R.string.app_payment), style = MaterialTheme.typography.labelLarge)
        amountLine?.let { Text(it, fontWeight = FontWeight.SemiBold) }
        networkLine?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        fiatLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // You will share
        Text(stringResource(R.string.app_you_will_share), style = MaterialTheme.typography.labelLarge)
        when {
            !hasMatch -> Text(
                stringResource(R.string.app_no_matching_credential),
                color = MaterialTheme.colorScheme.error,
            )
            requiredClaims.isEmpty() -> Text(
                stringResource(R.string.app_no_personal_attributes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> requiredClaims.forEach { key ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(key, fontWeight = FontWeight.Medium)
                    Text(claimValue(key), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Pay from
        Text(stringResource(R.string.app_pay_from), style = MaterialTheme.typography.labelLarge)
        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.app_no_wallet_can_pay),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            candidates.forEach { c ->
                // Any wallet is selectable so the payer can inspect each one's
                // balance; the Confirm button (confirmEnabled) is what gates on
                // payable + sufficient.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selectedId == c.id, onClick = { onSelect(c.id) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedId == c.id, onClick = { onSelect(c.id) })
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(c.label, fontWeight = FontWeight.Medium)
                        // Asset balance (displayUnits already includes the ticker).
                        Text(
                            "${c.balanceText} · ${c.networkLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.sufficient) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                        // For a token rail, also show the native (gas) balance.
                        if (!c.assetIsNative) {
                            Text(
                                stringResource(R.string.app_gas_balance, c.gasBalanceText),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        c.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
            }
        }

        phaseLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        errorText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        when {
            // Settled: confirm success + go to the wallet to watch it confirm.
            doneTxid != null -> {
                Text(
                    stringResource(R.string.app_payment_sent),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.app_reference, doneTxid.take(18)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onViewInWallet, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_view_in_wallet))
                }
            }
            // Hardware: signed on-device, waiting for the explicit Broadcast tap.
            signedAwaitingBroadcast -> {
                Text(
                    stringResource(R.string.app_signed_awaiting_broadcast),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onBroadcast, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_broadcast))
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            else -> {
                Button(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_confirm_and_pay))
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}
