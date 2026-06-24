// Settings > Apps. Ported 1:1 from the iOS AppStoreSettingsView.swift.
//
// iOS AppStoreSettingsView is a grouped Form with three sections, in this exact
// order:
//   1. "Default Apps catalog"     - one read-only row for the built-in catalog
//      ("Built in"), footer explaining it ships with Maknoon and cannot be
//      removed.
//   2. "Additional Apps catalogs" - the user-added catalogs (or a
//      "No additional Apps catalogs configured." line), then an
//      "Add Apps catalog..." row that presents an add sheet.
//   3. "Beta apps"                - a "Show beta apps" toggle, with the footer
//      explaining beta apps are hidden by default.
// The add sheet (AddStoreSheet) is a Form with a name field, a URL field, an
// inline error line, an explanatory footer, and an "Add" button; plus a Cancel
// toolbar item.
//
// On Android we render the same three sections, in the same order, as
// section-grouped rows inside a scrollable Column under a Scaffold titled
// "Apps". The add sheet becomes a ModalBottomSheet with the same fields,
// validation, and footer.
//
// Backing logic: iOS uses store.appStores (its AppStoreRegistry). The Android
// SDK ships no app-store catalog registry; per the porting note in
// ui/miniapp/AppsScreen.kt the catalog is a hardcoded seed ("Maknoon Apps").
// So the Default-catalog row mirrors that built-in
// identity, and the user-added catalogs + the Show-beta-apps flag persist
// through the existing MiniAppSettingsStore (under a reserved host bucket), the
// store the contract names. No new persistence type is introduced.

package com.elabify.app.maknoon.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.miniapp.MiniAppCatalogSettings
import com.elabify.app.maknoon.miniapp.MiniAppSettingsStore
import com.elabify.app.maknoon.ui.components.AdvancedSection
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint

// The built-in catalog identity. Mirrors the hardcoded seed in
// ui/miniapp/AppsScreen.kt (those constants are private to that file), matching
// what iOS surfaces from store.appStores.defaultStore.
private const val DEFAULT_CATALOG_NAME = "Maknoon Apps"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MiniAppCatalogSettings(MiniAppSettingsStore(context)) }

    val userStores = remember { mutableStateListOf<MiniAppCatalogSettings.Catalog>().apply { addAll(store.userStores()) } }
    var showBetaApps by remember { mutableStateOf(store.showBetaApps()) }
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_apps)) },
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
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Section 1: Default Apps catalog.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader(title = stringResource(R.string.settings_default_apps_catalog))
                SectionCardGroup {
                    CatalogRow(
                        title = DEFAULT_CATALOG_NAME,
                        subtitle = stringResource(R.string.settings_built_in),
                        canRemove = false,
                        onRemove = null,
                    )
                }
                FooterCaption(stringResource(R.string.settings_default_catalog_footer))
            }

            // Advanced: additional catalogs + beta apps (0.6.1 friendliness pass).
            AdvancedSection {
            // Section 2: Additional Apps catalogs.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader(title = stringResource(R.string.settings_additional_apps_catalogs))
                SectionCardGroup {
                    if (userStores.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_no_additional_catalogs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(Spacing.md),
                        )
                    } else {
                        userStores.forEach { s ->
                            CatalogRow(
                                title = s.name,
                                subtitle = s.url,
                                canRemove = true,
                                onRemove = {
                                    store.removeStore(s.id)
                                    userStores.clear()
                                    userStores.addAll(store.userStores())
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddSheet = true }
                            .padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.settings_add_apps_catalog_action),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaknoonBrand.accent,
                        )
                    }
                }
            }

            // Section 3: Beta apps.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader(title = stringResource(R.string.settings_beta_apps))
                SectionCardGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.settings_show_beta_apps), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = showBetaApps,
                            onCheckedChange = {
                                showBetaApps = it
                                store.setShowBetaApps(it)
                            },
                        )
                    }
                }
            }
            }
        }
    }

    if (showAddSheet) {
        AddStoreSheet(
            onDismiss = { showAddSheet = false },
            onAdd = { name, url ->
                store.addStore(name, url)
                userStores.clear()
                userStores.addAll(store.userStores())
                showAddSheet = false
            },
        )
    }
}

@Composable
private fun CatalogRow(
    title: String,
    subtitle: String,
    canRemove: Boolean,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radii.xs))
                .background(MaknoonBrand.accent.tint(MaknoonColors.TintCellAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Storefront, contentDescription = null, tint = MaknoonBrand.accent, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (canRemove && onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.RemoveCircle, contentDescription = stringResource(R.string.common_remove), tint = MaknoonColors.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddStoreSheet(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var name by remember { mutableStateOf("") }
    var urlString by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val nameRequiredError = stringResource(R.string.settings_name_required_error)
    val urlSchemeError = stringResource(R.string.settings_url_scheme_error)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.settings_add_apps_catalog_title), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_catalog_name_placeholder)) },
            )
            OutlinedTextField(
                value = urlString,
                onValueChange = { urlString = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.settings_catalog_url_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaknoonColors.error) }
            FooterCaption(
                stringResource(R.string.settings_add_catalog_footer),
            )
            Button(
                onClick = {
                    val trimmedName = name.trim()
                    val trimmedUrl = urlString.trim()
                    if (trimmedName.isEmpty()) {
                        error = nameRequiredError
                        return@Button
                    }
                    val lower = trimmedUrl.lowercase()
                    if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
                        error = urlSchemeError
                        return@Button
                    }
                    onAdd(trimmedName, trimmedUrl)
                },
                enabled = name.isNotEmpty() && urlString.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_add))
            }
        }
    }
}

@Composable
private fun FooterCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

@Composable
private fun SectionCardGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}
