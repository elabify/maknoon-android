// The wallet + asset selection controls shared by every chain's Send screen,
// ported from the common iOS design.
//
//   WalletSelector -> the iOS header row that names the active wallet (label +
//     optional device sublabel) and a trailing network chip. On iOS this is
//     the top-of-form "headerSection" / "networkSection". Tappable to switch
//     wallets (replaces ad-hoc "Change" links).
//
//   AssetPicker -> the single token/asset picker every multi-asset chain uses
//     (the iOS "Token" Picker section: "SOL (native)" / "TRX (native)" plus
//     each SPL / TRC-20 / ERC-20 token, or the BTC/sats/fiat denomination
//     menu). A Material3 ExposedDropdownMenuBox so the control is identical
//     across chains.
//
//   NetworkChip -> the small tinted pill that names the active network, used
//     in the WalletSelector header and reusable in the Review section.

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint

// One option in the AssetPicker. `symbol` is the short ticker shown when
// collapsed (e.g. "SOL", "USDC", "BTC"); `label` is the longer menu label
// (e.g. "SOL (native)", "USDC - USD Coin"); `balance` is an optional trailing
// caption shown in the dropdown row (e.g. "12.5"). `id` is the stable key the
// caller switches on. `iconUrl` is reserved for a future token logo; callers
// that have no icon pass null.
data class AssetOption(
    val id: String,
    val symbol: String,
    val label: String,
    val balance: String? = null,
    val iconUrl: String? = null,
)

// The active-wallet header row. `label` is the wallet name ("Ethereum
// wallet"), `subtitle` is the optional hardware-device sublabel ("Ledger Nano
// X") or null for software wallets, `networkName` is the active network's
// display name, `networkTint` is the per-chain accent (orange BTC, indigo ETH,
// purple SOL, red TRX). Tapping the row invokes `onClick` to switch wallets;
// pass an empty lambda to make it non-interactive. Renders inside a FormSection
// (no header) as the first section of the form.
@Composable
fun WalletSelector(
    label: String,
    networkName: String,
    networkTint: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        NetworkChip(text = networkName, tint = networkTint)
    }
}

// The small tinted pill naming the active network. Mirrors the iOS
// NetworkChipLabel: the network name on a per-chain tinted background.
@Composable
fun NetworkChip(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier =
            modifier
                .clip(RoundedCornerShape(Radii.sm))
                .background(tint.tint(MaknoonColors.TintPillAlpha))
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

// THE single token/asset selection control. A Material3
// ExposedDropdownMenuBox: a read-only OutlinedTextField showing the selected
// option's label with a trailing chevron, expanding into a menu of every
// option (each row shows the label and, when present, the balance). Multi-asset
// chains (Solana SPL, Tron TRC-20, Ethereum ERC-20) and the Bitcoin
// denomination menu all use this. `label` is the field caption ("Token" /
// "Asset"); `selected` is the currently-selected AssetOption; `onSelect`
// fires with the chosen option.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetPicker(
    options: List<AssetOption>,
    selected: AssetOption,
    onSelect: (AssetOption) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.wallet_asset),
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (option.balance != null) {
                                Text(
                                    text = option.balance,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
