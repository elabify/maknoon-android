// Full-screen sign-message and verify-message screens for Ethereum, ported from
// iOS EthereumMessageSign.swift. Implements EIP-191 `personal_sign`: software
// wallets sign locally (biometric-gated) via EthereumDescriptors; hardware
// wallets route to the bound Ledger / Trezor over BLE. The signature is the
// 0x-hex r||s||v (v in {27,28}) that MetaMask / Etherscan produce and verify.
//
// personal_sign carries no chain id, so it is network-agnostic. Verification is
// keyless: it recovers the signer address and compares it to the given address.
// Both screens are full-page (Scaffold + TopAppBar), matching Bitcoin and the
// iOS navigation presentation (ADR-0033).

package com.elabify.app.maknoon.ui.wallet.ethereum

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import com.elabify.app.maknoon.ui.wallet.common.authorizeSend
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumSignMessageScreen(
    active: EthereumWalletDescriptor?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf("") }
    var hostPassphrase by remember { mutableStateOf("") }
    var signing by remember { mutableStateOf(false) }
    var signature by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val account = when (val k = active?.kind) {
        is EthereumWalletKind.Software -> k.account
        is EthereumWalletKind.Hardware -> k.account
        null -> 0L
    }
    val isHardware = active?.kind is EthereumWalletKind.Hardware
    val needsPassphrase = remember(active?.id) {
        active?.let { HardwarePassphraseRef.fromWireId(it.hidden)?.needsHostPassphrase } ?: false
    }
    val canSign = !signing && active != null && message.isNotEmpty() &&
        (!needsPassphrase || hostPassphrase.isNotEmpty())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_sign_message)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (active == null) {
                Text("No active wallet. Pick one in the Ethereum tab first.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(active.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            active.address?.let { EthLabeledCopyableField(label = stringResource(R.string.wallet_signing_address), value = it) }
            Text(
                "Signs with the EIP-191 \"personal_sign\" format (the one MetaMask and Etherscan produce). The signature is bound to this wallet's address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Message") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (needsPassphrase) {
                PassphraseField(
                    value = hostPassphrase,
                    onValueChange = { hostPassphrase = it },
                    label = "Hidden wallet passphrase",
                )
                Text(
                    "This is a hidden (passphrase) wallet. Enter its passphrase to sign; it is never stored.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                enabled = canSign,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        error = null; signature = null
                        try {
                            when (val kind = active.kind) {
                                is EthereumWalletKind.Software -> {
                                    if (!authorizeSend(context, "Ethereum")) return@launch
                                    val sandwich = loadEthereumSandwich(context)
                                        ?: throw IllegalStateException("Unlock Maknoon first; signing needs your wallet's private key.")
                                    signing = true
                                    signature = withContext(Dispatchers.IO) {
                                        EthereumDescriptors.signPersonalMessage(
                                            words = sandwich.recoveryWords(),
                                            passphrase = "",
                                            account = account,
                                            message = message.toByteArray(Charsets.UTF_8),
                                            derivationPath = active.derivationPath,
                                        )
                                    }
                                }
                                is EthereumWalletKind.Hardware -> {
                                    val device = DeviceRegistry(context).find(kind.deviceId)
                                        ?: throw IllegalStateException("The hardware device for this wallet is no longer registered. Re-add it under Settings, Devices.")
                                    signing = true
                                    signature = withContext(Dispatchers.IO) {
                                        signEthereumHardwareMessage(
                                            device = device,
                                            account = account,
                                            message = message.toByteArray(Charsets.UTF_8),
                                            hidden = active.hidden,
                                            derivationPath = active.derivationPath,
                                            hostPassphrase = hostPassphrase.ifEmpty { null },
                                        )
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        } finally {
                            signing = false
                        }
                    }
                },
            ) {
                if (signing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  " + if (isHardware) "Confirm on your device…" else "Signing…")
                } else {
                    Text(if (isHardware) "Sign on device" else stringResource(R.string.wallet_sign_message))
                }
            }
            if (isHardware) {
                Text(
                    "You'll confirm the message on the device screen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            val sig = signature
            if (sig != null) {
                EthLabeledCopyableField(label = "Signature", value = sig)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EthereumVerifyMessageScreen(onClose: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var signature by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wallet_verify_message)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Verifies an EIP-191 \"personal_sign\" signature (MetaMask / Etherscan). Paste the address, message, and 0x-hex signature from any source.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; result = null },
                label = { Text("Address (0x…)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it; result = null },
                label = { Text("Message") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it; result = null },
                label = { Text("Signature (0x…)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = address.isNotEmpty() && message.isNotEmpty() && signature.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    result = EthereumDescriptors.verifyMessage(
                        address = address.trim(),
                        message = message.toByteArray(Charsets.UTF_8),
                        signature = signature.trim(),
                    )
                },
            ) { Text("Verify signature") }
            when (result) {
                true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                    Text("Signature valid", color = Color(0xFF2E7D32))
                }
                false -> Text(
                    "Signature does not match this address and message",
                    color = MaterialTheme.colorScheme.error,
                )
                null -> {}
            }
        }
    }
}

@Composable
private fun EthLabeledCopyableField(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1400)
            copied = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (copied) Color(0xFF2E7D32).copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(8.dp),
            )
        }
        TextButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            copied = true
        }) {
            Icon(
                if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = if (copied) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text("  " + if (copied) "Copied" else "Copy", color = if (copied) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary)
        }
    }
}
