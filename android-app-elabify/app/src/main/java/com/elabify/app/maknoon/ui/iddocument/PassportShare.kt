// Composes the passport Share picture (Android mirror of iOS
// PassportCardDetailView.buildShare / composeShareImage, ADR-0039): the
// rectangular navy passport card drawn above the verifiable drop QR, with a
// footer stating how long the QR is valid, the exact ISO 8601 / UTC expiry, and
// the download call-to-action. Shared as a PNG via FileProvider (ACTION_SEND).
//
// Drawn with android.graphics.Canvas (not Compose) so it is deterministic and
// needs no composition/capture timing. The card omits the ledger/pinned-network
// strip, matching iOS's forSharing card.

package com.elabify.app.maknoon.ui.iddocument

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.PassiveAuthResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max

object PassportShare {
    private const val DOWNLOAD_CTA = "Download Elabify Maknoon for Apple and Android to verify"
    private const val NAVY_START = 0xFF1A3D6D.toInt()
    private const val NAVY_END = 0xFF0D2447.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val S = 3f // render scale for crispness

    /** Compose the shareable picture: navy card, then the drop QR, then footer.
     *  Card labels are localized via [context] so the picture matches the holder's
     *  language (iOS renders the same card with LocalizedStringKey). */
    fun composeShareBitmap(context: Context, doc: IDDocument, photo: Bitmap?, qr: Bitmap, expiresAt: Long?): Bitmap {
        val width = (360f * S).toInt()
        val pad = 24f * S
        val gap = 20f * S
        val card = drawCard(context, doc, photo, width - (pad * 2).toInt())
        val qrSize = (width * 0.62f).toInt()
        val footer = footerLines(expiresAt)

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * S; textAlign = Paint.Align.CENTER }
        val footerLineH = footerPaint.fontSpacing
        val footerH = footerLineH * footer.size + 6f * S

        val height = (pad + card.height + gap + qrSize + gap + footerH + pad).toInt()
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFF7F7F7.toInt())
        var y = pad
        c.drawBitmap(card, pad, y, null)
        y += card.height + gap
        c.drawBitmap(qr, null, RectF((width - qrSize) / 2f, y, (width + qrSize) / 2f, y + qrSize), null)
        y += qrSize + gap + footerLineH
        footer.forEachIndexed { i, (text, bold, dark) ->
            footerPaint.color = if (dark) 0xFF404040.toInt() else 0xFF737373.toInt()
            footerPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            footerPaint.textSize = if (i == footer.lastIndex && !bold) 11f * S else 12f * S
            c.drawText(text, width / 2f, y + i * footerLineH, footerPaint)
        }
        return out
    }

    fun shareImage(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        val file = File(dir, "passport-share.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // ClipData carries the URI + grant so messaging/contact targets reliably
            // read and preview the image. No EXTRA_TEXT: with both present some apps
            // treat it as a text share and drop the image (the download call-to-action
            // is already rendered in the picture footer).
            clipData = ClipData.newUri(context.contentResolver, "Passport", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // ── card drawing ─────────────────────────────────────────────────────────

    private data class FieldRow(val label: String, val value: String)

    private fun drawCard(context: Context, doc: IDDocument, photo: Bitmap?, cardWidth: Int): Bitmap {
        val inset = 18f * S
        val photoW = 88f * S
        val photoH = 108f * S
        val colGap = 15f * S

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (WHITE and 0x00FFFFFF) or 0xB3000000.toInt(); textSize = 9f * S; typeface = Typeface.DEFAULT_BOLD }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = 15f * S; typeface = Typeface.DEFAULT_BOLD }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = 20f * S; typeface = Typeface.DEFAULT_BOLD }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = 12f * S; typeface = Typeface.DEFAULT_BOLD }
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WHITE; textSize = 14f * S; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
        val numLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (WHITE and 0x00FFFFFF) or 0xB3000000.toInt(); textSize = 9f * S; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }

        // Fields laid out in the same grouped rows as the on-screen card / iOS:
        // Surname, Given names, then [Nationality | Sex | Date of birth],
        // [Issued | Expires], then Place of birth.
        val colSpacing = 18f * S
        val lines: List<List<FieldRow>> = buildList {
            add(listOf(FieldRow(context.getString(R.string.passport_field_surname), cleanShareName(doc.latinSurname ?: doc.surname))))
            add(listOf(FieldRow(context.getString(R.string.passport_field_given_names), cleanShareName(doc.latinGivenNames ?: doc.givenNames))))
            add(
                buildList {
                    add(FieldRow(context.getString(R.string.passport_field_nationality), shareIssuerCode(doc.nationality)))
                    doc.sex?.takeIf { it.isNotEmpty() }?.let { add(FieldRow(context.getString(R.string.passport_field_sex), it.uppercase())) }
                    add(FieldRow(context.getString(R.string.passport_field_dob), shareIsoDate(doc.dateOfBirth, isBirth = true)))
                },
            )
            add(
                listOf(
                    FieldRow(context.getString(R.string.passport_field_issued), shareIssueDate(doc.dg12) ?: "—"),
                    FieldRow(context.getString(R.string.passport_field_expires), shareIsoDate(doc.dateOfExpiry, isBirth = false)),
                ),
            )
            doc.formattedPlaceOfBirth?.takeIf { it.isNotEmpty() }?.let { add(listOf(FieldRow(context.getString(R.string.passport_field_place_of_birth), it))) }
        }

        val rowH = labelPaint.fontSpacing + valuePaint.fontSpacing + 8f * S
        val fieldsH = lines.size * rowH
        val blockH = max(photoH, fieldsH)
        // issuer row + a second line for the passport number under its label.
        val height = (inset + titlePaint.fontSpacing + 14f * S + smallPaint.fontSpacing + numPaint.fontSpacing + 16f * S + blockH + 14f * S + 24f * S + inset).toInt()

        val bmp = Bitmap.createBitmap(cardWidth, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(0f, 0f, cardWidth.toFloat(), height.toFloat(), NAVY_START, NAVY_END, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, cardWidth.toFloat(), height.toFloat()), 12f * S, 12f * S, bg)

        var y = inset + titlePaint.textSize
        val title = context.getString(R.string.passport_title)
        c.drawText(title, inset, y, titlePaint)
        c.drawText("v${doc.schemaVersion}", inset + titlePaint.measureText(title) + 10f * S, y, smallPaint)

        y += 14f * S + smallPaint.textSize
        // left: issuer flag + "Issued by CC"
        val flag = shareFlagEmoji(doc.issuingAuthority)
        val issuedBy = context.getString(R.string.passport_issued_by, shareIssuerCode(doc.issuingAuthority))
        c.drawText(if (flag.isNotEmpty()) "$flag  $issuedBy" else issuedBy, inset, y, smallPaint)
        // right: "Passport No" label, with the number on the line below it
        c.drawText(context.getString(R.string.passport_number_label), cardWidth - inset, y, numLabelPaint)
        c.drawText(doc.documentNumber, cardWidth - inset, y + numPaint.fontSpacing, numPaint)

        val blockTop = y + numPaint.fontSpacing + 16f * S
        // photo
        if (photo != null) {
            c.drawBitmap(photo, null, RectF(inset, blockTop, inset + photoW, blockTop + photoH), null)
        } else {
            val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
            c.drawRoundRect(RectF(inset, blockTop, inset + photoW, blockTop + photoH), 11f * S, 11f * S, ph)
        }
        // fields column: each line draws its fields left-to-right with column
        // spacing, so DOB sits right of Sex and Expires right of Issued (iOS parity).
        val fx = inset + photoW + colGap
        var fy = blockTop + labelPaint.textSize
        for (line in lines) {
            var lx = fx
            for (f in line) {
                c.drawText(f.label, lx, fy, labelPaint)
                c.drawText(f.value, lx, fy + valuePaint.fontSpacing, valuePaint)
                val w = max(labelPaint.measureText(f.label), valuePaint.measureText(f.value))
                lx += w + colSpacing
            }
            fy += rowH
        }

        // divider + seal: colored badge (circle + glyph) then the label, mirroring
        // the on-screen GenuineSeal so the verified logo is in the shared picture.
        val sealY = blockTop + blockH + 14f * S
        val divPaint = Paint().apply { color = 0x29FFFFFF }
        c.drawRect(inset, sealY - 14f * S, cardWidth - inset, sealY - 14f * S + 1f, divPaint)
        val textBaseline = sealY + smallPaint.textSize
        val rad = 10.5f * S
        val cy = textBaseline - smallPaint.textSize * 0.32f
        val cx = inset + rad
        c.drawCircle(cx, cy, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sealColor(doc.passiveAuthResult) })
        drawSealGlyph(c, doc.passiveAuthResult, cx, cy, rad)
        c.drawText(sealLabel(context, doc.passiveAuthResult), inset + 2f * rad + 7f * S, textBaseline, smallPaint)
        return bmp
    }

    private fun sealLabel(context: Context, r: PassiveAuthResult?): String = context.getString(
        when (r?.status) {
            PassiveAuthResult.Status.VERIFIED -> R.string.passport_seal_verified
            PassiveAuthResult.Status.INTEGRITY_ONLY -> R.string.passport_seal_genuine
            PassiveAuthResult.Status.FAILED -> R.string.passport_seal_failed
            else -> R.string.passport_seal_unverified
        },
    )

    private fun sealColor(r: PassiveAuthResult?): Int = when (r?.status) {
        PassiveAuthResult.Status.VERIFIED -> 0xFF34D399.toInt()
        PassiveAuthResult.Status.INTEGRITY_ONLY -> 0xFF3B82F6.toInt()
        PassiveAuthResult.Status.FAILED -> 0xFFF87171.toInt()
        else -> 0xFF9E9E9E.toInt()
    }

    /** White glyph inside the badge: check (verified/genuine), X (failed), ? (unknown). */
    private fun drawSealGlyph(c: Canvas, r: PassiveAuthResult?, cx: Float, cy: Float, rad: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.2f * S
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (r?.status) {
            PassiveAuthResult.Status.VERIFIED, PassiveAuthResult.Status.INTEGRITY_ONLY -> {
                val path = android.graphics.Path().apply {
                    moveTo(cx - 0.38f * rad, cy + 0.02f * rad)
                    lineTo(cx - 0.08f * rad, cy + 0.30f * rad)
                    lineTo(cx + 0.40f * rad, cy - 0.30f * rad)
                }
                c.drawPath(path, p)
            }
            PassiveAuthResult.Status.FAILED -> {
                c.drawLine(cx - 0.3f * rad, cy - 0.3f * rad, cx + 0.3f * rad, cy + 0.3f * rad, p)
                c.drawLine(cx - 0.3f * rad, cy + 0.3f * rad, cx + 0.3f * rad, cy - 0.3f * rad, p)
            }
            else -> {
                val q = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = WHITE; textSize = rad * 1.3f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
                }
                c.drawText("?", cx, cy + rad * 0.45f, q)
            }
        }
    }

    /** (text, bold, dark) lines. Validity + UTC expiry appear only when known;
     *  the download CTA always closes it (matches iOS footerString). */
    private fun footerLines(expiresAt: Long?): List<Triple<String, Boolean, Boolean>> = buildList {
        if (expiresAt != null) {
            val mins = max(0L, ceil((expiresAt - System.currentTimeMillis() / 1000L) / 60.0).toLong())
            add(Triple("This QR is valid for the next $mins minute${if (mins == 1L) "" else "s"}.", true, true))
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            add(Triple("Expires ${iso.format(Date(expiresAt * 1000L))} (UTC)", false, false))
        }
        add(Triple(DOWNLOAD_CTA, true, true))
    }

    // ── field helpers (mirror the navy screen's, kept local to the bitmap) ────

    private fun cleanShareName(s: String): String {
        val cleaned = s.replace("<", " ").split(" ").filter { it.isNotEmpty() }.joinToString(" ")
        return cleaned.ifEmpty { "—" }
    }

    private fun shareIssuerCode(alpha3: String): String =
        com.elabify.app.maknoon.iddocument.ISO3166.alpha2(alpha3) ?: alpha3.uppercase()

    /** alpha-3 -> flag emoji via alpha-2 regional indicators (iOS parity). */
    private fun shareFlagEmoji(alpha3: String): String {
        val a2 = com.elabify.app.maknoon.iddocument.ISO3166.alpha2(alpha3) ?: return ""
        if (a2.length != 2) return ""
        val base = 0x1F1E6
        val sb = StringBuilder()
        for (ch in a2.uppercase()) {
            if (ch !in 'A'..'Z') return ""
            sb.appendCodePoint(base + (ch.code - 'A'.code))
        }
        return sb.toString()
    }

    private fun shareIsoDate(yymmdd: String, isBirth: Boolean): String {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return "—"
        val yy = yymmdd.substring(0, 2).toInt()
        val century = if (isBirth) {
            val cur = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).get(java.util.Calendar.YEAR) % 100
            if (yy <= cur) 2000 else 1900
        } else {
            2000
        }
        return "${century + yy}-${yymmdd.substring(2, 4)}-${yymmdd.substring(4, 6)}"
    }

    private fun shareIssueDate(dg12: ByteArray?): String? {
        val b = dg12 ?: return null
        var i = 0
        while (i + 2 < b.size) {
            if (b[i] == 0x5F.toByte() && b[i + 1] == 0x26.toByte()) {
                val len = b[i + 2].toInt() and 0xFF
                val start = i + 3
                if (len == 8 && start + len <= b.size) {
                    val s = String(b, start, len, Charsets.US_ASCII)
                    if (s.length == 8 && s.all { it.isDigit() }) {
                        return "${s.substring(0, 4)}-${s.substring(4, 6)}-${s.substring(6, 8)}"
                    }
                }
            }
            i++
        }
        return null
    }
}
