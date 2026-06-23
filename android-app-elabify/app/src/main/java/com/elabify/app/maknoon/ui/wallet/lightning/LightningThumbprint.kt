// Per-account thumbprint avatar, the Compose analog of iOS WalletThumbprint.
// A bolt glyph on a circular tint derived from the (serverURL, username) seed,
// so duplicate imports render the same colour and the user can tell accounts
// apart at a glance. Lives in the lightning package; not shared.

package com.elabify.app.maknoon.ui.wallet.lightning

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun LightningThumbprint(seed: String, size: Dp = 36.dp) {
    val hue = ((seed.hashCode().toLong() and 0xffffffffL) % 360L).toFloat()
    val tint = hsvColor(hue, 0.55f, 0.85f)
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

private fun hsvColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}
