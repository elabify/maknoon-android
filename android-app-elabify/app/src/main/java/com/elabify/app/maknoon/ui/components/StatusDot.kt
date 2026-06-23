// Small traffic-light status dot: an 8 dp filled circle with a thin white
// ring, used for the credential card's expiry status and a saved document's
// passive-auth status. Mirrors the iOS CredentialCard.statusDot (green more
// than 30 days out, yellow within 30 days, red past expiry).

package com.elabify.app.maknoon.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.ui.theme.StatusColors

enum class StatusLevel {
    OK,
    WARN,
    EXPIRED,
}

fun StatusLevel.color(): Color =
    when (this) {
        StatusLevel.OK -> StatusColors.ok
        StatusLevel.WARN -> StatusColors.warn
        StatusLevel.EXPIRED -> StatusColors.expired
    }

@Composable
fun StatusDot(
    level: StatusLevel,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    ringColor: Color = Color.White.copy(alpha = 0.5f),
) {
    androidx.compose.foundation.layout.Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(level.color())
                .border(0.5.dp, ringColor, CircleShape),
    )
}

// Classify an expiry instant (epoch millis) into a traffic-light level using
// the iOS rule: null/absent or more than 30 days out is OK, within 30 days is
// WARN, at or past expiry is EXPIRED.
fun expiryStatus(
    expiresAtEpochMillis: Long?,
    nowEpochMillis: Long,
): StatusLevel {
    val exp = expiresAtEpochMillis ?: return StatusLevel.OK
    if (exp <= nowEpochMillis) return StatusLevel.EXPIRED
    val thirtyDaysMillis = 30L * 24L * 3600L * 1000L
    if (exp - nowEpochMillis < thirtyDaysMillis) return StatusLevel.WARN
    return StatusLevel.OK
}
