// "Choose a QR photo" affordance shared by every camera-scan screen (Scan
// verifier, Verify someone, Receive credential), mirroring iOS
// QRPhotoPickerButton. GMS-free: the system Android Photo Picker
// (ActivityResultContracts.PickVisualMedia, no Google Play dependency) supplies
// the image, and com.google.zxing core decodes the QR off an RGBLuminanceSource
// (the same decoder family MiniAppQrScanner uses for the live camera).

package com.elabify.app.maknoon.ui.miniapp

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.elabify.app.maknoon.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decode a QR from a picked image. Returns the decoded text, or null when the
 * image holds no readable QR. Blocking (decode + getPixels); call off the main
 * thread.
 */
fun decodeQrFromImageUri(context: Context, uri: Uri): String? {
    val bitmap = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // getPixels needs a software (non-hardware) bitmap.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            // Cap the longest side so a full-res photo doesn't blow up memory.
            val maxSide = 2048
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val scale = maxSide.toDouble() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
        }
    } catch (_: Exception) {
        return null
    }
    val w = bitmap.width
    val h = bitmap.height
    if (w == 0 || h == 0) return null
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, pixels)))).text
    } catch (_: Exception) {
        null
    }
}

/**
 * "Choose a QR photo" button: opens the system photo picker, decodes a QR off
 * the main thread, and calls [onCode] with the text (or [onNoQr] when none was
 * found / the user cancels the pick is a no-op).
 */
@Composable
fun QrPhotoPickerButton(
    onCode: (String) -> Unit,
    onNoQr: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val buttonLabel = label ?: stringResource(R.string.app_choose_qr_photo)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val code = withContext(Dispatchers.IO) { decodeQrFromImageUri(context, uri) }
            if (code != null) onCode(code) else onNoQr()
        }
    }
    OutlinedButton(
        onClick = {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        modifier = modifier,
    ) {
        Text(buttonLabel)
    }
}
