// Solana receive screen, 1:1 with iOS SolanaReceiveView.swift. A Solana
// account's primary key IS its single address (no per-receive derivation),
// so this shows that one base58 address as a QR plus copy. Derivation runs
// off the main thread via the engine's SolanaWallet.resolvedAddress().

package com.elabify.app.maknoon.ui.wallet.solana

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.QrCode
import com.elabify.app.maknoon.ui.wallet.common.ReceiveScaffold
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SolanaReceiveScreen(
    descriptor: SolanaWalletDescriptor,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val env = remember { SolanaEnv.get(context) }

    var address by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(descriptor.id) {
        busy = true
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching { env.openWallet(descriptor).resolvedAddress() }
        }
        result.onSuccess { address = it }.onFailure { error = it.message ?: it.toString() }
        busy = false
    }

    ReceiveScaffold(onBack = onBack) { innerPadding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.sol_receive_sol), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.sol_receive_description, env.walletStore.currentNetwork.displayName),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        when {
            busy -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            error != null -> Text(
                stringResource(R.string.sol_error_prefix, error ?: ""),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            address != null -> {
                QrCode(content = address!!, modifier = Modifier.size(240.dp))
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        address!!,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(
                    onClick = { copyToClipboard(context, "Solana address", address!!) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sol_copy_address)) }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_done)) }
    }
    }
}

internal fun copyToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}
