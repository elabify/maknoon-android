// App-level auto-lock, the Android analog of the iOS AutoLockManager. Tracks the
// last user interaction; after Settings > Display > Auto-Lock of inactivity (or
// when returning from the background past that window) it raises a lock gate
// that MaknoonRoot shows over everything, requiring biometric to dismiss. NEVER
// disables it. Process-wide observable state so the gate appears/clears live.

package com.elabify.app.maknoon.ui

import androidx.compose.runtime.mutableStateOf
import com.elabify.app.maknoon.ui.theme.DisplayPreferences
import com.elabify.musnad.identity.IdentitySession

object AppLockManager {
    private val lockedState = mutableStateOf(false)
    val locked: Boolean get() = lockedState.value

    private var lastActivityMs = System.currentTimeMillis()

    /** Any user interaction resets the inactivity timer. */
    fun recordActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun lockNow() {
        lockedState.value = true
        // Drop the cached decrypted identity so a locked device holds none in
        // memory; it is re-derived on the next unlock + Identity-tab load.
        IdentitySession.clear()
    }

    fun unlock() {
        lockedState.value = false
        lastActivityMs = System.currentTimeMillis()
    }

    /** Lock if inactive beyond the configured timeout. NEVER (null) never locks. */
    fun lockIfTimedOut() {
        if (locked) return
        val secs = DisplayPreferences.autoLock.seconds ?: return
        if (System.currentTimeMillis() - lastActivityMs >= secs * 1000L) lockNow()
    }
}
