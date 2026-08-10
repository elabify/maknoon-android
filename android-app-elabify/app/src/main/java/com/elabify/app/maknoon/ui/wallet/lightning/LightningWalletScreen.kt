// LightningWalletScreen: the single top-level entry composable the Wallet tab
// routes to for the Lightning chain. Mirrors iOS LightningWalletView.swift: an
// account picker, sat balance (+ refresh/sync state), Send / Receive (+
// Withdraw) buttons, and the recent-payments list, plus internal navigation to
// receive / send / withdraw / history / settings / accounts screens.
//
// Lightning is custodial (LNDHub), so there is no seed-derived key or biometric
// reveal here: the active account's password is sealed in AndroidSecureStore
// and resolved by LightningEnv when building the LndHubClient. All LNDHub
// network calls run off the main thread (Dispatchers.IO).

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.EmptyState
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.BalanceCard
import com.elabify.app.maknoon.ui.wallet.common.FiatReference
import com.elabify.app.maknoon.ui.wallet.common.WalletActionsMenu
import com.elabify.app.maknoon.ui.wallet.common.WalletChainScaffold
import com.elabify.musnad.wallet.lightning.LightningTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class LnRoute { DASHBOARD, RECEIVE, SEND, HISTORY, SETTINGS, ACCOUNTS, ADD_ACCOUNT }

@Composable
fun LightningWalletScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }

    var route by remember { mutableStateOf(LnRoute.DASHBOARD) }
    var reloadKey by remember { mutableIntStateOf(0) }

    when (route) {
        LnRoute.DASHBOARD -> LightningDashboard(
            reloadKey = reloadKey,
            onBack = onBack,
            onReceive = { route = LnRoute.RECEIVE },
            onSend = { route = LnRoute.SEND },
            onHistory = { route = LnRoute.HISTORY },
            onSettings = { route = LnRoute.SETTINGS },
            onAccounts = { route = LnRoute.ACCOUNTS },
            onAddAccount = { route = LnRoute.ADD_ACCOUNT },
            onChanged = { reloadKey++ },
        )

        LnRoute.RECEIVE -> LightningReceiveScreen(
            onCreated = { reloadKey++ },
            onBack = { route = LnRoute.DASHBOARD },
        )

        LnRoute.SEND -> LightningSendScreen(
            onPaid = { reloadKey++ },
            onBack = { route = LnRoute.DASHBOARD },
        )

        LnRoute.HISTORY -> LightningHistoryScreen(onBack = { route = LnRoute.DASHBOARD })

        LnRoute.SETTINGS -> LightningSettingsScreen(
            onManageAccounts = { route = LnRoute.ACCOUNTS },
            onBack = { route = LnRoute.DASHBOARD },
        )

        LnRoute.ACCOUNTS -> LightningAccountsScreen(
            onBack = { route = LnRoute.DASHBOARD },
            onChanged = { reloadKey++ },
        )

        LnRoute.ADD_ACCOUNT -> LightningAccountsScreen(
            onBack = { route = LnRoute.DASHBOARD },
            onChanged = { reloadKey++ },
            startInAdd = true,
        )
    }
}

private data class DashboardData(
    val balanceSat: Long,
    val txs: List<LightningTx>,
    val fiat: String? = null,
)

@Composable
private fun LightningDashboard(
    reloadKey: Int,
    onBack: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onChanged: () -> Unit,
) {
    val syncFailedMsg = stringResource(R.string.ln_sync_failed)
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }

    var data by remember { mutableStateOf<DashboardData?>(null) }
    var syncing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    val active = env.activeAccount

    LaunchedEffect(reloadKey, active?.id) {
        syncing = true
        error = null
        val account = active
        if (account == null) { syncing = false; data = null; return@LaunchedEffect }

        // Build the client (keystore unwrap) + fetch balance and history all
        // off-main. Balance and history are independent so a history hiccup
        // doesn't hide a good balance (mirrors the iOS two-step refresh).
        val balanceResult = withContext(Dispatchers.IO) {
            runCatching {
                val client = env.clientFor(account) ?: error("Password missing for this account. Re-import it.")
                client.balanceSat()
            }
        }
        val historyResult = withContext(Dispatchers.IO) {
            runCatching {
                val client = env.clientFor(account) ?: error("Password missing for this account. Re-import it.")
                client.history(limit = 50)
            }
        }

        val bal = balanceResult.getOrNull()
        val txs = historyResult.getOrNull() ?: data?.txs ?: emptyList()
        if (bal != null) {
            // Lightning is custodial BTC: always price sats as bitcoin (sats -> BTC).
            val fiat = FiatReference.caption("bitcoin", bal / 100_000_000.0)
            data = DashboardData(balanceSat = bal, txs = txs, fiat = fiat)
        } else {
            error = balanceResult.exceptionOrNull()?.let { it.message ?: it.toString() } ?: syncFailedMsg
            historyResult.getOrNull()?.let { data = (data ?: DashboardData(0, emptyList())).copy(txs = it) }
        }
        syncing = false
    }

    WalletChainScaffold(
        title = stringResource(R.string.ln_lightning),
        onBack = onBack,
        actions = {
            WalletActionsMenu(onManage = onAccounts, onSettings = onSettings)
        },
        isRefreshing = syncing,
        onRefresh = onChanged,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Account picker.
            Box {
            Surface(
                shape = RoundedCornerShape(Radii.md),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = Elevation.card,
                modifier = Modifier.fillMaxWidth().clickable(enabled = env.accountStore.accounts.isNotEmpty()) { pickerOpen = true },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    // Brand-yellow Lightning logo (consistent with the other chains
                    // and the Wallet-tab list); accounts are disambiguated by label.
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFFAB300).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(com.elabify.app.maknoon.R.drawable.ic_chain_lightning),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(active?.label ?: stringResource(R.string.ln_no_account), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            active?.let { "${it.username} · ${hostOf(it.serverURL)}" } ?: "-",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Icon(Icons.Filled.UnfoldMore, contentDescription = stringResource(R.string.ln_switch_account), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                env.accountStore.accounts.forEach { a ->
                    DropdownMenuItem(
                        text = { Text(if (a.id == active?.id) "✓ ${a.label}" else a.label) },
                        onClick = {
                            env.accountStore.setActive(a.id)
                            pickerOpen = false
                            onChanged()
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(stringResource(R.string.ln_manage_accounts)) }, onClick = { pickerOpen = false; onAccounts() })
            }
        }

        when {
            active == null -> {
                EmptyState(
                    icon = Icons.Filled.Bolt,
                    title = stringResource(R.string.ln_no_account_yet),
                    subtitle = stringResource(R.string.ln_no_account_subtitle),
                    action = {
                        TextButton(onClick = onAddAccount) {
                            Icon(Icons.Filled.Bolt, contentDescription = null)
                            Spacer(Modifier.size(Spacing.xs))
                            Text(stringResource(R.string.ln_add_account))
                        }
                    },
                )
            }

            else -> {
                // Balance card (elevated, brand-tinted) with sync state inside.
                BalanceCard(
                    amount = data?.let { formatSats(it.balanceSat) } ?: "-",
                    ticker = "sats",
                    syncing = syncing,
                    syncLabel = stringResource(R.string.ln_synced),
                    onRefresh = onChanged,
                    subnote = data?.fiat,
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    LnActionTile(stringResource(R.string.walletc_send), Icons.AutoMirrored.Filled.CallMade, Modifier.weight(1f), onSend)
                    LnActionTile(stringResource(R.string.walletc_receive), Icons.AutoMirrored.Filled.CallReceived, Modifier.weight(1f), onReceive)
                }

                error?.let {
                    Banner(title = stringResource(R.string.ln_sync_error), variant = BannerVariant.ERROR, body = it)
                }

                // Recent payments.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ln_recent_payments), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onHistory) { Text(stringResource(R.string.common_see_all)) }
                }
                val txs = data?.txs ?: emptyList()
                if (txs.isEmpty()) {
                    Surface(shape = RoundedCornerShape(Radii.md), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            icon = Icons.Filled.Inbox,
                            title = stringResource(R.string.ln_no_payments_yet),
                            subtitle = stringResource(R.string.ln_no_payments_subtitle),
                            iconSize = 40.dp,
                        )
                    }
                } else {
                    txs.take(20).forEach { tx ->
                        Surface(shape = RoundedCornerShape(Radii.sm), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)) { LightningTxRow(tx) }
                        }
                    }
                }
            }
        }
    }
    }
}

// MARK: -- shared dashboard visuals (Lightning)

/** Lightning brand amber, theming the dashboard action row to the network. */
private val LightningAmber = Color(0xFFFAB300)

@Composable
private fun LnActionTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(Radii.md),
        color = LightningAmber.tint(0.12f),
    ) {
        Column(
            Modifier.padding(vertical = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(icon, contentDescription = null, tint = LightningAmber)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = LightningAmber)
        }
    }
}
