package com.elabify.app.maknoon.ui
import com.elabify.app.maknoon.R

import androidx.compose.ui.res.stringResource

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.elabify.app.maknoon.ui.identity.IdentityScreen
import com.elabify.app.maknoon.ui.miniapp.AppsScreen
import com.elabify.app.maknoon.ui.onboarding.OnboardingScreen
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonTheme
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.wallet.WalletScreen
import com.elabify.musnad.identity.IdentitySession
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The three top-level tabs, mirroring the iOS ContentView: Identity | Wallet
// | Apps. Icons match the iOS SF Symbols as closely as Material allows, and
// like iOS swap outline (unselected) -> filled (selected): AccountCircle ~
// person.crop.circle, CreditCard = creditcard, GridView ~ square.grid.2x2.
//
// The label is a string RESOURCE, not a String. As literals in this enum's
// constructor the three tab names shipped English in all 31 locales: an enum
// constructor cannot call stringResource, and the strings themselves already
// existed and were translated, so the only thing missing was the indirection.
// iOS had them localized, which is how the gap showed up.
private enum class Tab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    IDENTITY(R.string.identity_title, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
    WALLET(R.string.devices_wallet, Icons.Outlined.CreditCard, Icons.Filled.CreditCard),
    APPS(R.string.app_apps, Icons.Outlined.GridView, Icons.Filled.GridView),
}

@Composable
fun MaknoonRoot() {
    // Root identity gate (mirrors iOS): with NO identity, the app is a full-screen
    // welcome/onboarding flow, NO bottom tabs, NO wallet. Only once an identity
    // exists (created or restored) do the Identity / Wallet / Apps tabs appear.
    // Reactive: onboarding's onComplete bumps reloadKey; a fresh process after a
    // Reset (identity wiped) lands here with no identity and shows onboarding.
    val context = LocalContext.current
    val store = remember { IdentityStore(context) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var hasIdentity by remember {
        mutableStateOf<Boolean?>(if (IdentitySession.peek() != null) true else null)
    }
    LaunchedEffect(reloadKey) {
        hasIdentity = if (IdentitySession.peek() != null) {
            true
        } else {
            withContext(Dispatchers.IO) {
                runCatching { IdentitySession.loadCached(store) != null }.getOrDefault(false)
            }
        }
    }

    when (hasIdentity) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        // Onboarding is always dark, mirroring iOS (preferredColorScheme(.dark)):
        // crisp light text on a dark ground, regardless of the system theme, so
        // the welcome / setup copy is never the washed-out grey of light mode.
        false -> MaknoonTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                OnboardingScreen(onComplete = { reloadKey++ })
            }
        }
        true -> MainTabs()
    }
}

@Composable
private fun MainTabs() {
    // rememberSaveable so the selected tab survives Activity recreation / process
    // death. Rotation no longer recreates the Activity (MainActivity declares
    // configChanges), so together they stop the tab snapping back to Identity on
    // rotation (item 9). Tab is an enum (Serializable), so the default saver
    // handles it.
    var selected by rememberSaveable { mutableStateOf(Tab.IDENTITY) }
    // Re-tap-to-home: tapping a tab while ALREADY on it bumps its key, which the
    // tab screen observes to pop its in-screen navigation back to its home
    // (mirrors iOS popping that tab's stack to root).
    var walletResetKey by remember { mutableIntStateOf(0) }
    var identityResetKey by remember { mutableIntStateOf(0) }
    var appsResetKey by remember { mutableIntStateOf(0) }
    // Set when a Verify & Pay settles: switch to the Wallet tab and open this
    // chain so the payer sees their pending tx. Consumed (cleared) by WalletScreen.
    var walletDeepLinkChain by remember { mutableStateOf<String?>(null) }

    // Auto-lock: any touch resets the inactivity timer; a ticking check + a
    // resume check raise the lock gate after the configured timeout.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            AppLockManager.lockIfTimedOut()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) AppLockManager.lockIfTimedOut()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Observe (do not consume) any pointer-down to record activity.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        AppLockManager.recordActivity()
                    }
                }
            },
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = {
                                // Re-tapping a tab while already on it resets it
                                // to its home (network list / identity hub / apps).
                                if (selected == tab) {
                                    when (tab) {
                                        Tab.WALLET -> walletResetKey++
                                        Tab.IDENTITY -> identityResetKey++
                                        Tab.APPS -> appsResetKey++
                                    }
                                }
                                selected = tab
                            },
                            icon = {
                                Icon(
                                    if (selected == tab) tab.selectedIcon else tab.icon,
                                    contentDescription = stringResource(tab.labelRes),
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                            // Mirror iOS .tint(.purple): selected tab is the brand
                            // purple (icon + label), unselected is muted, and no
                            // filled pill (iOS shows selection by colour alone).
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            // Only consume the BOTTOM inset (the nav/tab bar). Each tab screen
            // owns its own top status-bar inset via its TopAppBar.
            Box(modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                when (selected) {
                    Tab.IDENTITY -> IdentityScreen(
                        resetKey = identityResetKey,
                        onNavigateToWallet = { chain ->
                            walletDeepLinkChain = chain
                            selected = Tab.WALLET
                        },
                    )
                    Tab.WALLET -> WalletScreen(
                        resetKey = walletResetKey,
                        initialChain = walletDeepLinkChain,
                        onInitialChainConsumed = { walletDeepLinkChain = null },
                    )
                    Tab.APPS -> AppsScreen(
                        resetKey = appsResetKey,
                        onNavigateToWallet = { chain ->
                            walletDeepLinkChain = chain
                            selected = Tab.WALLET
                        },
                    )
                }
            }
        }

        if (AppLockManager.locked) {
            LockScreen(onUnlocked = { AppLockManager.unlock() })
        }
    }
}

// Full-screen lock gate shown after auto-lock. Auto-prompts biometric on appear
// and offers an Unlock button to retry.
@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var attempting by remember { mutableStateOf(false) }
    // Resolved here: attempt() below is a plain local fun, not a composable.
    val promptTitle = stringResource(R.string.app_unlock_maknoon)
    val promptSubtitle = stringResource(R.string.app_confirm_its_you)

    fun attempt() {
        val act = activity ?: return
        if (attempting) return
        // Only launch the prompt while the activity is actually resumed:
        // launching during a lifecycle transition makes BiometricPrompt throw,
        // which previously stranded `attempting` = true and froze the Unlock
        // button (item 8). The ON_RESUME observer below re-attempts once resumed.
        if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        attempting = true
        scope.launch {
            // authenticate() no longer throws (it resumes false on error), but
            // guard anyway so `attempting` is ALWAYS cleared and the button stays
            // live for a retry.
            val ok = runCatching {
                BiometricGate.authenticate(act, title = promptTitle, subtitle = promptSubtitle)
            }.getOrDefault(false)
            attempting = false
            if (ok) onUnlocked()
        }
    }

    LaunchedEffect(Unit) { attempt() }
    // Re-prompt when the app returns to the foreground (the initial auto-prompt
    // may have been skipped if the activity was mid-transition).
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) attempt()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            // Edge-to-edge: this lock overlay renders above the tab Scaffold, so
            // it must inset itself from the system bars.
            Modifier.fillMaxSize().systemBarsPadding().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = MaknoonBrand.accent,
                modifier = Modifier.size(48.dp),
            )
            Text(
                stringResource(R.string.app_maknoon_is_locked),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.md),
            )
            Text(
                stringResource(R.string.app_unlock_with_your_fingerprint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg),
            )
            Button(onClick = { attempt() }) { Text(stringResource(R.string.app_unlock)) }
        }
    }
}
