package com.elabify.app.maknoon.ui

import android.app.Activity
import android.graphics.Color
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Edge-to-edge window setup, hand-rolled rather than via `enableEdgeToEdge()`.
 *
 * Two reasons, and the first is the one Google Play complained about.
 *
 * `enableEdgeToEdge()` reaches `androidx.activity.EdgeToEdgeApi28`, whose
 * `adjustLayoutInDisplayCutoutMode` sets
 * `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`, deprecated in Android 15. Play
 * reported it against the obfuscated symbol `b.r.b`. It is unreachable at runtime
 * here (androidx selects its implementation by `SDK_INT`, `minSdk` is 33, so the
 * API 30 branch always wins and that one uses the non-deprecated `..._ALWAYS`), but
 * R8 retains the dead class and Play's static analysis flags it. Calling the
 * platform APIs ourselves drops the whole `EdgeToEdge*` family from the DEX, so the
 * deprecated constant is simply not present.
 *
 * It also forecloses the next warning. Every androidx implementation still assigns
 * `window.statusBarColor` and `window.navigationBarColor`, which are separately
 * deprecated in Android 15. We never call them.
 *
 * Nothing is lost by hand-rolling. androidx's value is backward compatibility down
 * to API 21, and `minSdk` is 33: there is no pre-API-30 device to be compatible
 * with. The scrims androidx paints by default are also unnecessary here, because
 * the app draws its own Material 3 `NavigationBar` behind the gesture area and a
 * `TopAppBar` behind the status bar, so the contrast comes from real app surfaces
 * rather than a translucent overlay.
 *
 * See ADR-0080.
 */
fun Activity.applyEdgeToEdgeWindow() {
    // Draw behind the system bars. This is the actual edge-to-edge switch; from
    // Android 15 (targetSdk 35+) the platform does it regardless, so this mainly
    // makes the intent explicit and keeps behaviour identical on API 33-34.
    WindowCompat.setDecorFitsSystemWindows(window, false)

    // Fully transparent bars. The app's own surfaces provide contrast.
    @Suppress("DEPRECATION")
    run {
        // No-ops from API 35 and deprecated there, but API 33-34 devices still
        // honour them, and without this those devices keep the theme's opaque bars
        // and the edge-to-edge layout is invisible. Scoped so the deprecation
        // suppression cannot spread.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    // Draw into the display cutout on every edge, including in landscape. This is
    // the non-deprecated mode; SHORT_EDGES is the one Android 15 deprecated.
    // Reassigned rather than mutated in place, so the window definitely re-reads it.
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    // Let the transparent bars stay transparent instead of the platform painting a
    // scrim behind them. Both require API 29; minSdk is 33.
    window.isStatusBarContrastEnforced = false
    window.isNavigationBarContrastEnforced = false
}

/**
 * Keeps the system-bar ICON colours in step with the app theme.
 *
 * Deliberately a composable rather than part of the one-shot window setup.
 * `enableEdgeToEdge()` resolves dark mode once, when it is called from `onCreate`,
 * and the theme here is an observable preference that recomposes WITHOUT recreating
 * the activity (Settings > Display). So a one-shot call leaves white-on-white or
 * black-on-black status bar icons after a theme switch until the next process
 * start. Reading the resolved theme from composition fixes that.
 */
@Composable
fun SystemBarIconAppearance(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).run {
            // Light icons on a dark background and vice versa.
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

/**
 * `systemBars` plus the HORIZONTAL display-cutout inset.
 *
 * For full-screen surfaces that render ABOVE the root Scaffold (the app-lock
 * overlay, onboarding, the present/scan sheets) and therefore do not inherit the
 * root's cutout padding.
 *
 * The cutout is its own inset type: `systemBarsPadding()` does not include it, and
 * nothing in the app consumed it before, so in landscape on a cutout device these
 * surfaces drew content under the notch. Horizontal only, because the vertical
 * cutout inset overlaps the status bar the caller is already insetting from.
 *
 * Deliberately NOT `safeDrawingPadding()`, which would be the idiomatic one-liner:
 * that also adds the IME inset and would change how these screens behave when the
 * keyboard opens. Several are scrolling forms that handle that themselves, and this
 * change is meant to fix the cutout gap without altering keyboard behaviour.
 */
@Composable
fun safeBarsInsets(): WindowInsets =
    WindowInsets.systemBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

/**
 * `statusBars` plus the horizontal display-cutout inset, for surfaces that inset
 * only from the top (they sit above the tab bar, so the bottom is already clear).
 */
@Composable
fun safeStatusBarInsets(): WindowInsets =
    WindowInsets.statusBars.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
