// EIP-681 payment-URI parser. A scanned or pasted "ethereum:" URI can be a plain
// address, a native-value request, or a token transfer. The token-transfer form
//
//   ethereum:0x<TOKEN>@<chainId>/transfer?address=0x<RECIPIENT>&uint256=<amount>
//
// puts the TOKEN CONTRACT in the path and the real recipient in the `address`
// query param. Keeping only the path target would set the recipient to the token
// contract, so a token transfer would go to the contract instead of the
// recipient. This parser pulls the fields apart correctly. Mirrors the iOS
// EthereumUriParser.

package com.elabify.app.maknoon.ui.wallet.ethereum

import java.net.URLDecoder

/**
 * A parsed EIP-681 URI. [recipient] is always the address the funds should go
 * to (the `address` query param for a token transfer, otherwise the path
 * target). [tokenContract] is non-null only for a `/transfer` (ERC-20) request.
 * [amountBaseUnits] is the raw on-chain amount (uint256 for a token transfer,
 * value in wei for a native request), or null when unspecified. [chainId] is the
 * EIP-155 id from the `@<chainId>` hint: the send screen compares it against the
 * wallet's active network BEFORE looking the token contract up, because the same
 * address means different tokens on different chains, so probing the wrong chain
 * can match something unrelated. Null when absent or not a plain integer.
 */
data class ParsedEthUri(
    val recipient: String,
    val tokenContract: String?,
    val amountBaseUnits: String?,
    val chainId: Long?,
)

object EthereumUriParser {
    fun parse(raw: String): ParsedEthUri {
        var cleaned = raw.trim()
        if (cleaned.lowercase().startsWith("ethereum:")) cleaned = cleaned.substring("ethereum:".length)

        // Split off the query string at the first '?'.
        val qIndex = cleaned.indexOf('?')
        val beforeQuery = if (qIndex >= 0) cleaned.substring(0, qIndex) else cleaned
        val query = parseQuery(if (qIndex >= 0) cleaned.substring(qIndex + 1) else "")

        // From the part before '?': split off "/function" at the first '/'.
        val slashIndex = beforeQuery.indexOf('/')
        val function = if (slashIndex >= 0) beforeQuery.substring(slashIndex + 1) else null
        val beforeFunction = if (slashIndex >= 0) beforeQuery.substring(0, slashIndex) else beforeQuery

        // Split "@chainId" at the first '@'; the remainder is the path target.
        val atIndex = beforeFunction.indexOf('@')
        val target = (if (atIndex >= 0) beforeFunction.substring(0, atIndex) else beforeFunction).trim()
        val chainId = if (atIndex >= 0) beforeFunction.substring(atIndex + 1).trim().toLongOrNull() else null

        return if (function == "transfer") {
            ParsedEthUri(
                recipient = query["address"] ?: "",
                tokenContract = target,
                amountBaseUnits = query["uint256"],
                chainId = chainId,
            )
        } else {
            ParsedEthUri(
                recipient = target,
                tokenContract = null,
                amountBaseUnits = query["value"],
                chainId = chainId,
            )
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            out[pair.substring(0, eq)] = urlDecode(pair.substring(eq + 1))
        }
        return out
    }

    private fun urlDecode(value: String): String =
        try { URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
}

/**
 * Safety guard for token sends. Pure + unit-testable (the send screen computes
 * its inputs and calls this). Sending an ERC-20 to a contract address (its own
 * contract, another token's contract, or any address that has bytecode) sends
 * the tokens to that contract rather than a person, so the send path blocks it.
 * Native ETH sends to a contract are legitimate and never blocked here. Mirrors
 * iOS EthereumSendGuard.
 *
 * @param recipient the resolved 0x recipient address.
 * @param isTokenSend true when an ERC-20 token (not native ETH) is selected.
 * @param selectedTokenContract the selected token's own contract, if any.
 * @param knownTokenContracts every ERC-20 contract configured for this (wallet, chain).
 * @param recipientHasCode eth_getCode found bytecode at the recipient.
 */
fun tokenSendBlocksContract(
    recipient: String,
    isTokenSend: Boolean,
    selectedTokenContract: String?,
    knownTokenContracts: List<String>,
    recipientHasCode: Boolean,
): Boolean {
    if (!isTokenSend) return false
    if (recipient.isBlank()) return false
    if (selectedTokenContract != null && recipient.equals(selectedTokenContract, ignoreCase = true)) return true
    if (knownTokenContracts.any { it.equals(recipient, ignoreCase = true) }) return true
    return recipientHasCode
}
