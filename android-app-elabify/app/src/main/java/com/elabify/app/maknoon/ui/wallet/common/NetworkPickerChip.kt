// The shared network/cluster picker, ported from the iOS networkPicker Menu
// (SolanaWalletView / EthereumWalletView): a single rounded chip showing the
// active network (network glyph + name + an optional "Testnet" badge + an
// up/down chevron) that opens a dropdown of the available networks with a
// check on the active one. Every chain uses this so the network selector is
// identical across wallets, not a per-chain row of buttons.

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.StatusLevel
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint

/** One selectable network/cluster. [isTestnet] drives the orange badge. */
data class NetworkOption(
    val id: String,
    val displayName: String,
    val isTestnet: Boolean = false,
)

@Composable
fun NetworkPickerChip(
    options: List<NetworkOption>,
    selectedId: String,
    onSelect: (NetworkOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull()

    Box(modifier.padding(horizontal = Spacing.lg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { open = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(Icons.Filled.Hub, contentDescription = null, tint = MaknoonBrand.accent)
            Text(
                selected?.displayName ?: "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (selected?.isTestnet == true) {
                Text(
                    stringResource(R.string.walletc_testnet),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaknoonColors.warning,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaknoonColors.warning.tint(0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
            Box(Modifier.weight(1f))
            Icon(
                Icons.Filled.UnfoldMore,
                contentDescription = stringResource(R.string.wallet_switch_network),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.displayName) },
                    onClick = {
                        open = false
                        if (opt.id != selectedId) onSelect(opt)
                    },
                    leadingIcon = {
                        // Reserve the checkmark slot; a filled dot marks the active one.
                        if (opt.id == selectedId) {
                            Icon(Icons.Filled.Hub, contentDescription = stringResource(R.string.wallet_active), tint = MaknoonBrand.accent)
                        }
                    },
                )
            }
        }
    }
}
