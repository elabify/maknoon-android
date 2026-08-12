// Lightning account management, combining iOS LightningAccountsView.swift +
// AddLightningAccountSheet.swift + EditLightningAccountSheet (LightningSettings
// reuses the same edit flow). Three inner views, each wrapped in the shared
// WalletChainScaffold so the chrome matches the on-chain wallets (status-bar
// insets, leading back arrow, and, on the list, a top-right "+" Add action),
// per ADR-0033. Lightning has no Software/Hardware source or seed derivation,
// so the Add screen is the LNDHub credential form rather than the SourcePicker
// anatomy; the navigation shell is identical to the other chains' Manage flow.
//
//   - list: LNDHub accounts with thumbprint icons, tap to make active,
//     per-row Edit / Remove. The "+" in the bar opens the add view.
//   - add: paste/scan an lndhub:// URL (parsed into the manual fields) or fill
//     server + username + password by hand, with a Validate-TLS toggle.
//   - edit: rename, flip the TLS flag, optionally rotate the password.
//
// All persistence goes through LightningAccountStore; passwords are sealed in
// AndroidSecureStore by the store itself.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.miniapp.MiniAppQrScanner
import com.elabify.app.maknoon.ui.wallet.common.ManageWalletRow
import com.elabify.app.maknoon.ui.wallet.common.WalletChainScaffold
import com.elabify.app.maknoon.ui.wallet.common.WalletManageList
import com.elabify.musnad.wallet.lightning.LightningAccount
import com.elabify.musnad.wallet.lightning.LightningAccountStore

private enum class AccountsRoute { LIST, ADD, EDIT }

@Composable
internal fun LightningAccountsScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
    startInAdd: Boolean = false,
) {
    val context = LocalContext.current
    val env = remember { LightningEnv.get(context) }

    var route by remember { mutableStateOf(if (startInAdd) AccountsRoute.ADD else AccountsRoute.LIST) }
    var reload by remember { mutableIntStateOf(0) }
    var editTarget by remember { mutableStateOf<LightningAccount?>(null) }

    @Suppress("UNUSED_EXPRESSION") reload

    when (route) {
        AccountsRoute.LIST -> AccountsList(
            env = env,
            reload = reload,
            onAdd = { route = AccountsRoute.ADD },
            onEdit = { editTarget = it; route = AccountsRoute.EDIT },
            onMutated = { reload++; onChanged() },
            onBack = onBack,
        )

        // When entered directly from the dashboard "Add account" (startInAdd),
        // there is no list behind us, so back / done returns to the dashboard;
        // otherwise it pops back to the accounts list (the normal Manage flow).
        AccountsRoute.ADD -> AddAccount(
            env = env,
            onBack = { if (startInAdd) onBack() else route = AccountsRoute.LIST },
            onAdded = { reload++; onChanged(); if (startInAdd) onBack() else route = AccountsRoute.LIST },
        )

        AccountsRoute.EDIT -> editTarget?.let { target ->
            EditAccount(
                env = env,
                account = target,
                onBack = { route = AccountsRoute.LIST },
                onSaved = { reload++; onChanged(); route = AccountsRoute.LIST },
            )
        } ?: run { route = AccountsRoute.LIST }
    }
}

@Composable
private fun AccountsList(
    env: LightningEnv,
    reload: Int,
    onAdd: () -> Unit,
    onEdit: (LightningAccount) -> Unit,
    onMutated: () -> Unit,
    onBack: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") reload
    val context = LocalContext.current
    val accounts = env.accountStore.accounts
    val activeId = env.activeAccount?.id

    WalletChainScaffold(
        title = stringResource(R.string.ln_lightning_accounts),
        onBack = onBack,
        actions = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ln_add_account))
            }
        },
    ) { padding ->
        // Custodial Lightning accounts may be removed down to zero (canRemove = true
        // always), unlike the seed chains; otherwise this is the same shared
        // Bitcoin-identical manage list as every other chain.
        WalletManageList(
            rows = accounts.map { ManageWalletRow(it.id.toString(), it.label, accountSubtitle(context, it), it.id == activeId) },
            emptyTitle = stringResource(R.string.ln_no_account_yet),
            onActivate = { id -> env.accountStore.setActive(java.util.UUID.fromString(id)); onMutated() },
            onEdit = { id -> accounts.firstOrNull { it.id.toString() == id }?.let { onEdit(it) } },
            onRemove = { id -> env.accountStore.remove(java.util.UUID.fromString(id)); onMutated() },
            modifier = Modifier.padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccount(
    env: LightningEnv,
    onBack: () -> Unit,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var validateTLS by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }

    fun applyImport(raw: String) {
        val t = raw.trim()
        val parsed = LightningAccountStore.parseImportURL(t)
        if (parsed == null) {
            error = context.getString(
                if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
                    R.string.ln_import_looks_like_web_page
                } else {
                    R.string.ln_import_not_an_lndhub_url
                },
            )
            return
        }
        error = null
        server = parsed.first.serverURL
        username = parsed.first.username
        password = parsed.second
        validateTLS = !parsed.first.allowInsecureTLS
        if (label.isBlank()) label = parsed.first.label
    }

    WalletChainScaffold(title = stringResource(R.string.ln_add_lightning_account), onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ln_import), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ln_import_note),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { error = null; showScanner = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ln_scan_lndhub_qr)) }
            OutlinedButton(
                onClick = {
                    val s = clipboardText(context)
                    if (s.isNullOrBlank()) error = context.getString(R.string.ln_clipboard_empty) else applyImport(s)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ln_paste_lndhub_url)) }

            Text(stringResource(R.string.ln_server_credentials), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(stringResource(R.string.ln_label_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(R.string.ln_server_url)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.ln_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.common_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = validateTLS, onCheckedChange = { validateTLS = it })
                Text(stringResource(R.string.ln_validate_tls), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.ln_tls_note),
                style = MaterialTheme.typography.bodySmall,
            )

            error?.let { Text(stringResource(R.string.ln_error_prefix, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(
                enabled = server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    error = null
                    val trimmedServer = server.trim()
                    val finalLabel = label.trim().ifEmpty { hostOf(trimmedServer) }
                    val account = LightningAccount(
                        label = finalLabel,
                        serverURL = trimmedServer,
                        username = username.trim(),
                        allowInsecureTLS = !validateTLS,
                    )
                    runCatching { env.accountStore.add(account, password) }
                        .onSuccess { onAdded() }
                        .onFailure { error = it.message ?: it.toString() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.ln_add_account)) }
        }

        if (showScanner) {
            ModalBottomSheet(onDismissRequest = { showScanner = false }) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.ln_scan_lndhub_qr_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.ln_scan_lndhub_qr_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    MiniAppQrScanner(
                        continuous = false,
                        onCode = { code ->
                            showScanner = false
                            applyImport(code)
                        },
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
                    )
                    OutlinedButton(onClick = { showScanner = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun EditAccount(
    env: LightningEnv,
    account: LightningAccount,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var label by remember { mutableStateOf(account.label) }
    var validateTLS by remember { mutableStateOf(!account.allowInsecureTLS) }
    var newPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    WalletChainScaffold(title = stringResource(R.string.ln_edit_account), onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(stringResource(R.string.common_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.ln_server_value, account.serverURL), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ln_username_value, account.username), style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = validateTLS, onCheckedChange = { validateTLS = it })
                Text(stringResource(R.string.ln_validate_tls), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.ln_tls_note),
                style = MaterialTheme.typography.bodySmall,
            )

            Text(stringResource(R.string.ln_rotate_password), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text(stringResource(R.string.ln_new_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let { Text(stringResource(R.string.ln_error_prefix, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Button(
                onClick = {
                    error = null
                    val updated = account.copy(label = label.trim(), allowInsecureTLS = !validateTLS)
                    runCatching {
                        env.accountStore.update(updated, newPassword = newPassword.ifEmpty { null })
                    }.onSuccess { onSaved() }.onFailure { error = it.message ?: it.toString() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}
