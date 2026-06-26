// Addresses screen, ported from iOS BitcoinAddressesView. Segmented toggle
// between the receive (external) and change (internal) keychains; each row
// shows the derivation index, the bech32 address, the current balance and
// total-received (from BDK listOutput + listUnspent), and the UTXO count.
// Tap-to-copy + explorer link per row.

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import com.elabify.app.maknoon.ui.components.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import android.content.Intent
import android.net.Uri
import com.elabify.app.maknoon.R
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoindevkit.KeychainKind

private data class AddressRow(
    val index: Long,
    val address: String,
    val balanceSat: Long,
    val totalReceivedSat: Long,
    val utxoCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BitcoinAddressesScreen(
    env: BitcoinWalletEnv,
    active: BitcoinWalletDescriptor?,
    onSignAddress: (BitcoinSignAddressTarget) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var receiveKeychain by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<AddressRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf<String?>(null) }
    // Address whose QR is shown full-screen so another person can scan it to pay.
    var qrFor by remember { mutableStateOf<String?>(null) }
    // Index of the row whose tap-menu (Copy address / Sign message) is open.
    var menuForIndex by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(active?.id, receiveKeychain) {
        val descriptor = active ?: return@LaunchedEffect
        loading = true
        val kc = if (receiveKeychain) KeychainKind.EXTERNAL else KeychainKind.INTERNAL
        rows = withContext(Dispatchers.IO) {
            runCatching {
                val words = loadRecoveryWords(context)
                val e = BitcoinWalletEngine.open(descriptor, env.filesDirPath, words, null)
                val infos = e.revealedAddresses(kc, 25L)
                val outputs = e.listOutput().filter { it.keychain == kc }
                val unspent = e.listUnspent().filter { it.keychain == kc && !it.isSpent }
                val received = HashMap<Long, Long>()
                val balance = HashMap<Long, Long>()
                val utxos = HashMap<Long, Int>()
                outputs.forEach { received[it.derivationIndex.toLong()] = (received[it.derivationIndex.toLong()] ?: 0) + it.txout.value.toSat().toLong() }
                unspent.forEach {
                    val i = it.derivationIndex.toLong()
                    balance[i] = (balance[i] ?: 0) + it.txout.value.toSat().toLong()
                    utxos[i] = (utxos[i] ?: 0) + 1
                }
                infos.map { info ->
                    val i = info.index.toLong()
                    AddressRow(i, info.address.toString(), balance[i] ?: 0, received[i] ?: 0, utxos[i] ?: 0)
                }
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btc_addresses)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = receiveKeychain,
                    onClick = { receiveKeychain = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.walletc_receive)) }
                SegmentedButton(
                    selected = !receiveKeychain,
                    onClick = { receiveKeychain = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.btc_change)) }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    rows.isEmpty() -> Text(stringResource(R.string.btc_no_addresses), Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(rows, key = { it.index }) { row ->
                            val ticker = active?.network?.ticker ?: "BTC"
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { menuForIndex = row.index }
                                    .padding(vertical = 6.dp),
                            ) {
                                // Tap-menu: Copy address / Sign message (mirrors the
                                // iOS row context menu). Anchored to this row.
                                DropdownMenu(
                                    expanded = menuForIndex == row.index,
                                    onDismissRequest = { menuForIndex = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.btc_copy_address)) },
                                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                        onClick = {
                                            clipboard.setText(AnnotatedString(row.address))
                                            copied = row.address
                                            menuForIndex = null
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.wallet_sign_message)) },
                                        leadingIcon = { Icon(Icons.Filled.Draw, contentDescription = null) },
                                        onClick = {
                                            menuForIndex = null
                                            onSignAddress(
                                                BitcoinSignAddressTarget(
                                                    chain = if (receiveKeychain) 0L else 1L,
                                                    index = row.index,
                                                    address = row.address,
                                                ),
                                            )
                                        },
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("#${row.index}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Box(Modifier.weight(1f))
                                    Text(
                                        when {
                                            row.balanceSat > 0 -> formatSatsCompact(row.balanceSat, ticker)
                                            row.totalReceivedSat > 0 -> formatSatsCompact(row.totalReceivedSat, ticker)
                                            else -> "-"
                                        },
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (row.balanceSat > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        row.address,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { qrFor = row.address }) {
                                        Icon(Icons.Filled.QrCode2, contentDescription = stringResource(R.string.btc_show_qr))
                                    }
                                    IconButton(onClick = {
                                        clipboard.setText(AnnotatedString(row.address))
                                        copied = row.address
                                    }) {
                                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.btc_copy_address))
                                    }
                                    if (row.totalReceivedSat > 0 && active != null) {
                                        IconButton(onClick = {
                                            val url = env.settings.addressUrl(row.address, active.network)
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                        }) {
                                            Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.btc_open_in_explorer))
                                        }
                                    }
                                }
                                val utxoTag = stringResource(R.string.btc_utxo_count, row.utxoCount.toString())
                                val unusedTag = stringResource(R.string.btc_unused)
                                val tags = buildList {
                                    if (row.utxoCount > 0) add(utxoTag)
                                    if (row.balanceSat == 0L && row.totalReceivedSat == 0L) add(unusedTag)
                                }
                                if (tags.isNotEmpty()) {
                                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    qrFor?.let { addr ->
        AlertDialog(
            onDismissRequest = { qrFor = null },
            confirmButton = { TextButton(onClick = { qrFor = null }) { Text(stringResource(R.string.common_done)) } },
            title = { Text(stringResource(R.string.btc_receive_to_address)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    QrCode(content = addr, modifier = Modifier.size(240.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        addr,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
        )
    }
}
