// Horizontal scrollable folder pill strip, ported from IdentityView's folder
// strip. The selected pill is filled with the brand accent (white label + a
// translucent count badge); unselected pills sit on the secondary surface with
// a muted count badge. An optional trailing "+ New" outlined pill creates a
// folder. Long-press surfaces a context action when onPillLongPress is set
// (mirrors the iOS rename / delete context menu).

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.theme.Spacing

// One folder pill's data. `id` is opaque to the strip; callers map it back to
// their own folder model. A null id conventionally means the "All" pseudo
// folder.
data class FolderPillItem(
    val id: String?,
    val label: String,
    val count: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderPills(
    items: List<FolderPillItem>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onNewFolder: (() -> Unit)? = null,
    onPillLongPress: ((FolderPillItem) -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            FolderPill(
                item = item,
                isSelected = item.id == selectedId,
                onClick = { onSelect(item.id) },
                onLongPress = onPillLongPress?.let { cb -> { cb(item) } },
            )
        }
        if (onNewFolder != null) {
            NewFolderPill(onClick = onNewFolder)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPill(
    item: FolderPillItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val fill = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val badgeFill =
        if (isSelected) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
    val badgeText =
        if (isSelected) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(fill)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs + 2.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
        )
        Text(
            text = item.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = badgeText,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(badgeFill)
                    .padding(horizontal = Spacing.xs + 2.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun NewFolderPill(onClick: () -> Unit) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(percent = 50))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(percent = 50))
                .clickable(onClick = onClick)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "New",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
