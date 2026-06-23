// Bridges the Rust PairingCodeProvider callback to a host-supplied
// prompt. Ported from iOS Maknoon/HardwareWallet/TrezorPairing.swift.
//
// During CodeEntry pairing the Trezor shows a 6-digit code on its
// screen and the Rust pairing flow calls requestCode() to get it. The
// coordinator suspends until the UI layer calls submit(code); an empty
// code tells Rust to abort. The Compose/Android UI observes
// `awaitingCode` and drives submit/cancel, exactly as the SwiftUI
// sheet does on iOS.

package com.elabify.musnad.hardware.trezor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uniffi.trezor_core.PairingCodeProvider

class TrezorPairingCoordinator : PairingCodeProvider {

    private val _awaitingCode = MutableStateFlow(false)
    /** True while the device is showing its code and the UI must prompt. */
    val awaitingCode: StateFlow<Boolean> = _awaitingCode

    private val lock = Any()
    private var pending: CompletableDeferred<String>? = null

    /**
     * Called by the Rust pairing flow (off the main thread) when the
     * device displays its code. Suspends until the user submits.
     */
    override suspend fun requestCode(): String {
        android.util.Log.d("TrezorBLE", "pairing: requestCode (device showing code, awaiting user submit)")
        val deferred = CompletableDeferred<String>()
        synchronized(lock) { pending = deferred }
        _awaitingCode.value = true
        val code = deferred.await()
        android.util.Log.d("TrezorBLE", "pairing: requestCode resumed with code len=${code.length}")
        return code
    }

    /** Resume pairing with the entered code. */
    fun submit(code: String) {
        android.util.Log.d("TrezorBLE", "pairing: submit code len=${code.trim().length}")
        _awaitingCode.value = false
        val deferred = synchronized(lock) {
            val d = pending
            pending = null
            d
        }
        deferred?.complete(code.trim())
    }

    /** User dismissed the prompt; an empty code tells Rust to abort. */
    fun cancel() = submit("")
}
