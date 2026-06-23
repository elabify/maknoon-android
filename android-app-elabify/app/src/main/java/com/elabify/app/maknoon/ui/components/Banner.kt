// Tinted, rounded informational banner. Mirrors the iOS banners (the indigo
// "Identity is locked" row, orange backup warnings, blue info notes): a
// leading icon, a semibold title, an optional body line, all sitting on a
// semantic-color background tinted at the iOS banner alpha (0.12) inside a
// 12 dp rounded rectangle.

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.theme.MaknoonColors
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.app.maknoon.ui.theme.tint

enum class BannerVariant {
    WARNING,
    ERROR,
    SUCCESS,
    INFO,
}

private fun BannerVariant.tintColor(): Color =
    when (this) {
        BannerVariant.WARNING -> MaknoonColors.warning
        BannerVariant.ERROR -> MaknoonColors.error
        BannerVariant.SUCCESS -> MaknoonColors.success
        BannerVariant.INFO -> MaknoonColors.info
    }

private fun BannerVariant.defaultIcon(): ImageVector =
    when (this) {
        BannerVariant.WARNING -> Icons.Filled.Warning
        BannerVariant.ERROR -> Icons.Filled.Warning
        BannerVariant.SUCCESS -> Icons.Filled.CheckCircle
        BannerVariant.INFO -> Icons.Filled.Info
    }

@Composable
fun Banner(
    title: String,
    variant: BannerVariant,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = variant.defaultIcon(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = variant.tintColor()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.md))
                .background(accent.tint(MaknoonColors.TintBannerAlpha))
                .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}
