// The shared Manage Wallets / Accounts list, extracted from Bitcoin's
// BitcoinWalletsScreen so every chain's manage screen is pixel-identical
// (ADR-0033 "Manage Wallets row" reference): a LazyColumn of plain rows with
// 16.dp padding, tap-the-row-to-activate, a trailing greyscale CheckCircle
// (20.dp) on the active row, greyscale Edit + Delete icon buttons, and a
// HorizontalDivider between rows. No per-row brand icon / avatar; no colored
// tints. ETH/SOL/TRON/Lightning render through this so they match Bitcoin
// exactly in icons, sizes, and spacing.

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R

/** One manage-list row's data. [canRemove] hides Delete on the last wallet for
 *  seed chains; custodial Lightning passes true always. */
data class ManageWalletRow(
    val id: String,
    val label: String,
    val subtitle: String,
    val isActive: Boolean,
    val canRemove: Boolean = true,
)

/** Bitcoin-identical manage list. Place inside a chain's Scaffold content; pass
 *  the Scaffold's inner padding via [modifier]. */
@Composable
fun WalletManageList(
    rows: List<ManageWalletRow>,
    emptyTitle: String,
    onActivate: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(emptyTitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.wallet_tap_plus_to_add),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { r ->
            Row(
                Modifier.fillMaxWidth().clickable { onActivate(r.id) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.titleMedium)
                    Text(r.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (r.isActive) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.wallet_active), modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { onEdit(r.id) }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.wallet_rename)) }
                if (r.canRemove) {
                    IconButton(onClick = { onRemove(r.id) }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_remove)) }
                }
            }
            HorizontalDivider()
        }
    }
}
