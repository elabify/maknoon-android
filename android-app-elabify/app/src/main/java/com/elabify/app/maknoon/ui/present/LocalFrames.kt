// Local multi-frame QR transport (Android mirror of iOS LocalFrames). The
// holder renders a rotating sequence of small QRs; the verifier collects frames
// until the payload reassembles. No network, no third-party drop.
//
// Frame envelope (each QR encodes one): { v:"elabify-frames-1", id, idx, total,
// data:<base64 chunk> }. Source bytes = the Presentation JSON, base64-encoded;
// chunked at ~750 base64 chars so each envelope fits a QR version ~25 at medium
// error correction. Byte-for-byte the same wire format as iOS / the React
// verifier, so an Android offline QR is cross-platform scannable.

package com.elabify.app.maknoon.ui.present

import android.graphics.Bitmap
import android.util.Base64
import com.elabify.app.maknoon.ui.components.qrBitmap
import org.json.JSONObject
import java.security.SecureRandom
import kotlin.math.ceil

object LocalFrames {
    const val VERSION = "elabify-frames-1"
    private const val CHUNK_CHARS = 750

    data class Frame(val v: String, val id: String, val idx: Int, val total: Int, val data: String) {
        fun toJsonString(): String =
            JSONObject()
                .put("v", v)
                .put("id", id)
                .put("idx", idx)
                .put("total", total)
                .put("data", data)
                .toString()
    }

    /** Split the source JSON into the numbered frame sequence. */
    fun chunks(sourceJson: String): List<Frame> {
        val base64 = Base64.encodeToString(sourceJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val id = randomHex()
        val total = maxOf(1, ceil(base64.length.toDouble() / CHUNK_CHARS).toInt())
        val frames = ArrayList<Frame>()
        var offset = 0
        var idx = 0
        while (offset < base64.length) {
            val end = minOf(offset + CHUNK_CHARS, base64.length)
            frames.add(Frame(VERSION, id, idx, total, base64.substring(offset, end)))
            offset = end
            idx++
        }
        if (frames.isEmpty()) frames.add(Frame(VERSION, id, 0, 1, ""))
        return frames
    }

    /** Pre-render every frame to a QR bitmap so rotation is glitch-free. */
    fun renderFrames(frames: List<Frame>, sizePx: Int = 600): List<Bitmap> =
        frames.map { qrBitmap(it.toJsonString(), sizePx) }

    private fun randomHex(): String {
        val b = ByteArray(8)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
