// Add a Bitcoin wallet (ADR-0033 universal Add-wallet anatomy). Bitcoin is the
// reference the other chains follow:
//
//   Top      : the single Chain dropdown (Mainnet / Testnet3 / Signet) - Bitcoin
//              is the ONLY chain with a top-level chain selector, because its
//              coinType is load-bearing (it changes the xpub/address). It feeds
//              software create, hardware add, and BOTH Auto Discovery sweeps.
//   Source   : Software | Hardware.
//
//   Software : Wallet Label -> Account (auto-incremented) -> Create wallet, then
//              a divider and an "Auto Discovery" section that sweeps SEED-derived
//              accounts on the chosen chain (BitcoinWalletDiscovery) and lets the
//              user add the funded ones.
//
//   Hardware : Device picker + inline "+ Add New Device" -> Account (Ledger:
//              auto-incremented stepper; Trezor: fixed 0, because a hidden-wallet
//              passphrase makes the index ambiguous) -> Add wallet, then a divider
//              and an "Auto Discovery" section that sweeps the device.
//
//   Passphrase (Trezor): NOT inline. Tapping "Add wallet" or "Discover existing
//              wallets" for a Trezor opens HardwareConnectDialog, which hosts the
//              None / On Device / Type Here selector (the one choice drives BOTH
//              add and discover) and then shows the live connection stage. Ledger
//              skips the dialog (its passphrase lives on the device) and runs the
//              operation directly. The passphrase is never stored, only the
//              hidden CONFIG on the descriptor.
//
// UI copy says "second factor" / "security key", never "Identity Sandwich".

package com.elabify.app.maknoon.ui.wallet.bitcoin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.devices.DiscoveredHardwareAccount
import com.elabify.app.maknoon.ui.devices.persistDiscoveredSelection
import com.elabify.app.maknoon.ui.devices.sweepBitcoinAccounts
import com.elabify.app.maknoon.ui.wallet.common.AccountStepper
import com.elabify.app.maknoon.ui.wallet.common.AddWalletSource
import com.elabify.app.maknoon.ui.wallet.common.DevicePicker
import com.elabify.app.maknoon.ui.wallet.common.HardwareConnectDialog
import com.elabify.app.maknoon.ui.wallet.common.HardwareConnectPurpose
import com.elabify.app.maknoon.ui.wallet.common.HardwareStage
import com.elabify.app.maknoon.ui.wallet.common.HardwareStageLine
import com.elabify.app.maknoon.ui.wallet.common.SourcePicker
import com.elabify.app.maknoon.ui.wallet.common.orderChainsForMenu
import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.devices.DeviceKind
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.devices.RegisteredDevice
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.musnad.hardware.trezor.HiddenWalletSelection
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDiscovery
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddBitcoinWalletScreen(
    env: BitcoinWalletEnv,
    onRegisterDevice: () -> Unit,
    onDone: () -> Unit,
    // Set right after an inline "Register a device": open on the Hardware tab
    // with this device pre-selected (ADR-0033), instead of defaulting to
    // Software. null = a normal Add entry.
    initialDeviceId: UUID? = null,
) {
    val context = LocalContext.current
    val registry = remember { DeviceRegistry(context) }

    // Re-read the registry whenever this screen resumes (e.g. after the inline
    // device-registration flow pairs a new device) so a freshly-registered
    // device shows in the picker WITHOUT restarting the app.
    var deviceRefreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deviceRefreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val hardwareDevices = remember(deviceRefreshKey) {
        registry.devices.filter { it.kind == DeviceKind.LEDGER || it.kind == DeviceKind.TREZOR }
    }
    val hasIdentity = remember { loadRecoveryWords(context) != null }

    var source by remember(deviceRefreshKey) {
        mutableStateOf(
            when {
                initialDeviceId != null -> AddWalletSource.HARDWARE
                !hasIdentity && hardwareDevices.isNotEmpty() -> AddWalletSource.HARDWARE
                else -> AddWalletSource.SOFTWARE
            },
        )
    }
    var label by remember { mutableStateOf("") }
    // ONE shared chain at the top, driving software create, hardware add, and
    // both Auto Discovery sweeps (ADR-0033). Bitcoin is the only chain with this
    // top-level selector because its coinType is load-bearing.
    var chain by remember { mutableStateOf(BitcoinNetwork.MAINNET) }
    var account by remember { mutableStateOf(env.store.nextSoftwareAccount(BitcoinNetwork.MAINNET)) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var hwDeviceId by remember { mutableStateOf<UUID?>(initialDeviceId) }
    val hwDevice: RegisteredDevice? = remember(deviceRefreshKey, hwDeviceId) {
        hardwareDevices.firstOrNull { it.id == hwDeviceId } ?: hardwareDevices.firstOrNull()
    }
    // Ledger hardware account seeds to the next free index for the (device,
    // chain); Trezor ignores it (fixed 0, see HardwareSection).
    var hwAccount by remember(hwDevice?.id, chain) {
        mutableStateOf(hwDevice?.let { env.store.nextHardwareAccount(it.id, chain) } ?: 0L)
    }

    val duplicate = env.store.hasSoftwareWallet(account, chain)

    fun createSoftware() {
        if (!hasIdentity) {
            errorText = "Software wallets need your identity unlocked. Unlock from the Identity tab, or switch the Source to Hardware."
            return
        }
        if (env.store.hasSoftwareWallet(account, chain)) {
            errorText = "Account $account already exists on ${chain.displayName}. Pick another to avoid a duplicate."
            return
        }
        val name = label.trim().ifEmpty { context.getString(R.string.wallet_default_label, "Bitcoin", account.toString()) }
        creating = true
        errorText = null
        env.store.add(
            BitcoinWalletDescriptor(
                id = UUID.randomUUID(),
                label = name,
                kind = BitcoinWalletKind.Software(account = account),
                network = chain,
            ),
            makeActive = true,
        )
        creating = false
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.btc_add_bitcoin_wallet)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                },
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BitcoinChainDropdown(
                chain = chain,
                onChange = {
                    chain = it
                    account = env.store.nextSoftwareAccount(it)
                    errorText = null
                },
            )

            SourcePicker(selected = source, onSelect = { source = it; errorText = null })

            if (source == AddWalletSource.SOFTWARE) {
                if (!hasIdentity) {
                    Text(
                        stringResource(R.string.btc_software_needs_identity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.walletc_wallet_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                AccountStepper(account = account, onChange = { account = it })
                if (duplicate) {
                    Text(
                        stringResource(R.string.btc_account_exists, account.toString(), chain.displayName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = { createSoftware() },
                    enabled = !creating && hasIdentity && !duplicate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (creating) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (creating) stringResource(R.string.btc_setting_up) else stringResource(R.string.btc_add_wallet))
                }

                HorizontalDivider()
                SoftwareAutoDiscovery(
                    env = env,
                    network = chain,
                    enabled = hasIdentity,
                    onError = { errorText = it },
                    onDone = onDone,
                )
            } else {
                HardwareSection(
                    env = env,
                    registry = registry,
                    devices = hardwareDevices,
                    label = label,
                    onLabelChange = { label = it },
                    device = hwDevice,
                    onDeviceChange = { hwDeviceId = it.id; errorText = null },
                    network = chain,
                    ledgerAccount = hwAccount,
                    onLedgerAccountChange = { hwAccount = it },
                    onRegisterDevice = onRegisterDevice,
                    onError = { errorText = it },
                    onDone = onDone,
                    scrollState = scrollState,
                )
            }

            errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * Software "Auto Discovery": sweep SEED-derived Bitcoin accounts on [network]
 * (BitcoinWalletDiscovery, BIP84 full Electrum scan), surface the funded ones,
 * and add the selected accounts as Software wallets. Inherits the screen's
 * top-level chain (no own dropdown - the chain is load-bearing and chosen once).
 */
@Composable
private fun SoftwareAutoDiscovery(
    env: BitcoinWalletEnv,
    network: BitcoinNetwork,
    enabled: Boolean,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val progress = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateListOf<BitcoinWalletDiscovery.DiscoveredAccount>() }
    val selected = remember { mutableStateListOf<BitcoinWalletDiscovery.DiscoveredAccount>() }

    Text(stringResource(R.string.walletc_auto_discovery), style = MaterialTheme.typography.titleSmall)
    OutlinedButton(
        enabled = enabled && !scanning && !adding,
        onClick = {
            val words = loadRecoveryWords(context)
            if (words == null) { onError(context.getString(R.string.wallet_unlock_to_discover)); return@OutlinedButton }
            scanning = true
            progress.clear(); found.clear(); selected.clear(); onError(null)
            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        BitcoinWalletDiscovery.scan(
                            mnemonicWords = words.joinToString(" "),
                            passphrase = loadBip39Passphrase(context),
                            networks = listOf(network),
                            electrumURL = { env.settings.electrumURL(it) },
                            onProgress = { p -> progress.add(
                                    context.getString(
                                        R.string.discover_account_phase,
                                        p.account.toString(),
                                        p.phase::class.simpleName ?: "",
                                    ),
                                ) },
                        )
                    }
                }
                scanning = false
                result.onSuccess { rows ->
                    rows.forEach { found.add(it); selected.add(it) }
                    if (rows.isEmpty()) progress.add(context.getString(R.string.discover_no_funded_accounts, network.displayName))
                }.onFailure { onError(it.message ?: it.toString()) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (scanning) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.Filled.Search, contentDescription = null)
        Text(if (scanning) stringResource(R.string.btc_scanning) else stringResource(R.string.btc_discover_existing))
    }
    Text(
        stringResource(R.string.btc_software_discover_caption, network.displayName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    progress.forEach { line ->
        Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }

    if (found.isNotEmpty()) {
        Text(stringResource(R.string.walletc_found_accounts), style = MaterialTheme.typography.titleSmall)
        found.forEach { acct ->
            val alreadyAdded = env.store.hasSoftwareWallet(acct.account, network)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = !alreadyAdded && selected.contains(acct),
                    enabled = !alreadyAdded,
                    onCheckedChange = { include ->
                        if (include) { if (!selected.contains(acct)) selected.add(acct) } else selected.remove(acct)
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.walletc_account, acct.account.toString()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (alreadyAdded) stringResource(R.string.btc_already_in_wallets)
                        else stringResource(R.string.btc_balance_txs, acct.balanceSat.toString(), acct.txCount.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (alreadyAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(
            enabled = !adding && selected.isNotEmpty(),
            onClick = {
                adding = true; onError(null)
                val toAdd = selected.toList()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        toAdd.forEach { acct ->
                            if (!env.store.hasSoftwareWallet(acct.account, acct.network)) {
                                env.store.add(
                                    BitcoinWalletDescriptor(
                                        id = UUID.randomUUID(),
                                        label = "Bitcoin #${acct.account}",
                                        kind = BitcoinWalletKind.Software(account = acct.account),
                                        network = acct.network,
                                    ),
                                    makeActive = false,
                                )
                            }
                        }
                    }
                    adding = false
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (adding) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (adding) stringResource(R.string.btc_adding) else stringResource(R.string.btc_add_selected))
        }
    }
}

/**
 * The Hardware source body: device picker + inline register, the manual single-
 * account Add, and the "Auto Discovery" sweep. The Trezor passphrase is collected
 * in [HardwareConnectDialog] on Add / Discover (not inline); Ledger runs directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareSection(
    env: BitcoinWalletEnv,
    registry: DeviceRegistry,
    devices: List<RegisteredDevice>,
    label: String,
    onLabelChange: (String) -> Unit,
    device: RegisteredDevice?,
    onDeviceChange: (RegisteredDevice) -> Unit,
    network: BitcoinNetwork,
    ledgerAccount: Long,
    onLedgerAccountChange: (Long) -> Unit,
    onRegisterDevice: () -> Unit,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Trezor passphrase state, collected in the connect dialog (not inline).
    var hidden by remember { mutableStateOf(HiddenWalletSelection.STANDARD) }
    var hostPassphrase by remember { mutableStateOf("") }
    // Reset the passphrase when the chosen device changes.
    LaunchedEffect(device?.id) { hidden = HiddenWalletSelection.STANDARD; hostPassphrase = "" }

    var connectIntent by remember { mutableStateOf<HardwareConnectPurpose?>(null) }
    var dialogRunning by remember { mutableStateOf(false) }

    var creating by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<HardwareStage?>(null) }
    var stageAccount by remember { mutableStateOf<Long?>(null) }
    val scanProgress = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateListOf<DiscoveredHardwareAccount>() }
    val selected = remember { mutableStateListOf<DiscoveredHardwareAccount>() }
    var adding by remember { mutableStateOf(false) }

    val isTrezor = device?.kind == DeviceKind.TREZOR
    // Trezor: fixed account 0 (a hidden-wallet passphrase makes the index
    // ambiguous). Ledger: the chosen stepper index.
    val effectiveAccount = if (isTrezor) 0L else ledgerAccount
    val accountDuplicate = device?.let { env.store.hasHardwareWalletAtAccount(it.id, effectiveAccount, network) } ?: false

    LaunchedEffect(found.size, stage, scanning) {
        if (scanning || found.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    // The single-account hardware add (one xpub read), threaded with the chosen
    // passphrase. Runs off the main thread; emits stages into the dialog.
    fun runAdd() {
        val dev = device ?: return
        val hiddenRef = HardwarePassphraseRef.persist(hidden)
        val baseLabel = label.trim().ifEmpty {
            context.getString(R.string.wallet_default_label_device, dev.label, "Bitcoin", effectiveAccount.toString())
        }
        creating = true; onError(null); stage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val choice = HardwarePassphraseRef.resolveChoice(hiddenRef, hostPassphrase)
                    val (fingerprint, xpub) = withHardwareDevice(dev, choice, null, onStage = { stage = it }) { wallet ->
                        val fp = wallet.getBitcoinMasterFingerprint(networkCoinType = network.coinType)
                        val xp = wallet.getBitcoinAccountXpub(account = effectiveAccount, networkCoinType = network.coinType)
                        fp to xp
                    }
                    val exists = env.store.wallets.any { w ->
                        val k = w.kind as? BitcoinWalletKind.Hardware
                        w.network == network && k != null && k.deviceId == dev.id && k.accountXpub == xpub
                    }
                    if (exists) throw IllegalStateException("This wallet is already in your list.")
                    val descriptor = BitcoinWalletDescriptor(
                        label = baseLabel,
                        kind = BitcoinWalletKind.Hardware(
                            deviceId = dev.id,
                            accountFingerprint = fingerprint,
                            accountXpub = xpub,
                            account = effectiveAccount,
                        ),
                        network = network,
                        hidden = HardwarePassphraseRef.toJson(hiddenRef),
                        derivationPath = null,
                    )
                    env.store.add(descriptor, makeActive = false)
                    registry.addBitcoinWallet(dev.id, descriptor.id)
                }
            }
            creating = false; connectIntent = null; dialogRunning = false
            result.onSuccess { onDone() }.onFailure { onError(it.friendlyBtcHardwareMessage()) }
        }
    }

    // The Auto Discovery sweep (accounts 0,1,2,...), threaded with the chosen
    // passphrase. A fresh hidden wallet has no history yet, so keep account 0.
    fun runDiscover() {
        val dev = device ?: return
        val choice = hidden.choice(hostPassphrase)
        val keepFirst = hidden != HiddenWalletSelection.STANDARD
        scanning = true; stage = null; stageAccount = null
        scanProgress.clear(); found.clear(); selected.clear(); onError(null)
        scope.launch {
            val result = runCatching {
                sweepBitcoinAccounts(
                    context = context,
                    device = dev,
                    network = network,
                    passphraseChoice = choice,
                    derivationPathOverride = null,
                    includeFirstAlways = keepFirst,
                    onProgress = { line -> scanProgress.add(line) },
                    onStage = { stage = it },
                    onScanningAccount = { stageAccount = it },
                )
            }
            scanning = false; connectIntent = null; dialogRunning = false
            result.onSuccess { rows ->
                rows.forEach { found.add(it); selected.add(it) }
                if (rows.isEmpty()) scanProgress.add(context.getString(R.string.discover_no_active_accounts))
            }.onFailure { onError(it.friendlyBtcHardwareMessage()) }
        }
    }

    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.btc_no_device_registered),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRegisterDevice, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(R.string.btc_add_new_device))
        }
        return
    }

    DevicePicker(devices = devices, selectedId = device?.id, onSelect = onDeviceChange)

    TextButton(
        enabled = !creating && !scanning && !adding,
        onClick = onRegisterDevice,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.btc_add_new_device))
    }

    OutlinedTextField(
        value = label,
        onValueChange = onLabelChange,
        label = { Text(stringResource(R.string.walletc_wallet_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (isTrezor) {
        Text(
            stringResource(R.string.btc_account_zero),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        AccountStepper(account = ledgerAccount, onChange = onLedgerAccountChange)
        if (accountDuplicate) {
            Text(
                stringResource(R.string.btc_account_added, ledgerAccount.toString(), network.displayName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            when (device?.kind) {
                DeviceKind.LEDGER -> stringResource(R.string.btc_ledger_add_hint)
                DeviceKind.TREZOR -> stringResource(R.string.btc_trezor_add_hint)
                else -> stringResource(R.string.btc_generic_add_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }

    Button(
        onClick = { if (isTrezor) connectIntent = HardwareConnectPurpose.ADD else runAdd() },
        enabled = !creating && !scanning && !adding && device != null && (isTrezor || !accountDuplicate),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (creating && connectIntent == null) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(if (creating && connectIntent == null) stringResource(R.string.btc_connecting) else stringResource(R.string.btc_add_wallet))
    }

    HorizontalDivider()
    Text(stringResource(R.string.walletc_auto_discovery), style = MaterialTheme.typography.titleSmall)
    OutlinedButton(
        enabled = !creating && !scanning && !adding && device != null,
        onClick = { if (isTrezor) connectIntent = HardwareConnectPurpose.DISCOVER else runDiscover() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (scanning && connectIntent == null) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.Filled.Search, contentDescription = null)
        Text(if (scanning && connectIntent == null) stringResource(R.string.btc_scanning) else stringResource(R.string.btc_discover_existing))
    }
    Text(
        stringResource(R.string.btc_hardware_discover_caption, network.displayName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Inline stage line for the Ledger (no dialog) discover; Trezor shows stages
    // inside the connect dialog instead.
    val st = stage
    if (st != null && device != null && connectIntent == null && (scanning || st == HardwareStage.DONE)) {
        HardwareStageLine(stage = st, device = device, account = stageAccount)
    }

    scanProgress.forEach { line ->
        Text(line, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }

    if (found.isNotEmpty()) {
        Text(stringResource(R.string.walletc_found_accounts), style = MaterialTheme.typography.titleSmall)
        found.forEach { acct ->
            val alreadyAdded = env.store.wallets.any { w ->
                val k = w.kind as? BitcoinWalletKind.Hardware
                w.network == network && k != null && k.deviceId == device?.id && k.accountXpub == acct.identity
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = !alreadyAdded && selected.contains(acct),
                    enabled = !alreadyAdded,
                    onCheckedChange = { include ->
                        if (include) { if (!selected.contains(acct)) selected.add(acct) } else selected.remove(acct)
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.walletc_account, acct.account.toString()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        acct.identity.middleEllipsis(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        alreadyAdded -> Text(
                            stringResource(R.string.btc_already_added_device),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        acct.activitySummary.isNotEmpty() -> Text(
                            acct.activitySummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Button(
            enabled = !adding && selected.isNotEmpty(),
            onClick = {
                val dev = device ?: return@Button
                adding = true; onError(null)
                val toAdd = selected.toList()
                scope.launch {
                    val failures = withContext(Dispatchers.IO) {
                        persistDiscoveredSelection(
                            context = context.applicationContext,
                            registry = registry,
                            device = dev,
                            selected = toAdd,
                        )
                    }
                    adding = false
                    if (failures.isNotEmpty()) onError(failures.joinToString("\n")) else onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (adding) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (adding) stringResource(R.string.btc_adding) else stringResource(R.string.btc_add_selected))
        }
    }

    // Trezor connect dialog: hosts the passphrase selector for BOTH add and
    // discover, then shows the live connection stage while the op runs.
    val intent = connectIntent
    if (intent != null && device != null && isTrezor) {
        HardwareConnectDialog(
            device = device,
            purpose = intent,
            selection = hidden,
            onSelectionChange = { hidden = it },
            hostPassphrase = hostPassphrase,
            onHostPassphraseChange = { hostPassphrase = it },
            running = dialogRunning,
            stage = stage,
            stageAccount = stageAccount,
            onContinue = {
                dialogRunning = true
                if (intent == HardwareConnectPurpose.ADD) runAdd() else runDiscover()
            },
            onCancel = { connectIntent = null; dialogRunning = false },
        )
    }
}

/**
 * The single Chain dropdown at the top of Add Bitcoin wallet (ADR-0033): Mainnet
 * / Testnet3 / Signet. Bitcoin is the only chain with this because its coinType
 * is load-bearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitcoinChainDropdown(
    chain: BitcoinNetwork,
    onChange: (BitcoinNetwork) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = chain.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.btc_chain)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val order = orderChainsForMenu(
                all = BitcoinNetwork.entries.toList(),
                primary = BitcoinNetwork.MAINNET,
                isTestnet = { it != BitcoinNetwork.MAINNET },
                name = { it.displayName },
            )
            order.mainnets.forEach { net ->
                DropdownMenuItem(text = { Text(net.displayName) }, onClick = { onChange(net); expanded = false })
            }
            if (order.testnets.isNotEmpty()) HorizontalDivider()
            order.testnets.forEach { net ->
                DropdownMenuItem(text = { Text(net.displayName) }, onClick = { onChange(net); expanded = false })
            }
        }
    }
}

/** Truncate a long identity (xpub) for a list row. */
private fun String.middleEllipsis(head: Int = 10, tail: Int = 6): String =
    if (length <= head + tail + 1) this else "${take(head)}…${takeLast(tail)}"

/** Friendly one-line message for a failed Bitcoin hardware add. */
private fun Throwable.friendlyBtcHardwareMessage(): String = when (this) {
    is com.elabify.musnad.hardware.HardwareWalletException.UserCancelled -> "Cancelled on the device."
    is com.elabify.musnad.hardware.HardwareWalletException.Transport -> "Hardware transport error: $detail"
    is com.elabify.musnad.hardware.HardwareWalletException.NotImplemented ->
        "${kind.displayName}: Bitcoin is not supported on this device."
    else -> message ?: toString()
}
