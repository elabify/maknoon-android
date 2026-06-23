// Receive a verified credential from an issuer, the Android analog of the iOS
// ReceiveView. The user scans (or pastes) an issuer pickup URL; the orchestrator
// polls it (ADR-0022 batch-anchor window) and imports the credential when the
// issuer's batch flushes. This screen owns only the scan / paste / status UI;
// the poll + import logic is the suspending [receive] lambda.

package com.elabify.app.maknoon.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.miniapp.QrPhotoPickerButton
import com.elabify.app.maknoon.ui.theme.Spacing

private sealed interface ReceivePhase {
    data object Scan : ReceivePhase
    data object Manual : ReceivePhase
    data class Working(val status: String) : ReceivePhase
    data object Done : ReceivePhase
    data class Error(val message: String) : ReceivePhase
}

/**
 * @param receive polls the pickup URL and imports the credential; reports
 *   progress via the callback and throws on error / timeout.
 * @param onReceived called once a credential has been imported.
 * @param onClose dismiss without receiving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveCredentialScreen(
    receive: suspend (pickupUrl: String, onStatus: (String) -> Unit) -> Unit,
    onReceived: () -> Unit,
    onClose: () -> Unit,
) {
    var phase by remember { mutableStateOf<ReceivePhase>(ReceivePhase.Scan) }
    var manualUrl by remember { mutableStateOf("") }
    var pickupUrl by remember { mutableStateOf<String?>(null) }
    var photoNoQr by remember { mutableStateOf(false) }

    fun start(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        pickupUrl = trimmed
        phase = ReceivePhase.Working("Contacting the issuer…")
    }

    if (pickupUrl != null && phase is ReceivePhase.Working) {
        LaunchedEffect(pickupUrl) {
            try {
                receive(pickupUrl!!) { msg -> phase = ReceivePhase.Working(msg) }
                phase = ReceivePhase.Done
            } catch (e: Throwable) {
                phase = ReceivePhase.Error(e.message ?: "Could not receive the credential.")
            }
        }
    }

    if (phase is ReceivePhase.Done) {
        LaunchedEffect(Unit) { onReceived() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_receive_credential)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (val p = phase) {
                is ReceivePhase.Scan -> {
                    Text(
                        stringResource(R.string.identity_scan_qr_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    MiniAppQrScanner(
                        continuous = false,
                        onCode = { code -> start(code) },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                    )
                    if (photoNoQr) {
                        Text(
                            stringResource(R.string.identity_no_qr_in_photo),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // Parity with iOS: pick a QR from the photo library.
                    QrPhotoPickerButton(
                        onCode = { photoNoQr = false; start(it) },
                        onNoQr = { photoNoQr = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { phase = ReceivePhase.Manual }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.identity_paste_pickup_url_instead))
                    }
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }

                is ReceivePhase.Manual -> {
                    OutlinedTextField(
                        value = manualUrl,
                        onValueChange = { manualUrl = it },
                        label = { Text(stringResource(R.string.identity_pickup_url)) },
                        placeholder = { Text(stringResource(R.string.identity_pickup_url_placeholder)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { start(manualUrl) },
                        enabled = manualUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.identity_fetch_credential)) }
                    OutlinedButton(onClick = { phase = ReceivePhase.Scan }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.identity_scan_qr_instead))
                    }
                }

                is ReceivePhase.Working -> {
                    Spacer(Modifier.size(Spacing.xl))
                    CircularProgressIndicator()
                    Text(p.status, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.identity_anchoring_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                is ReceivePhase.Done -> {
                    Spacer(Modifier.size(Spacing.xl))
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.identity_credential_received), style = MaterialTheme.typography.titleMedium)
                }

                is ReceivePhase.Error -> {
                    Spacer(Modifier.size(Spacing.xl))
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.identity_couldnt_receive), style = MaterialTheme.typography.titleMedium)
                    Text(p.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Button(onClick = { phase = ReceivePhase.Scan }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_try_again)) }
                }
            }
        }
    }
}
