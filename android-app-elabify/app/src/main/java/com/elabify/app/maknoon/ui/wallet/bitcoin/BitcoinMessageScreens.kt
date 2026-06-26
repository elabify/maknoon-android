// Full-screen sign-message and verify-message screens for Bitcoin, ported from
// iOS BitcoinMessageSign.swift (BitcoinSignMessageSheet / BitcoinVerifyMessageSheet,
// which present as full navigation screens on iOS).
//
// Signing uses the standard "Bitcoin Signed Message" (Electrum-compatible)
// format via the shared Rust core: software wallets derive the key locally
// (biometric-gated), hardware wallets route to the bound Ledger / Trezor over
// BLE. By default it signs with the wallet's first receive address (native
// segwit); from the Addresses screen, tapping a row -> "Sign message" signs with
// that exact address. All networks (mainnet, testnet3, signet) are supported.
//
// The screen shows the address being signed, and after signing offers a copy
// button for BOTH the address and the signature. Verification is keyless and
// works for any address + message + base64 signature from any source.

package com.elabify.app.maknoon.ui.wallet.bitcoin

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
import com.elabify.musnad.wallet.bitcoin.Bip32Path
import com.elabify.musnad.wallet.bitcoin.BitcoinMessageSigning
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A specific derived address (keychain + index) to sign with, from the
 *  Addresses screen's row menu. Null target = the first receive key. */
data class BitcoinSignAddressTarget(
    val chain: Long, // 0 = receive (external), 1 = change (internal)
    val index: Long,
    val address: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinSignMessageScreen(
    active: BitcoinWalletDescriptor?,
    target: BitcoinSignAddressTarget?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var message by remember { mutableStateOf("") }
    var hostPassphrase by remember { mutableStateOf("") }
    var signing by remember { mutableStateOf(false) }
    var resultAddress by remember { mutableStateOf<String?>(null) }
    var resultSig by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val accountIndex = active?.softwareAccountOrNull() ?: 0L
    val isHardware = active?.kind is BitcoinWalletKind.Hardware
    val needsPassphrase = remember(active?.id) {
        active?.let { HardwarePassphraseRef.fromJson(it.hidden)?.needsHostPassphrase } ?: false
    }
    val accountPath = active?.let {
        it.derivationPath ?: Bip32Path.standardBitcoin(account = accountIndex, coinType = it.network.coinType)
    }
    val scriptType = accountPath?.let { Bip32Path.bitcoinScriptType(it) } ?: Bip32Path.BitcoinScriptType.NATIVE_SEGWIT
    val fullPath = accountPath?.let { "$it/${target?.chain ?: 0L}/${target?.index ?: 0L}" }

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
                Text("No active wallet. Pick one in the Bitcoin tab first.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            // Wallet + the address being signed.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(active.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(active.network.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val signingAddress = target?.address ?: resultAddress
            if (signingAddress != null) {
                LabeledCopyableField(label = stringResource(R.string.wallet_signing_address), value = signingAddress)
            } else {
                Text(
                    "Signs with the wallet's first receive address using the standard \"Bitcoin Signed Message\" (Electrum) format.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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
                    val path = fullPath ?: return@Button
                    scope.launch {
                        error = null; resultSig = null
                        if (target == null) resultAddress = null
                        try {
                            when (val kind = active.kind) {
                                is BitcoinWalletKind.Software -> {
                                    if (!authorizeSend(context, "Bitcoin")) return@launch
                                    signing = true
                                    val words = withContext(Dispatchers.IO) { loadRecoveryWords(context) }
                                        ?: throw IllegalStateException("Unlock Maknoon first; signing needs your wallet's private key.")
                                    val (addr, sig) = withContext(Dispatchers.IO) {
                                        BitcoinMessageSigning.sign(
                                            message = message,
                                            derivationPath = path,
                                            scriptType = scriptType,
                                            network = active.network,
                                            mnemonicWords = words,
                                            passphrase = null,
                                        )
                                    }
                                    resultAddress = addr; resultSig = sig
                                }
                                is BitcoinWalletKind.Hardware -> {
                                    val device = DeviceRegistry(context).find(kind.deviceId)
                                        ?: throw IllegalStateException("The hardware device for this wallet is no longer registered. Re-add it under Settings, Devices.")
                                    signing = true
                                    val (addr, sig) = withContext(Dispatchers.IO) {
                                        signBitcoinHardwareMessage(
                                            device = device,
                                            path = path,
                                            message = message.toByteArray(Charsets.UTF_8),
                                            network = active.network,
                                            hidden = active.hidden,
                                            hostEnteredPassphrase = hostPassphrase.ifEmpty { null },
                                        )
                                    }
                                    resultAddress = addr; resultSig = sig
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

            // Result: the bound address (with copy) when not already shown above,
            // and the signature, each with its own copy button.
            val addr = resultAddress
            val sig = resultSig
            if (addr != null && sig != null) {
                if (target == null) LabeledCopyableField(label = stringResource(R.string.wallet_signing_address), value = addr)
                LabeledCopyableField(label = "Signature", value = sig)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinVerifyMessageScreen(onClose: () -> Unit) {
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
                "Checks that a signature was produced by the owner of an address. Paste the address, message, and signature.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; result = null },
                label = { Text("Address") },
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
                label = { Text("Signature") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = address.isNotEmpty() && message.isNotEmpty() && signature.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    result = BitcoinMessageSigning.verify(
                        address = address.trim(),
                        message = message,
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

/** A labelled monospace value with a copy button that flashes the field green
 *  for ~1.4s after copying (mirrors the iOS labeledCopyable). */
@Composable
private fun LabeledCopyableField(label: String, value: String) {
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
