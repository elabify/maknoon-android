// Three-tier EIP-1559 gas estimator. 1:1 port of EthereumGasEstimator.swift.
// Pulls next-block base fee + the network's priority-fee suggestion from the
// RPC, then produces Slow / Standard / Fast cuts.
//
// Multipliers (tuned for a holder app, not an HFT trader):
//   tip:     slow 0.8x, standard 1.0x, fast 1.5x
//   maxFee:  slow 1.25x baseFee + tip, standard 2.0x + tip, fast 3.0x + tip

package com.elabify.musnad.wallet.ethereum

import java.math.BigDecimal
import java.math.RoundingMode

object EthereumGasEstimator {

    enum class Tier(val label: String) {
        SLOW("Slow"),
        STANDARD("Standard"),
        FAST("Fast"),
    }

    data class Estimate(
        val tier: Tier,
        val baseFeePerGas: EthereumWeiValue,
        val maxPriorityFeePerGas: EthereumWeiValue,
        val maxFeePerGas: EthereumWeiValue,
    )

    /** Pull baseFee + tip from RPC, return all three tiers. */
    fun estimate(client: EthereumRPCClient): List<Estimate> {
        val baseFee = client.nextBlockBaseFee()
        val tip: EthereumWeiValue = try {
            client.maxPriorityFeePerGas()
        } catch (_: Exception) {
            // Some chains reject eth_maxPriorityFeePerGas. 2 gwei is a floor.
            EthereumWeiValue.fromGwei("2") ?: EthereumWeiValue.ZERO
        }
        return tiers(baseFee, tip)
    }

    /** Convenience: resolve a client from an RPC URL then estimate. */
    fun estimate(rpcURL: String): List<Estimate> {
        val client = EthereumRPCClient.orNull(rpcURL)
            ?: throw EthereumRPCException(EthereumRPCException.MALFORMED, "Bad RPC URL")
        return estimate(client)
    }

    private fun tiers(baseFee: EthereumWeiValue, tip: EthereumWeiValue): List<Estimate> {
        val slowTip = scale(tip, 80)
        val stdTip = tip
        val fastTip = scale(tip, 150)

        val slowMax = scale(baseFee, 125) + slowTip
        val stdMax = scale(baseFee, 200) + stdTip
        val fastMax = scale(baseFee, 300) + fastTip

        return listOf(
            Estimate(Tier.SLOW, baseFee, slowTip, slowMax),
            Estimate(Tier.STANDARD, baseFee, stdTip, stdMax),
            Estimate(Tier.FAST, baseFee, fastTip, fastMax),
        )
    }

    /** Multiply a wei value by an integer percentage (80 = x0.8). */
    private fun scale(v: EthereumWeiValue, percent: Int): EthereumWeiValue {
        val scaled = v.decimal.multiply(BigDecimal(percent)).divide(BigDecimal(100), 0, RoundingMode.DOWN)
        return EthereumWeiValue.fromDecimal(scaled)
    }
}
