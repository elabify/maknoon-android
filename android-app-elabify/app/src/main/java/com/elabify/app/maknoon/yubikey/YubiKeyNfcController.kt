// Owns the NFC radio for YubiKey taps, the same single-owner discipline the
// passport reader uses (IDDocumentNfcReaderMode). yubikit-android's
// NfcYubiKeyManager drives NfcAdapter reader-mode under the hood, exactly like
// the passport path; the hard rule is that only ONE of them owns the radio at
// a time. So we enable YubiKey discovery only while a YubiKey screen is on
// screen and disable it the moment that screen leaves (DisposableEffect), the
// way IdentityScreen scopes IDDocumentNfcReaderMode to the scanning step.
//
// yubikit is GMS-free: NfcYubiKeyManager talks to android.nfc.NfcAdapter
// directly, no Play services. This keeps the GrapheneOS / checkNoGms
// constraint satisfied.

package com.elabify.app.maknoon.yubikey

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import com.yubico.yubikit.android.transport.nfc.NfcYubiKeyDevice
import com.yubico.yubikit.core.YubiKeyDevice
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Foreground NFC discovery for YubiKey taps, scoped to one [Activity] and one
 * screen. Mirrors IDDocumentNfcReaderMode.start/stop. Construct it in the
 * enroll Composable, [start] it when the screen appears, [stop] it on dispose.
 */
class YubiKeyNfcController(private val activity: Activity) {

    private val executor = Executors.newSingleThreadExecutor()
    private var running = false

    /**
     * Begin listening for NFC YubiKey taps. [onDevice] fires on the NFC
     * dispatch thread each time a key is tapped; hop to a coroutine before
     * doing CTAP2 work. Returns false if NFC is unavailable / disabled so the
     * UI can fall back to a "USB-C only" message. Safe to call twice (no-op if
     * already running).
     *
     * The passport reader MUST be stopped before this is called: both use
     * NfcAdapter reader-mode and the second enable() silently wins, leaving
     * the first owner deaf. We never start this while a passport scan screen
     * is up (separate screens, separate lifecycles).
     */
    fun start(onDevice: (YubiKeyDevice) -> Unit): Boolean {
        if (running) return true
        // Default NfcConfiguration: no sound, no NDEF read; we only want raw
        // ISO-DEP CTAP2 / smart-card access, like the passport's
        // FLAG_READER_SKIP_NDEF_CHECK.
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return false
        // Use our OWN proven reader-mode config (the passport's), not yubikit's
        // NfcYubiKeyManager: on GrapheneOS yubikit armed flags=3 (NFC-A|B) and
        // the OS never reported a tag. The passport detects fine with NFC-A|B +
        // SKIP_NDEF + a presence-check delay, so reuse exactly that and hand the
        // raw Tag to yubikit's NfcYubiKeyDevice for the CTAP2 session.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5_000)
        }
        return try {
            adapter.enableReaderMode(
                activity,
                { tag: Tag ->
                    android.util.Log.d("YubiKeyNfc", "tag discovered tech=${tag.techList.joinToString()}")
                    onDevice(NfcYubiKeyDevice(tag, TAG_TIMEOUT_MS, executor))
                },
                flags,
                extras,
            )
            running = true
            android.util.Log.d("YubiKeyNfc", "reader mode armed (own path, flags=$flags)")
            true
        } catch (e: Throwable) {
            android.util.Log.w("YubiKeyNfc", "enableReaderMode threw ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    /** Release the NFC radio. Idempotent; always called on screen dispose so
     *  the passport reader (or anything else) can reclaim it. */
    fun stop() {
        if (!running) return
        runCatching { NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity) }
        running = false
    }

    companion object {
        /** IsoDep transceive timeout for the CTAP2 session. Generous: a
         *  makeCredential + getAssertion (with PIN) round-trip over NFC on one
         *  tap is slow, and the passport reader proved 20s is safe. */
        private const val TAG_TIMEOUT_MS = 20_000

        /**
         * Suspend until the user taps a YubiKey, then return the
         * [YubiKeyDevice]. Bridges yubikit's callback to a coroutine the same
         * way IdentityScreen.awaitIsoDepTag bridges the passport reader.
         * Cancellation (back / dismiss) releases the radio.
         *
         * Note: a fresh [YubiKeyDevice] is delivered per tap, so the caller
         * must finish all CTAP2 work for one operation within a single tap.
         * For enroll that means makeCredential + getAssertion both run on the
         * same tapped device before this resumes.
         */
        suspend fun awaitTap(controller: YubiKeyNfcController): YubiKeyDevice =
            suspendCancellableCoroutine { cont ->
                val started = controller.start { device ->
                    if (cont.isActive) cont.resume(device)
                }
                if (!started && cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException(
                            "NFC is off or unavailable. Turn on NFC, or use a USB-C YubiKey path.",
                        ),
                    )
                }
                cont.invokeOnCancellation { controller.stop() }
            }
    }
}
