// WalletConnect UI (EVM-only, ADR-0049), the Android mirror of the iOS
// WalletConnect screen + approval sheets. The Connections screen is reached
// from the EVM wallet "+" menu; the approval host (proposal + sign request) is
// hoisted at the EVM wallet root so a request surfaces over any EVM sub-screen.
//
// This first cut signs with SOFTWARE wallets across the full method set. A
// hardware (Ledger / Trezor) request still surfaces and routes through Sign, but
// the manager returns a clear "being wired" error for hardware for now; the
// prepare-device popup integration is a follow-up.

package com.elabify.app.maknoon.walletconnect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.ui.BiometricGate
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import kotlinx.coroutines.launch

// MARK: Connections screen (reached from the EVM "+" menu)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletConnectScreen(onClose: () -> Unit) {
    val sessions by WalletConnectManager.sessions.collectAsState()
    val relayConnected by WalletConnectManager.relayConnected.collectAsState()
    val diagLog by WalletConnectManager.log.collectAsState()
    var pasted by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Return to the wallet once a connection is established, mirroring iOS
    // (which dismisses the WalletConnect screen when the session count rises).
    var prevSessionCount by remember { mutableStateOf(sessions.size) }
    LaunchedEffect(sessions.size) {
        if (sessions.size > prevSessionCount) onClose()
        prevSessionCount = sessions.size
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WalletConnect") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Connect to an app", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { showScanner = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Text("  Scan WalletConnect QR")
            }
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("or paste wc: link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { WalletConnectManager.pair(pasted.trim()); pasted = "" },
                enabled = pasted.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect") }
            Text(
                "Connects this wallet to an external app over the WalletConnect relay. EVM (Ethereum) only. You approve every request.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Active connections", style = MaterialTheme.typography.titleMedium)
            if (sessions.isEmpty()) {
                Text(
                    "No active connections.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                sessions.forEach { session ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session.metaData?.name?.takeIf { it.isNotBlank() } ?: "Connected app",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                session.metaData?.url?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            TextButton(onClick = { WalletConnectManager.disconnect(session.topic) }) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            // Advanced: collapsed by default, mirroring the iOS Advanced
            // disclosure (relay status + Reset). Diagnostics on Android go to
            // logcat under the "walletconnect" tag rather than an in-app feed.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
            if (advancedExpanded) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Relay", modifier = Modifier.weight(1f))
                    Text(
                        if (relayConnected) "Connected" else "Not connected",
                        color = if (relayConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { WalletConnectManager.resetAll() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset WalletConnect")
                }
                Text(
                    "Disconnects everything and clears stored connection state. Use this if a connection is stuck, then scan a fresh QR.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
                if (diagLog.isEmpty()) {
                    Text(
                        "No WalletConnect activity yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        ) {
                            diagLog.takeLast(40).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Scan WalletConnect QR", style = MaterialTheme.typography.titleMedium)
                    MiniAppQrScanner(
                        onCode = { code ->
                            showScanner = false
                            WalletConnectManager.pair(code.trim())
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 360.dp),
                        continuous = false,
                    )
                    // Import a wc: QR from a saved screenshot (mirrors iOS Photos).
                    QrPhotoPickerButton(
                        onCode = { code ->
                            showScanner = false
                            WalletConnectManager.pair(code.trim())
                        },
                        onNoQr = {
                            showScanner = false
                            WalletConnectManager.reportError("No WalletConnect QR code was found in that image.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { showScanner = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

// MARK: Approval host (hoist at the EVM wallet root)

@Composable
fun WalletConnectApprovalHost() {
    val proposal by WalletConnectManager.pendingProposal.collectAsState()
    val request by WalletConnectManager.pendingRequest.collectAsState()
    val error by WalletConnectManager.lastError.collectAsState()
    val signing by WalletConnectManager.signingMessage.collectAsState()

    signing?.let { msg ->
        // Blocking progress while a sign/broadcast runs; "Confirm on your
        // device…" for hardware. Not dismissable (the op is in flight).
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(msg)
                }
            }
        }
    }

    proposal?.let { p ->
        AlertDialog(
            onDismissRequest = { WalletConnectManager.rejectProposal() },
            title = { Text(p.name.takeIf { it.isNotBlank() } ?: "An app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.url.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("wants to connect to your Ethereum wallet. It can request signatures and transactions, which you approve one at a time.")
                }
            },
            confirmButton = { TextButton(onClick = { WalletConnectManager.approveProposal() }) { Text("Connect") } },
            dismissButton = { TextButton(onClick = { WalletConnectManager.rejectProposal() }) { Text("Reject") } },
        )
    }

    request?.let { r -> RequestDialog(r) }

    if (proposal == null && request == null) {
        error?.let { e ->
            AlertDialog(
                onDismissRequest = { WalletConnectManager.clearError() },
                title = { Text("WalletConnect") },
                text = { Text(e) },
                confirmButton = { TextButton(onClick = { WalletConnectManager.clearError() }) { Text("OK") } },
            )
        }
    }
}

@Composable
private fun RequestDialog(pending: WalletConnectManager.PendingRequest) {
    var passphrase by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { WalletConnectManager.rejectRequest() },
        title = { Text(pending.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pending.walletLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(
                    pending.address,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(pending.preview, style = MaterialTheme.typography.bodyMedium)
                if (pending.isHardware) {
                    Text(
                        "You will confirm this on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pending.requiresHostPassphrase) {
                    PassphraseField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = "Hidden wallet passphrase",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !(pending.requiresHostPassphrase && passphrase.isBlank()),
                onClick = {
                    val pass = if (pending.requiresHostPassphrase) passphrase else null
                    if (pending.isHardware) {
                        // Hardware: the device's own on-screen confirmation is the
                        // authorization gate (no app biometric, matching the send flow).
                        WalletConnectManager.approveRequest(pass)
                    } else {
                        // Software: require a fresh biometric before signing, the
                        // ADR-0045 authorization invariant (same gate as authorizeSend).
                        val activity = context as? FragmentActivity
                        if (activity == null) {
                            WalletConnectManager.approveRequest(pass)
                        } else {
                            scope.launch {
                                val ok = BiometricGate.authenticate(
                                    activity,
                                    title = "Authorize signature",
                                    subtitle = "Confirm it's you to sign for the connected app.",
                                )
                                if (ok) WalletConnectManager.approveRequest(pass)
                            }
                        }
                    }
                },
            ) { Text("Sign") }
        },
        dismissButton = { TextButton(onClick = { WalletConnectManager.rejectRequest() }) { Text("Reject") } },
    )
}
