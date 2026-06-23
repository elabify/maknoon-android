// Design-token system for the Maknoon Android app, ported from the shipped
// iOS SwiftUI visual language. Spacing rhythm (16/12/8/6 dp), the rounded
// corner radii the iOS cards/banners use (6/8/12/16/18/24 dp), card
// elevations, and the semantic color set (warning orange, error red,
// success green, info blue) with the iOS tint alphas (0.10 / 0.12 / 0.15)
// for banner and cell backgrounds.

package com.elabify.app.maknoon.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 16 / 12 / 8 / 6 dp spacing rhythm from the iOS layouts, plus an xs (4) and
// xl/xxl steps for section gaps.
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

// Corner radii observed across the iOS tree (6/8/12/16/18/24). `card` is the
// 18 dp continuous radius the wallet credential card uses.
object Radii {
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val card: Dp = 18.dp
    val xl: Dp = 24.dp
}

// Material3 Shapes instance wired into MaknoonTheme so default components
// pick up the brand radii.
val MaknoonShapes =
    Shapes(
        extraSmall = RoundedCornerShape(Radii.xs),
        small = RoundedCornerShape(Radii.sm),
        medium = RoundedCornerShape(Radii.md),
        large = RoundedCornerShape(Radii.lg),
        extraLarge = RoundedCornerShape(Radii.xl),
    )

// Soft layered shadow specs. The iOS credential card uses a 16-radius / y=8
// black shadow at ~0.35 alpha; cards elsewhere use lighter drops. These are
// the resting elevations (Compose draws its own ambient + spot shadow from a
// single dp value, so we approximate the iOS layering with a primary dp and
// an optional secondary "ambient" dp for callers that stack two shadows).
object Elevation {
    val none: Dp = 0.dp
    val card: Dp = 2.dp
    val cardRaised: Dp = 6.dp
    // The big wallet card. Matches the heavier iOS drop (radius 16, y 8).
    val walletCard: Dp = 12.dp
    val sheet: Dp = 16.dp
}

// Semantic colors mirroring the iOS orange=warning, red=error, green=success,
// blue=info set. These are fixed brand-semantic hues (not theme-derived) so a
// success green reads the same in light + dark, exactly like the SF-symbol
// tints on iOS. Container helpers apply the iOS tint alphas.
object MaknoonColors {
    val warning = Color(0xFFE8870E) // orange
    val error = Color(0xFFD92D20) // red
    val success = Color(0xFF2E9E5B) // green
    val info = Color(0xFF2563EB) // blue

    // iOS uses tints at 0.10 / 0.12 / 0.15 for banner + cell backgrounds.
    // 0.12 is the canonical banner fill (e.g. the iOS indigo locked banner at
    // 0.10, status pills at 0.15); expose all three.
    const val TintBannerAlpha = 0.12f
    const val TintCellAlpha = 0.10f
    const val TintPillAlpha = 0.15f

    val warningContainer: Color = warning.tint(TintBannerAlpha)
    val errorContainer: Color = error.tint(TintBannerAlpha)
    val successContainer: Color = success.tint(TintBannerAlpha)
    val infoContainer: Color = info.tint(TintBannerAlpha)
}

// Traffic-light status colors for the expiry / passive-auth StatusDot,
// matching the iOS green / yellow / red expiry dot.
object StatusColors {
    val ok = Color(0xFF2E9E5B) // green, more than 30 days out / valid
    val warn = Color(0xFFE6A700) // yellow, within 30 days
    val expired = Color(0xFFD92D20) // red, past expiry / failed
}

// Tint helper: a brand/semantic color at the given alpha, for banner and cell
// backgrounds (mirrors the SwiftUI `Color.opacity(_:)` tint pattern).
fun Color.tint(alpha: Float): Color = copy(alpha = alpha)

// MaterialTheme-style accessors so screens can read the brand purples without
// importing the internal theme vals. Read-only composable getters.
object MaknoonBrand {
    val deepPurple: Color
        @Composable @ReadOnlyComposable get() = BrandDeepPurple
    // Tracks the scheme primary so it follows the iOS systemPurple light/dark
    // stops (0xAF52DE / 0xBF5AF2) rather than a single fixed tone.
    val accent: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val accentLight: Color
        @Composable @ReadOnlyComposable get() = BrandAccentPurpleLight
}
