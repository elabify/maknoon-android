// The 24-word recovery phrase rendered as a two-column grid of rounded,
// brand-tinted cells (number + monospace word), mirroring the iOS
// OnboardingView.wordGrid. Used both when revealing a saved phrase and when
// backing up a freshly generated one. `masked` swaps each word for dots so the
// grid can be shown before the user explicitly reveals it.

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun RecoveryPhraseGrid(
    words: List<String>,
    modifier: Modifier = Modifier,
    masked: Boolean = false,
) {
    val cellBackground =
        if (masked) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { colIndex, word ->
                    WordCell(
                        number = rowIndex * 2 + colIndex + 1,
                        word = word,
                        masked = masked,
                        background = cellBackground,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep two-column alignment if the list is odd (24 is even, so
                // this is just defensive).
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WordCell(
    number: Int,
    word: String,
    masked: Boolean,
    background: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(vertical = 6.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = if (masked) "•••••" else word,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (masked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
