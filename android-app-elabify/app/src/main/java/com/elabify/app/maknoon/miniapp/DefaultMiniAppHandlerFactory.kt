// Concrete MiniAppHandlerFactory: the single place the Android host wires the
// namespace handlers to the real Context-backed SDK stores + the per-host
// ApprovalGate. The Android analog of iOS MiniAppHostView.makeHandlers
// (see that file for the canonical grant-gating).
//
// What gets built, and the dep each handler receives:
//   storage     (auto)      StorageBridgeHandler(installedAppId, MiniAppSettingsStore)
//   merchant    (auto)      MerchantBridgeHandler(CommerceHolderContext, installedAppId)
//   fiat        (auto)      FiatBridgeHandler(Context)
//   device      (auto)      DeviceBridgeHandler(Context, activityProvider)
//   haptic      (auto)      HapticBridgeHandler(Context)
//   addressBook ("payment") AddressBookBridgeHandler(Context)
//   clipboard   ("clipboard") ClipboardBridgeHandler(Context)
//   share       ("share")   ShareBridgeHandler(Context)
//   wallet      ("wallet")  WalletBridgeHandler(Context)
//   scan        ("scan")    ScanBridgeHandler(appTitle, gate)
//   eth         ("evm")     Web3BridgeHandler(MiniAppWeb3Environment, gate, appTitle)
//   maknoon     ("identity") IdentityBridgeHandler(IdentityStore, appTitle, installedAppId, gate, verifierBaseUrl)
//   payment     ("payment") PaymentBridgeHandler(appTitle, MiniAppPaymentCoordinator, gate, lightningAccounts)
//   commerce    ("payment") CommerceBridgeHandler(CommerceHolderContext, appTitle, installedAppId, MiniAppCommerceCoordinator, gate)
//
// We register handlers UNCONDITIONALLY (the iOS code grant-gates a couple at
// build time, but the bridge dispatcher already enforces requiredPermission
// against the granted set before calling handle(), so registering an ungranted
// handler is harmless: its calls reject with 4100). Registering them all keeps
// this factory free of grant logic and matches the dispatcher's contract.
//
// The per-install merchant display name (set by the dApp via
// window.maknoon.storage("merchantName")) overrides spec.title for the consent
// UI, mirroring iOS.
//
// Commerce: the factory injects RealCommerceHolderContext, which wires the
// holder side (credential match, presentation build, offline verify), the
// wallet side (EVM software signing + keccak), ML-DSA verify, and the X-Wing
// transport (M2) to real implementations. EVM Verify & Pay works end to end;
// hardware EVM commerce + non-EVM settlement (SOL/TRON/BTC/Lightning) are a
// later add (M4), flagged not-yet-payable by signEvmTransfer.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.PrefsEthereumStore
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumStores
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.walletPrefs
import com.elabify.app.maknoon.ui.miniapp.MiniAppHandlerFactory
import com.elabify.app.maknoon.ui.miniapp.MiniAppLaunchSpec
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * The host-trusted endpoints the mini-app stack talks to. The verifier base and
 * the relay/drop host are the same origin the rest of the holder app already
 * trusts (iOS HolderStore.elabifyDropHost). Hoisted to a constant here so a
 * future Settings override has one place to feed.
 */
object MiniAppHosts {
    // Read from the user-overridable RelaySettings (#61). Default is the public
    // elabify verifier; a self-hoster can repoint it in Settings > Identity.
    val VERIFIER_BASE_URL: String get() = com.elabify.app.maknoon.ui.settings.RelaySettings.host
    val DROP_HOST: String get() = com.elabify.app.maknoon.ui.settings.RelaySettings.host
}

/**
 * The concrete [MiniAppHandlerFactory]. Construct one per host with the
 * application [Context]; it owns the per-app settings store + the side-table
 * coordinators the commerce / payment sheets resolve their live state from.
 *
 * @param context any Context; only the applicationContext is retained, plus an
 *   [activityProvider] for the few capabilities (device.authenticate) that need
 *   a FragmentActivity for a BiometricPrompt. The AppsScreen passes the hosting
 *   activity through so the biometric gate is available.
 */
class DefaultMiniAppHandlerFactory(
    context: Context,
    private val settingsStore: MiniAppSettingsStore,
    /** Side-tables the commerce / payment handlers stash live state into; the
     *  approval-sheet host reads them back by token. Shared per host. */
    val commerceCoordinator: MiniAppCommerceCoordinator = MiniAppCommerceCoordinator(),
    val paymentCoordinator: MiniAppPaymentCoordinator = MiniAppPaymentCoordinator(),
    /** Bridge -> host navigation (e.g. open the wallet after a swap). Shared with
     *  AppsScreen, which collects it and performs the pop + tab switch. */
    val navCoordinator: MiniAppNavCoordinator = MiniAppNavCoordinator(),
    /** Supplies the visible activity for BiometricPrompt; null degrades device
     *  auth to { ok:false, reason:"unavailable" }, matching iOS. */
    private val activityProvider: () -> FragmentActivity? = { null },
) : MiniAppHandlerFactory {

    private val appContext = context.applicationContext

    override fun build(
        spec: MiniAppLaunchSpec,
        scope: CoroutineScope,
        gate: ApprovalGate,
    ): List<MiniAppNamespaceHandler> {
        // Per-install merchant display name overrides the catalog title for the
        // consent UI (the dApp sets it via window.maknoon.storage("merchantName")).
        val displayName = settingsStore
            .value(spec.installedAppId, "merchantName")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: spec.title

        // Context-backed SDK stores. Each handler that reads wallets builds its
        // own EthereumWalletStore over the shared wallet prefs (cheap; matches
        // the system handlers' own construction), so we only build the env the
        // Web3 handler needs here.
        val prefs = walletPrefs(appContext)
        val ethSettings = EthereumSettings(PrefsEthereumStore(prefs)).also { it.reload() }
        val ethStore = EthereumWalletStore(PrefsEthereumStore(prefs)).also { it.reload() }
        val web3Env = MiniAppWeb3Environment.of(
            walletStore = ethStore,
            settings = ethSettings,
            deviceResolver = { id -> com.elabify.musnad.devices.DeviceRegistry(appContext).find(id) },
        ) { loadSandwichOrNull() }

        // The commerce / merchant slice's cross-cutting context, fully real:
        // holder present/match, EVM software signing, ML-DSA verify, and the
        // X-Wing transport (M2/M3). See RealCommerceHolderContext.
        val commerceCtx = RealCommerceHolderContext(
            context = appContext,
            dropHost = MiniAppHosts.DROP_HOST,
            sandwichLoader = { loadSandwichOrNull() },
        )

        return listOf(
            // ---- always available (auto) ----
            StorageBridgeHandler(
                installedAppId = spec.installedAppId,
                store = settingsStore,
            ),
            MerchantBridgeHandler(
                ctx = commerceCtx,
                installedAppId = spec.installedAppId,
            ),
            FiatBridgeHandler(appContext),
            DeviceBridgeHandler(appContext, activityProvider),
            HapticBridgeHandler(appContext),

            // ---- install-gated (the bridge enforces the grant) ----
            AddressBookBridgeHandler(appContext),   // "payment"
            ClipboardBridgeHandler(appContext),     // "clipboard"
            ShareBridgeHandler(appContext),         // "share"
            WalletBridgeHandler(appContext),        // "wallet"

            // ---- per-use (gate + the bridge enforces the grant) ----
            ScanBridgeHandler(appTitle = displayName, gate = gate),                // "scan"
            Web3BridgeHandler(
                env = web3Env, gate = gate, appTitle = displayName,       // "evm"
                onBroadcast = { walletId, hash, senderAddr, recipient, wei, net ->
                    // Record on the SHARED store the UI reads (web3Env uses a throwaway).
                    val shared = EthereumStores.walletStore(appContext)
                    shared.markPendingOutbound(
                        senderWalletId = walletId, txHash = hash, senderAddress = senderAddr,
                        recipientAddress = recipient, weiValue = wei,
                    )
                    shared.setCurrentNetwork(net, walletId)
                },
            ),
            IdentityBridgeHandler(                                                 // "identity"
                store = IdentityStore(appContext),
                appTitle = displayName,
                installedAppId = spec.installedAppId,
                gate = gate,
                verifierBaseUrl = MiniAppHosts.VERIFIER_BASE_URL,
                loadCredentials = { MaknoonStore.open(appContext).credentials().all() },
                recordDisclosure = { e -> MaknoonStore.open(appContext).verifierHistory().insert(e) },
            ),
            PoolAccessBridgeHandler(                                               // "poolAccess"
                env = web3Env,
                gate = gate,
                appTitle = displayName,
                loadCredentials = { MaknoonStore.open(appContext).credentials().all() },
            ),
            PoolRegistryBridgeHandler(),                                           // "pools" (wallet.ethereum.read)
            OpenWalletBridgeHandler(nav = navCoordinator),                         // "walletView" (wallet.ethereum.read)
            PaymentBridgeHandler(                                                  // "payment"
                appTitle = displayName,
                coordinator = paymentCoordinator,
                gate = gate,
                // Lightning accounts are not yet wired into the Android mini-app
                // host (the Lightning store lives in the wallet slice and is not
                // surfaced here yet); empty list = the dApp's picker shows none.
                lightningAccounts = { emptyList() },
            ),
            CommerceBridgeHandler(                                                 // "payment"
                ctx = commerceCtx,
                appTitle = displayName,
                installedAppId = spec.installedAppId,
                coordinator = commerceCoordinator,
                gate = gate,
            ),
        )
    }

    private fun loadSandwichOrNull(): IdentitySandwich? =
        runCatching { IdentitySandwich.load(IdentityStore(appContext)) }.getOrNull()
}
