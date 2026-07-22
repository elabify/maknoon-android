// Add an Ethereum wallet (ADR-0033 universal Add-wallet anatomy, the chain-
// agnostic sibling of AddBitcoinWalletScreen). EVM addresses are the same on
// every chain, so there is NO top-level Chain selector; the dashboard's global
// network switcher picks the view. The Auto Discovery sections carry their own
// network dropdown because a SWEEP must probe one specific chain.
//
//   Software : Wallet Label -> Account (auto-incremented) -> Create wallet,
//              then a divider + "Auto Discovery": pick a chain, sweep the seed
//              (EthereumWalletDiscovery.Software), add the funded accounts.
//
//   Hardware : Device picker + inline "+ Add New Device" -> Account (Ledger:
//              auto-incremented stepper; Trezor: fixed 0) -> Add wallet, then a
//              divider + "Auto Discovery": pick a chain, sweep the device
//              (sweepEthereumAccounts), add the funded accounts.
//
//   Passphrase (Trezor): collected in HardwareConnectDialog on Add / Discover,
//              not inline; the one choice drives both. Ledger runs directly.
//
// UI copy says "second factor" / "security key", never "Identity Sandwich".

package com.elabify.app.maknoon.ui.wallet.ethereum

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
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.elabify.app.maknoon.ui.devices.DiscoveredHardwareAccount
import com.elabify.app.maknoon.ui.devices.persistDiscoveredSelection
import com.elabify.app.maknoon.ui.devices.sweepEthereumAccounts
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
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletDiscovery
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddEthereumWalletScreen(
    onRegisterDevice: () -> Unit,
    onDone: () -> Unit,
    initialDeviceId: UUID? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val walletStore = remember { EthereumStores.walletStore(context) }
    val sandwich = remember { loadEthereumSandwich(context) }
    val registry = remember { DeviceRegistry(context) }

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

    var source by remember(deviceRefreshKey) {
        mutableStateOf(
            when {
                initialDeviceId != null -> AddWalletSource.HARDWARE
                sandwich == null && hardwareDevices.isNotEmpty() -> AddWalletSource.HARDWARE
                else -> AddWalletSource.SOFTWARE
            },
        )
    }
    var label by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(walletStore.nextSoftwareAccount()) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    var hwDeviceId by remember { mutableStateOf<UUID?>(initialDeviceId) }
    val hwDevice: RegisteredDevice? = remember(deviceRefreshKey, hwDeviceId) {
        hardwareDevices.firstOrNull { it.id == hwDeviceId } ?: hardwareDevices.firstOrNull()
    }
    var hwAccount by remember(hwDevice?.id) {
        mutableStateOf(nextHardwareAccount(walletStore, hwDevice?.id))
    }

    val accountInUse = walletStore.hasSoftwareWallet(account)

    fun createSoftware() {
        val sw = sandwich ?: run { errorText = "Identity is locked. Unlock from the Identity tab first."; return }
        if (walletStore.hasSoftwareWallet(account)) { errorText = "A software wallet at account $account already exists."; return }
        val baseLabel = label.trim().ifEmpty { "Ethereum #$account" }
        scope.launch {
            creating = true; errorText = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val addr = EthereumDescriptors.addressFromSandwich(sw, account)
                    walletStore.add(
                        EthereumWalletDescriptor(
                            label = baseLabel,
                            kind = EthereumWalletKind.Software(account),
                            cachedAddress = addr,
                        ),
                    )
                }
            }
            creating = false
            result.onSuccess { onDone() }.onFailure { errorText = it.message ?: it.toString() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.eth_add_ethereum_wallet)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel)) } },
            )
        },
    ) { padding ->
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SourcePicker(selected = source, onSelect = { source = it; errorText = null })

            if (source == AddWalletSource.SOFTWARE) {
                if (sandwich == null) {
                    Text(
                        stringResource(R.string.eth_software_needs_identity),
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
                if (accountInUse) {
                    Text(
                        stringResource(R.string.eth_account_in_use, account.toString()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { createSoftware() },
                    enabled = !creating && sandwich != null && !accountInUse,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (creating) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (creating) stringResource(R.string.eth_setting_up) else stringResource(R.string.eth_add_wallet))
                }
                Text(
                    stringResource(R.string.eth_same_address_every_network),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                SoftwareAutoDiscovery(
                    walletStore = walletStore,
                    sandwich = sandwich,
                    onError = { errorText = it },
                    onDone = onDone,
                )
            } else {
                EthereumHardwareSection(
                    walletStore = walletStore,
                    registry = registry,
                    devices = hardwareDevices,
                    label = label,
                    onLabelChange = { label = it },
                    device = hwDevice,
                    onDeviceChange = { hwDeviceId = it.id; errorText = null },
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

/** Software Auto Discovery: pick a chain, sweep SEED-derived EVM accounts for
 *  on-chain activity (EthereumWalletDiscovery.Software), add the funded ones. */
@Composable
private fun SoftwareAutoDiscovery(
    walletStore: com.elabify.musnad.wallet.ethereum.EthereumWalletStore,
    sandwich: com.elabify.musnad.identity.IdentitySandwich?,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var network by remember { mutableStateOf(EthereumNetwork.MAINNET) }
    var scanning by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val progress = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateListOf<EthereumWalletDiscovery.DiscoveredAccount>() }
    val selected = remember { mutableStateListOf<EthereumWalletDiscovery.DiscoveredAccount>() }

    Text(stringResource(R.string.walletc_auto_discovery), style = MaterialTheme.typography.titleSmall)
    EthereumNetworkDropdown(network = network, onChange = { network = it })
    OutlinedButton(
        enabled = sandwich != null && !scanning && !adding,
        onClick = {
            val sw = sandwich ?: run { onError("Unlock your identity to discover software wallets."); return@OutlinedButton }
            scanning = true; progress.clear(); found.clear(); selected.clear(); onError(null)
            scope.launch {
                val settings = EthereumStores.settings(context)
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        EthereumWalletDiscovery.scan(
                            source = EthereumWalletDiscovery.Source.Software(sw),
                            network = network,
                            rpcURL = settings.rpcURL(network),
                            explorerAPIURL = settings.explorerAPIURL(network),
                            apiKey = settings.explorerAPIKey(network),
                            onProgress = { p -> progress.add("Account ${p.account}: ${p.phase::class.simpleName}") },
                        )
                    }
                }
                scanning = false
                result.onSuccess { rows ->
                    rows.forEach { found.add(it); selected.add(it) }
                    if (rows.isEmpty()) progress.add("No funded accounts found on ${network.displayName}.")
                }.onFailure { onError(it.message ?: it.toString()) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (scanning) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.Filled.Search, contentDescription = null)
        Text(if (scanning) "  " + stringResource(R.string.eth_scanning) else "  " + stringResource(R.string.eth_discover_existing_wallets))
    }
    Text(
        stringResource(R.string.eth_software_discover_caption, network.displayName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    progress.forEach { line ->
        Text(line, style = MaterialTheme.typography.labelSmall)
    }

    if (found.isNotEmpty()) {
        Text(stringResource(R.string.walletc_found_accounts), style = MaterialTheme.typography.titleSmall)
        found.forEach { acct ->
            val alreadyAdded = walletStore.hasSoftwareWallet(acct.account)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = !alreadyAdded && selected.contains(acct),
                    enabled = !alreadyAdded,
                    onCheckedChange = { include -> if (include) { if (!selected.contains(acct)) selected.add(acct) } else selected.remove(acct) },
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.walletc_account, acct.account.toString()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (alreadyAdded) stringResource(R.string.eth_already_in_your_wallets) else acct.address,
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
                            if (!walletStore.hasSoftwareWallet(acct.account)) {
                                walletStore.add(
                                    EthereumWalletDescriptor(
                                        label = "Ethereum #${acct.account}",
                                        kind = EthereumWalletKind.Software(acct.account),
                                        cachedAddress = acct.address,
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
            Text(if (adding) stringResource(R.string.eth_adding) else stringResource(R.string.eth_add_selected))
        }
    }
}

/** Hardware source: device + inline register, single-account Add, and the Auto
 *  Discovery sweep. Trezor passphrase is collected in HardwareConnectDialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EthereumHardwareSection(
    walletStore: com.elabify.musnad.wallet.ethereum.EthereumWalletStore,
    registry: DeviceRegistry,
    devices: List<RegisteredDevice>,
    label: String,
    onLabelChange: (String) -> Unit,
    device: RegisteredDevice?,
    onDeviceChange: (RegisteredDevice) -> Unit,
    ledgerAccount: Long,
    onLedgerAccountChange: (Long) -> Unit,
    onRegisterDevice: () -> Unit,
    onError: (String?) -> Unit,
    onDone: () -> Unit,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hidden by remember { mutableStateOf(HiddenWalletSelection.STANDARD) }
    var hostPassphrase by remember { mutableStateOf("") }
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
    var scanNetwork by remember { mutableStateOf(EthereumNetwork.MAINNET) }

    val isTrezor = device?.kind == DeviceKind.TREZOR
    val effectiveAccount = if (isTrezor) 0L else ledgerAccount
    val accountDuplicate = device?.let { walletStore.hasHardwareWallet(it.id, effectiveAccount) } ?: false

    LaunchedEffect(found.size, stage, scanning) {
        if (scanning || found.isNotEmpty()) scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun runAdd() {
        val dev = device ?: return
        val hiddenRef = HardwarePassphraseRef.persist(hidden)
        creating = true; onError(null); stage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val choice = HardwarePassphraseRef.resolveChoice(hiddenRef, hostPassphrase)
                    val addr = withHardwareDevice(dev, choice, null, onStage = { stage = it }) { wallet ->
                        wallet.getEthereumAddress(effectiveAccount)
                    }
                    val exists = walletStore.wallets.any { w ->
                        val k = w.kind as? EthereumWalletKind.Hardware
                        k != null && k.deviceId == dev.id && k.address.equals(addr, ignoreCase = true)
                    }
                    if (exists) throw IllegalStateException("This wallet is already in your list.")
                    val descriptor = EthereumWalletDescriptor(
                        label = "${dev.label} Ethereum #$effectiveAccount",
                        kind = EthereumWalletKind.Hardware(deviceId = dev.id, account = effectiveAccount, address = addr),
                        cachedAddress = addr,
                        hidden = hiddenRef?.wireId,
                        derivationPath = null,
                    )
                    walletStore.add(descriptor, makeActive = false)
                    registry.addEthereumWallet(dev.id, descriptor.id)
                }
            }
            creating = false; connectIntent = null; dialogRunning = false
            result.onSuccess { onDone() }.onFailure { onError(it.friendlyEthHardwareMessage()) }
        }
    }

    fun runDiscover() {
        val dev = device ?: return
        val choice = hidden.choice(hostPassphrase)
        val keepFirst = hidden != HiddenWalletSelection.STANDARD
        scanning = true; stage = null; stageAccount = null
        scanProgress.clear(); found.clear(); selected.clear(); onError(null)
        scope.launch {
            val result = runCatching {
                sweepEthereumAccounts(
                    context = context,
                    device = dev,
                    network = scanNetwork,
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
                if (rows.isEmpty()) scanProgress.add("No active accounts found.")
            }.onFailure { onError(it.friendlyEthHardwareMessage()) }
        }
    }

    if (devices.isEmpty()) {
        Text(
            stringResource(R.string.eth_no_device_registered),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRegisterDevice, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  " + stringResource(R.string.eth_add_new_device))
        }
        return
    }

    DevicePicker(devices = devices, selectedId = device?.id, onSelect = onDeviceChange)
    TextButton(enabled = !creating && !scanning && !adding, onClick = onRegisterDevice) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(" " + stringResource(R.string.eth_add_new_device))
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
            stringResource(R.string.eth_account_zero),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        AccountStepper(account = ledgerAccount, onChange = onLedgerAccountChange)
        if (accountDuplicate) {
            Text(
                stringResource(R.string.eth_hardware_account_in_use, ledgerAccount.toString(), device?.label ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(
            when (device?.kind) {
                DeviceKind.LEDGER -> stringResource(R.string.eth_ledger_unlock_open_app)
                DeviceKind.TREZOR -> stringResource(R.string.eth_trezor_unlock)
                else -> stringResource(R.string.eth_connect_device_approve)
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
        Text(if (creating && connectIntent == null) stringResource(R.string.eth_connecting) else stringResource(R.string.eth_add_wallet))
    }

    HorizontalDivider()
    Text(stringResource(R.string.walletc_auto_discovery), style = MaterialTheme.typography.titleSmall)
    EthereumNetworkDropdown(network = scanNetwork, onChange = { scanNetwork = it })
    OutlinedButton(
        enabled = !creating && !scanning && !adding && device != null,
        onClick = { if (isTrezor) connectIntent = HardwareConnectPurpose.DISCOVER else runDiscover() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (scanning && connectIntent == null) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Icon(Icons.Filled.Search, contentDescription = null)
        Text(if (scanning && connectIntent == null) "  " + stringResource(R.string.eth_scanning) else "  " + stringResource(R.string.eth_discover_existing_wallets))
    }
    Text(
        stringResource(R.string.eth_hardware_discover_caption, scanNetwork.displayName),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val st = stage
    if (st != null && device != null && connectIntent == null && (scanning || st == HardwareStage.DONE)) {
        HardwareStageLine(stage = st, device = device, account = stageAccount)
    }
    scanProgress.forEach { line -> Text(line, style = MaterialTheme.typography.labelSmall) }

    if (found.isNotEmpty()) {
        Text(stringResource(R.string.walletc_found_accounts), style = MaterialTheme.typography.titleSmall)
        found.forEach { acct ->
            val alreadyAdded = walletStore.wallets.any { w ->
                val k = w.kind as? EthereumWalletKind.Hardware
                k != null && k.deviceId == device?.id && k.address.equals(acct.identity, ignoreCase = true)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = !alreadyAdded && selected.contains(acct),
                    enabled = !alreadyAdded,
                    onCheckedChange = { include -> if (include) { if (!selected.contains(acct)) selected.add(acct) } else selected.remove(acct) },
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.walletc_account, acct.account.toString()), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (alreadyAdded) stringResource(R.string.eth_already_added_on_device) else acct.identity,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (alreadyAdded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        persistDiscoveredSelection(context = context.applicationContext, registry = registry, device = dev, selected = toAdd)
                    }
                    adding = false
                    if (failures.isNotEmpty()) onError(failures.joinToString("\n")) else onDone()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (adding) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (adding) stringResource(R.string.eth_adding) else stringResource(R.string.eth_add_selected))
        }
    }

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

/** EVM network dropdown for the Auto Discovery sweeps (which chain to scan).
 *  Ethereum addresses are chain-agnostic, so this only chooses where to PROBE. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EthereumNetworkDropdown(
    network: EthereumNetwork,
    onChange: (EthereumNetwork) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = network.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.walletc_chain_to_scan)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val order = orderChainsForMenu(
                all = EthereumNetwork.entries.toList(),
                primary = EthereumNetwork.MAINNET,
                isTestnet = { it.isTestnet },
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

/** Next free hardware-account index for [deviceId] across the ETH wallet store. */
private fun nextHardwareAccount(
    store: com.elabify.musnad.wallet.ethereum.EthereumWalletStore,
    deviceId: java.util.UUID?,
): Long {
    if (deviceId == null) return 0L
    var i = 0L
    while (store.hasHardwareWallet(deviceId, i)) i++
    return i
}

/** Friendly one-line message for a failed Ethereum hardware add. */
private fun Throwable.friendlyEthHardwareMessage(): String = when (this) {
    is com.elabify.musnad.hardware.HardwareWalletException.UserCancelled -> "Cancelled on the device."
    is com.elabify.musnad.hardware.HardwareWalletException.Transport -> "Hardware transport error: $detail"
    is com.elabify.musnad.hardware.HardwareWalletException.NotImplemented ->
        "${kind.displayName}: Ethereum is not supported on this device."
    else -> message ?: toString()
}
