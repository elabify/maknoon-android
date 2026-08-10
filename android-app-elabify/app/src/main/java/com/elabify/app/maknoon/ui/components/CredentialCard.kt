// Apple-Wallet-style credential card, ported from the iOS CredentialCard.swift.
//
// Layout: a 36 dp icon top-left, a stacked column (type label semibold,
// nickname, then "issuer · identifier" with a monospace identifier), and the
// expiry StatusDot top-right. A bottom strip carries the issued date, optional
// expiry text, and optional network label. The card is ~168 dp tall, drawn on
// a brand gradient with a 18 dp continuous corner radius, a hairline border at
// 0.08 alpha foreground, and a soft layered shadow (the iOS radius-16 / y-8
// drop).

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elabify.app.maknoon.ui.theme.Elevation
import com.elabify.app.maknoon.ui.theme.Radii
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R

// Total card height + the peek region used when cards are stacked with overlap
// (the iOS Identity tab stacks cards so each peeks the top 90 dp).
object CredentialCardDefaults {
    val height = 168.dp
    val peekHeight = 90.dp
}

// A schema palette: the gradient stops drawn behind the card and the
// foreground color used for text + icon. Mirrors SchemaPalette in iOS.
data class CardPalette(
    val gradientStart: Color,
    val gradientEnd: Color,
    val foreground: Color,
) {
    val brush: Brush
        get() = Brush.linearGradient(listOf(gradientStart, gradientEnd))

    companion object {
        // Schema-keyed palettes, matching the iOS SchemaPalette.forSchema hex
        // stops. Use this so credential cards on Android read identically.
        fun forSchema(schemaUri: String): CardPalette =
            when (schemaUri) {
                "elabify://schema/global/passport/v1" ->
                    CardPalette(Color(0xFF1A3D6D), Color(0xFF0D2447), Color.White)
                "elabify://schema/adgm/emiratesId/v1" ->
                    CardPalette(Color(0xFFB91C1C), Color(0xFF7F1010), Color.White)
                "elabify://schema/global/musnadMaknoon/v1" ->
                    CardPalette(Color(0xFF93278F), Color(0xFF5E1660), Color.White)
                "elabify://schema/global/walletControlEth/v1" ->
                    CardPalette(Color(0xFF475569), Color(0xFF1E293B), Color.White)
                "elabify://schema/global/walletControlBtc/v1" ->
                    CardPalette(Color(0xFFD97706), Color(0xFF92400E), Color.White)
                "elabify://schema/global/corporateIdentity/v1" ->
                    CardPalette(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFFFDE68A))
                "elabify://schema/global/corporateOfficer/v1" ->
                    CardPalette(Color(0xFF0F172A), Color(0xFF422006), Color(0xFFFDE68A))
                else ->
                    CardPalette(Color(0xFF5B6370), Color(0xFF1A1A1A), Color.White)
            }
    }
}

// Display fields for one credential card. `statusLevel` drives the expiry dot;
// resolve it from an expiry instant with expiryStatus(...) at the call site.
data class CredentialCardData(
    val id: String,
    val title: String,
    val issuer: String,
    val identifier: String,
    val nickname: String? = null,
    val issuedText: String? = null,
    val expiryText: String? = null,
    val networkLabel: String? = null,
    val statusLevel: StatusLevel = StatusLevel.OK,
    val palette: CardPalette,
    val icon: ImageVector = Icons.Filled.Badge,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CredentialCard(
    data: CredentialCardData,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val fg = data.palette.foreground
    val shape = RoundedCornerShape(Radii.card)
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
            Icon(
                imageVector = data.icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(36.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = data.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val nick = data.nickname?.takeIf { it.isNotEmpty() }
                Text(
                    text = nick ?: stringResource(R.string.credential_tap_to_rename),
                    fontSize = 15.sp,
                    color = fg.copy(alpha = if (nick != null) 0.85f else 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = data.issuer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fg.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("·", color = fg.copy(alpha = 0.5f), fontSize = 12.sp)
                    Text(
                        text = data.identifier,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = fg.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }
            }
            StatusDot(
                level = data.statusLevel,
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
            data.issuedText?.let { BottomMeta(it, fg) }
            data.expiryText?.let {
                Text("·", color = fg.copy(alpha = 0.4f), fontSize = 11.sp)
                BottomMeta(it, fg)
            }
            data.networkLabel?.let {
                Text("·", color = fg.copy(alpha = 0.4f), fontSize = 11.sp)
                BottomMeta(it, fg)
            }
        }
    }
}

@Composable
private fun BottomMeta(text: String, fg: Color) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = fg.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
