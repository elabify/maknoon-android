// Shared send-form scaffolding for every chain's Send screen (Bitcoin,
// Ethereum, Solana, Tron), ported faithfully from the common iOS SwiftUI
// design. Every iOS *SendView is an inset-grouped Form with the SAME section
// sequence: network/header, (token picker), recipient, amount, fee, advanced,
// review, primary action, error/status. The Android equivalent of an
// inset-grouped Form is a scrollable Column of rounded surface "section
// cards", each with an optional header. This file provides the three building
// blocks that make that grouping identical across all chains:
//
//   SendFormScaffold  -> the Scaffold + TopAppBar (Cancel) + scrollable Column
//   FormSection       -> one rounded "section card" (an iOS Form Section)
//   ReviewRow         -> one right-aligned review line (Network, Pay to, ...)
//
// No chain defines its own layout. They compose these three plus the controls
// in WalletPickers.kt and SendFields.kt.

package com.elabify.app.maknoon.ui.wallet.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing

// The iOS Send Form lives inside a NavigationStack with an inline title and a
// leading "Cancel" toolbar button. SendFormScaffold reproduces that chrome:
// a TopAppBar titled "Send" (default) with a Cancel (X) navigation icon, and a
// vertically-scrolling Column body whose children are FormSections separated
// by a consistent inset-grouped gap (Spacing.lg). The content lambda receives
// no parameters; chains simply place FormSection blocks in the documented
// order.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendFormScaffold(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.walletc_send),
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.common_cancel),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            content()
        }
    }
}

// One iOS Form Section, rendered as a rounded surface card. An optional header
// sits above the card (matching the iOS grouped-section header, which floats
// above the inset cell group). Inside the card, children stack with a
// consistent gap. All chains use this so the grouping, padding, and radius are
// identical everywhere. The content lambda is a ColumnScope so callers can use
// weight / fillMaxWidth on their rows.
@Composable
fun FormSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (header != null) {
            SectionHeader(title = header)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Radii.xs,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

// One review line: a secondary label on the left, a value right-aligned on the
// right. Mirrors the iOS ReviewRow used in every chain's Review section
// (Network, Pay to, Amount, Fee). `valueColor` tints the value (e.g. the
// network chip color, or red for an over-balance warning); `mono` renders the
// value in a monospaced font for addresses / hashes / amounts.
@Composable
fun ReviewRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    mono: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}
