// Compact card used on the Identity tab above the credential stack.
// Shows the document photo (if available), name, kind, and country.
//
// Kotlin port of the iOS IDDocumentCard. Stateless: the caller passes
// the IDDocument plus the already-decoded portrait (DG2 JPEG decoded to
// an ImageBitmap) and an onClick callback. The orchestrator owns
// navigation into the detail screen.

package com.elabify.app.maknoon.ui.iddocument

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.IDDocument

@Composable
fun IDDocumentCard(
    document: IDDocument,
    photo: ImageBitmap?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IDDocumentThumbnail(document = document, photo = photo, width = 52.dp, height = 64.dp)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                document.nickname ?: document.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val native = document.nativeDisplayName
            if (document.nickname == null && native != null) {
                Text(
                    native,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.id_kind_summary, stringResource(document.kindLabelRes), document.summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val exp = document.formattedDateOfExpiry
            if (exp != null) {
                Text(
                    stringResource(R.string.id_expires_value, exp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

/// Portrait thumbnail with a rounded placeholder fallback when the chip
/// did not expose a DG2 photo. Shared by the card, the read-review row,
/// and the detail header so all three render the portrait identically.
@Composable
internal fun IDDocumentThumbnail(
    document: IDDocument,
    photo: ImageBitmap?,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp,
) {
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = stringResource(R.string.id_document_portrait),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(cornerRadius)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                iconForDocument(document.iconName),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/// Map the model's logical icon key (iconName) to a Material icon. The
/// model stays UI-agnostic (it only emits a string key), so the mapping
/// lives here in the Compose layer.
private fun iconForDocument(iconName: String): ImageVector = when (iconName) {
    "passport" -> Icons.Filled.MenuBook
    "id_card" -> Icons.Filled.CreditCard
    "residence_permit" -> Icons.Filled.Home
    "visa" -> Icons.Filled.Style
    else -> Icons.Filled.Badge
}
