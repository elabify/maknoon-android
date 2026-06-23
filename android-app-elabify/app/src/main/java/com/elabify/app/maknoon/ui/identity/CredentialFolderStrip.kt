// Horizontal folder-pill strip for the Identity credential stack, ported from
// the iOS folderStrip. An "All" pill (selected when activeFolderId == null) sits
// first, then each user folder with a live count badge, then a "+ New" pill.
// Long-pressing a folder pill opens a Rename / Delete menu. Create + rename use
// a shared name dialog; delete confirms (member cards return to "All").

package com.elabify.app.maknoon.ui.identity

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.theme.Spacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CredentialFolderStrip(
    folders: List<CredentialFolder>,
    activeFolderId: String?,
    allCount: Int,
    countFor: (String) -> Int,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNameDialog by remember { mutableStateOf(false) }
    // Non-null while renaming an existing folder; null means the dialog creates.
    var renameTarget by remember { mutableStateOf<CredentialFolder?>(null) }
    var deleteTarget by remember { mutableStateOf<CredentialFolder?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FolderPill(
            label = stringResource(R.string.identity_all),
            count = allCount,
            selected = activeFolderId == null,
            onClick = { onSelect(null) },
        )
        folders.forEach { folder ->
            Box2(
                pill = {
                    FolderPill(
                        label = folder.name,
                        count = countFor(folder.id),
                        selected = activeFolderId == folder.id,
                        onClick = { onSelect(folder.id) },
                        onLongClick = { menuFor = folder.id },
                    )
                },
                menu = {
                    DropdownMenu(expanded = menuFor == folder.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_rename)) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuFor = null; renameTarget = folder; showNameDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuFor = null; deleteTarget = folder },
                        )
                    }
                },
            )
        }
        // "+ New" pill.
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.combinedClickable(onClick = { renameTarget = null; showNameDialog = true }),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.identity_new), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (showNameDialog) {
        val target = renameTarget
        FolderNameDialog(
            title = if (target == null) stringResource(R.string.identity_new_folder) else stringResource(R.string.identity_rename_folder),
            initial = target?.name ?: "",
            onConfirm = { name ->
                if (target == null) onCreate(name) else onRename(target.id, name)
                showNameDialog = false
                renameTarget = null
            },
            onDismiss = { showNameDialog = false; renameTarget = null },
        )
    }

    deleteTarget?.let { folder ->
        val n = countFor(folder.id)
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.identity_delete_folder)) },
            text = {
                Text(
                    stringResource(
                        R.string.identity_delete_folder_message,
                        n.toString(),
                        if (n == 1) "" else "s",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (activeFolderId == folder.id) onSelect(null)
                    onDelete(folder.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.identity_delete_folder)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

// A folder pill plus its anchored long-press menu, grouped so the DropdownMenu
// anchors to the pill.
@Composable
private fun Box2(pill: @Composable () -> Unit, menu: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box {
        pill()
        menu()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPill(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = bg,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.8f),
            )
        }
    }
}

/** Long-press "Move to folder" picker for a hub card (credential or ID
 *  document). Mirrors the iOS card context menu: None (All) + each folder, with
 *  the current one marked. */
@Composable
fun MoveToFolderDialog(
    folders: List<CredentialFolder>,
    currentFolderId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.identity_move_to_folder)) },
        text = {
            Column {
                TextButton(onClick = { onSelect(null) }) {
                    Text(if (currentFolderId == null) stringResource(R.string.identity_none_all_current) else stringResource(R.string.identity_none_all))
                }
                folders.forEach { folder ->
                    TextButton(onClick = { onSelect(folder.id) }) {
                        Text(if (currentFolderId == folder.id) stringResource(R.string.identity_folder_name_current, folder.name) else folder.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

@Composable
private fun FolderNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.identity_folder_name)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotEmpty(),
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
