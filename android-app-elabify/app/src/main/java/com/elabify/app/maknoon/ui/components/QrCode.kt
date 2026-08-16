// Shared QR composable for receive addresses / payment URIs (ZXing, GMS-free).

package com.elabify.app.maknoon.ui.components
import com.elabify.app.maknoon.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, sizePx: Int = 512) {
    // Any on-screen QR is meant to be scanned, so ramp to full brightness while
    // it is shown (ref-counted, restored on dispose). Covers wallet receive
    // addresses and the online drop QR. See ADR (QR display conventions).
    com.elabify.app.maknoon.ui.MaxBrightness()
    // Encode off the main thread: ZXing encode + bitmap fill on a large payload
    // is heavy enough to visibly freeze the UI if done during composition. Show
    // a spinner until the bitmap is ready instead of blocking on a blank screen.
    // ProduceStateDoesNotAssignValue is a FALSE POSITIVE here: the producer does
    // assign `value` on the next line. The check does not see through an assignment
    // whose right-hand side is a suspend call, which was confirmed by trying an
    // explicit `this.value` receiver and getting the same report. Suppressed rather
    // than worked around, because restructuring correct code to satisfy a broken
    // check is worse than saying so.
    @Suppress("ProduceStateDoesNotAssignValue")
    val bitmap by produceState<ImageBitmap?>(initialValue = null, content, sizePx) {
        value = withContext(Dispatchers.Default) { qrBitmap(content, sizePx).asImageBitmap() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator()
        } else {
            Image(
                painter = BitmapPainter(bmp),
                contentDescription = stringResource(R.string.common_qr_code),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// Public so the passport Share composer can embed the same drop QR in a
// shareable image (mirrors iOS BadgeQR.render into the composed picture).
fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    // Bulk setPixels: a per-pixel setPixel loop over size*size (262k calls at
    // 512px) is pathologically slow. Fill an IntArray once and hand it over.
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
