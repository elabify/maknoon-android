// Temporarily ramps the screen to full brightness while a QR code is shown, so a
// scanner can read it reliably (especially the dense multi-frame QR frames), then
// restores the previous window brightness once no QR is on screen. Call
// MaxBrightness() from any composable that displays a QR the user holds up to
// another device or reader. Mirrors the iOS .maxBrightnessWhilePresented()
// modifier. See ADR (QR display conventions).

package com.elabify.app.maknoon.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Unwrap a (possibly wrapped) Compose context to its host Activity. */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Ref-counted so nested / simultaneous QR composables (e.g. a QrCode inside a
 * screen that also raises brightness) never clobber the saved original: the
 * original window brightness is captured on the first acquire and restored only
 * when the last consumer releases.
 */
private object BrightnessController {
    private var activeCount = 0
    private var original: Float? = null

    fun acquire(window: Window) {
        if (activeCount == 0) {
            original = window.attributes.screenBrightness
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        activeCount++
    }

    fun release(window: Window) {
        activeCount--
        if (activeCount <= 0) {
            activeCount = 0
            window.attributes = window.attributes.apply {
                screenBrightness = original ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            original = null
        }
    }
}

/**
 * While this composable is in the composition, force the window to full
 * brightness; restore the prior brightness (or the system default) once the
 * last such composable leaves.
 */
@Composable
fun MaxBrightness() {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window) {
        BrightnessController.acquire(window)
        onDispose { BrightnessController.release(window) }
    }
}
