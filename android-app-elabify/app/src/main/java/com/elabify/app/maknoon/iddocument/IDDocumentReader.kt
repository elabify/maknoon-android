// Thin wrapper around jMRTD's PassportService over an Android NFC IsoDep tag.
//
// Hides the ICAO 9303 vocabulary from the rest of the app. The caller hands in
// three plain strings the user typed on the previous screen (document number,
// birth date, expiry date) and gets back an IDDocument plus an optional photo
// Bitmap. Nothing in here leaks "MRZ", "BAC", "PACE", "DG2", "SOD" up the
// call stack: failures surface as IDDocumentReaderError instead of raw jMRTD
// or scuba exception types.
//
// This is the Android counterpart of the iOS IDDocumentReader, which wrapped
// AndyQ/NFCPassportReader. On Android we drive jMRTD (org.jmrtd.PassportService)
// over scuba's CardService bound to an android.nfc.tech.IsoDep tag. We try
// PACE first when the chip exposes EF.CardAccess, then fall back to BAC, the
// same precedence the iOS library used internally.

package com.elabify.app.maknoon.iddocument

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.elabify.app.maknoon.R
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.PACEKeySpec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.SecureRandom

/**
 * Foreground reader-mode dispatch for the eMRTD scan. Replaces the iOS
 * NFCTagReaderSession: while [start] is active the activity owns the NFC
 * radio, so an ICAO chip tapped to the back of the phone is delivered straight
 * to [onTag] as an IsoDep tag instead of bouncing through the system tag
 * dispatch (and away from this app).
 *
 * We poll both ISO 14443 Type A and Type B (eMRTD chips come in both, and a
 * Type-B passport is never seen if we poll A only) and skip the NDEF presence
 * check so the platform doesn't waste a round trip looking for NDEF records
 * that passports never carry. The caller is responsible for connecting /
 * reading on a background thread: [onTag] fires on a binder thread, not the
 * main thread.
 */
object IDDocumentNfcReaderMode {

    /**
     * Begin foreground reader mode. Returns true if NFC is present and was
     * enabled for this activity, false if the device has no NFC adapter (the
     * UI should show the "needs an NFC phone" copy in that case).
     *
     * Call [stop] from the activity's onPause to release the radio.
     */
    fun start(activity: Activity, onTag: (IsoDep) -> Unit): Boolean {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        // Poll BOTH ISO 14443 Type A and Type B: ICAO 9303 eMRTD chips come in
        // both flavours, and a Type-B passport is simply never discovered if we
        // poll A only. iOS CoreNFC polls the whole ISO 14443 family. Skip the
        // NDEF presence check so the platform doesn't waste a round trip on NDEF
        // records that passports never carry.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        android.util.Log.d("IDDocNfc", "enableReaderMode flags=$flags")
        // Slow the platform's tag-presence polling so a hand-held chip isn't
        // declared "removed" between data groups. 5s presence delay.
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5_000)
        }
        adapter.enableReaderMode(
            activity,
            { tag: Tag ->
                android.util.Log.d("IDDocNfc", "tag discovered: tech=${tag.techList.joinToString()}")
                val isoDep = IsoDep.get(tag)
                if (isoDep == null) {
                    android.util.Log.w("IDDocNfc", "tag has no IsoDep (not an ISO 14443-4 chip)")
                    return@enableReaderMode
                }
                onTag(isoDep)
            },
            flags,
            extras,
        )
        return true
    }

    /** Release foreground reader mode. Safe to call when NFC is absent. */
    fun stop(activity: Activity) {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
    }

    /** Whether this device can read ID documents over NFC at all. */
    fun isAvailable(activity: Activity): Boolean =
        NfcAdapter.getDefaultAdapter(activity)?.isEnabled == true
}

/**
 * Clean, app-facing failure surface. We never leak raw jMRTD / scuba types
 * (CardServiceException, exotic ASN.1 parse errors) past this boundary.
 */
sealed class IDDocumentReaderError(message: String) : Exception(message) {

    /** The phone has no usable NFC reader (or the tag dropped before we opened it). */
    object NfcUnavailable : IDDocumentReaderError(
        "This phone doesn't support NFC reading. The Tap ID document feature needs an NFC-capable phone."
    )

    /** The build / device cannot read ID documents at all. */
    object UnsupportedDevice : IDDocumentReaderError(
        "ID document reading isn't available on this device."
    )

    /** Everything else, with a user-actionable message already baked in. */
    class ReadFailed(message: String) : IDDocumentReaderError(message)
}

// IDDocumentReadParameters and IDDocumentReadResult are the shared model
// types declared in IDDocument.kt. This reader consumes the parameters and
// produces the result; it does not redeclare either type. The model's result
// carries portraitJpeg (the raw DG2 image bytes), the rawChipData map, and the
// parsed IDDocument; the UI decodes the JPEG to a Bitmap when it needs one.

/**
 * The NFC eMRTD reader. Stateless, so a single shared instance is fine; the
 * tag-bound work happens entirely inside [read].
 *
 * [context] is held only to resolve the user-facing failure copy in [read] and
 * [humanMessage]: those run deep inside a coroutine, far from any composable,
 * so a Context is how the strings reach the corpus at all.
 */
class IDDocumentReader(private val context: Context) {

    /**
     * Read the chip behind [isoDep] using the access key derived from
     * [parameters]. Runs on an IO dispatcher because every jMRTD call blocks
     * on the synchronous APDU transceive loop.
     *
     * Tries PACE first when the chip publishes EF.CardAccess, then falls back
     * to BAC. Reads DG1 (MRZ), DG2 (portrait), optional DG11 / DG12, DG15
     * (Active Authentication public key) and the SOD. Lifts the raw bytes of
     * each so a verifier can re-hash the data groups and walk the CMS chain
     * inside the SOD; the Document Signing Certificate lives inside the SOD's
     * SignedData, so storing the SOD is enough.
     */
    suspend fun read(
        isoDep: IsoDep,
        parameters: IDDocumentReadParameters,
        onProgress: (String) -> Unit = {},
    ): IDDocumentReadResult = withContext(Dispatchers.IO) {
        android.util.Log.d("IDDocNfc", "read: isoDep connected=${isoDep.isConnected}, maxTransceive=${isoDep.maxTransceiveLength}")
        if (!isoDep.isConnected) {
            // scuba's open() will connect, but a tag that vanished between
            // discovery and here is the most common "nothing happened" case.
            try {
                isoDep.connect()
                android.util.Log.d("IDDocNfc", "read: isoDep.connect() ok")
            } catch (e: Exception) {
                android.util.Log.w("IDDocNfc", "read: isoDep.connect() failed", e)
                throw IDDocumentReaderError.NfcUnavailable
            }
        }
        // Extended-length APDUs let us pull DG2 (the portrait, often 15 to 25 KB)
        // in far fewer round trips. Generous timeout: passive NFC chips are slow
        // and the user is holding the phone by hand.
        isoDep.timeout = 20_000

        val bacKey = buildBACKey(
            documentNumber = parameters.documentNumber.uppercase(),
            dateOfBirthYYMMDD = parameters.dateOfBirth,
            dateOfExpiryYYMMDD = parameters.dateOfExpiry,
        )

        val cardService = CardService.getInstance(isoDep)
        val service: PassportService
        try {
            cardService.open()
            // 0.7.x constructor: (service, maxTranceiveLengthForSecureMessaging,
            // maxBlockSize, maxTranceiveLengthForPACEProtocol, shouldCheckMAC,
            // isSFIEnabled). NORMAL_MAX_TRANCEIVE_LENGTH + DEFAULT_MAX_BLOCKSIZE
            // are the library's vetted defaults for hand-held reads.
            service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false,
            )
            service.open()
        } catch (e: CardServiceException) {
            throw IDDocumentReaderError.ReadFailed(humanMessage(e))
        } catch (e: Exception) {
            throw IDDocumentReaderError.ReadFailed(
                context.getString(R.string.id_lost_connection_to_document)
            )
        }

        android.util.Log.d("IDDocNfc", "read: PassportService open, attempting access control")
        onProgress("Reading and checking the passport chip on this phone.")
        // PACE first (reads EF.CardAccess at the master file, no applet selection
        // needed). tryPACE returns false when the chip publishes no CardAccess,
        // so we fall through to BAC.
        var paceSucceeded = false
        try {
            paceSucceeded = tryPACE(service, bacKey)
            android.util.Log.d("IDDocNfc", "read: PACE succeeded=$paceSucceeded")
        } catch (e: CardServiceException) {
            android.util.Log.w("IDDocNfc", "read: PACE failed sw=0x%04X".format(e.sw and 0xFFFF), e)
            throw IDDocumentReaderError.ReadFailed(humanMessage(e))
        } catch (e: Exception) {
            android.util.Log.w("IDDocNfc", "read: PACE failed (non-card exception)", e)
            throw IDDocumentReaderError.ReadFailed(
                "Couldn't unlock the document. Double-check the document number, birth date, and expiry date and try again."
            )
        }

        // Select the eMRTD LDS1 applet BEFORE BAC. After PACE this select rides
        // the PACE secure channel; for BAC the mutual-authenticate must run with
        // the applet already selected, otherwise the chip answers 0x6A88 (key /
        // referenced data not found). This ordering matches jMRTD's reference
        // sequence (doPACE -> sendSelectApplet -> doBAC).
        try {
            service.sendSelectApplet(paceSucceeded)
            android.util.Log.d("IDDocNfc", "read: applet selected (pace=$paceSucceeded)")
        } catch (e: CardServiceException) {
            android.util.Log.w("IDDocNfc", "read: select applet failed sw=0x%04X".format(e.sw and 0xFFFF), e)
            throw IDDocumentReaderError.ReadFailed(humanMessage(e))
        }

        if (!paceSucceeded) {
            // BAC is the legacy access protocol. doBAC throws on a wrong key,
            // which we translate into the "re-check your three values" guidance.
            try {
                service.doBAC(bacKey)
                android.util.Log.d("IDDocNfc", "read: BAC ok")
            } catch (e: CardServiceException) {
                android.util.Log.w("IDDocNfc", "read: BAC failed sw=0x%04X".format(e.sw and 0xFFFF), e)
                throw IDDocumentReaderError.ReadFailed(humanMessage(e))
            } catch (e: Exception) {
                android.util.Log.w("IDDocNfc", "read: BAC failed (non-card exception)", e)
                throw IDDocumentReaderError.ReadFailed(
                    "Couldn't unlock the document. Double-check the document number, birth date, and expiry date and try again."
                )
            }
        }

        val rawChipData = LinkedHashMap<String, ByteArray>()

        // ---- SOD ---------------------------------------------------------
        // Read first so a chip that refuses everything after BAC fails fast
        // with a clear status word. We keep only the raw bytes; we never touch
        // jMRTD's lazy document-signing-certificate accessor here (the iOS note
        // about that accessor crashing the NFC thread applies to the AndyQ
        // library, but the principle stands: the issuer-side parser owns CMS
        // extraction, the DSC is embedded in the SOD anyway).
        onProgress("Reading passport data on this phone.")
        val sodFile: SODFile = try {
            val raw = readRaw(service, PassportService.EF_SOD)
            android.util.Log.d("IDDocNfc", "read: SOD ${raw.size} bytes")
            rawChipData["sod"] = raw
            SODFile(raw.inputStream())
        } catch (e: CardServiceException) {
            throw IDDocumentReaderError.ReadFailed(humanMessage(e))
        } catch (e: Exception) {
            throw IDDocumentReaderError.ReadFailed(
                "This document didn't expose a readable security object (SOD). It may not be an ICAO 9303 chip."
            )
        }

        // ---- DG1 (MRZ) ---------------------------------------------------
        onProgress("Reading passport data on this phone.")
        val dg1File: DG1File = try {
            val raw = readRaw(service, PassportService.EF_DG1)
            rawChipData["dg1"] = raw
            DG1File(raw.inputStream())
        } catch (e: CardServiceException) {
            throw IDDocumentReaderError.ReadFailed(humanMessage(e))
        } catch (e: Exception) {
            throw IDDocumentReaderError.ReadFailed(
                "Couldn't read the document's MRZ data group. Hold the phone steady and try again."
            )
        }
        val mrzInfo = dg1File.mrzInfo
        val mrzString = mrzInfo.toString().filterNot { it == '\n' || it == '\r' }

        // ---- DG2 (portrait) ----------------------------------------------
        // Best-effort: some cards omit DG2 or reject the read. The portrait is
        // a nicety, not a hard requirement. We keep the raw encoded image bytes
        // (JPEG / JPEG2000) so the model carries portraitJpeg; the UI decodes
        // them to a Bitmap when it needs to render.
        var portraitJpeg: ByteArray? = null
        onProgress("Reading passport data on this phone.")
        try {
            val raw = readRaw(service, PassportService.EF_DG2)
            rawChipData["dg2"] = raw
            portraitJpeg = extractPortraitBytes(DG2File(raw.inputStream()))
        } catch (_: Exception) {
            // No portrait. Leave portraitJpeg null.
        }

        // ---- DG11 (additional personal details) --------------------------
        var dg11File: DG11File? = null
        onProgress("Reading passport data on this phone.")
        try {
            val raw = readRaw(service, PassportService.EF_DG11)
            rawChipData["dg11"] = raw
            dg11File = DG11File(raw.inputStream())
        } catch (_: Exception) {
            // Most passports leave DG11 absent.
        }

        // ---- DG12 (additional document details) --------------------------
        onProgress("Reading passport data on this phone.")
        try {
            val raw = readRaw(service, PassportService.EF_DG12)
            rawChipData["dg12"] = raw
            // Parsed only to validate; we persist the raw bytes for the issuer.
            DG12File(raw.inputStream())
        } catch (_: Exception) {
            // Many passports leave DG12 absent.
        }

        // ---- DG15 (Active Authentication public key) ---------------------
        var dg15File: DG15File? = null
        onProgress("Reading passport data on this phone.")
        try {
            val raw = readRaw(service, PassportService.EF_DG15)
            rawChipData["dg15"] = raw
            dg15File = DG15File(raw.inputStream())
        } catch (_: Exception) {
            // Not all chips support Active Authentication.
        }

        // ---- Active Authentication ---------------------------------------
        // Challenge the chip to sign 8 random bytes with its DG15-protected
        // private key, then verify the signature against the DG15 public key.
        // Mirrors what the iOS library did automatically; we keep both the
        // challenge and the signature (hex) so the issuer can replay the check.
        var aaChallengeHex: String? = null
        var aaSignatureHex: String? = null
        var aaVerified: Boolean? = null
        if (dg15File != null) {
            onProgress("Reading and checking the passport chip on this phone.")
            try {
                val challenge = ByteArray(8).also { SecureRandom().nextBytes(it) }
                val response = service.doAA(
                    dg15File.publicKey,
                    sodFile.digestAlgorithm,
                    sodFile.signerInfoDigestAlgorithm,
                    challenge,
                )
                val signature = response.response
                aaChallengeHex = hex(challenge)
                aaSignatureHex = hex(signature)
                aaVerified = true
            } catch (_: Exception) {
                // AA failed or is unsupported. If we got far enough to record a
                // challenge / signature, mark the local check as failed so the
                // UI can warn; otherwise leave everything null.
                if (aaChallengeHex != null && aaSignatureHex != null) {
                    aaVerified = false
                }
            }
        }

        // ---- Bio data ----------------------------------------------------
        // Pull the Latin (romanized) name from the MRZ regardless of whether
        // DG11 also provided a native-script name: the MRZ form is the
        // canonical pinyin / transliteration that travel infrastructure uses.
        val latin = parseMRZName(mrzString)
        val nativeFullName = dg11File?.nameOfHolder?.takeIf { it.isNotBlank() }

        // jMRTD's primary/secondary identifiers are the MRZ name halves. For
        // documents that expose a native-script name in DG11 we still report
        // the MRZ name in surname/givenNames (iOS preserved both forms; the
        // native form lives in nativeFullName).
        val surname = mrzInfo.primaryIdentifier?.replace("<", " ")?.trim().orEmpty()
        val givenNames = mrzInfo.secondaryIdentifier?.replace("<", " ")?.trim().orEmpty()

        val document = IDDocument(
            nickname = null,
            surname = surname,
            givenNames = givenNames,
            documentNumber = mrzInfo.documentNumber?.replace("<", "")?.trim().orEmpty(),
            nationality = mrzInfo.nationality.orEmpty(),
            issuingAuthority = mrzInfo.issuingState.orEmpty(),
            sex = mrzInfo.gender?.toString()?.take(1),
            dateOfBirth = mrzInfo.dateOfBirth.orEmpty(),
            dateOfExpiry = mrzInfo.dateOfExpiry.orEmpty(),
            documentType = mrzInfo.documentCode?.trim().orEmpty(),
            latinSurname = latin?.surname,
            latinGivenNames = latin?.givenNames,
            nativeFullName = nativeFullName,
            // Chip is authoritative for passports (ADR-0037): an MRZ document code
            // starting with "P" is saved as a Passport regardless of the pre-scan
            // picker, which fixes a passport being mis-saved as "Other ID document".
            userDeclaredKind = if (mrzInfo.documentCode?.trim()?.take(1)?.uppercase() == "P") {
                IDDocumentKind.PASSPORT
            } else {
                parameters.declaredKind
            },
            personalNumber = dg11File?.personalNumber?.takeIf { it.isNotBlank() }
                ?: mrzInfo.optionalData1?.replace("<", "")?.trim()?.takeIf { it.isNotEmpty() },
            placeOfBirth = dg11File?.placeOfBirth?.joinToString("<")?.takeIf { it.isNotBlank() },
            // Raw chip blobs travel on the read result's rawChipData map; the
            // store seals them and stamps the model's sod/dg* ByteArray fields.
            sod = rawChipData["sod"],
            dg1 = rawChipData["dg1"],
            dg2 = rawChipData["dg2"],
            dg11 = rawChipData["dg11"],
            dg12 = rawChipData["dg12"],
            dg15 = rawChipData["dg15"],
            portraitJpeg = portraitJpeg,
            activeAuthChallengeHex = aaChallengeHex,
            activeAuthSignatureHex = aaSignatureHex,
            activeAuthVerifiedLocally = aaVerified,
            readAt = System.currentTimeMillis(),
        )

        onProgress("Passport read successfully")
        android.util.Log.d(
            "IDDocNfc",
            "read: complete name='${document.surname} ${document.givenNames}' dgs=${rawChipData.keys.joinToString()} portrait=${portraitJpeg != null}",
        )
        IDDocumentReadResult(
            document = document,
            rawChipData = rawChipData,
            portraitJpeg = portraitJpeg,
        )
    }

    // MARK: -- raw data group lifting

    /**
     * Read the full raw contents of a data group / EF as a byte array. jMRTD's
     * getInputStream returns a length-prefixed CardFileInputStream we drain
     * fully so the persistence layer and any verifier see the exact on-chip
     * bytes (the SOD hashes the raw DG, so re-encoding would break the chain).
     */
    private fun readRaw(service: PassportService, fid: Short): ByteArray {
        val input: InputStream = service.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE)
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    /**
     * Extract the raw encoded portrait bytes from the DG2 facial-image record.
     * jMRTD exposes the encoded image (typically JPEG, sometimes JPEG2000) via
     * the face info; we return those bytes unchanged so the model carries the
     * exact on-chip portrait. The Compose layer decodes them to a Bitmap (and
     * is responsible for handling encodings the platform decoder can't open,
     * e.g. older Android lacking JPEG2000). Returns null if DG2 has no image.
     */
    private fun extractPortraitBytes(dg2: DG2File): ByteArray? {
        val faceInfo = dg2.faceInfos.firstOrNull() ?: return null
        val faceImageInfo = faceInfo.faceImageInfos.firstOrNull() ?: return null
        val length = faceImageInfo.imageLength
        if (length <= 0) return null
        val bytes = ByteArray(length)
        val stream = faceImageInfo.imageInputStream
        var off = 0
        while (off < length) {
            val n = stream.read(bytes, off, length - off)
            if (n < 0) break
            off += n
        }
        return if (off == length) bytes else bytes.copyOf(off)
    }

    // MARK: -- PACE / BAC

    /**
     * Attempt PACE when the chip publishes EF.CardAccess. Returns true on a
     * successful PACE run, false if the chip has no CardAccess (so the caller
     * should fall back to BAC). Throws on a genuine PACE failure (wrong key)
     * so the caller can surface the "re-check your details" message.
     */
    private fun tryPACE(service: PassportService, bacKey: BACKey): Boolean {
        val cardAccess: CardAccessFile = try {
            val raw = readRaw(service, PassportService.EF_CARD_ACCESS)
            CardAccessFile(raw.inputStream())
        } catch (_: Exception) {
            // No EF.CardAccess: this chip is BAC-only.
            return false
        }
        val paceInfo = cardAccess.securityInfos
            .filterIsInstance<PACEInfo>()
            .firstOrNull() ?: return false

        val keySpec = PACEKeySpec.createMRZKey(bacKey)
        service.doPACE(
            keySpec,
            paceInfo.objectIdentifier,
            PACEInfo.toParameterSpec(paceInfo.parameterId),
            paceInfo.parameterId,
        )
        return true
    }

    // MARK: -- MRZ helpers

    companion object {

        /**
         * Build the BAC / PACE access key from the three printed fields. jMRTD's
         * BACKey computes the ICAO 9303 check digits and padding internally, so
         * unlike the iOS path we hand it the raw fields rather than a
         * pre-concatenated MRZ key string. PACEKeySpec.createMRZKey reuses the
         * same BACKey for the PACE password.
         */
        fun buildBACKey(
            documentNumber: String,
            dateOfBirthYYMMDD: String,
            dateOfExpiryYYMMDD: String,
        ): BACKey = BACKey(
            documentNumber,
            dateOfBirthYYMMDD,
            dateOfExpiryYYMMDD,
        )

        /**
         * Build the canonical concatenated ICAO 9303 MRZ access key string
         * (padded fields plus their per-field 7-3-1 check digits). Kept as a
         * standalone helper for callers / tests that want the raw key the chip
         * sees; the live read path uses [buildBACKey] and lets jMRTD do this
         * internally.
         */
        fun buildMRZKey(
            documentNumber: String,
            dateOfBirthYYMMDD: String,
            dateOfExpiryYYMMDD: String,
        ): String {
            val docNo = padMRZ(documentNumber.uppercase(), 9)
            val dob = padMRZ(dateOfBirthYYMMDD, 6)
            val exp = padMRZ(dateOfExpiryYYMMDD, 6)
            return "$docNo${mrzCheck(docNo)}$dob${mrzCheck(dob)}$exp${mrzCheck(exp)}"
        }

        private fun padMRZ(value: String, fieldLength: Int): String {
            val padded = value + "<".repeat(fieldLength)
            return padded.substring(0, fieldLength)
        }

        /**
         * ICAO 9303 7-3-1 weighted check digit. Digits / "<" / space map to
         * their numeric values; "A".."Z" map to 10..35.
         */
        private fun mrzCheck(s: String): Int {
            val weights = intArrayOf(7, 3, 1)
            var sum = 0
            for ((i, ch) in s.withIndex()) {
                val value: Int = when {
                    ch in '0'..'9' -> ch - '0'
                    ch == '<' || ch == ' ' -> 0
                    ch in 'A'..'Z' -> ch.code - 55 // A=10, B=11, ... Z=35
                    else -> return 0
                }
                sum += value * weights[i % 3]
            }
            return sum % 10
        }

        /**
         * Extract the Latin (ASCII) name from a raw MRZ string. ICAO 9303 puts
         * the name into a fixed slot:
         *   - TD3 (passport, 2 lines x 44): line 1, positions 5..43.
         *   - TD2 (ID card variant, 2 lines x 36): line 1, positions 5..35.
         *   - TD1 (ID card, 3 lines x 30): line 3, full 30 chars.
         * Inside the name slot, "<<" separates surname from given names and "<"
         * is the filler used in place of spaces. Returns null if the MRZ isn't
         * recognizable.
         *
         * This is the canonical pinyin / romanized form of the holder's name.
         * jMRTD's primary/secondary identifiers track the MRZ too, but parsing
         * here keeps the Latin form independent of any DG11 native-script
         * override and matches the iOS behavior byte for byte.
         */
        fun parseMRZName(mrz: String): MRZName? {
            val cleaned = mrz.filterNot { it == '\n' || it == '\r' }
            val nameField: String = when (cleaned.length) {
                88 -> cleaned.take(44).drop(5)       // TD3 (passport): 2 x 44
                72 -> cleaned.take(36).drop(5)       // TD2: 2 x 36
                90 -> cleaned.takeLast(30)           // TD1: 3 x 30, name on line 3
                else -> return null
            }
            val parts = nameField.split("<<")
            if (parts.size < 2) return null
            val surname = parts[0].replace("<", " ").trim()
            val givens = parts.drop(1).joinToString(" ").replace("<", " ").trim()
            if (surname.isEmpty() && givens.isEmpty()) return null
            return MRZName(surname = surname, givenNames = givens)
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }

    /** Parsed Latin name halves from the MRZ. */
    data class MRZName(val surname: String, val givenNames: String)

    // MARK: -- error mapping

    /**
     * Translate a scuba CardServiceException into something the user can act
     * on. jMRTD surfaces the chip's APDU status word in the exception's status
     * word (sw) field; we decode the common ones the same way iOS did.
     *
     *   0x6A88 / 0x6A82 = referenced data / file not found (chip lacks the DG).
     *   0x63xx / 0x6982 = BAC/PACE auth failed (wrong key).
     *   0x6984          = card locked.
     *   0x67xx          = wrong APDU length (non-standard chip).
     */
    private fun humanMessage(e: CardServiceException): String {
        val sw = e.sw and 0xFFFF
        if (sw == 0) {
            // No status word: the channel dropped or the host refused the APDU.
            return context.getString(R.string.id_lost_connection_to_document)
        }
        val sw1 = (sw shr 8) and 0xFF
        val sw2 = sw and 0xFF
        val swHex = "0x%04X".format(sw)
        return when {
            (sw1 == 0x6A && sw2 == 0x88) || (sw1 == 0x6A && sw2 == 0x82) ->
                context.getString(R.string.id_chip_no_icao_data_groups, swHex)
            (sw1 == 0x63) || (sw1 == 0x69 && sw2 == 0x82) ->
                context.getString(R.string.id_chip_rejected_mrz_key, swHex)
            (sw1 == 0x69 && sw2 == 0x84) ->
                context.getString(R.string.id_chip_card_locked, swHex)
            (sw1 == 0x67) ->
                context.getString(R.string.id_chip_wrong_apdu_length, swHex)
            else ->
                context.getString(
                    R.string.id_chip_returned_sw,
                    swHex,
                    e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName,
                )
        }
    }
}
