// BiometricPrompt confirmation, the Android analog of the iOS Face-ID gate
// (KeyStore .userPresence). Sensitive identity operations -- revealing the
// recovery phrase, signing with the master -- are gated through this before
// the SDK unwraps the StrongBox-sealed material. Class-3 (strong) biometric
// with device-credential fallback.

package com.elabify.app.maknoon.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object BiometricGate {

    enum class Availability { AVAILABLE, NONE_ENROLLED, UNAVAILABLE }

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun availability(activity: FragmentActivity): Availability =
        when (BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> Availability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Availability.NONE_ENROLLED
            else -> Availability.UNAVAILABLE
        }

    /** Show the prompt; resume true on success, false on error/cancel. */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onAuthenticationFailed() {
                    // A single mismatch; the prompt stays up for a retry.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        // Cancel the system prompt if the caller's coroutine is cancelled (e.g.
        // the screen is disposed) so it doesn't linger.
        cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        // authenticate() can throw if the host activity's FragmentManager has
        // already saved its state (a lifecycle transition). Resume false instead
        // of letting the exception escape and strand the caller forever (that was
        // the frozen "Unlock" button: the caller's `attempting` flag never reset).
        try {
            prompt.authenticate(info)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }
}
