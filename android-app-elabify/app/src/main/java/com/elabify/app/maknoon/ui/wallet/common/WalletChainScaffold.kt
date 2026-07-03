// Shared chrome for every chain DETAIL screen (the chain dashboard and the
// per-chain receive screen). Previously each chain dashboard hand-rolled its
// own Column with an in-body header row, which never inset the status bar, so
// the chain title collided with the system clock and battery. The Send screens
// already used SendFormScaffold (a Scaffold + TopAppBar that owns the
// status-bar inset and is correct). This file gives the dashboards and receive
// screens the SAME inset-correct chrome: ONE Scaffold + TopAppBar that owns the
// leading back arrow, the chain title, the trailing actions slot, and the
// default Material3 status-bar window insets. No chain owns its own chrome.
//
//   WalletChainScaffold -> the chain dashboard chrome (back arrow + title +
//                          trailing actions like Wallets / Settings)
//   ReceiveScaffold     -> the same chrome for the per-chain receive screen
//                          (default title "Receive", back arrow, no actions)
//
// Both pass the inner PaddingValues through to content so callers apply the
// scaffold inset to their own scrollable body (exactly like SendFormScaffold).

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R

// The chain dashboard chrome. A Scaffold whose TopAppBar carries a leading
// back arrow (calls onBack), the chain [title], and a trailing [actions] slot
// (a RowScope so callers drop in TextButton("Wallets") / TextButton("Settings")
// or IconButtons exactly where the iOS toolbar trailing items sit). The
// TopAppBar keeps the default Material3 window insets, so it consumes the
// status-bar inset and the title never collides with the system clock /
// battery. [content] receives the inner PaddingValues; callers apply it to
// their scrollable body the same way the Send screens do.
//
// When [onRefresh] is non-null the body is wrapped in a PullToRefreshBox so a
// swipe-down anywhere on the dashboard resyncs the account (iOS parity); the
// dashboards pass their existing sync/busy flag as [isRefreshing] and their
// existing resync as [onRefresh]. Screens that pass neither (receive, settings,
// accounts) keep the plain non-refreshing body.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletChainScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = actions,
            )
        },
    ) { innerPadding ->
        if (onRefresh != null) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                content(innerPadding)
            }
        } else {
            content(innerPadding)
        }
    }
}

// The single, shared per-network dashboard "+" menu. Every chain dashboard drops
// this into its WalletChainScaffold [actions] slot, so the menu chrome AND its
// ORDER are identical across Bitcoin, Ethereum, Solana, Tron, and Lightning.
//
// CANONICAL ORDER (ADR-0033, revised 0.6.2) — every item is optional except
// Manage wallets, and each chain shows the subset it supports, ALWAYS in this
// order so the layout never reshuffles between chains:
//
//   1. Add wallet…      (onAddWallet)     plus.circle           -> AddCircleOutline
//   2. Manage wallets…  (onManage)        list.bullet.rectangle -> ListAlt
//   3. Settings         (onSettings)      gearshape             -> Settings
//   --- divider (only when a message tool follows) ---
//   4. Sign message     (onSignMessage)   signature             -> Draw
//   5. Verify message   (onVerifyMessage) checkmark.seal        -> Verified
//   --- divider (only when WalletConnect follows) ---
//   6. WalletConnect    (onWalletConnect) link                  -> Link   (EVM only)
//
// Management / configuration items come first, then a divider, then the
// message-signing tools, mirroring the iOS BitcoinWalletView / EthereumWalletView
// toolbar menus. Chains that move Settings to a gear on their Manage-wallets
// screen (Bitcoin + Ethereum, matching iOS, which keeps Settings out of this
// menu) pass null for onSettings. A chain without message signing passes null
// for onSignMessage / onVerifyMessage and those items + the divider are omitted.
// Any future network that gains sign/verify MUST reuse this menu so the order
// stays uniform. No OTHER chain-specific tool may be added here.
@Composable
fun WalletActionsMenu(
    onManage: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onAddWallet: (() -> Unit)? = null,
    onSignMessage: (() -> Unit)? = null,
    onVerifyMessage: (() -> Unit)? = null,
    onWalletConnect: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.AddCircleOutline, contentDescription = stringResource(R.string.wallet_wallet_actions))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        if (onAddWallet != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wallet_add_wallet_ellipsis)) },
                leadingIcon = { Icon(Icons.Filled.AddCircleOutline, contentDescription = null) },
                onClick = { expanded = false; onAddWallet() },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.wallet_manage_wallets_ellipsis)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null) },
            onClick = { expanded = false; onManage() },
        )
        if (onSettings != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_settings)) },
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                onClick = { expanded = false; onSettings() },
            )
        }
        if (onSignMessage != null || onVerifyMessage != null) {
            HorizontalDivider()
        }
        if (onSignMessage != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wallet_sign_message)) },
                leadingIcon = { Icon(Icons.Filled.Draw, contentDescription = null) },
                onClick = { expanded = false; onSignMessage() },
            )
        }
        if (onVerifyMessage != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wallet_verify_message)) },
                leadingIcon = { Icon(Icons.Filled.Verified, contentDescription = null) },
                onClick = { expanded = false; onVerifyMessage() },
            )
        }
        // WalletConnect is its OWN group after a SECOND divider and is LAST in
        // the menu, matching iOS EthereumWalletView exactly: Add/Manage -- div --
        // Sign/Verify -- div -- WalletConnect. EVM-only (other chains pass null).
        if (onWalletConnect != null) {
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("WalletConnect") },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                onClick = { expanded = false; onWalletConnect() },
            )
        }
    }
}

// The per-chain receive-screen chrome. Same Scaffold + TopAppBar (and the same
// status-bar inset ownership) as the dashboard, with a default [title] of
// "Receive" and a leading back arrow. No trailing actions. [content] receives
// the inner PaddingValues.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScaffold(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.walletc_receive),
    content: @Composable (PaddingValues) -> Unit,
) {
    WalletChainScaffold(
        title = title,
        onBack = onBack,
        modifier = modifier,
        content = content,
    )
}
