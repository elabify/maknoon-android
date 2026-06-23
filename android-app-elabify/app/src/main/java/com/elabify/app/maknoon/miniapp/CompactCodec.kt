// Compact wire codec for serverless commerce payloads (ADR-0031). Android port
// of CompactCodec.swift.
//
// Encodes a JSON value as UTF-8, then zlib-deflates it. The size win comes from
// recovering binary density: presentations are dominated by 0x-hex strings
// (ML-DSA-65 signatures are ~3.3 kB each, the holder pubkey ~1.9 kB), and hex
// over a 16-symbol alphabet deflates ~2x. So an ~18 kB JSON+hex single-attribute
// presentation lands near ~9 kB, small enough to move over an NFC ISO-DEP tap.
//
// It round-trips losslessly to the exact same JSON bytes, so signature
// verification is unaffected. Swift used (NSData).compressed(using: .zlib);
// Android uses java.util.zip.Deflater/Inflater (zlib RFC-1950 wrapper, the
// matching format) so an iOS peer needs no special-casing.

package com.elabify.app.maknoon.miniapp

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import org.json.JSONObject

class CompactCodecException(override val message: String) : Exception(message)

object CompactCodec {

    /** Serialize then zlib-deflate. */
    fun encode(json: JSONObject): ByteArray {
        val raw = json.toString().toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size / 2 + 16)
            val buf = ByteArray(8 * 1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (e: Exception) {
            throw CompactCodecException("compression failed: ${e.message}")
        } finally {
            deflater.end()
        }
    }

    /** zlib-inflate then parse back to JSON. */
    fun decode(data: ByteArray): JSONObject {
        val inflater = Inflater()
        val json = try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2 + 16)
            val buf = ByteArray(8 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (e: Exception) {
            throw CompactCodecException("decompression failed: ${e.message}")
        } finally {
            inflater.end()
        }
        return JSONObject(String(json, Charsets.UTF_8))
    }
}
