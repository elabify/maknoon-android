// EVM payment leg for Maknoon Pay (ADR-0031). Android port of
// CommerceEVMPayment.swift. The holder assembles + signs a raw EIP-1559
// transaction; the merchant broadcasts it (offline-capable: the holder can sign
// with a cached nonce/fee and the merchant broadcasts when next online).
//
// This is a thin orchestrator over the EXISTING send-path primitives so there
// is exactly one Ethereum signing path:
//   EthereumWeiValue.fromUnits   precise decimal -> smallest-unit parse
//   EthereumRPCClient            nonce / fees / gas estimate / broadcast
//   EthereumTxPlan / payload     EIP-1559 plan the descriptors signer consumes
//   EthereumABI.transferData     ERC-20 transfer calldata, for gas estimation
// The actual signing (software sandwich or hardware) lives behind
// CommerceHolderContext.signEvmTransfer so the commerce layer never touches keys.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.wallet.ethereum.EthereumABI
import com.elabify.musnad.wallet.ethereum.EthereumRPCClient
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue

class CommerceEVMPaymentException(override val message: String) : Exception(message) {
    companion object {
        fun badAmount() = CommerceEVMPaymentException("Payment amount is not a positive number.")
        fun badRPCURL() = CommerceEVMPaymentException("The network RPC URL is invalid.")
    }
}

object CommerceEVMPayment {

    /**
     * An EVM asset on a chain. [contract] == null means the native coin (ETH);
     * otherwise it is an ERC-20 contract (e.g. USDC). [decimals]: ETH=18, USDC=6.
     */
    data class Asset(val symbol: String, val contract: String?, val decimals: Int) {
        val isNative: Boolean get() = contract == null

        companion object {
            val ETH = Asset(symbol = "ETH", contract = null, decimals = 18)
        }
    }

    /**
     * Parse a human decimal amount into the asset's smallest unit (wei / token
     * base units). Throws on non-positive or unparseable input.
     */
    fun smallestUnits(amount: String, asset: Asset): EthereumWeiValue {
        val v = EthereumWeiValue.fromUnits(amount, asset.decimals)
        if (v == null || !(v > EthereumWeiValue.ZERO)) throw CommerceEVMPaymentException.badAmount()
        return v
    }

    /**
     * Assemble the signer plan for (recipient, value, asset) given resolved
     * chain params. Native: toAddress = recipient. ERC-20: toAddress = the token
     * contract and the recipient rides in the transfer(to,amount) calldata (the
     * encoder forces the tx value to zero for ERC-20).
     */
    fun plan(
        chainId: Long,
        nonce: Long,
        recipient: String,
        value: EthereumWeiValue,
        asset: Asset,
        gasLimit: Long,
        maxFeePerGas: EthereumWeiValue,
        maxPriorityFeePerGas: EthereumWeiValue,
    ): EthereumTxPlan {
        val contract = asset.contract
        return if (contract != null) {
            EthereumTxPlan(
                chainId = chainId, nonce = nonce, toAddress = contract, value = value,
                gasLimit = gasLimit, maxFeePerGas = maxFeePerGas,
                maxPriorityFeePerGas = maxPriorityFeePerGas,
                payload = EthereumTxPlan.Payload.Erc20(recipient = recipient),
            )
        } else {
            EthereumTxPlan(
                chainId = chainId, nonce = nonce, toAddress = recipient, value = value,
                gasLimit = gasLimit, maxFeePerGas = maxFeePerGas,
                maxPriorityFeePerGas = maxPriorityFeePerGas,
                payload = EthereumTxPlan.Payload.Native,
            )
        }
    }

    /**
     * Resolve chainId + nonce + fees + gas via RPC and assemble the unsigned
     * EIP-1559 plan, WITHOUT signing. Shared by the software (sandwich) and
     * hardware (Ledger) signing paths so both estimate fees identically.
     * Blocking; call off the main thread.
     */
    fun buildPlan(
        from: String,
        rpcURLString: String,
        recipient: String,
        amount: String,
        asset: Asset,
    ): EthereumTxPlan {
        val rpc = EthereumRPCClient.orNull(rpcURLString) ?: throw CommerceEVMPaymentException.badRPCURL()
        val value = smallestUnits(amount, asset)
        val chainId = rpc.chainId()
        val nonce = rpc.transactionCount(from, block = "pending")
        val priority = rpc.maxPriorityFeePerGas()
        val baseFee = rpc.nextBlockBaseFee()
        // 2x base fee headroom + the priority tip, the standard EIP-1559 ceiling.
        val maxFee = baseFee + baseFee + priority

        val estTo = asset.contract ?: recipient
        val estValue = if (asset.isNative) value else EthereumWeiValue.ZERO
        val estData = if (asset.isNative) null else EthereumABI.transferData(recipient, value)
        val fallbackGas: Long = if (asset.isNative) 21_000 else 90_000
        val gasLimit = try { rpc.estimateGas(from, estTo, estValue, estData) } catch (_: Exception) { fallbackGas }

        return plan(
            chainId = chainId, nonce = nonce, recipient = recipient, value = value,
            asset = asset, gasLimit = gasLimit, maxFeePerGas = maxFee,
            maxPriorityFeePerGas = priority,
        )
    }

    /** Merchant broadcasts the holder's signed transaction. Returns the tx hash. Blocking. */
    fun broadcast(rawHex: String, rpcURLString: String): String {
        val rpc = EthereumRPCClient.orNull(rpcURLString) ?: throw CommerceEVMPaymentException.badRPCURL()
        return rpc.sendRawTransaction(rawHex)
    }
}
