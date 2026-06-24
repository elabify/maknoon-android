// "Scan a paired hardware device for its wallets" flow, ported from iOS
// DiscoverHardwareWalletsView / DiscoverSolanaWalletsView.
//
// Pins ONE BLE session for the whole sweep (beginSession/endSession) so the
// back-to-back reads share a single connection. Ledger drops the link
// mid-scan otherwise (see the contract's session-pinning note). Within the
// session it:
//
//   1. Reconnects and confirms the live serial matches the registered
//      serial (refuses to sweep a different physical device).
//   2. Walks account indices 0, 1, 2, ... on the chosen chain, reading each
//      account's receive identity via the HardwareWallet contract:
//        - Bitcoin  -> getBitcoinAccountXpub(account, coinType)
//        - Ethereum -> getEthereumAddress(account)
//        - Solana   -> getSolanaAddress(account)
//        - Tron     -> getTronAddress(account)
//      then probes that identity for on-chain activity over the network.
//   3. Stops after EMPTY_ACCOUNT_GAP_LIMIT (4) consecutive empty accounts
//      (the BIP44 account-level gap rule iOS uses), and surfaces ONLY the
//      accounts that had activity, mirroring iOS. A fresh Trezor hidden
//      wallet's account 0 is always kept (iOS keepEmptyFirst) so it can be
//      added even with no activity yet.
//
// Each device round-trip prompts the user on-device only once per session
// (Ledger auto-confirms address reads with display_flag=0 once the chain's
// app is open). The activity probe is a real network call per account
// (Electrum full-scan for Bitcoin, JSON-RPC for the others); each can take
// several seconds. Per iOS, an endpoint error for an account counts as "no
// activity" for that account rather than aborting the whole sweep. The
// wallet-store insertion is orchestrator-owned (the wallet stores live in
// sibling components); this screen reports the discovered accounts via
// onDone so the host can persist the user's selection.

package com.elabify.app.maknoon.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.content.Context
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.wallet.common.PassphraseField
import android.util.Log
import com.elabify.app.maknoon.ui.wallet.common.HardwareStage
import com.elabify.app.maknoon.ui.wallet.common.HardwareStageLine
import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumStores
import com.elabify.app.maknoon.ui.wallet.ethereum.resolveCurrentNetwork
import com.elabify.app.maknoon.ui.wallet.tron.TronStores
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.HardwareWallet
import com.elabify.musnad.hardware.HardwareWalletFactory
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.hardware.trezor.PassphraseChoice
import com.elabify.musnad.hardware.trezor.TrezorHardwareWallet
import com.elabify.musnad.wallet.PrefsBitcoinStore
import com.elabify.musnad.wallet.PrefsSolanaStore
import com.elabify.musnad.wallet.bitcoin.Bip32Path
import com.elabify.musnad.wallet.bitcoin.BitcoinDescriptors
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinSettings
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.solana.SolanaRPCClient
import com.elabify.musnad.wallet.solana.SolanaSettings
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronRPCClient
import com.elabify.musnad.wallet.walletPrefs
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Chains the discover sweep can read from a registered device.
 *  `internal` (not file-private) so the host (DevicesScreen) can route the
 *  selected accounts to the right per-chain wallet store when persisting.
 *
 *  NOTE: the BIP44 coin type is NO LONGER fixed on the chain. Bitcoin's coin
 *  type varies by the SELECTED network (0' mainnet, 1' testnet3/signet), so it
 *  is resolved from the chosen [SweepNetwork] at sweep time. The other three
 *  chains derive the same address on every network of their family, so their
 *  coin type is constant and read from the chain itself. */
internal enum class DiscoverChain(val displayName: String, val coinType: Long) {
    BITCOIN("Bitcoin", 0L),
    ETHEREUM("Ethereum", 60L),
    SOLANA("Solana", 501L),
    TRON("Tron", 195L);

    /**
     * All four chains read on both vendors: Ledger and Trezor each implement
     * getTronAddress (the earlier Ledger-only Tron gate was wrong; Trezor
     * firmware does support Tron and TrezorHardwareWallet.getTronAddress exists).
     */
    fun isSupportedOn(@Suppress("UNUSED_PARAMETER") kind: DeviceKind): Boolean = true
}

/**
 * The single chain + network the sweep runs on. Wraps each chain's own
 * network enum so the picker, the probe, and persistence all agree on exactly
 * one (chain, network) target. [rawValue] is the network enum's persisted
 * rawValue (so it round-trips onto [DiscoveredHardwareAccount.networkRawValue]
 * and back into the right enum when the host persists); [displayName] is the
 * dropdown label.
 */
internal sealed class SweepNetwork {
    abstract val rawValue: String
    abstract val displayName: String

    data class Bitcoin(val network: BitcoinNetwork) : SweepNetwork() {
        override val rawValue get() = network.rawValue
        override val displayName get() = network.displayName
    }

    data class Ethereum(val network: EthereumNetwork) : SweepNetwork() {
        override val rawValue get() = network.rawValue
        override val displayName get() = network.displayName
    }

    data class Solana(val network: SolanaNetwork) : SweepNetwork() {
        override val rawValue get() = network.rawValue
        override val displayName get() = network.displayName
    }

    data class Tron(val network: TronNetwork) : SweepNetwork() {
        override val rawValue get() = network.rawValue
        override val displayName get() = network.displayName
    }

    companion object {
        /**
         * All networks of [chain]'s family, INCLUDING testnets, in enum order.
         * This is what populates the network dropdown.
         */
        fun networksFor(chain: DiscoverChain): List<SweepNetwork> = when (chain) {
            DiscoverChain.BITCOIN -> BitcoinNetwork.entries.map { Bitcoin(it) }
            DiscoverChain.ETHEREUM -> EthereumNetwork.entries.map { Ethereum(it) }
            DiscoverChain.SOLANA -> SolanaNetwork.entries.map { Solana(it) }
            DiscoverChain.TRON -> TronNetwork.entries.map { Tron(it) }
        }

        /**
         * The network the picker should default to when [chain] is selected:
         * that chain's currently-selected network from its settings, falling
         * back to mainnet. Reads SharedPreferences, so call off the hot path.
         */
        fun defaultFor(context: Context, chain: DiscoverChain): SweepNetwork {
            val app = context.applicationContext
            return when (chain) {
                DiscoverChain.BITCOIN -> Bitcoin(BitcoinNetwork.MAINNET)
                DiscoverChain.ETHEREUM -> {
                    val resolved = runCatching { resolveCurrentNetwork(app) }.getOrNull()
                    val net = resolved?.let { EthereumNetwork.fromChainId(it.chainId) }
                        ?: EthereumNetwork.MAINNET
                    Ethereum(net)
                }
                DiscoverChain.SOLANA -> {
                    val sel = runCatching {
                        SolanaSettings(PrefsSolanaStore(walletPrefs(app))).selectedNetwork
                    }.getOrDefault(SolanaNetwork.MAINNET)
                    Solana(sel)
                }
                DiscoverChain.TRON -> {
                    val sel = runCatching { TronStores.settings(app).selectedNetwork }
                        .getOrDefault(TronNetwork.MAINNET)
                    Tron(sel)
                }
            }
        }

        /** Re-hydrate a [SweepNetwork] from a chain + persisted rawValue, used
         *  by the host when persisting a discovered account. Falls back to the
         *  chain's mainnet if the rawValue does not parse. */
        fun fromRawValue(chain: DiscoverChain, raw: String): SweepNetwork = when (chain) {
            DiscoverChain.BITCOIN ->
                Bitcoin(BitcoinNetwork.fromRawValue(raw) ?: BitcoinNetwork.MAINNET)
            DiscoverChain.ETHEREUM ->
                Ethereum(EthereumNetwork.fromRawValue(raw) ?: EthereumNetwork.MAINNET)
            DiscoverChain.SOLANA ->
                Solana(SolanaNetwork.fromRawValue(raw) ?: SolanaNetwork.MAINNET)
            DiscoverChain.TRON ->
                Tron(TronNetwork.fromRawValue(raw) ?: TronNetwork.MAINNET)
        }
    }
}

/** One active account discovered off the device during the sweep. */
internal data class DiscoveredHardwareAccount(
    /** Chain this account belongs to. Carried so the host can route the
     *  selection to the right per-chain wallet store + registry call when
     *  persisting (the sweep is single-chain, but the selection list is
     *  routed per-account for safety). */
    val chain: DiscoverChain,
    val chainDisplayName: String,
    /** The network the sweep ran on, as the chain's network-enum rawValue
     *  (e.g. "testnet3", "sepolia", "devnet", "nile"). The host re-hydrates
     *  this with [SweepNetwork.fromRawValue] and persists the wallet on THAT
     *  network, so a Testnet3 sweep adds a Bitcoin testnet wallet. */
    val networkRawValue: String,
    val account: Long,
    /** Receive identity: an address (EVM/Solana/Tron) or the account xpub (Bitcoin). */
    val identity: String,
    /** Short, human one-liner describing the on-chain activity that made
     *  this account active (e.g. "0.0123 SOL, active"). Empty for a kept
     *  fresh-hidden-wallet account 0 with no activity yet. */
    val activitySummary: String = "",
    /** Bitcoin only: the device's BIP32 master fingerprint, read once in the
     *  sweep session. Carried so the host can build the watch-only Hardware
     *  descriptor's key origin WITHOUT re-opening a BLE session. Null for the
     *  other chains (their identity is a self-contained address). */
    val bitcoinFingerprint: String? = null,
    /** The Trezor hidden-wallet passphrase CONFIG this account was read under,
     *  as the [HardwarePassphraseRef] wire id ("onDevice" / "hostEntry"), or
     *  null for the STANDARD wallet (no passphrase, Ledger-style). The sweep
     *  applies the chosen passphrase to READ this account's identity, so the
     *  same config MUST be stamped onto the descriptor when persisting (via
     *  [HardwarePassphraseRef.fromWireId]) or the wallet is persisted as
     *  standard and the send screen never re-prompts for the passphrase,
     *  signing the WRONG (standard) wallet. This carries the CONFIG only, never
     *  the passphrase secret itself. Mirrors the manual Add path, which sets
     *  descriptor.hidden = HardwarePassphraseRef.toJson(persist(selection)). */
    val hiddenRefWireId: String? = null,
)

/**
 * BIP44-style gap-limit at the ACCOUNT level: stop the sweep once this many
 * consecutive empty accounts are seen. Matches iOS
 * (SolanaWalletDiscovery.emptyAccountGapLimit and the Bitcoin
 * DiscoverHardwareWalletsView), so a long-tail of active accounts is not
 * truncated at a small fixed ceiling.
 */
private const val EMPTY_ACCOUNT_GAP_LIMIT = 4

/**
 * Hard ceiling on device round-trips so a misbehaving probe (e.g. an
 * endpoint that always reports activity) cannot loop forever. The gap limit
 * normally terminates the sweep far sooner; hitting this is logged.
 */
private const val MAX_ACCOUNT_INDEX = 20L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverHardwareWalletsScreen(
    @Suppress("UNUSED_PARAMETER") registry: DeviceRegistry,
    device: RegisteredDevice,
    onDone: (selected: List<DiscoveredHardwareAccount>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var chain by remember {
        mutableStateOf(
            DiscoverChain.entries.first { it.isSupportedOn(device.kind) },
        )
    }
    // The network within the selected chain's family, INCLUDING testnets.
    // Defaults to that chain's currently-selected network (or mainnet). When
    // the chain changes we repopulate this from the new chain's default. Only
    // this single (chain, network) target is swept + persisted.
    var network by remember { mutableStateOf(SweepNetwork.defaultFor(context, chain)) }
    // Trezor hidden (BIP39 passphrase) wallet selection. Standard reproduces
    // Ledger behavior (empty passphrase); a distinct passphrase yields a
    // separate hidden wallet. Ledger has no host-side analog. iOS parity.
    val isTrezor = device.kind == DeviceKind.TREZOR
    var passphraseMode by remember { mutableStateOf("standard") } // standard | ondevice | host
    var hostPassphrase by remember { mutableStateOf("") }
    fun passphraseChoice(): PassphraseChoice? = if (!isTrezor) {
        null
    } else when (passphraseMode) {
        "ondevice" -> PassphraseChoice.OnDevice
        "host" -> PassphraseChoice.HostTyped(hostPassphrase)
        else -> PassphraseChoice.Standard
    }

    var scanning by remember { mutableStateOf(false) }
    // Live connection stage (ADR-0033): the headline status line above the
    // detail log. [stageAccount] carries the 0-based index for SCANNING so the
    // line reads "Scanning account N...".
    var stage by remember { mutableStateOf<HardwareStage?>(null) }
    var stageAccount by remember { mutableStateOf<Long?>(null) }
    val progress = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateListOf<DiscoveredHardwareAccount>() }
    val selected = remember { mutableStateListOf<DiscoveredHardwareAccount>() }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.devices_discover_wallets)) },
                navigationIcon = {
                    IconButton(onClick = { onDone(emptyList()) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_close))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.devices_device), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.devices_discover_device_line, device.label, device.kind.displayName), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.devices_discover_card_body, EMPTY_ACCOUNT_GAP_LIMIT.toString(), device.kind.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Text(stringResource(R.string.devices_chain), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DiscoverChain.entries.filter { it.isSupportedOn(device.kind) }.forEach { c ->
                    FilterChip(
                        selected = chain == c,
                        onClick = {
                            if (!scanning && chain != c) {
                                chain = c
                                // Repopulate the network list for the new chain
                                // and default the selection to its current network.
                                network = SweepNetwork.defaultFor(context, c)
                            }
                        },
                        label = { Text(c.displayName) },
                    )
                }
            }

            // Network dropdown for the selected chain, INCLUDING testnets
            // (Bitcoin Testnet3/Signet, ETH Sepolia + L2 sepolias + ADI,
            // Solana Devnet/Testnet, Tron Nile/Shasta). Only this network is
            // swept and persisted. Mirrors the iOS Bitcoin network picker,
            // generalized to every chain.
            Text(stringResource(R.string.common_network), style = MaterialTheme.typography.labelMedium)
            NetworkDropdown(
                options = SweepNetwork.networksFor(chain),
                selected = network,
                enabled = !scanning,
                onSelect = { network = it },
            )

            if (isTrezor) {
                Text(stringResource(R.string.devices_wallet), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = passphraseMode == "standard", onClick = { if (!scanning) passphraseMode = "standard" }, label = { Text(stringResource(R.string.devices_passphrase_standard)) })
                    FilterChip(selected = passphraseMode == "ondevice", onClick = { if (!scanning) passphraseMode = "ondevice" }, label = { Text(stringResource(R.string.devices_passphrase_on_trezor)) })
                    FilterChip(selected = passphraseMode == "host", onClick = { if (!scanning) passphraseMode = "host" }, label = { Text(stringResource(R.string.devices_passphrase_type_on_phone)) })
                }
                if (passphraseMode == "host") {
                    PassphraseField(
                        value = hostPassphrase,
                        onValueChange = { hostPassphrase = it },
                        label = stringResource(R.string.devices_passphrase),
                        enabled = !scanning,
                    )
                }
                Text(
                    when (passphraseMode) {
                        "ondevice" -> stringResource(R.string.devices_passphrase_help_ondevice)
                        "host" -> stringResource(R.string.devices_passphrase_help_host)
                        else -> stringResource(R.string.devices_passphrase_help_standard)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!scanning && found.isEmpty()) {
                Button(
                    enabled = !(isTrezor && passphraseMode == "host" && hostPassphrase.isEmpty()),
                    onClick = {
                        val choice = passphraseChoice()
                        scope.launch {
                            runSweep(
                                context = context,
                                device = device,
                                chain = chain,
                                network = network,
                                passphraseChoice = choice,
                                includeFirstAlways = isTrezor && passphraseMode != "standard",
                                onScanning = { scanning = it },
                                onStage = { stage = it },
                                onScanningAccount = { stageAccount = it },
                                progress = progress,
                                found = found,
                                selected = selected,
                                onError = { error = it },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text(stringResource(R.string.devices_start_scan))
                }
            }

            // Headline live-stage line (ADR-0033): Connecting -> Connected ->
            // Confirm on device -> Scanning account N -> Done. Shown while a
            // scan is in flight or once a stage has been seen; the detail log
            // below is the per-account breakdown.
            stage?.let { st ->
                if (scanning || st == HardwareStage.DONE) {
                    HardwareStageLine(stage = st, device = device, account = stageAccount)
                }
            }

            progress.forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }

            if (found.isNotEmpty()) {
                Text(stringResource(R.string.devices_found_accounts), style = MaterialTheme.typography.titleSmall)
                found.forEach { acct ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected.contains(acct),
                            onCheckedChange = { include ->
                                if (include) {
                                    if (!selected.contains(acct)) selected.add(acct)
                                } else {
                                    selected.remove(acct)
                                }
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.devices_account_row, acct.chainDisplayName, acct.account.toString()), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                acct.identity.middleEllipsis(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (acct.activitySummary.isNotEmpty()) {
                                Text(
                                    acct.activitySummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { onDone(selected.toList()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.devices_add_selected_wallets)) }
                OutlinedButton(
                    onClick = { onDone(emptyList()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.common_close)) }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Read-only Material3 ExposedDropdownMenuBox for the selected chain's network
 * list (the codebase's existing dropdown pattern, cf. WalletPickers.AssetPicker).
 * Disabled while a scan is in flight so the target cannot change mid-sweep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkDropdown(
    options: List<SweepNetwork>,
    selected: SweepNetwork,
    enabled: Boolean,
    onSelect: (SweepNetwork) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.common_network)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.displayName, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val LOG_TAG = "DiscoverHWWallets"

/**
 * Map the passphrase choice the sweep READ each account under to the
 * [HardwarePassphraseRef] wire id stamped onto every [DiscoveredHardwareAccount]
 * it emits, so the host persists the SAME hidden config the manual Add path
 * does. Standard (and Ledger, which has no passphrase) -> null; on-device ->
 * "onDevice"; host-typed -> "hostEntry". The HostTyped passphrase secret is
 * intentionally NOT carried; only the config (which kind) round-trips, so the
 * send screen re-prompts (hostEntry) / lets the device prompt (onDevice) /
 * signs standard (null), reproducing the wallet the account was discovered in.
 */
private fun hiddenRefWireIdFor(choice: PassphraseChoice?): String? = when (choice) {
    null, PassphraseChoice.Standard -> null
    PassphraseChoice.OnDevice -> HardwarePassphraseRef.ON_DEVICE.wireId
    is PassphraseChoice.HostTyped -> HardwarePassphraseRef.HOST_ENTRY.wireId
}

/**
 * Result of probing one account's on-chain activity. [active] gates whether
 * the account is surfaced; [summary] is the human one-liner appended to the
 * progress log and shown under a found row.
 */
private data class ActivityProbe(val active: Boolean, val summary: String)

/**
 * Pin one BLE session, confirm the serial, then walk account indices on the
 * chosen chain. For each account it reads the device identity, probes
 * on-chain activity over the network, and stops after
 * [EMPTY_ACCOUNT_GAP_LIMIT] consecutive empty accounts. Only accounts with
 * activity are surfaced (plus account 0 when [includeFirstAlways] is set, for
 * a fresh Trezor hidden wallet). Off the main thread. State is streamed back
 * through the passed-in snapshot lists / callbacks. Ported from iOS
 * DiscoverHardwareWalletsView (Bitcoin) + DiscoverSolanaWalletsView.
 */
private suspend fun runSweep(
    context: Context,
    device: RegisteredDevice,
    chain: DiscoverChain,
    network: SweepNetwork,
    passphraseChoice: PassphraseChoice?,
    includeFirstAlways: Boolean,
    onScanning: (Boolean) -> Unit,
    onStage: (HardwareStage) -> Unit,
    onScanningAccount: (Long) -> Unit,
    progress: SnapshotStateList<String>,
    found: SnapshotStateList<DiscoveredHardwareAccount>,
    selected: SnapshotStateList<DiscoveredHardwareAccount>,
    onError: (String?) -> Unit,
) {
    onScanning(true)
    progress.clear()
    found.clear()
    selected.clear()
    onError(null)
    // Advisory headline stage emits (ADR-0033); guarded so a UI callback throw
    // cannot poison the sweep, mirroring withHardwareDevice's contract.
    fun emitStage(s: HardwareStage) { runCatching { onStage(s) } }
    fun emitAccount(n: Long) { runCatching { onScanningAccount(n) } }
    emitStage(HardwareStage.CONNECTING)

    // Resolve the per-chain network config OFF the device session (reads
    // SharedPreferences). A missing / unparseable config leaves the probe
    // inconclusive, which iOS treats as "no activity" for every account.
    val appContext = context.applicationContext
    val probeConfig = runCatching { resolveProbeConfig(appContext, network) }
        .onFailure { Log.w(LOG_TAG, "probe config unavailable for ${chain.displayName} ${network.displayName}", it) }
        .getOrNull()

    // Bitcoin's BIP44 coin type follows the SELECTED network (0' mainnet,
    // 1' testnet3/signet), so the device reads + descriptor all derive on the
    // chosen subnetwork. The other chains derive the same address on every
    // network, so their coin type is constant (read from the chain).
    val bitcoinCoinType: Long = (network as? SweepNetwork.Bitcoin)?.network?.coinType ?: chain.coinType

    // The hidden-wallet config every discovered account is READ under. Stamped
    // onto each emitted account so the host persists descriptor.hidden = this
    // config (so a host-typed hidden wallet re-prompts on send instead of
    // signing the standard wallet). Null for standard / Ledger.
    val hiddenRefWireId: String? = hiddenRefWireIdFor(passphraseChoice)

    val result = withContext(Dispatchers.IO) {
        runCatching {
            val client: HardwareWallet = HardwareWalletFactory.make(device.kind.hardwareWalletKind())
            // Pin the session for the whole sweep so the back-to-back reads
            // share one connection; without this each read drops the link.
            client.beginSession()
            try {
                // Refuse to sweep a different physical device.
                val liveSerial = client.identifyDevice()
                require(liveSerial == device.serial) {
                    "Connected device serial $liveSerial does not match the registered serial ${device.serial}. Reconnect the correct device."
                }
                emitStage(HardwareStage.CONNECTED)
                // Tell the user to look at the device before applyPassphraseMode
                // (a Trezor on-device passphrase prompts here) and the reads.
                emitStage(HardwareStage.AWAITING_DEVICE)
                client.setDerivationPathOverride(null)
                // Apply the chosen Trezor hidden-wallet passphrase before the
                // per-account reads so each address derives in that wallet.
                if (client is TrezorHardwareWallet && passphraseChoice != null) {
                    client.applyPassphraseMode(passphraseChoice)
                }

                // Bitcoin probes need the device's master fingerprint to
                // build a watch-only descriptor from each account xpub. Read
                // it once, in-session.
                val bitcoinFingerprint: String? =
                    if (chain == DiscoverChain.BITCOIN) {
                        client.getBitcoinMasterFingerprint(networkCoinType = bitcoinCoinType)
                    } else {
                        null
                    }

                val rows = mutableListOf<DiscoveredHardwareAccount>()
                var firstEntry: DiscoveredHardwareAccount? = null
                var account = 0L
                var consecutiveEmpty = 0
                while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT && account <= MAX_ACCOUNT_INDEX) {
                    emitStage(HardwareStage.SCANNING)
                    emitAccount(account)
                    progress.add("Account $account: scanning…")
                    // Read the receive identity off the device (in-session).
                    val identity = when (chain) {
                        DiscoverChain.BITCOIN ->
                            client.getBitcoinAccountXpub(account = account, networkCoinType = bitcoinCoinType)
                        DiscoverChain.ETHEREUM -> client.getEthereumAddress(account = account)
                        DiscoverChain.SOLANA -> client.getSolanaAddress(account = account)
                        DiscoverChain.TRON -> client.getTronAddress(account = account)
                    }

                    // Probe on-chain activity over the network. An endpoint
                    // error counts as "no activity" (iOS parity), never a
                    // sweep abort.
                    val probe = probeActivity(chain, identity, probeConfig, bitcoinFingerprint)

                    val entry = DiscoveredHardwareAccount(
                        chain = chain,
                        chainDisplayName = chain.displayName,
                        networkRawValue = network.rawValue,
                        account = account,
                        identity = identity,
                        activitySummary = probe.summary,
                        bitcoinFingerprint = bitcoinFingerprint,
                        hiddenRefWireId = hiddenRefWireId,
                    )
                    if (account == 0L && firstEntry == null) firstEntry = entry

                    if (probe.active) {
                        consecutiveEmpty = 0
                        rows.add(entry)
                        progress.add("Account $account: ${probe.summary}")
                    } else {
                        consecutiveEmpty++
                        progress.add("Account $account: empty")
                    }
                    account++
                }
                if (account > MAX_ACCOUNT_INDEX && consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT) {
                    Log.w(LOG_TAG, "hit MAX_ACCOUNT_INDEX ceiling before gap limit on ${chain.displayName}")
                }

                // Keep account 0 for a fresh Trezor hidden wallet so the user
                // can still add it before it has any history. iOS keepEmptyFirst.
                if (includeFirstAlways) {
                    val first = firstEntry
                    if (first != null && rows.none { it.account == 0L }) {
                        rows.add(0, first.copy(activitySummary = "fresh wallet (no activity yet)"))
                    }
                }
                rows
            } finally {
                client.endSession()
            }
        }
    }

    result.onSuccess { rows ->
        rows.forEach {
            found.add(it)
            // Pre-select discovered accounts, matching iOS. The orchestrator
            // dedups against existing wallets when persisting the selection.
            selected.add(it)
        }
        progress.add("Stopped after $EMPTY_ACCOUNT_GAP_LIMIT consecutive empty accounts.")
        if (rows.isEmpty()) progress.add("No active accounts found.")
        emitStage(HardwareStage.DONE)
    }.onFailure {
        onError(it.friendlyMessage())
    }
    onScanning(false)
}

// MARK: -- per-chain network config + activity probes
//
// The config sources mirror exactly what the per-chain wallet screens use,
// read from the shared `maknoon.wallets.v1` SharedPreferences via the SDK
// stores, but resolved for the CHOSEN sweep network (not the globally-
// selected one), so a Testnet3 / Sepolia / Devnet / Nile selection probes
// that subnetwork's endpoints. Resolution can throw (e.g. a missing prefs
// blob); the caller logs + treats a null config as "every account is
// inconclusive / empty", per iOS.

/** Opaque per-chain config bundle consumed by [probeActivity]. */
private sealed class ProbeConfig {
    data class Bitcoin(
        val electrumURL: String,
        val network: BitcoinNetwork,
    ) : ProbeConfig()

    data class Ethereum(
        val rpcURL: String,
        val explorerAPIURL: String?,
        val apiKey: String?,
        val chainId: Long,
    ) : ProbeConfig()

    data class Solana(val rpcURL: String) : ProbeConfig()
    data class Tron(val rpcURL: String) : ProbeConfig()
}

private fun resolveProbeConfig(appContext: Context, network: SweepNetwork): ProbeConfig =
    when (network) {
        is SweepNetwork.Bitcoin -> {
            val kv = PrefsBitcoinStore(walletPrefs(appContext))
            val settings = BitcoinSettings(kv)
            // Probe on the CHOSEN Bitcoin network: its Electrum endpoint + its
            // bdk Network. A Testnet3 selection scans testnet.
            ProbeConfig.Bitcoin(
                electrumURL = settings.electrumURL(network.network),
                network = network.network,
            )
        }
        is SweepNetwork.Ethereum -> {
            // Probe on the CHOSEN EVM network (RPC + explorer + chainId),
            // overriding the globally-selected network for this sweep. The
            // device address is identical on every EVM chain, so only the
            // probe + persistence network differ.
            val settings = EthereumStores.settings(appContext)
            val net = network.network
            ProbeConfig.Ethereum(
                rpcURL = settings.rpcURL(net),
                explorerAPIURL = settings.explorerAPIURL(net),
                apiKey = settings.explorerAPIKey(net),
                chainId = net.chainId,
            )
        }
        is SweepNetwork.Solana -> {
            val settings = SolanaSettings(PrefsSolanaStore(walletPrefs(appContext)))
            ProbeConfig.Solana(rpcURL = settings.rpcURL(network.network))
        }
        is SweepNetwork.Tron -> {
            val settings = TronStores.settings(appContext)
            ProbeConfig.Tron(rpcURL = settings.rpcURL(network.network))
        }
    }

/**
 * Probe one account's identity for on-chain activity. Per iOS, any network /
 * config error is swallowed and reported as "no activity" so one bad endpoint
 * cannot poison the sweep. Returns [ActivityProbe.active] + a short summary.
 */
private fun probeActivity(
    chain: DiscoverChain,
    identity: String,
    config: ProbeConfig?,
    bitcoinFingerprint: String?,
): ActivityProbe {
    if (config == null) return ActivityProbe(active = false, summary = "endpoint unavailable")
    return try {
        when (chain) {
            DiscoverChain.BITCOIN -> probeBitcoin(identity, bitcoinFingerprint, config as ProbeConfig.Bitcoin)
            DiscoverChain.ETHEREUM -> probeEthereum(identity, config as ProbeConfig.Ethereum)
            DiscoverChain.SOLANA -> probeSolana(identity, config as ProbeConfig.Solana)
            DiscoverChain.TRON -> probeTron(identity, config as ProbeConfig.Tron)
        }
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "activity probe failed for ${chain.displayName} account", e)
        ActivityProbe(active = false, summary = "no activity (probe error)")
    }
}

/**
 * Bitcoin: build a WATCH-ONLY BDK wallet from the device account xpub (the
 * hardware-wallet descriptor path) and run a full Electrum scan. Active when
 * it has any transactions OR a non-zero balance. Mirrors iOS
 * DiscoverHardwareWalletsView's per-account scan.
 */
private fun probeBitcoin(xpub: String, fingerprint: String?, cfg: ProbeConfig.Bitcoin): ActivityProbe {
    if (fingerprint == null) return ActivityProbe(active = false, summary = "no master fingerprint")
    // Standard hardware accounts are native SegWit (BIP84); the sweep does
    // not vary script type (alternative-path sweeps are out of scope here).
    val pair = BitcoinDescriptors.watchOnlyFromXpub(
        xpub = xpub,
        fingerprint = fingerprint,
        network = cfg.network,
        scriptType = Bip32Path.BitcoinScriptType.NATIVE_SEGWIT,
    )
    val wallet = Wallet(pair.external, pair.internal, cfg.network.bdk, Persister.newInMemory())
    val client = ElectrumClient(cfg.electrumURL, null)
    val request = wallet.startFullScan().build()
    val update = client.fullScan(
        request,
        /* stopGap = */ 20uL,
        /* batchSize = */ 10uL,
        /* fetchPrevTxouts = */ false,
    )
    wallet.applyUpdate(update)
    val txCount = wallet.transactions().size
    val balanceSat = wallet.balance().total.toSat().toLong()
    val active = txCount > 0 || balanceSat > 0
    val btc = balanceSat / 100_000_000.0
    return ActivityProbe(
        active = active,
        summary = if (active) String.format("%d tx, %.8f BTC, active", txCount, btc) else "empty",
    )
}

/**
 * Bitcoin-only activity sweep, scoped to an ALREADY-chosen network + device +
 * passphrase, for the per-chain Add screen's inline Discover (so it never shows
 * the generic chain + network picker page). Opens ONE BLE session via the
 * shared [withHardwareDevice], reads the master fingerprint + each account's
 * xpub at the network's coin type in-session, and probes each for on-chain
 * activity with the SAME [probeBitcoin] + [EMPTY_ACCOUNT_GAP_LIMIT] /
 * [MAX_ACCOUNT_INDEX] gap logic the full sweep uses, so the two cannot drift.
 *
 * Returns the active accounts as [DiscoveredHardwareAccount] rows (Bitcoin
 * chain, the chosen network's rawValue, the master fingerprint), suitable for
 * the same [persistDiscoveredSelection] path the generic flow persists through.
 * When [includeFirstAlways] is set (a fresh Trezor hidden wallet), account 0 is
 * kept even with no activity yet, matching the generic sweep's keepEmptyFirst.
 *
 * Off the main thread (BDK + Electrum + BLE). [onProgress] streams a per-account
 * status line for the caller's live log; it is invoked on the calling thread.
 * [onStage] is the optional headline live-stage callback (ADR-0033): CONNECTING
 * / CONNECTED / AWAITING_DEVICE come from [withHardwareDevice], SCANNING(account
 * = N) is emitted as each account is read, and DONE when the sweep finishes. It
 * is advisory only and never aborts the sweep; the per-account [onProgress] log
 * is the detail under that headline. Generic so Solana / Tron reuse the model.
 */
internal suspend fun sweepBitcoinAccounts(
    context: Context,
    device: RegisteredDevice,
    network: BitcoinNetwork,
    passphraseChoice: PassphraseChoice,
    derivationPathOverride: String?,
    includeFirstAlways: Boolean,
    onProgress: (String) -> Unit,
    onStage: ((HardwareStage) -> Unit)? = null,
    onScanningAccount: ((Long) -> Unit)? = null,
): List<DiscoveredHardwareAccount> {
    val appContext = context.applicationContext
    val probeConfig = runCatching {
        resolveProbeConfig(appContext, SweepNetwork.Bitcoin(network))
    }.onFailure {
        Log.w(LOG_TAG, "probe config unavailable for Bitcoin ${network.displayName}", it)
    }.getOrNull() as? ProbeConfig.Bitcoin
    val coinType = network.coinType
    // Stamp the hidden config the sweep reads under onto every emitted account,
    // so persistBitcoin records descriptor.hidden and the send screen re-prompts
    // for a host-typed hidden wallet instead of signing the standard wallet.
    val hiddenRefWireId: String? = hiddenRefWireIdFor(passphraseChoice)

    return withContext(Dispatchers.IO) {
        // ONE pinned session for the whole sweep (the shared helper applies the
        // Trezor passphrase mode + the derivation-path override + the serial
        // guard, then tears the link down on exit). The helper emits CONNECTING
        // / CONNECTED / AWAITING_DEVICE; the sweep adds SCANNING + DONE.
        val rows = withHardwareDevice(device, passphraseChoice, derivationPathOverride, onStage) { wallet ->
            val fingerprint = wallet.getBitcoinMasterFingerprint(networkCoinType = coinType)
            val rows = mutableListOf<DiscoveredHardwareAccount>()
            var firstEntry: DiscoveredHardwareAccount? = null
            var account = 0L
            var consecutiveEmpty = 0
            while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT && account <= MAX_ACCOUNT_INDEX) {
                onStage?.let { runCatching { it(HardwareStage.SCANNING) } }
                onScanningAccount?.let { runCatching { it(account) } }
                onProgress("Account $account: scanning…")
                val xpub = wallet.getBitcoinAccountXpub(account = account, networkCoinType = coinType)
                val probe = if (probeConfig == null) {
                    ActivityProbe(active = false, summary = "endpoint unavailable")
                } else {
                    runCatching { probeBitcoin(xpub, fingerprint, probeConfig) }
                        .getOrElse {
                            Log.w(LOG_TAG, "Bitcoin activity probe failed for account $account", it)
                            ActivityProbe(active = false, summary = "no activity (probe error)")
                        }
                }
                val entry = DiscoveredHardwareAccount(
                    chain = DiscoverChain.BITCOIN,
                    chainDisplayName = DiscoverChain.BITCOIN.displayName,
                    networkRawValue = network.rawValue,
                    account = account,
                    identity = xpub,
                    activitySummary = probe.summary,
                    bitcoinFingerprint = fingerprint,
                    hiddenRefWireId = hiddenRefWireId,
                )
                if (account == 0L && firstEntry == null) firstEntry = entry
                if (probe.active) {
                    consecutiveEmpty = 0
                    rows.add(entry)
                    onProgress("Account $account: ${probe.summary}")
                } else {
                    consecutiveEmpty++
                    onProgress("Account $account: empty")
                }
                account++
            }
            if (includeFirstAlways) {
                val first = firstEntry
                if (first != null && rows.none { it.account == 0L }) {
                    rows.add(0, first.copy(activitySummary = "fresh wallet (no activity yet)"))
                }
            }
            rows
        }
        // The sweep is finished (link torn down): the headline reads Done.
        onStage?.let { runCatching { it(HardwareStage.DONE) } }
        rows
    }
}

/**
 * Sweep a hardware device for Ethereum accounts on [network] - the EVM twin of
 * [sweepBitcoinAccounts]. ONE pinned session (the shared helper applies the
 * Trezor passphrase mode + serial guard), walk account indices, read the EIP-55
 * address, probe RPC + explorer for activity, and emit the funded accounts so the
 * inline Add screen can persist them via [persistDiscoveredSelection] (which
 * routes ETHEREUM -> persistEthereum). The chosen passphrase config is stamped on
 * every row so a hidden wallet is persisted with descriptor.hidden, like Bitcoin.
 */
internal suspend fun sweepEthereumAccounts(
    context: Context,
    device: RegisteredDevice,
    network: EthereumNetwork,
    passphraseChoice: PassphraseChoice,
    derivationPathOverride: String?,
    includeFirstAlways: Boolean,
    onProgress: (String) -> Unit,
    onStage: ((HardwareStage) -> Unit)? = null,
    onScanningAccount: ((Long) -> Unit)? = null,
): List<DiscoveredHardwareAccount> {
    val appContext = context.applicationContext
    val probeConfig = runCatching {
        resolveProbeConfig(appContext, SweepNetwork.Ethereum(network))
    }.onFailure {
        Log.w(LOG_TAG, "probe config unavailable for Ethereum ${network.displayName}", it)
    }.getOrNull() as? ProbeConfig.Ethereum
    val hiddenRefWireId: String? = hiddenRefWireIdFor(passphraseChoice)

    return withContext(Dispatchers.IO) {
        val rows = withHardwareDevice(device, passphraseChoice, derivationPathOverride, onStage) { wallet ->
            val rows = mutableListOf<DiscoveredHardwareAccount>()
            var firstEntry: DiscoveredHardwareAccount? = null
            var account = 0L
            var consecutiveEmpty = 0
            while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT && account <= MAX_ACCOUNT_INDEX) {
                onStage?.let { runCatching { it(HardwareStage.SCANNING) } }
                onScanningAccount?.let { runCatching { it(account) } }
                onProgress("Account $account: scanning…")
                val address = wallet.getEthereumAddress(account)
                val probe = if (probeConfig == null) {
                    ActivityProbe(active = false, summary = "endpoint unavailable")
                } else {
                    runCatching { probeEthereum(address, probeConfig) }
                        .getOrElse {
                            Log.w(LOG_TAG, "Ethereum activity probe failed for account $account", it)
                            ActivityProbe(active = false, summary = "no activity (probe error)")
                        }
                }
                val entry = DiscoveredHardwareAccount(
                    chain = DiscoverChain.ETHEREUM,
                    chainDisplayName = DiscoverChain.ETHEREUM.displayName,
                    networkRawValue = network.rawValue,
                    account = account,
                    identity = address,
                    activitySummary = probe.summary,
                    hiddenRefWireId = hiddenRefWireId,
                )
                if (account == 0L && firstEntry == null) firstEntry = entry
                if (probe.active) {
                    consecutiveEmpty = 0
                    rows.add(entry)
                    onProgress("Account $account: ${probe.summary}")
                } else {
                    consecutiveEmpty++
                    onProgress("Account $account: empty")
                }
                account++
            }
            if (includeFirstAlways) {
                val first = firstEntry
                if (first != null && rows.none { it.account == 0L }) {
                    rows.add(0, first.copy(activitySummary = "fresh wallet (no activity yet)"))
                }
            }
            rows
        }
        onStage?.let { runCatching { it(HardwareStage.DONE) } }
        rows
    }
}

/**
 * Sweep a hardware device for Tron accounts on [network] - the Tron twin of
 * [sweepEthereumAccounts]. ONE pinned session, walk account indices, read the
 * base58check address (getTronAddress), probe TronGrid, emit the funded ones for
 * inline persistence via [persistDiscoveredSelection] (routes TRON ->
 * persistTron). The chosen passphrase config is stamped on every row.
 */
internal suspend fun sweepTronAccounts(
    context: Context,
    device: RegisteredDevice,
    network: TronNetwork,
    passphraseChoice: PassphraseChoice,
    derivationPathOverride: String?,
    includeFirstAlways: Boolean,
    onProgress: (String) -> Unit,
    onStage: ((HardwareStage) -> Unit)? = null,
    onScanningAccount: ((Long) -> Unit)? = null,
): List<DiscoveredHardwareAccount> {
    val appContext = context.applicationContext
    val probeConfig = runCatching {
        resolveProbeConfig(appContext, SweepNetwork.Tron(network))
    }.onFailure {
        Log.w(LOG_TAG, "probe config unavailable for Tron ${network.displayName}", it)
    }.getOrNull() as? ProbeConfig.Tron
    val hiddenRefWireId: String? = hiddenRefWireIdFor(passphraseChoice)

    return withContext(Dispatchers.IO) {
        val rows = withHardwareDevice(device, passphraseChoice, derivationPathOverride, onStage) { wallet ->
            val rows = mutableListOf<DiscoveredHardwareAccount>()
            var firstEntry: DiscoveredHardwareAccount? = null
            var account = 0L
            var consecutiveEmpty = 0
            while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT && account <= MAX_ACCOUNT_INDEX) {
                onStage?.let { runCatching { it(HardwareStage.SCANNING) } }
                onScanningAccount?.let { runCatching { it(account) } }
                onProgress("Account $account: scanning…")
                val address = wallet.getTronAddress(account)
                val probe = if (probeConfig == null) {
                    ActivityProbe(active = false, summary = "endpoint unavailable")
                } else {
                    runCatching { probeTron(address, probeConfig) }
                        .getOrElse {
                            Log.w(LOG_TAG, "Tron activity probe failed for account $account", it)
                            ActivityProbe(active = false, summary = "no activity (probe error)")
                        }
                }
                val entry = DiscoveredHardwareAccount(
                    chain = DiscoverChain.TRON,
                    chainDisplayName = DiscoverChain.TRON.displayName,
                    networkRawValue = network.rawValue,
                    account = account,
                    identity = address,
                    activitySummary = probe.summary,
                    hiddenRefWireId = hiddenRefWireId,
                )
                if (account == 0L && firstEntry == null) firstEntry = entry
                if (probe.active) {
                    consecutiveEmpty = 0
                    rows.add(entry)
                    onProgress("Account $account: ${probe.summary}")
                } else {
                    consecutiveEmpty++
                    onProgress("Account $account: empty")
                }
                account++
            }
            if (includeFirstAlways) {
                val first = firstEntry
                if (first != null && rows.none { it.account == 0L }) {
                    rows.add(0, first.copy(activitySummary = "fresh wallet (no activity yet)"))
                }
            }
            rows
        }
        onStage?.let { runCatching { it(HardwareStage.DONE) } }
        rows
    }
}

/**
 * Sweep a hardware device for Solana accounts on [network] - the Solana twin of
 * [sweepEthereumAccounts]. ONE pinned session, walk account indices, read the
 * base58 address (getSolanaAddress), probe the RPC, emit the funded ones for
 * inline persistence via [persistDiscoveredSelection] (routes SOLANA ->
 * persistSolana). The chosen passphrase config is stamped on every row.
 */
internal suspend fun sweepSolanaAccounts(
    context: Context,
    device: RegisteredDevice,
    network: SolanaNetwork,
    passphraseChoice: PassphraseChoice,
    derivationPathOverride: String?,
    includeFirstAlways: Boolean,
    onProgress: (String) -> Unit,
    onStage: ((HardwareStage) -> Unit)? = null,
    onScanningAccount: ((Long) -> Unit)? = null,
): List<DiscoveredHardwareAccount> {
    val appContext = context.applicationContext
    val probeConfig = runCatching {
        resolveProbeConfig(appContext, SweepNetwork.Solana(network))
    }.onFailure {
        Log.w(LOG_TAG, "probe config unavailable for Solana ${network.displayName}", it)
    }.getOrNull() as? ProbeConfig.Solana
    val hiddenRefWireId: String? = hiddenRefWireIdFor(passphraseChoice)

    return withContext(Dispatchers.IO) {
        val rows = withHardwareDevice(device, passphraseChoice, derivationPathOverride, onStage) { wallet ->
            val rows = mutableListOf<DiscoveredHardwareAccount>()
            var firstEntry: DiscoveredHardwareAccount? = null
            var account = 0L
            var consecutiveEmpty = 0
            while (consecutiveEmpty < EMPTY_ACCOUNT_GAP_LIMIT && account <= MAX_ACCOUNT_INDEX) {
                onStage?.let { runCatching { it(HardwareStage.SCANNING) } }
                onScanningAccount?.let { runCatching { it(account) } }
                onProgress("Account $account: scanning…")
                val address = wallet.getSolanaAddress(account)
                val probe = if (probeConfig == null) {
                    ActivityProbe(active = false, summary = "endpoint unavailable")
                } else {
                    runCatching { probeSolana(address, probeConfig) }
                        .getOrElse {
                            Log.w(LOG_TAG, "Solana activity probe failed for account $account", it)
                            ActivityProbe(active = false, summary = "no activity (probe error)")
                        }
                }
                val entry = DiscoveredHardwareAccount(
                    chain = DiscoverChain.SOLANA,
                    chainDisplayName = DiscoverChain.SOLANA.displayName,
                    networkRawValue = network.rawValue,
                    account = account,
                    identity = address,
                    activitySummary = probe.summary,
                    hiddenRefWireId = hiddenRefWireId,
                )
                if (account == 0L && firstEntry == null) firstEntry = entry
                if (probe.active) {
                    consecutiveEmpty = 0
                    rows.add(entry)
                    onProgress("Account $account: ${probe.summary}")
                } else {
                    consecutiveEmpty++
                    onProgress("Account $account: empty")
                }
                account++
            }
            if (includeFirstAlways) {
                val first = firstEntry
                if (first != null && rows.none { it.account == 0L }) {
                    rows.add(0, first.copy(activitySummary = "fresh wallet (no activity yet)"))
                }
            }
            rows
        }
        onStage?.let { runCatching { it(HardwareStage.DONE) } }
        rows
    }
}

/** Ethereum: balance>0 OR any tx history, via the SDK's reusable probe. */
private fun probeEthereum(address: String, cfg: ProbeConfig.Ethereum): ActivityProbe {
    val (hasBal, txCount) = EthereumWallet.probeActivity(
        address = address,
        rpcURL = cfg.rpcURL,
        explorerAPIURL = cfg.explorerAPIURL,
        apiKey = cfg.apiKey,
        chainId = cfg.chainId,
    )
    val active = hasBal || txCount > 0
    val parts = buildList {
        if (hasBal) add("balance")
        if (txCount > 0) add("tx history")
    }
    return ActivityProbe(
        active = active,
        summary = if (active) "${parts.joinToString(" + ")}, active" else "empty",
    )
}

/** Solana: lamports>0 OR a non-empty recent-signature list. */
private fun probeSolana(address: String, cfg: ProbeConfig.Solana): ActivityProbe {
    val rpc = SolanaRPCClient(endpoint = cfg.rpcURL)
    val lamports = runCatching { rpc.getBalance(address) }.getOrDefault(0L)
    val sigs = runCatching { rpc.getSignaturesForAddress(address, limit = 1) }.getOrDefault(emptyList())
    val active = lamports > 0 || sigs.isNotEmpty()
    val sol = lamports / 1_000_000_000.0
    return ActivityProbe(
        active = active,
        summary = if (active) String.format("%.6f SOL, active", sol) else "empty",
    )
}

/** Tron: sun balance>0 OR a non-empty recent-transaction list. */
private fun probeTron(addressBase58: String, cfg: ProbeConfig.Tron): ActivityProbe {
    val rpc = TronRPCClient(base = cfg.rpcURL)
    val sun = runCatching { rpc.getBalance(addressBase58) }.getOrDefault(0L)
    val txs = runCatching { rpc.getTransactionsByAddress(addressBase58, limit = 1) }.getOrDefault(emptyList())
    val active = sun > 0 || txs.isNotEmpty()
    val trx = sun / 1_000_000.0
    return ActivityProbe(
        active = active,
        summary = if (active) String.format("%.6f TRX, active", trx) else "empty",
    )
}

/** Truncate a long identity (xpub / address) for a list row. */
private fun String.middleEllipsis(head: Int = 10, tail: Int = 6): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"
