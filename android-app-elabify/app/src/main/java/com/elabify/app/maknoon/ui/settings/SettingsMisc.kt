// Four Settings sub-screens ported 1:1 from the shipped iOS views:
//   * AddressBookScreen      <- AddressBook/AddressBookView.swift
//   * CurrencySettingsScreen <- CurrencySettingsView.swift
//   * DisplaySettingsScreen  <- Display/DisplaySettingsView.swift
//   * AboutScreen            <- AboutView.swift
//
// Each is a Scaffold with a TopAppBar (the iOS navigationTitle, a back arrow
// wired to onBack) and the iOS Form sections reproduced as rounded "section
// card" groups, matching the grouping look already used in SettingsScreen.kt
// and the other ported sub-screens (NetworksSettingsScreen / IdentitySettings
// Screen). Section footers render as a small caption below each card.
//
// Backing stores. The iOS app keeps these in observable @MainActor stores
// (FiatPreferences, DisplayPreferences, AddressBookStore on HolderStore). The
// Android SDK ships none of those, so rather than invent an SDK API each screen
// is backed here by a tiny SharedPreferences store, reusing the exact iOS
// UserDefaults keys where iOS has them:
//   * CurrencyPrefs : "app.fiatCurrencyCode" / "app.fiatReferenceEnabled".
//   * DisplayPrefs  : "display.theme" / "display.autoLock" / "display.language".
//   * AddressBookStore : one JSON document under a private prefs file, with
//     add / list / delete (user entries only; see note below).
//
// Notes on iOS rows that do not map cleanly to Android today:
//   * AddressBook: iOS shows read-only "system" entries that mirror the user's
//     own wallets (AddressBookEntrySource.systemWallet, rendered with a lock and
//     no swipe-to-delete). The Android SDK has no wallet-mirror feed into an
//     address book, so only user entries exist here; every row is editable +
//     deletable. The add/edit sheet, per-network grouping, network picker, and
//     address placeholders / footers are reproduced verbatim.
//   * Currency: the iOS "Preview" section pulls live fiat captions from
//     store.assetPrices (CoinGecko). Android has no spot-price service, so the
//     three preview rows render with an em-free "no price" dash, exactly the
//     fallback iOS shows before its cache warms.
//   * About → Diagnostics: iOS shares LogStore.shared. Android has no LogStore;
//     a minimal in-file DiagnosticsLog (count / formatted / clear, sharing via
//     a system share Intent) backs the section so the rows, count, warning
//     dialog, and clear dialog all work. It reports 0 until something logs.
//   * About → Commit: iOS reads CFBundleShortVersionString / CFBundleVersion /
//     ELABIFY_BUILD_COMMIT from the bundle. Android uses BuildConfig.VERSION_NAME
//     and BuildConfig.VERSION_CODE; there is no build-commit field baked into
//     BuildConfig, so Commit falls back to "dev" (matching the iOS `?? "dev"`).

package com.elabify.app.maknoon.ui.settings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.BuildConfig
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.theme.AppLanguage
import com.elabify.app.maknoon.ui.theme.AppTheme
import com.elabify.app.maknoon.ui.theme.AutoLockTimeout
import com.elabify.app.maknoon.ui.theme.DisplayPreferences
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.components.AdvancedSection
import com.elabify.app.maknoon.ui.theme.Spacing
import java.io.File
import java.util.Currency
import java.util.Locale
import java.util.UUID

// ===========================================================================
// Shared bits (local to this file so each screen stands alone).
// ===========================================================================

// A rounded "section card" group, the Android stand-in for an iOS Form Section.
@Composable
private fun MiscSectionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

// The small secondary caption rendered below a section card (iOS Section footer).
@Composable
private fun MiscFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

@Composable
private fun MiscHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

// ===========================================================================
// Address book
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressBookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AddressBookStore(context) }
    val entries = remember { mutableStateListOf<AddressBookEntry>().apply { addAll(store.all()) } }

    fun reload() {
        entries.clear()
        entries.addAll(store.all())
    }

    var showAdd by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<AddressBookEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_address_book)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Filled.AddCircle, contentDescription = stringResource(R.string.settings_add_contact))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (entries.isEmpty()) {
                MiscSectionCard {
                    Text(
                        stringResource(R.string.settings_no_contacts_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.md),
                    )
                }
            } else {
                // One section per network type, in the iOS enum order, skipping
                // empty groups (matches the iOS ForEach guard).
                AddressBookNetwork.entries.forEach { net ->
                    val group = entries.filter { it.network == net }
                    if (group.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            MiscHeader(net.displayName)
                            MiscSectionCard {
                                group.forEach { entry ->
                                    AddressBookRow(entry = entry, onClick = { editTarget = entry })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddressBookEntrySheet(
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { e -> store.upsert(e); reload(); showAdd = false },
            onDelete = null,
        )
    }
    editTarget?.let { target ->
        AddressBookEntrySheet(
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { e -> store.upsert(e); reload(); editTarget = null },
            onDelete = { store.remove(target.id); reload(); editTarget = null },
        )
    }
}

@Composable
private fun AddressBookRow(entry: AddressBookEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            entry.network.icon,
            contentDescription = null,
            tint = entry.network.tint,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name.ifEmpty { shortAddress(entry.address) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (entry.name.isNotEmpty()) {
                Text(
                    shortAddress(entry.address),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Add / edit one contact. iOS presents this as a sheet with a NavigationStack
// (Network picker, Name, Address with paste, Save). On Android it is an
// AlertDialog with the same fields, plus a destructive Remove on edit (iOS does
// delete via the list's swipe action; the dialog folds it in for parity).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressBookEntrySheet(
    initial: AddressBookEntry?,
    onDismiss: () -> Unit,
    onSave: (AddressBookEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var network by remember { mutableStateOf(initial?.network ?: AddressBookNetwork.BITCOIN) }
    var networkMenu by remember { mutableStateOf(false) }
    // Name-service resolution on save: a .eth (Ethereum) or .sol (Solana) entry is
    // resolved to its address before persisting, so saved contacts hold addresses.
    var resolving by remember { mutableStateOf(false) }
    var resolveError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.settings_new_contact) else stringResource(R.string.settings_edit_contact)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                // Network.
                MiscHeader(stringResource(R.string.common_network))
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.sm))
                            .clickable { networkMenu = true }
                            .padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(network.icon, contentDescription = null, tint = network.tint, modifier = Modifier.size(20.dp))
                        Text(network.displayName, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    DropdownMenu(expanded = networkMenu, onDismissRequest = { networkMenu = false }) {
                        AddressBookNetwork.entries.forEach { net ->
                            DropdownMenuItem(
                                text = { Text(net.displayName) },
                                leadingIcon = { Icon(net.icon, contentDescription = null, tint = net.tint) },
                                onClick = { network = net; networkMenu = false },
                            )
                        }
                    }
                }
                MiscFooter(stringResource(R.string.settings_pick_chain_footer))

                // Name.
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Address.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(addressPlaceholder(network)) },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let { address = it.trim() }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.settings_paste))
                    }
                }
                MiscFooter(addressFooter(network))
                resolveError?.let {
                    Text(it, color = MaknoonColors.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.trim().isNotEmpty() && !resolving,
                onClick = {
                    val raw = address.trim()
                    fun save(addr: String) = onSave(
                        AddressBookEntry(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            address = addr,
                            network = network,
                        ),
                    )
                    val isEns = network == AddressBookNetwork.ETHEREUM &&
                        com.elabify.musnad.wallet.ethereum.ENSResolver.looksLikeName(raw)
                    val isSns = network == AddressBookNetwork.SOLANA &&
                        com.elabify.musnad.wallet.solana.SolanaNameResolver.looksLikeName(raw)
                    if (!isEns && !isSns) { save(raw); return@TextButton }
                    // Resolve the name to an address before saving (off-thread).
                    resolving = true; resolveError = null
                    scope.launch {
                        val resolved = withContext(Dispatchers.IO) {
                            runCatching {
                                if (isEns) {
                                    com.elabify.musnad.wallet.ethereum.ENSResolver(
                                        com.elabify.musnad.wallet.ethereum.EthereumNetwork.MAINNET.defaultRPCURL,
                                    ).resolve(raw)
                                } else {
                                    com.elabify.musnad.wallet.solana.SolanaNameResolver(
                                        com.elabify.musnad.wallet.solana.SolanaNetwork.MAINNET.defaultRpcURL,
                                    ).resolve(raw)
                                }
                            }
                        }
                        resolving = false
                        resolved.onSuccess { save(it) }.onFailure { resolveError = it.message ?: it.toString() }
                    }
                },
            ) {
                Text(if (resolving) stringResource(R.string.settings_resolving) else if (initial == null) stringResource(R.string.settings_save_contact) else stringResource(R.string.settings_save_changes))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.common_remove), color = MaknoonColors.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

private fun addressPlaceholder(net: AddressBookNetwork): String = when (net) {
    AddressBookNetwork.BITCOIN -> "bc1q… / bc1p… / 3… / 1…"
    AddressBookNetwork.ETHEREUM -> "0x… or vitalik.eth"
    AddressBookNetwork.LIGHTNING -> "user@domain.com (LNURL address)"
    AddressBookNetwork.SOLANA -> "Base58 32-byte address"
    AddressBookNetwork.TRON -> "T… (base58check)"
}

private fun addressFooter(net: AddressBookNetwork): String = when (net) {
    AddressBookNetwork.BITCOIN ->
        "Any valid Bitcoin address. Works on mainnet, testnet3, and signet, the address itself encodes which."
    AddressBookNetwork.ETHEREUM ->
        "Either a 0x hex address or an ENS name like vitalik.eth. ENS names are resolved on demand each time you send, using the ENS gateway configured in Ethereum settings. EVM addresses are chain-agnostic and work on Mainnet, Sepolia, every L2, and any custom chain you've added."
    AddressBookNetwork.LIGHTNING ->
        "Use LUD-16 lightning addresses (e.g. you@walletofsatoshi.com). BOLT11 invoices aren't stored here because they're single-use."
    AddressBookNetwork.SOLANA ->
        "Solana addresses are base58 32-byte Ed25519 public keys. Cluster-agnostic: the same address works on Mainnet, Devnet, and Testnet."
    AddressBookNetwork.TRON ->
        "Tron addresses are base58check, prefixed with T. Network-agnostic: works on Mainnet, Shasta, and Nile."
}

private fun shortAddress(s: String): String =
    if (s.length <= 24) s else "${s.take(10)}…${s.takeLast(8)}"

// ===========================================================================
// Currency
// ===========================================================================

// Curated fiat list + picker labels, ported from FiatCurrencyCatalog.swift. The
// sorted codes and the "USD ($), US Dollar" label shape match iOS; localized
// names + symbols come from java.util.Currency / Locale rather than a table.
private object FiatCurrencyCatalog {
    private val codes = listOf(
        "usd", "eur", "gbp", "jpy", "chf",
        "aud", "cad", "nzd", "cny", "hkd", "sgd",
        "krw", "inr", "aed", "sar", "qar",
        "bhd", "kwd", "omr", "ils",
        "brl", "mxn", "ars", "clp", "cop", "pen",
        "zar", "ngn", "egp",
        "myr", "idr", "php", "thb", "vnd",
        "try", "rub", "uah",
        "pln", "czk", "huf", "ron",
        "sek", "nok", "dkk", "isk",
    )

    val sortedCodes: List<String> = codes.sorted()

    private fun currency(code: String): Currency? =
        runCatching { Currency.getInstance(code.uppercase(Locale.US)) }.getOrNull()

    fun displayName(code: String): String =
        currency(code)?.getDisplayName(Locale.getDefault()) ?: code.uppercase(Locale.US)

    fun symbol(code: String): String {
        val upper = code.uppercase(Locale.US)
        return currency(code)?.getSymbol(Locale.getDefault()) ?: upper
    }

    fun pickerLabel(code: String): String {
        val upper = code.uppercase(Locale.US)
        val sym = symbol(code)
        val symPart = if (sym == upper) "" else " ($sym)"
        return "$upper$symPart, ${displayName(code)}"
    }

    // The "no price" caption used in the preview rows. Android has no
    // spot-price service, so there is no live rate to format; show a dash,
    // matching the iOS fallback before its CoinGecko cache warms.
    fun previewCaption(code: String): String = "-"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    FiatPreferences.init(context)
    // Observable global fiat prefs: changes apply live across wallets + mini-app.
    val showReferencePrices = FiatPreferences.showReferencePrices
    val code = FiatPreferences.code
    var pickerMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_currency)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Section 1: master toggle + footer.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.settings_show_reference_prices), modifier = Modifier.weight(1f))
                        Switch(
                            checked = showReferencePrices,
                            onCheckedChange = { FiatPreferences.showReferencePrices = it },
                        )
                    }
                }
                MiscFooter(
                    stringResource(R.string.settings_reference_prices_footer),
                )
            }

            if (showReferencePrices) {
                // Section 2: display-currency picker + footer.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MiscHeader(stringResource(R.string.settings_currency))
                    MiscSectionCard {
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { pickerMenu = true }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.settings_display_currency), modifier = Modifier.weight(1f))
                                Text(
                                    code.uppercase(Locale.US),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(expanded = pickerMenu, onDismissRequest = { pickerMenu = false }) {
                                FiatCurrencyCatalog.sortedCodes.forEach { c ->
                                    DropdownMenuItem(
                                        text = { Text(FiatCurrencyCatalog.pickerLabel(c)) },
                                        onClick = {
                                            FiatPreferences.code = c
                                            pickerMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    MiscFooter(
                        stringResource(R.string.settings_display_currency_footer),
                    )
                }

                // Section 4: preview.
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MiscHeader(stringResource(R.string.settings_preview))
                    MiscSectionCard {
                        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            previewRow(stringResource(R.string.settings_preview_1_btc), code)
                            previewRow(stringResource(R.string.settings_preview_0001_btc), code)
                            previewRow(stringResource(R.string.settings_preview_1_eth), code)
                        }
                    }
                }

                // Advanced: overridable price-data sources, pinned to the very
                // bottom (0.6.1 friendliness pass).
                AdvancedSection {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    MiscHeader(stringResource(R.string.settings_price_data_sources))
                    MiscSectionCard {
                        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            OutlinedTextField(
                                value = FiatPreferences.coinGeckoBaseURL,
                                onValueChange = { FiatPreferences.coinGeckoBaseURL = it },
                                label = { Text(stringResource(R.string.settings_coingecko_base_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = FiatPreferences.fxBaseURL,
                                onValueChange = { FiatPreferences.fxBaseURL = it },
                                label = { Text(stringResource(R.string.settings_fx_rates_url)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                onClick = {
                                    FiatPreferences.coinGeckoBaseURL = FiatPreferences.DEFAULT_COINGECKO
                                    FiatPreferences.fxBaseURL = FiatPreferences.DEFAULT_FX
                                },
                            ) { Text(stringResource(R.string.settings_use_defaults)) }
                        }
                    }
                    MiscFooter(
                        stringResource(R.string.settings_price_sources_footer),
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun previewRow(label: String, code: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(
            FiatCurrencyCatalog.previewCaption(code),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ===========================================================================
// Display
// ===========================================================================

// Ported from Display/DisplaySettings.swift (AppTheme / AutoLockTimeout /
// AppLanguage). Same case order, same labels, same defaults.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    var showLanguagePicker by remember { mutableStateOf(false) }

    if (showLanguagePicker) {
        LanguagePickerScreen(onPicked = { showLanguagePicker = false })
        return
    }

    val context = LocalContext.current
    DisplayPreferences.init(context)
    // Read the observable shared prefs directly so changes apply app-wide.
    val theme = DisplayPreferences.theme
    val autoLock = DisplayPreferences.autoLock
    val language = DisplayPreferences.language

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_display)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Theme.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_theme))
                MiscSectionCard {
                    MenuPickerRow(
                        icon = Icons.Filled.Palette,
                        label = stringResource(R.string.settings_theme),
                        valueLabel = stringResource(theme.labelRes),
                        options = AppTheme.entries.map { it to stringResource(it.labelRes) },
                        onSelect = { DisplayPreferences.theme = it },
                    )
                }
                if (theme == AppTheme.AUTOMATIC) {
                    MiscFooter(stringResource(R.string.settings_theme_automatic_footer))
                }
            }

            // Auto-Lock.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_auto_lock))
                MiscSectionCard {
                    MenuPickerRow(
                        icon = Icons.Filled.Lock,
                        label = stringResource(R.string.settings_auto_lock),
                        valueLabel = stringResource(autoLock.labelRes),
                        options = AutoLockTimeout.entries.map { it to stringResource(it.labelRes) },
                        onSelect = { DisplayPreferences.autoLock = it },
                    )
                }
                MiscFooter(stringResource(R.string.settings_auto_lock_footer))
            }

            // Language.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_language))
                MiscSectionCard {
                    // A pushed searchable screen, not a dropdown: 32 options in a
                    // menu is unusable, and this is the one screen a user needs to
                    // operate while unable to read the current language.
                    SettingNavRow(
                        icon = Icons.Filled.Language,
                        label = stringResource(R.string.settings_language),
                        value = language.selfName
                            ?: stringResource(R.string.settings_language_use_phone),
                        onClick = { showLanguagePicker = true },
                    )
                }
                MiscFooter(stringResource(R.string.settings_language_footer))
            }
        }
    }
}

// A row with a leading icon, a label, the current value, and a tap-to-open menu
// (the Android stand-in for iOS Picker(.menu)).
@Composable
private fun <T> MenuPickerRow(
    icon: ImageVector,
    label: String,
    valueLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(icon, contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(22.dp))
            Text(label, modifier = Modifier.weight(1f))
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = { onSelect(value); expanded = false },
                )
            }
        }
    }
}

// ===========================================================================
// About
// ===========================================================================

// Minimal in-app diagnostics buffer. iOS shares LogStore.shared; Android has no
// such SDK store, so this gives the About → Diagnostics section a real count /
// formatted / clear to bind to. It reports 0 until something appends to it.
private object DiagnosticsLog {
    private val entries = mutableListOf<String>()

    val count: Int get() = entries.size

    fun formatted(): String = if (entries.isEmpty()) {
        "(no diagnostic entries)"
    } else {
        entries.joinToString("\n")
    }

    fun clear() = entries.clear()
}

internal data class CreditEntry(val name: String, val description: String, val url: String)

internal data class ComponentEntry(val name: String, val version: String, val license: String, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLogsWarning by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var logCount by remember { mutableStateOf(DiagnosticsLog.count) }

    val marketingVersion = BuildConfig.VERSION_NAME
    val bundleVersion = BuildConfig.VERSION_CODE.toString()
    val buildCommit = BuildConfig.GIT_COMMIT

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // App.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_app))
                MiscSectionCard {
                    Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AboutKeyValueRow(stringResource(R.string.common_name), stringResource(R.string.settings_app_name_value))
                        AboutKeyValueRow(stringResource(R.string.settings_version), marketingVersion)
                        AboutKeyValueRow(stringResource(R.string.settings_build), bundleVersion)
                        AboutKeyValueRow(stringResource(R.string.settings_commit), buildCommit)
                    }
                }
            }

            // Elabify.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_elabify))
                MiscSectionCard {
                    LinkRow(Icons.Filled.Public, "elabify.com", "https://elabify.com", context)
                    LinkRow(Icons.Filled.Code, stringResource(R.string.settings_source_code_link), "https://github.com/elabify/maknoon-android", context)
                    LinkRow(Icons.Filled.Description, stringResource(R.string.settings_license_link), "https://github.com/elabify/maknoon-android/blob/main/LICENSE.md", context)
                    LinkRow(Icons.Filled.PanTool, stringResource(R.string.settings_privacy_policy_link), "https://elabify.com/support/compliance/privacy-policy/", context)
                }
            }

            // Standards (mirrors iOS AboutView standardsSection).
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader("Standards")
                MiscSectionCard {
                    LinkRow(Icons.Filled.CheckCircle, "ToIP HAVID", "https://github.com/trustoverip/high-assurance-verifiable-identifiers", context)
                }
                MiscFooter(
                    "Maknoon verifiers can cross-check an issuer's DID against its X.509 organisational certificate (HAVID), and a passport's CSCA against an on-chain registry, so trust does not rest on the issuer's word alone.",
                )
            }

            // Default services.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_default_services))
                MiscSectionCard {
                    SERVICES.forEach { CreditRow(it, context) }
                }
                MiscFooter(stringResource(R.string.settings_default_services_footer))
            }

            // Open-source components.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_open_source_components))
                MiscSectionCard {
                    COMPONENTS.forEach { ComponentRow(it, context) }
                }
            }

            // Diagnostics.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MiscHeader(stringResource(R.string.settings_diagnostics))
                MiscSectionCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.settings_log_entries),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            logCount.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    AboutActionRow(Icons.Filled.Share, stringResource(R.string.settings_share_diagnostic_logs), MaknoonBrand.accent) {
                        showLogsWarning = true
                    }
                    AboutActionRow(Icons.Filled.Delete, stringResource(R.string.settings_clear_logs), MaknoonColors.error) {
                        showClearConfirm = true
                    }
                }
                MiscFooter(stringResource(R.string.settings_diagnostics_footer))
            }
        }
    }

    if (showLogsWarning) {
        AlertDialog(
            onDismissRequest = { showLogsWarning = false },
            title = { Text(stringResource(R.string.settings_share_logs_question)) },
            text = {
                Text(
                    stringResource(R.string.settings_share_logs_warning),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogsWarning = false
                    shareDiagnosticLogs(context, buildCommit, bundleVersion)
                }) {
                    Text(stringResource(R.string.settings_understand_share_logs))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogsWarning = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.settings_clear_logs_question)) },
            confirmButton = {
                TextButton(onClick = {
                    DiagnosticsLog.clear()
                    logCount = DiagnosticsLog.count
                    showClearConfirm = false
                }) {
                    Text(stringResource(R.string.settings_clear_all_entries), color = MaknoonColors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun AboutKeyValueRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LinkRow(icon: ImageVector, label: String, url: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, url) }
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(20.dp))
        Text(label, modifier = Modifier.weight(1f), color = MaknoonBrand.accent)
    }
}

@Composable
private fun CreditRow(credit: CreditEntry, context: Context) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, credit.url) }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(credit.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            credit.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComponentRow(component: ComponentEntry, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, component.url) }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(component.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                component.license,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            component.version,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutActionRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint)
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

// Write the formatted diagnostics buffer to a temp file and fire a system share
// sheet. Filename matches the iOS shape:
//   maknoon-diagnosticLog-<commit>-<build>-<yyyyMMdd-HHmmss>.txt
private fun shareDiagnosticLogs(context: Context, commit: String, build: String) {
    val body = DiagnosticsLog.formatted()
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
    val name = "maknoon-diagnosticLog-$commit-$build-$stamp.txt"
    runCatching {
        val file = File(context.cacheDir, name)
        file.writeText(body)
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, body)
        putExtra(Intent.EXTRA_TITLE, name)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share diagnostic logs")) }
}

// The default-services credit list, in the exact iOS order.
internal val SERVICES: List<CreditEntry> = listOf(
    CreditEntry("Arbitrum Foundation", "Arbitrum One + Sepolia RPC", "https://arbitrum.foundation"),
    CreditEntry("Ava Labs", "Avalanche C-Chain RPC", "https://www.avax.network"),
    CreditEntry("Base", "Base Mainnet + Sepolia RPC", "https://base.org"),
    CreditEntry("Blockscout", "Open-source block-explorer API used by default on every EVM chain that has a Blockscout deployment", "https://www.blockscout.com"),
    CreditEntry("Blockstream", "Public Electrum servers and esplora APIs that Bitcoin wallets fall back on", "https://blockstream.info"),
    CreditEntry("BNB Chain", "BSC RPC", "https://www.bnbchain.org"),
    CreditEntry("Chainlink", "LINK contract addresses and the Sepolia LINK faucet", "https://chain.link"),
    CreditEntry("Circle", "USDC contract addresses across chains and the Sepolia faucet for test USDC", "https://www.circle.com"),
    CreditEntry("CoinGecko", "Fiat price feeds for Bitcoin display", "https://www.coingecko.com"),
    CreditEntry("HashKey Chain", "HashKey Chain testnet RPC and block explorer", "https://hsk.xyz"),
    CreditEntry("Hyperliquid", "Hyperliquid EVM RPC", "https://hyperliquid.xyz"),
    CreditEntry("Linea", "Linea mainnet RPC", "https://linea.build"),
    CreditEntry("Mantle", "Mantle mainnet RPC", "https://mantle.xyz"),
    CreditEntry("Matter Labs", "zkSync Era RPC", "https://zksync.io"),
    CreditEntry("mempool.space", "Bitcoin fee estimates, block explorer, and the Electrum endpoint Maknoon uses by default", "https://mempool.space"),
    CreditEntry("Optimism", "OP Mainnet + Sepolia RPC", "https://www.optimism.io"),
    CreditEntry("Pharos", "Pharos Atlantic testnet RPC and block explorer", "https://pharosnetwork.xyz"),
    CreditEntry("Polygon Labs", "Polygon PoS + zkEVM RPC", "https://polygon.technology"),
    CreditEntry("PublicNode", "Default JSON-RPC for Ethereum mainnet + Sepolia, Polygon, and BNB Chain", "https://www.publicnode.com"),
    CreditEntry("Scroll", "Scroll mainnet RPC", "https://scroll.io"),
    CreditEntry("Snowtrace", "Avalanche block explorer API", "https://snowtrace.io"),
    CreditEntry("Trust Wallet token lists", "Reputable-token verification cross-reference for auto-discover", "https://github.com/trustwallet/assets"),
)

// The open-source components list. Alphabetical by name, matching iOS.
internal val COMPONENTS: List<ComponentEntry> = listOf(
    ComponentEntry("BC DCBOR", "1.0.7", "BSD-2-Clause-Patent", "https://github.com/BlockchainCommons/BCSwiftDCBOR"),
    ComponentEntry("BC Float16", "1.0.0", "BSD-2-Clause-Patent", "https://github.com/BlockchainCommons/BCSwiftFloat16"),
    ComponentEntry("BC Tags", "0.2.3", "BSD-2-Clause-Patent", "https://github.com/BlockchainCommons/BCSwiftTags"),
    ComponentEntry("BitcoinDevKit", "2.3.1", "MIT / Apache 2.0", "https://github.com/bitcoindevkit/bdk-swift"),
    ComponentEntry("ElabifyCore", "In-tree", "Apache 2.0 / MIT", "https://github.com/elabify/elabify-core"),
    ComponentEntry("Ledger device SDKs (BTC/ETH/SOL/TRON)", "In-tree", "Apache 2.0", "https://github.com/elabify/maknoon-ios"),
    ComponentEntry("NFCPassportReader", "2.3.0", "MIT", "https://github.com/AndyQ/NFCPassportReader"),
    ComponentEntry("NumberKit", "2.4.3", "Apache 2.0", "https://github.com/wolfmcnally/swift-numberkit"),
    ComponentEntry("OpenSSL", "3.3.3001", "Apache 2.0", "https://github.com/krzyzanowskim/OpenSSL-Package"),
    ComponentEntry("Swift Collections", "1.1.4", "Apache 2.0", "https://github.com/wolfmcnally/swift-collections"),
    ComponentEntry("SwiftProtobuf", "Bundled with TWC", "Apache 2.0", "https://github.com/apple/swift-protobuf"),
    ComponentEntry("Trust Wallet Core", "4.6.9", "Apache 2.0", "https://github.com/trustwallet/wallet-core"),
    ComponentEntry("URKit", "14.0.2", "BSD-2-Clause-Patent", "https://github.com/BlockchainCommons/URKit"),
    ComponentEntry("WalletConnect (Reown)", "1.5.0", "Apache 2.0", "https://github.com/reown-com/reown-android"),
    ComponentEntry("YubiKit", "4.7.0", "Apache 2.0", "https://github.com/Yubico/yubikit-ios"),
)

@Composable
private fun SettingNavRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
             tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
