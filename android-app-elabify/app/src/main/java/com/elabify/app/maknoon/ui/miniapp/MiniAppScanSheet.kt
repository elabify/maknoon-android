// window.maknoon.scan() native QR scanner sheet, plus the reusable camera2 +
// ZXing scanner composable the collect sheet also uses. Android port of the
// iOS MiniAppScanSheet.swift.
//
// The dApp never gets the camera stream, it gets back the decoded string. The
// scan namespace is gated by the "scan" capability + the OS camera permission
// + this explicit sheet (the user sees what the camera is pointed at and can
// cancel). The collect flow reuses MiniAppQrScanner for continuous scanning.
//
// GMS-free: decoding is com.google.zxing core (MultiFormatReader over a
// PlanarYUVLuminanceSource), camera is the platform android.hardware.camera2.
// No CameraX, no ML Kit, no Play services.

package com.elabify.app.maknoon.ui.miniapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.elabify.app.maknoon.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject

/**
 * window.maknoon.scan() sheet. Reads one code and resolves the JS promise with
 * { value: <code> }. The payload is { prompt? }.
 */
@Composable
fun MiniAppScanSheet(
    appTitle: String,
    payloadJson: String,
    onResolve: (resultJson: String) -> Unit,
    onCancel: () -> Unit,
) {
    val payload = remember(payloadJson) { runCatching { JSONObject(payloadJson) }.getOrNull() }
    val prompt = payload?.optString("prompt").takeUnless { it.isNullOrEmpty() }
        ?: stringResource(R.string.app_scan_a_code)
    var done by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(appTitle)
        Text(prompt)
        MiniAppQrScanner(
            continuous = false,
            onCode = { code ->
                if (done) return@MiniAppQrScanner
                done = true
                onResolve(JSONObject().put("value", code).toString())
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)),
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_cancel)) }
    }
}

/**
 * Reusable live QR scanner. Requests CAMERA at first composition, opens the
 * back camera with camera2, feeds YUV frames into a ZXing MultiFormatReader,
 * and calls [onCode] with the decoded text. When [continuous] is false the
 * caller is expected to dismiss after the first hit; when true the scanner
 * keeps decoding (the caller dedups), which the collect flow uses for
 * multi-frame / rotating codes.
 */
@Composable
fun MiniAppQrScanner(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    continuous: Boolean = true,
) {
    val context = LocalContext.current
    val latestOnCode by rememberUpdatedState(onCode)
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted = ok }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.app_camera_access_needed))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.app_allow_camera))
            }
        }
        return
    }

    // One controller per scanner instance, torn down on dispose.
    val controller = remember { Camera2QrController(context, continuous) }
    DisposableEffect(Unit) {
        controller.onDecoded = { code -> latestOnCode(code) }
        onDispose { controller.close() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                        controller.start(tv, s, w, h)
                    }

                    override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {
                        controller.configureTransform(tv, w, h)
                    }

                    override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                        controller.close()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                }
            }
        },
    )
}

/**
 * camera2 plumbing: opens the back camera, streams preview to the TextureView
 * and YUV frames to an ImageReader, runs ZXing on each frame off the main
 * thread, and reports decoded text via [onDecoded]. Idempotent close().
 */
private class Camera2QrController(
    context: Context,
    private val continuous: Boolean,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    var onDecoded: ((String) -> Unit)? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSize = Size(1280, 720)
    private var closed = false
    private var fired = false

    fun start(textureView: TextureView, surfaceTexture: SurfaceTexture, viewW: Int, viewH: Int) {
        if (closed) return
        thread = HandlerThread("miniapp-qr").also { it.start() }
        handler = Handler(thread!!.looper)
        val cameraId = backCameraId() ?: return
        previewSize = chooseSize(cameraId)
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        configureTransform(textureView, viewW, viewH)

        imageReader = ImageReader.newInstance(
            previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2,
        ).apply { setOnImageAvailableListener({ ir -> onFrame(ir) }, handler) }

        try {
            @Suppress("MissingPermission")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession(camera, Surface(surfaceTexture))
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, handler)
        } catch (_: Exception) {
            // Camera open can throw on devices without a back camera; the sheet
            // still shows and the user can cancel.
        }
    }

    private fun createSession(camera: CameraDevice, previewSurface: Surface) {
        val analysisSurface = imageReader?.surface ?: return
        val targets = listOf(previewSurface, analysisSurface)
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) {
                        s.close(); return
                    }
                    session = s
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        addTarget(analysisSurface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }
                    runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, handler)
        } catch (_: Exception) {
        }
    }

    private fun onFrame(ir: ImageReader) {
        val image = ir.acquireLatestImage() ?: return
        try {
            if (fired && !continuous) return
            val plane = image.planes[0]
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val w = image.width
            val h = image.height
            val source = PlanarYUVLuminanceSource(data, plane.rowStride, h, 0, 0, w, h, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val text = runCatching { reader.decodeWithState(bitmap).text }.getOrNull()
            reader.reset()
            if (text != null) {
                if (!continuous) {
                    if (fired) return
                    fired = true
                }
                onDecoded?.invoke(text)
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    fun configureTransform(textureView: TextureView, viewW: Int, viewH: Int) {
        if (viewW == 0 || viewH == 0) return
        // Keep the preview upright; a simple identity transform is fine for a
        // square viewport, decoding does not depend on display orientation.
        textureView.setTransform(Matrix())
    }

    private fun backCameraId(): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.firstOrNull()
    }.getOrNull()

    private fun chooseSize(cameraId: String): Size = runCatching {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: return@runCatching Size(1280, 720)
        // Prefer ~720p: large enough for dense QR, small enough to decode fast.
        sizes.filter { it.width <= 1920 && it.height <= 1080 }
            .maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
    }.getOrDefault(Size(1280, 720))

    fun close() {
        if (closed) return
        closed = true
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { imageReader?.close() }
        runCatching { thread?.quitSafely() }
        session = null
        device = null
        imageReader = null
        thread = null
        handler = null
    }
}
