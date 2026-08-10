// Per-send biometric authorization, the Android analog of the iOS
// "Authorize <chain> send" Face/Touch ID gate. On iOS the signing key is
// accessed with user presence before each broadcast (Secure Enclave); the
// software wallets on Android decrypt their seed without an OS-level presence
// check, so the send path must gate explicitly here to match. Fail-closed: a
// cancelled or failed prompt returns false and the caller MUST abort signing.

package com.elabify.app.maknoon.ui.wallet.common

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.ui.BiometricGate

/**
 * Prompt for biometric / device-credential confirmation before signing and
 * broadcasting a send on [chainDisplayName]. Returns true only when the user
 * authenticated. If there is no FragmentActivity to host the prompt (which does
 * not happen in the single-activity app) it returns true so a misconfiguration
 * cannot silently block all sends.
 */
suspend fun authorizeSend(context: Context, chainDisplayName: String): Boolean {
    val activity = context as? FragmentActivity ?: return true
    return BiometricGate.authenticate(
        activity,
        title = context.getString(R.string.walletc_authorize_send_title, chainDisplayName),
        subtitle = context.getString(R.string.walletc_authorize_send_subtitle),
    )
}

/**
 * Prompt for biometric / device-credential confirmation before signing a MESSAGE
 * on [chainDisplayName]. Message signing neither sends nor broadcasts, so the copy
 * must NOT mention send/broadcast (that is [authorizeSend]'s job). Same fail-open
 * behavior when there is no FragmentActivity to host the prompt.
 */
suspend fun authorizeSignature(context: Context, chainDisplayName: String): Boolean {
    val activity = context as? FragmentActivity ?: return true
    return BiometricGate.authenticate(
        activity,
        title = context.getString(R.string.walletc_authorize_signature_title, chainDisplayName),
        subtitle = context.getString(R.string.walletc_authorize_signature_subtitle),
    )
}
