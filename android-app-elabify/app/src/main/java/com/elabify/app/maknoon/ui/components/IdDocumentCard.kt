// Saved ID-document card (a scanned passport), drawn in the same wallet card
// language as CredentialCard. The leading slot shows the holder's portrait
// thumbnail clipped to a 6 dp rounded square (echoing the iOS photo-as-icon),
// or a placeholder glyph when there is no photo. The body shows the name and
// country, with a passive-auth StatusDot top-right.

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.Radii

// Display fields for one saved ID document. `portrait` is the MRZ/chip photo,
// null when unavailable. `passiveAuth` drives the status dot (OK when passive
// authentication passed, EXPIRED when it failed, WARN when not yet run).
data class IdDocumentCardData(
    val id: String,
    val name: String,
    val country: String,
    val documentType: String = "Passport",
    val expiryText: String? = null,
    val portrait: ImageBitmap? = null,
    val passiveAuth: StatusLevel = StatusLevel.OK,
    val palette: CardPalette = CardPalette.forSchema("elabify://schema/global/passport/v1"),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IdDocumentCard(
    data: IdDocumentCardData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val fg = data.palette.foreground
    val shape = RoundedCornerShape(Radii.card)
    val thumbShape = RoundedCornerShape(Radii.xs)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .shadow(Elevation.walletCard, shape, clip = false)
                .clip(shape)
                .background(data.palette.brush)
                .border(0.5.dp, fg.copy(alpha = 0.08f), shape)
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { onClick?.invoke() },
                            onLongClick = onLongClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .defaultMinSize(minHeight = CredentialCardDefaults.height)
                .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .clip(thumbShape)
                        .background(Color.Black.copy(alpha = 0.18f))
                        .border(0.5.dp, fg.copy(alpha = 0.4f), thumbShape),
                contentAlignment = Alignment.Center,
            ) {
                if (data.portrait != null) {
                    Image(
                        bitmap = data.portrait,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().size(36.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.ContactPage,
                        contentDescription = null,
                        tint = fg,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = data.documentType,
                    fontSize = 17.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = data.name,
                    fontSize = 15.sp,
                    color = fg.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = data.country,
                    fontSize = 12.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = fg.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusDot(
                level = data.passiveAuth,
                ringColor = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            data.expiryText?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = fg.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
