package com.elabify.app.maknoon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Maknoon brand palette, keyed on the iOS app icon ground (a deep purple,
// 0x3A1259) and the vibrant purple accent the iOS app draws controls in.
// Material You / dynamic color can be layered on later; this keeps the
// brand consistent across light + dark without depending on Android 12+
// dynamic palettes (and stays GMS-free).

// Deep purple icon ground. Anchors dark surfaces and acts as the brand
// "container" tone.
internal val BrandDeepPurple = Color(0xFF3A1259)

// Vibrant accent purple, matched EXACTLY to the iOS control tint. iOS draws
// all controls and tab icons with SwiftUI Color.purple, which is a fixed,
// concrete color 0xCB30E0 (rgb 203,48,224) - a bright magenta-purple, NOT the
// adaptive systemPurple (0xAF52DE). Because it is concrete it does not change
// between light and dark, so both schemes use this one value (replacing the
// old, duller magenta 0x9A2C93).
internal val BrandAccentPurple = Color(0xFFCB30E0)

// A lighter accent used for tints on dark surfaces and for the dark scheme
// primary, so purple stays legible on near-black backgrounds.
internal val BrandAccentPurpleLight = Color(0xFFC78BD6)

// Secondary + tertiary brand support tones (a muted indigo and a warm
// magenta) so chips, pills, and secondary buttons have somewhere to land.
internal val BrandIndigo = Color(0xFF5B4B8A)
internal val BrandIndigoLight = Color(0xFFB7A9E0)
internal val BrandMagenta = Color(0xFF8E2A6B)
internal val BrandMagentaLight = Color(0xFFE2A6CF)

private val LightColors =
    lightColorScheme(
        primary = BrandAccentPurple,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEFD9F4),
        onPrimaryContainer = BrandDeepPurple,
        secondary = BrandIndigo,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE4DCF6),
        onSecondaryContainer = Color(0xFF1F1639),
        tertiary = BrandMagenta,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFAD8EC),
        onTertiaryContainer = Color(0xFF3A0E2A),
        background = Color(0xFFFCF9FD),
        onBackground = Color(0xFF1C161F),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1C161F),
        surfaceVariant = Color(0xFFEDE5F0),
        onSurfaceVariant = Color(0xFF4C4453),
        outline = Color(0xFF7D7484),
        outlineVariant = Color(0xFFCFC4D4),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
    )

private val DarkColors =
    darkColorScheme(
        // SwiftUI Color.purple is concrete (does not adapt), so dark uses the
        // same 0xCB30E0 as light rather than dropping to a lighter pastel.
        primary = BrandAccentPurple,
        onPrimary = Color.White,
        primaryContainer = BrandDeepPurple,
        onPrimaryContainer = Color(0xFFEFD9F4),
        secondary = BrandIndigoLight,
        onSecondary = Color(0xFF2A1F4D),
        secondaryContainer = Color(0xFF433667),
        onSecondaryContainer = Color(0xFFE4DCF6),
        tertiary = BrandMagentaLight,
        onTertiary = Color(0xFF52123C),
        tertiaryContainer = Color(0xFF6F2553),
        onTertiaryContainer = Color(0xFFFAD8EC),
        background = Color(0xFF14101A),
        onBackground = Color(0xFFE8E0EC),
        surface = Color(0xFF1B1622),
        onSurface = Color(0xFFE8E0EC),
        surfaceVariant = Color(0xFF302938),
        onSurfaceVariant = Color(0xFFCFC4D4),
        outline = Color(0xFF988E9F),
        outlineVariant = Color(0xFF4C4453),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
    )

@Composable
fun MaknoonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = MaknoonShapes,
        content = content,
    )
}
