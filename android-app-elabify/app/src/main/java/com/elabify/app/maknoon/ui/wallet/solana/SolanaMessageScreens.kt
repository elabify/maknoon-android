// Full-screen sign-message and verify-message screens for Solana, mirroring the
// Ethereum/Tron screens (single address per wallet). Signing uses the Solana
// off-chain message (OCMS, SIMD-0048) format via the shared Rust core: software
// wallets derive the ed25519 key locally (biometric-gated), Ledger AND Trezor
// route over BLE (both vendors support OCMS). Verification is keyless and works
// for any base58 address + message + base58 signature produced by software,
// Ledger, or Trezor.
//
// Note: OCMS is the hardware-wallet format, NOT Phantom's raw signMessage; these
// verify in Maknoon + OCMS-aware tooling, not web3.js nacl.verify(rawMsg,...).

package com.elabify.app.maknoon.ui.wallet.solana

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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.authorizeSignature
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.wallet.solana.SolanaMessageSigning
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolanaSignMessageScreen(
    active: SolanaWalletDescriptor?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var message by remember { mutableStateOf("") }
    var signing by remember { mutableStateOf(false) }
    var resultAddress by remember { mutableStateOf<String?>(null) }
    var resultSig by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val account = when (val k = active?.kind) {
        is SolanaWalletKind.Software -> k.account
        is SolanaWalletKind.Hardware -> k.account
        null -> 0L
    }
    val hw = active?.kind as? SolanaWalletKind.Hardware
    val device = remember(active?.id) { hw?.let { DeviceRegistry(context).find(it.deviceId) } }
    val isHardware = hw != null

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
                Text(stringResource(R.string.sol_no_active_wallet_pick_one), color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Text(active.label, style = MaterialTheme.typography.titleMedium)
            (hw?.publicKeyBase58)?.let { SolanaCopyableField(label = stringResource(R.string.wallet_signing_address), value = it) }

            Text(
                stringResource(R.string.sol_signs_with_the_solana),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text(stringResource(R.string.btc_message)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = !signing && message.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        error = null; resultAddress = null; resultSig = null
                        try {
                            when (val kind = active.kind) {
                                is SolanaWalletKind.Software -> {
                                    if (!authorizeSignature(context, "Solana")) return@launch
                                    val sandwich = withContext(Dispatchers.IO) { env.loadSandwich() }
                                        ?: throw IllegalStateException("Unlock Maknoon first; signing needs your wallet's private key.")
                                    signing = true
                                    val (addr, sig) = withContext(Dispatchers.IO) {
                                        SolanaMessageSigning.sign(
                                            message = message,
                                            account = account,
                                            mnemonicWords = sandwich.recoveryWords(),
                                            passphrase = sandwich.bip39Passphrase(),
                                        )
                                    }
                                    resultAddress = addr; resultSig = sig
                                }
                                is SolanaWalletKind.Hardware -> {
                                    val dev = device
                                        ?: throw IllegalStateException("The hardware device for this wallet is no longer registered. Re-add it under Settings, Devices.")
                                    signing = true
                                    val (addr, sig) = withContext(Dispatchers.IO) {
                                        signSolanaHardwareMessage(
                                            device = dev,
                                            account = account,
                                            message = message,
                                            hidden = active.hidden,
                                            derivationPath = active.derivationPath,
                                            hostEnteredPassphrase = null,
                                        )
                                    }
                                    resultAddress = addr; resultSig = sig
                                }
                            }
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        } finally {
                            signing = false
                            keyboard?.hide() // reveal the signature (iOS parity)
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
                    stringResource(R.string.btc_youll_confirm_the_message),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            val addr = resultAddress
            val sig = resultSig
            if (addr != null && sig != null) {
                SolanaCopyableField(label = stringResource(R.string.wallet_signing_address), value = addr)
                SolanaCopyableField(label = "Signature", value = sig)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SolanaVerifyMessageScreen(onClose: () -> Unit) {
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
                stringResource(R.string.sol_verifies_a_solana_off_chain),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it; result = null },
                label = { Text(stringResource(R.string.walletc_address)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it; result = null },
                label = { Text(stringResource(R.string.btc_message)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = signature,
                onValueChange = { signature = it; result = null },
                label = { Text(stringResource(R.string.sol_signature_base58)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = address.isNotEmpty() && message.isNotEmpty() && signature.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    result = SolanaMessageSigning.verify(
                        address = address.trim(),
                        message = message,
                        signature = signature.trim(),
                    )
                },
            ) { Text(stringResource(R.string.btc_verify_signature)) }
            when (result) {
                true -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                    Text(stringResource(R.string.btc_signature_valid), color = Color(0xFF2E7D32))
                }
                false -> Text(
                    stringResource(R.string.btc_signature_does_not_match),
                    color = MaterialTheme.colorScheme.error,
                )
                null -> {}
            }
        }
    }
}

@Composable
private fun SolanaCopyableField(label: String, value: String) {
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
