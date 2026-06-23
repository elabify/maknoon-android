// The input controls shared by every chain's Send screen, ported faithfully
// from the common iOS *SendView design.
//
//   RecipientField -> the iOS "Pay to" / "Recipient" row: a monospaced address
//     TextField with trailing paste + QR-scan (and optional contacts) icon
//     buttons. The QR scan + contacts pickers are the caller's concern (they
//     present their own sheets); this control only fires the callbacks.
//
//   AmountField -> the iOS "Amount" section: an amount TextField, a trailing
//     denomination slot (the AssetPicker / fiat menu the caller supplies, or a
//     plain unit label), a "Max" button, and a balance caption below.
//
//   FeeSelector -> the iOS "Fee" / "Network fee" segmented picker
//     (Fastest / 30 min / ... for Bitcoin, Slow / Standard / Fast gas tiers
//     for Ethereum), rendered as a single-select chip row with an optional
//     footer line.
//
//   PrimaryActionButton -> the iOS full-width primary action (Send /
//     Sign using <device> / Broadcast), with a built-in loading spinner.

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.theme.Spacing

// One fee tier / gas tier. `id` is the stable key the caller switches on
// (e.g. "fastest", "standard"); `label` is the chip text ("Fastest",
// "30 min", "Standard"). Bitcoin builds five (Fastest / 30 min / 1 hour /
// Economy / Custom); Ethereum builds three (Slow / Standard / Fast).
data class FeeOption(
    val id: String,
    val label: String,
)

// The "Pay to" / "Recipient" row. `value` / `onValueChange` drive the address
// TextField (monospaced, no autocorrect, no autocapitalization). The trailing
// icon buttons fire `onPaste`, `onScanQr`, and (when non-null) `onPickContact`.
// `placeholder` is the per-chain hint ("bc1q...", "0x... or vitalik.eth",
// "Solana address", "T-prefixed address"). An optional `supporting` slot
// renders validation / ENS-resolution text below the field.
@Composable
fun RecipientField(
    value: String,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onPickContact: (() -> Unit)? = null,
    supporting: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = false,
            maxLines = 3,
            textStyle =
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false,
                ),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPaste) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = stringResource(R.string.wallet_paste),
                        )
                    }
                    IconButton(onClick = onScanQr) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = stringResource(R.string.wallet_scan_qr),
                        )
                    }
                    if (onPickContact != null) {
                        IconButton(onClick = onPickContact) {
                            Icon(
                                imageVector = Icons.Filled.PersonOutline,
                                contentDescription = stringResource(R.string.wallet_pick_from_contacts),
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (supporting != null) {
            supporting()
        }
    }
}

// The "Amount" section body. `value` / `onValueChange` drive the amount
// TextField (decimal keyboard). The trailing `denomination` slot is supplied by
// the caller: a chain with a single unit passes a plain unit label via the
// `unitLabel` convenience, while multi-denomination chains pass the AssetPicker
// / fiat menu through `denomination`. "Max" fires `onMax` (disabled when
// onMax is null). `balanceLabel` is the "Available: ..." caption shown below;
// `secondaryLabel` is the optional fiat / inverse-unit conversion caption.
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    onMax: (() -> Unit)?,
    balanceLabel: String?,
    modifier: Modifier = Modifier,
    unitLabel: String? = null,
    denomination: (@Composable () -> Unit)? = null,
    secondaryLabel: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("0.00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            when {
                denomination != null -> denomination()
                unitLabel != null ->
                    Text(
                        text = unitLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            TextButton(onClick = { onMax?.invoke() }, enabled = onMax != null) {
                Text(stringResource(R.string.walletc_max))
            }
        }
        if (balanceLabel != null) {
            Text(
                text = balanceLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (secondaryLabel != null) {
            Text(
                text = secondaryLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// The fee / gas tier picker. A horizontally-flowing single-select chip row
// (the Android equivalent of the iOS segmented Picker), with an optional
// `footer` caption below (e.g. "5 sats/vB" or the gas breakdown).
// `selected` is the id of the chosen FeeOption; `onSelect` fires with the
// chosen id.
@Composable
fun FeeSelector(
    options: List<FeeOption>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.id == selected,
                    onClick = { onSelect(option.id) },
                    label = { Text(option.label) },
                )
            }
        }
        if (footer != null) {
            footer()
        }
    }
}

// The full-width primary action button. `text` is the label
// ("Send" / "Sign using Ledger Nano X" / "Broadcast transaction").
// `loading` swaps in a spinner and forces the disabled state; `enabled`
// gates submission otherwise.
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text)
        }
    }
}
