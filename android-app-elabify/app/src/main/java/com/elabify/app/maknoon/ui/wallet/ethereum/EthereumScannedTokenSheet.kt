// Asked when a scanned EIP-681 payment code requests an ERC-20 this wallet does
// not have on this chain. Before 0.6.9 that was a dead end ("this payment is for
// a token that is not added to this wallet"), which is wrong in the common real
// case: a Coinbase Arbitrum deposit QR names bridged USDC.e while the wallet
// holds native USDC, and the payee accepts either.
//
// So: probe the requested contract on chain (symbol / name / decimals + this
// wallet's balance), show it next to any token already held under the same
// symbol, and make the user choose. Two contracts sharing a symbol are still
// different tokens and a payee may credit only the one it asked for, so nothing
// here substitutes silently. Mirrors iOS EthereumScannedTokenSheet.

package com.elabify.app.maknoon.ui.wallet.ethereum
import com.elabify.app.maknoon.R

import androidx.compose.ui.res.stringResource

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.musnad.wallet.ethereum.EIP55
import com.elabify.musnad.wallet.ethereum.ERC20Metadata
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumNetworkID
import com.elabify.musnad.wallet.ethereum.EthereumToken
import com.elabify.musnad.wallet.ethereum.EthereumTokenLookup
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import com.elabify.musnad.wallet.ethereum.ResolvedNetwork
import com.elabify.app.maknoon.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

// User-facing copy. Kept as consts alongside TOKEN_TO_CONTRACT_ERROR in
// EthereumSendScreen.kt; these scan strings are not in strings.xml yet.
internal const val SCAN_CHAIN_UNCONFIGURED =
    "That code is for chain %d, which this wallet has no network for. Add it in Settings, Networks, Ethereum, then scan again."
internal const val SCAN_CUSTOM_NETWORK_TOKEN =
    "Token payment codes are not supported on custom networks yet."
internal const val SCAN_SUBSTITUTED_NOTE =
    "The code asked for %s at %s. You are sending %s at %s. Confirm the payee accepts this contract."
internal const val SCAN_DECIMALS_MISMATCH =
    "The requested amount was not carried over: the two tokens use different decimals."

/** A scanned code for another chain, held until the user confirms the switch. */
internal data class PendingChainSwitch(
    val chainId: Long,
    val displayName: String,
    val rawUri: String,
)

/**
 * What the send screen hands to the dialog: the token contract the code asked
 * for, plus the recipient and amount to apply once an asset is settled on.
 */
internal data class ScannedTokenRequest(
    val contract: String,
    val recipient: String,
    val amountBaseUnits: String?,
)

/**
 * What the code asked for, carried back so the send screen can warn about a
 * substitution and decide whether the requested amount is still meaningful.
 */
internal data class RequestedTokenInfo(
    val contract: String,
    val symbol: String,
    val name: String,
    val decimals: Int,
)

/**
 * The user's answer: which token to send, and (when they picked a different
 * contract than the code named) what the code had actually requested.
 */
internal data class ScannedTokenChoice(
    val token: EthereumToken,
    val substitutedFrom: RequestedTokenInfo?,
)

/** "0xFF97…5CC8", checksummed so it can be compared against a block explorer. */
internal fun shortContract(contract: String): String {
    val display = runCatching { EIP55.checksum(contract) }.getOrDefault(contract)
    return if (display.length <= 12) display else "${display.take(6)}…${display.takeLast(4)}"
}

/**
 * Display name for a chain id the user has configured, across built-in and
 * custom networks. Null when nothing is configured for it, so a scanned code can
 * never move the wallet to an endpoint the user never set up.
 */
internal fun chainDisplayName(context: Context, chainId: Long): String? {
    EthereumNetwork.fromChainId(chainId)?.let { return it.displayName }
    return EthereumStores.customs(context).networks.firstOrNull { it.chainId == chainId }?.name
}

/** The built-in chain behind a resolved network, or null for a custom one. */
internal fun ResolvedNetwork.builtinNetwork(): EthereumNetwork? =
    (networkID as? EthereumNetworkID.Builtin)?.network

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumScannedTokenSheet(
    request: ScannedTokenRequest,
    descriptor: EthereumWalletDescriptor,
    walletId: UUID,
    network: EthereumNetwork,
    resolved: ResolvedNetwork,
    added: List<EthereumToken>,
    onChoose: (ScannedTokenChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tokenStore = remember { EthereumStores.tokenStore(context) }

    var probing by remember { mutableStateOf(true) }
    var meta by remember { mutableStateOf<ERC20Metadata?>(null) }
    var requestedBalance by remember { mutableStateOf<EthereumWeiValue?>(null) }
    var candidates by remember { mutableStateOf<List<Pair<EthereumToken, EthereumWeiValue?>>>(emptyList()) }
    var probeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(request.contract, resolved.rpcURL) {
        probing = true
        probeError = null
        val rpc = resolved.rpcURL
        val wallet = EthereumWallet(descriptor)
        val probed = withContext(Dispatchers.IO) { EthereumTokenLookup.fetch(request.contract, rpc) }
        if (probed == null) {
            probeError = context.getString(
                R.string.eth_token_probe_no_answer,
                resolved.displayName,
            )
            probing = false
            return@LaunchedEffect
        }
        meta = probed
        // Balances are informational: a failed read shows "unknown" rather than
        // blocking a decision the user can still make.
        withContext(Dispatchers.IO) {
            val probeToken = EthereumToken.create(
                network, request.contract, probed.symbol, probed.name, probed.decimals, false,
            )
            requestedBalance = runCatching { wallet.tokenBalance(probeToken, rpc) }.getOrNull()
            val match = EthereumScannedToken.resolve(request.contract, probed.symbol, added)
            if (match is EthereumScannedTokenMatch.SameSymbolCandidates) {
                candidates = match.tokens.map { t ->
                    t to runCatching { wallet.tokenBalance(t, rpc) }.getOrNull()
                }
            }
        }
        probing = false
    }

    fun balanceText(value: EthereumWeiValue?, decimals: Int, symbol: String): String =
        if (value == null) "unknown" else "${value.units(decimals).stripTrailingZeros().toPlainString()} $symbol"

    // A bottom sheet, not an AlertDialog: there can be several action rows (add
    // the requested contract, plus one per same-symbol holding) and each needs a
    // contract and balance under it, which does not fit a dialog's button row.
    // Matches the scanner / contacts sheets on this screen.
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(stringResource(R.string.eth_token_requested), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.eth_payment_code_token_not_here, resolved.displayName),
                style = MaterialTheme.typography.bodySmall,
            )
            if (probing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.eth_reading_the_contract_on, resolved.displayName),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            val m = meta
            if (m != null) {
                Text(stringResource(R.string.eth_requested), style = MaterialTheme.typography.labelMedium)
                Text("${m.symbol} · ${m.name}", style = MaterialTheme.typography.bodySmall)
                Text(
                    shortContract(request.contract),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.eth_your_balance_value, balanceText(requestedBalance, m.decimals, m.symbol)),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            probeError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (candidates.isNotEmpty()) {
                Text(stringResource(R.string.eth_you_already_hold), style = MaterialTheme.typography.labelMedium)
                candidates.forEach { (t, bal) ->
                    Text("${t.symbol} · ${t.name}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${shortContract(t.contractAddress)} · ${balanceText(bal, t.decimals, t.symbol)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    stringResource(R.string.eth_token_same_symbol_warning),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (m != null) {
                Text(
                    stringResource(R.string.eth_token_check_contract),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (m != null) {
                TextButton(
                    onClick = {
                        // curated = false so it stays removable in the token list.
                        val token = EthereumToken.create(
                            network, request.contract, m.symbol, m.name, m.decimals, false,
                        )
                        tokenStore.add(token, walletId)
                        onChoose(ScannedTokenChoice(token, substitutedFrom = null))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.eth_add_token_and_use, m.symbol, m.name))
                }
            }
            candidates.forEach { (t, _) ->
                TextButton(
                    onClick = {
                        val from = meta?.let {
                            RequestedTokenInfo(request.contract, it.symbol, it.name, it.decimals)
                        }
                        onChoose(ScannedTokenChoice(t, substitutedFrom = from))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.eth_send_token_instead, t.symbol, shortContract(t.contractAddress)))
                }
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
