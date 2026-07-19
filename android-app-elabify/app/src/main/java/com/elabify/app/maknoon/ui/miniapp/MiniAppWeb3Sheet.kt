// Approval sheet for window.ethereum (EIP-1193). Android port of the iOS
// MiniAppWeb3Sheet.swift, adapted to Compose Material3 + the ApprovalGate
// mechanism (the iOS MiniAppWeb3Coordinator's role is played by ApprovalGate;
// this file is only the UI plus a MiniAppApprovalSheetHost that decodes the
// gate's "web3" payload and resolves / cancels the request).
//
// The Web3BridgeHandler suspends on gate.request(kind="web3", payloadJson=...)
// where payloadJson is { action, ... }:
//   connect          -> { action:"connect", address }
//   signMessage      -> { action:"signMessage", preview }
//   sendTransaction  -> { action:"sendTransaction", to, amountEth, network }
//
// Like iOS: connect is low-risk (the address is public) so it skips biometrics;
// sign / send run a BiometricPrompt (Class-3 + device-credential fallback)
// inside approve() before resuming the suspended handler. Approve resolves the
// request with "null" (the handler does the real signing); cancel / dismiss
// rejects it with userRejected (4001).
//
// The sheet itself (MiniAppWeb3Sheet) is stateless: it renders the decoded
// request and calls back onApprove / onCancel. The host wires it to a real
// FragmentActivity for the biometric step.

package com.elabify.app.maknoon.ui.miniapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.ApprovalRequest
import com.elabify.app.maknoon.ui.BiometricGate
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Decoded form of the "web3" approval payload the Web3BridgeHandler posts.
 * Mirrors the iOS MiniAppWeb3Coordinator.Kind cases.
 */
sealed interface MiniAppWeb3Action {
    /** One selectable EVM wallet in the connect picker. */
    data class WalletChoice(val id: String, val label: String, val address: String)

    data class Connect(
        val address: String,
        val wallets: List<WalletChoice>,
        val activeId: String?,
    ) : MiniAppWeb3Action
    data class SignMessage(val preview: String) : MiniAppWeb3Action
    data class SignTypedData(val domain: String, val preview: String) : MiniAppWeb3Action

    /** `summary` is a decoded action line (null for a native send); `dataHex` is the
     *  raw calldata for a contract call (null for a native send). */
    data class SendTransaction(
        val to: String,
        val amountEth: String,
        val network: String,
        val summary: String?,
        val dataHex: String?,
    ) : MiniAppWeb3Action

    data class SwitchChain(val fromName: String, val toName: String) : MiniAppWeb3Action

    companion object {
        /** Parse the gate payload; null when it is not a web3 request shape. */
        fun parse(payloadJson: String): MiniAppWeb3Action? {
            val o = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
            return when (o.optString("action")) {
                "connect" -> {
                    val arr = o.optJSONArray("wallets")
                    val list = buildList {
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val w = arr.optJSONObject(i) ?: continue
                                add(WalletChoice(w.optString("id"), w.optString("label"), w.optString("address")))
                            }
                        }
                    }
                    Connect(
                        address = o.optString("address"),
                        wallets = list,
                        activeId = if (o.isNull("activeId")) null else o.optString("activeId").ifEmpty { null },
                    )
                }
                "signMessage" -> SignMessage(o.optString("preview"))
                "signTypedData" -> SignTypedData(o.optString("domain"), o.optString("preview"))
                "sendTransaction" -> SendTransaction(
                    to = o.optString("to"),
                    amountEth = o.optString("amountEth"),
                    network = o.optString("network"),
                    summary = if (o.isNull("summary")) null else o.optString("summary").ifEmpty { null },
                    dataHex = if (o.isNull("dataHex")) null else o.optString("dataHex").ifEmpty { null },
                )
                "switchChain" -> SwitchChain(o.optString("fromName"), o.optString("toName"))
                else -> null
            }
        }
    }
}

/**
 * Stateless connect / sign / send approval sheet. Renders the decoded
 * [action] and reports the user's decision through [onApprove] / [onCancel].
 * Biometric gating is the host's job (see [web3ApprovalSheetHost]); this view
 * disables its buttons while [working] is true.
 */
@Composable
fun MiniAppWeb3Sheet(
    appTitle: String,
    action: MiniAppWeb3Action,
    working: Boolean,
    authError: String?,
    selectedWalletId: String?,
    onSelectWallet: (String) -> Unit,
    onApprove: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(appTitle, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                when (action) {
                    is MiniAppWeb3Action.Connect -> R.string.app_web3_connect
                    is MiniAppWeb3Action.SignMessage -> R.string.app_web3_sign_message
                    is MiniAppWeb3Action.SignTypedData -> R.string.app_web3_sign_typed_data
                    is MiniAppWeb3Action.SendTransaction -> R.string.app_web3_confirm_send
                    is MiniAppWeb3Action.SwitchChain -> R.string.app_web3_switch_chain
                },
            ),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))

        when (action) {
            is MiniAppWeb3Action.Connect -> {
                Text(
                    stringResource(R.string.app_web3_connect_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (action.wallets.size > 1) {
                    val sel = selectedWalletId ?: action.activeId ?: action.wallets.first().id
                    action.wallets.forEach { w ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !working) { onSelectWallet(w.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = w.id == sel,
                                onClick = { onSelectWallet(w.id) },
                                enabled = !working,
                            )
                            Spacer(Modifier.width(4.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.label, style = MaterialTheme.typography.bodyMedium)
                                MonoValue(w.address)
                            }
                        }
                    }
                } else {
                    MonoValue(action.wallets.firstOrNull()?.address ?: action.address)
                }
            }
            is MiniAppWeb3Action.SignMessage -> {
                Text(
                    stringResource(R.string.app_web3_sign_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                MonoValue(action.preview)
            }
            is MiniAppWeb3Action.SignTypedData -> {
                Text(
                    stringResource(R.string.app_web3_sign_typed_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (action.domain.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LabeledRow(stringResource(R.string.app_web3_domain), action.domain)
                }
                Spacer(Modifier.height(8.dp))
                MonoValue(action.preview)
            }
            is MiniAppWeb3Action.SendTransaction -> {
                LabeledRow(stringResource(R.string.common_network), action.network)
                if (action.summary != null) {
                    Spacer(Modifier.height(6.dp))
                    LabeledRow(stringResource(R.string.app_web3_action), action.summary)
                }
                Spacer(Modifier.height(6.dp))
                LabeledRow(stringResource(R.string.app_amount), "${action.amountEth} ETH")
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.app_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                MonoValue(action.to)
                if (action.dataHex != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.app_web3_advanced_data),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (action.summary == null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.app_web3_advanced_data_warn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    MonoValue(action.dataHex)
                }
            }
            is MiniAppWeb3Action.SwitchChain -> {
                Text(
                    stringResource(R.string.app_web3_switch_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LabeledRow(stringResource(R.string.common_from), action.fromName)
                Spacer(Modifier.height(6.dp))
                LabeledRow(stringResource(R.string.app_to), action.toName)
            }
        }

        if (authError != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                authError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                enabled = !working,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.common_cancel)) }
            Button(
                onClick = onApprove,
                enabled = !working,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        when (action) {
                            is MiniAppWeb3Action.Connect -> R.string.app_web3_connect
                            is MiniAppWeb3Action.SignMessage -> R.string.app_web3_sign
                            is MiniAppWeb3Action.SignTypedData -> R.string.app_web3_sign
                            is MiniAppWeb3Action.SendTransaction -> R.string.app_web3_send
                            is MiniAppWeb3Action.SwitchChain -> R.string.app_web3_switch
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonoValue(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface,
        overflow = TextOverflow.Ellipsis,
        maxLines = 8,
    )
}

private fun title(action: MiniAppWeb3Action): String = when (action) {
    is MiniAppWeb3Action.Connect -> "Connect"
    is MiniAppWeb3Action.SignMessage -> "Sign message"
    is MiniAppWeb3Action.SignTypedData -> "Sign typed data"
    is MiniAppWeb3Action.SendTransaction -> "Confirm send"
    is MiniAppWeb3Action.SwitchChain -> "Switch network"
}

private fun biometricSubtitle(action: MiniAppWeb3Action, appTitle: String): String = when (action) {
    is MiniAppWeb3Action.Connect -> "Connect your wallet to $appTitle"
    is MiniAppWeb3Action.SignMessage -> "Sign a message for $appTitle"
    is MiniAppWeb3Action.SignTypedData -> "Sign typed data for $appTitle"
    is MiniAppWeb3Action.SendTransaction -> "Authorize sending from your wallet"
    is MiniAppWeb3Action.SwitchChain -> "Switch network for $appTitle"
}

/**
 * MiniAppApprovalSheetHost for kind == "web3". Decodes the gate payload, shows
 * the modal sheet, runs a biometric check for sign / send (connect skips it,
 * matching iOS), and resolves / cancels the [ApprovalRequest].
 *
 * Wire this into MiniAppHostScreen's approvalSheetHost (compose it with the
 * other handler agents' sheets in a when over request.kind). It resolves the
 * request with "null": the Web3BridgeHandler performs the actual signing once
 * the suspended gate.request() returns.
 */
val web3ApprovalSheetHost = MiniAppApprovalSheetHost { request, onDismiss ->
    Web3ApprovalSheet(request, onDismiss)
}

/**
 * The composable body of [web3ApprovalSheetHost]. Split out so the SAM lambda
 * stays a thin forwarder and the @Composable logic reads as a normal function.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Web3ApprovalSheet(request: ApprovalRequest, onDismiss: () -> Unit) {
    if (request.kind != "web3") {
        // Not ours: cancel cleanly so the JS promise rejects (4001) instead of hanging.
        LaunchedEffect(request.id) {
            request.cancel()
            onDismiss()
        }
        return
    }

    val action = remember(request.id) { MiniAppWeb3Action.parse(request.payloadJson) }
    if (action == null) {
        LaunchedEffect(request.id) {
            request.cancel()
            onDismiss()
        }
        return
    }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var working by remember(request.id) { mutableStateOf(false) }
    var authError by remember(request.id) { mutableStateOf<String?>(null) }
    var selectedWalletId by remember(request.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!working) {
                request.cancel()
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        MiniAppWeb3Sheet(
            appTitle = request.appTitle,
            action = action,
            working = working,
            authError = authError,
            selectedWalletId = selectedWalletId,
            onSelectWallet = { selectedWalletId = it },
            onCancel = {
                request.cancel()
                onDismiss()
            },
            onApprove = {
                scope.launch {
                    working = true
                    authError = null
                    // Connect + network switch touch no key material: skip biometrics.
                    val needsAuth = action !is MiniAppWeb3Action.Connect &&
                        action !is MiniAppWeb3Action.SwitchChain
                    val ok = if (!needsAuth || activity == null) {
                        // No FragmentActivity host (or low-risk): proceed.
                        // The signing path still runs inside the SDK, so a
                        // missing biometric host degrades to consent-only,
                        // matching iOS canEvaluatePolicy == false.
                        true
                    } else {
                        BiometricGate.authenticate(
                            activity = activity,
                            title = title(action),
                            subtitle = biometricSubtitle(action, request.appTitle),
                        )
                    }
                    if (ok) {
                        // On connect, hand the bridge the wallet the user picked so
                        // it can make that one active; other actions resolve "null"
                        // (the handler does the real signing once this returns).
                        val result = if (action is MiniAppWeb3Action.Connect) {
                            val chosen = selectedWalletId ?: action.activeId
                                ?: action.wallets.firstOrNull()?.id
                            if (chosen != null) {
                                JSONObject().put("walletId", chosen).toString()
                            } else {
                                "null"
                            }
                        } else {
                            "null"
                        }
                        request.approve(result)
                        onDismiss()
                    } else {
                        authError = "Authentication failed."
                        working = false
                    }
                }
            },
        )
    }
}
