// Shared chain-dashboard building blocks, generalized from the Tron dashboard
// (which matched the iOS layout) so every chain renders an identical wallet
// picker, account/address badge, balance card, and action row instead of each
// chain hand-rolling its own. iOS order per chain: wallet picker -> account
// address badge -> network picker (NetworkPickerChip) -> balance card ->
// action buttons. Network picker lives in NetworkPickerChip.kt.

package com.elabify.app.maknoon.ui.wallet.common

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.components.AddressChip
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.MaknoonBrand
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint

/** One row in the wallet picker dropdown. */
data class WalletChipItem(val id: String, val label: String)

/** Localized relative freshness label ("3 min. ago", "now", "Never synced") for
 *  a sync timestamp, the analog of iOS `Loc.relativeDate`.
 *
 *  ICU translates AND pluralizes this in every locale we ship, so the app must
 *  not hand-roll the ladder. Four hand-rolled copies used to, all returning
 *  hardcoded English ("3 min ago") interpolated into an otherwise-localized
 *  sentence, so every non-English locale rendered a translated frame around an
 *  English label. They had also drifted from each other: "never" vs
 *  "Never synced", "5m ago" vs "5 min ago".
 *
 *  The number here is a duration, not an amount, so locale-native digits are
 *  correct localization (ADR-0074); amounts go through the pinned formatters. */
@Composable
fun relativeSince(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return stringResource(R.string.common_never_synced)
    val locale = LocalConfiguration.current.locales[0]
    val fmt = remember(locale) {
        RelativeDateTimeFormatter.getInstance(
            ULocale.forLocale(locale),
            null,
            RelativeDateTimeFormatter.Style.SHORT,
            DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
        )
    }
    val sec = ((System.currentTimeMillis() - epochMs) / 1000L).coerceAtLeast(0L)
    fun ago(n: Long, unit: RelativeDateTimeFormatter.RelativeUnit) =
        fmt.format(n.toDouble(), RelativeDateTimeFormatter.Direction.LAST, unit)
    return when {
        sec < 5 -> fmt.format(RelativeDateTimeFormatter.Direction.PLAIN,
                              RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        sec < 60 -> ago(sec, RelativeDateTimeFormatter.RelativeUnit.SECONDS)
        sec < 3_600 -> ago(sec / 60, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        sec < 86_400 -> ago(sec / 3_600, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        else -> ago(sec / 86_400, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    }
}

/** Same label from a unix-SECONDS timestamp. */
@Composable
fun relativeSinceSec(unixSec: Long): String =
    relativeSince(if (unixSec <= 0L) null else unixSec * 1000L)

/** Active-wallet header chip (name + subtitle + dropdown to switch wallets). */
@Composable
fun WalletPickerChip(
    label: String,
    subtitle: String,
    accent: Color,
    items: List<WalletChipItem>,
    selectedId: String,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.padding(horizontal = Spacing.lg)) {
        Surface(
            shape = RoundedCornerShape(Radii.md),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Leading circle: the official brand logo (untinted) over a
                // brand-tinted background, matching the Wallet-tab list. Falls back
                // to the bare accent dot when no logo is supplied.
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(accent.tint(0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { row ->
                DropdownMenuItem(
                    text = { Text(row.label) },
                    leadingIcon = {
                        if (row.id == selectedId) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaknoonColors.success)
                    },
                    onClick = { expanded = false; onPick(row.id) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.wallet_manage_wallets)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                onClick = { expanded = false; onManage() },
            )
        }
    }
}

/** "Account #N" label + a tap-to-copy monospace address chip. */
@Composable
fun AccountAddressBadge(accountIndex: Long?, address: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (accountIndex != null) {
            Text(stringResource(R.string.wallet_account_number, accountIndex.toString()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AddressChip(text = address, head = 12, tail = 8, modifier = Modifier.fillMaxWidth())
    }
}

/** Elevated, brand-tinted balance card: big amount + ticker + sync state row. */
@Composable
fun BalanceCard(
    amount: String,
    ticker: String,
    syncing: Boolean,
    syncLabel: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    subnote: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(Radii.card),
        color = MaknoonBrand.deepPurple.tint(0.06f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .shadow(Elevation.cardRaised, RoundedCornerShape(Radii.card), clip = false),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(amount, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, color = MaknoonBrand.accent)
            Text(ticker, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (subnote != null) {
                Text(subnote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaknoonBrand.accent)
                    Text(stringResource(R.string.wallet_syncing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaknoonColors.success, modifier = Modifier.size(14.dp))
                    Text(syncLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !syncing, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.wallet_refresh), modifier = Modifier.size(18.dp), tint = MaknoonBrand.accent)
                }
            }
        }
    }
}

/** Send / Receive (+ optional Explorer) tile row. The [accent] is the chain's
 *  brand color so the actions match the network theme (Bitcoin orange, Ethereum
 *  blue, Solana purple, Tron red, Lightning amber); defaults to the app accent.
 *  Icons mirror iOS: up-right (send), down-left (receive), globe (explorer). */
@Composable
fun ActionButtons(
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
    onExplorer: (() -> Unit)? = null,
    accent: Color = MaknoonBrand.accent,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        ActionTile(stringResource(R.string.walletc_send), Icons.AutoMirrored.Filled.CallMade, sendEnabled, onSend, Modifier.weight(1f), accent)
        ActionTile(stringResource(R.string.walletc_receive), Icons.AutoMirrored.Filled.CallReceived, true, onReceive, Modifier.weight(1f), accent)
        if (onExplorer != null) {
            ActionTile(stringResource(R.string.wallet_explorer), Icons.Filled.Public, sendEnabled, onExplorer, Modifier.weight(1f), accent)
        }
    }
}

@Composable
private fun ActionTile(title: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, accent: Color) {
    val tintColor = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(Radii.md),
        color = if (enabled) accent.tint(0.12f) else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(vertical = Spacing.md), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Icon(icon, contentDescription = null, tint = tintColor)
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = tintColor)
        }
    }
}
