// A monospace, middle-truncating chip for addresses / DIDs / credential ids,
// with a tap-to-copy trailing icon and a tinted rounded background. Mirrors
// the iOS monospaced address rows that the user can copy.

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing

// Middle-truncate a string to fit a head + tail budget, inserting an ellipsis.
// Matches the iOS pattern (e.g. cid "abc123…7890", did "first20…last10").
fun middleTruncate(
    value: String,
    head: Int = 10,
    tail: Int = 6,
): String {
    if (value.length <= head + tail + 1) return value
    return value.take(head) + "…" + value.takeLast(tail)
}

@Composable
fun AddressChip(
    text: String,
    modifier: Modifier = Modifier,
    head: Int = 10,
    tail: Int = 6,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceVariant,
    copyValue: String = text,
    onCopy: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(Radii.sm))
                .background(background)
                .clickable {
                    clipboard.setText(AnnotatedString(copyValue))
                    onCopy?.invoke(copyValue)
                }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = middleTruncate(text, head, tail),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = textColor,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = "Copy",
            tint = LocalContentColor.current.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}
