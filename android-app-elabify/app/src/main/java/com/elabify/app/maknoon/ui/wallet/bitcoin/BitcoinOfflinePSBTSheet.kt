// Offline / air-gapped PSBT signing, ported from iOS BitcoinOfflinePSBTSheet.
// The universal hardware-wallet path: build the unsigned PSBT (export as
// base64 + QR), then paste the signed PSBT back to finalize + broadcast via
// the engine. Works with any BIP174 signer (Sparrow, Trezor Suite, Ledger
// Live, Specter, SeedSigner). The original unsigned PSBT is passed to the
// broadcast call so BDK can BIP-174-combine a stripped signed PSBT.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinOfflinePSBTSheet(
    env: BitcoinWalletEnv,
    engine: BitcoinWalletEngine,
    descriptor: BitcoinWalletDescriptor,
    recipient: String,
    amountSat: Long,
    feeRateSatsPerVb: Long,
    enableRbf: Boolean,
    onBroadcast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var unsigned by remember { mutableStateOf<String?>(null) }
    var buildError by remember { mutableStateOf<String?>(null) }
    var building by remember { mutableStateOf(true) }
    var signedInput by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var broadcasting by remember { mutableStateOf(false) }
    var broadcastedTxid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        building = true
        buildError = null
        val res = withContext(Dispatchers.IO) {
            runCatching {
                engine.buildUnsignedPSBT(recipient, amountSat, feeRateSatsPerVb, enableRbf, null)
            }
        }
        res.onSuccess { unsigned = it }.onFailure { buildError = it.message ?: it.toString() }
        building = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                // Edge-to-edge: inset from system bars (Dialog is outside the tab Scaffold).
                Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.btc_sign_offline), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }

                // Transaction details
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DetailRow(stringResource(R.string.btc_recipient), recipient, mono = true)
                        DetailRow(stringResource(R.string.walletc_amount), "$amountSat sats")
                        DetailRow(stringResource(R.string.btc_fee_rate), "$feeRateSatsPerVb sats/vB")
                        DetailRow(stringResource(R.string.btc_rbf), if (enableRbf) stringResource(R.string.btc_enabled) else stringResource(R.string.btc_disabled))
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.btc_step_unsigned_psbt), style = MaterialTheme.typography.titleSmall)
                when {
                    building -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.btc_building_unsigned_psbt))
                    }
                    unsigned != null -> {
                        val psbt = unsigned!!
                        Text(
                            psbt,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(psbt)) }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.btc_copy))
                            }
                        }
                        if (psbt.length <= 800) {
                            QrCode(content = psbt, modifier = Modifier.size(220.dp))
                        } else {
                            Text(
                                stringResource(R.string.btc_psbt_too_long, psbt.length.toString()),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            stringResource(R.string.btc_take_psbt_to_signer),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    buildError != null -> {
                        Text(buildError!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = {
                            scope.launch {
                                building = true
                                val res = withContext(Dispatchers.IO) {
                                    runCatching { engine.buildUnsignedPSBT(recipient, amountSat, feeRateSatsPerVb, enableRbf, null) }
                                }
                                res.onSuccess { unsigned = it; buildError = null }.onFailure { buildError = it.message ?: it.toString() }
                                building = false
                            }
                        }) { Text(stringResource(R.string.common_retry)) }
                    }
                }

                HorizontalDivider()
                Text(stringResource(R.string.btc_step_signed_psbt), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = signedInput,
                    onValueChange = { signedInput = it },
                    label = { Text(stringResource(R.string.btc_paste_signed_psbt)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { clipboard.getText()?.text?.let { signedInput = it } }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.btc_paste))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        enabled = signedInput.trim().isNotEmpty() && !broadcasting,
                        onClick = {
                            scope.launch {
                                broadcasting = true
                                importError = null
                                val res = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val url = env.settings.electrumURL(descriptor.network)
                                        engine.importSignedPSBTAndBroadcast(signedInput.trim(), unsigned, url)
                                    }
                                }
                                broadcasting = false
                                res.onSuccess { txid ->
                                    broadcastedTxid = txid
                                    onBroadcast(txid)
                                }.onFailure { importError = it.message ?: it.toString() }
                            }
                        },
                    ) {
                        if (broadcasting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (broadcasting) stringResource(R.string.btc_broadcasting) else stringResource(R.string.btc_broadcast))
                    }
                }
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                broadcastedTxid?.let { Text(stringResource(R.string.btc_broadcast_short, shortMiddle(it, 12, 0)), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
        )
    }
}
