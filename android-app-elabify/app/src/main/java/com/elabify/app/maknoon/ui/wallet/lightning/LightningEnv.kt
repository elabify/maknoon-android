// Shared engine wiring + formatting helpers for the Lightning wallet UI. This
// is the single place the Compose screens reach into the SDK engine layer
// (com.elabify.musnad.wallet.lightning.*): it constructs the
// LightningAccountStore over SharedPreferences and builds an LndHubClient for
// the active account.
//
// Mirrors the iOS HolderStore.lightningAccountStore plumbing the Lightning
// SwiftUI views read from. Everything LndHub/LNURL is blocking network I/O;
// callers wrap in withContext(Dispatchers.IO).

package com.elabify.app.maknoon.ui.wallet.lightning

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.elabify.musnad.wallet.lightning.LightningAccount
import com.elabify.musnad.wallet.lightning.LightningAccountStore
import com.elabify.musnad.wallet.lightning.LndHubClient
import java.net.URI

/** Lazily-built bundle of the Lightning store, one per app session. The store
 *  reads/writes its own `lightning.store.v1` prefs file (the iOS UserDefaults
 *  analog), with passwords sealed in AndroidSecureStore. */
internal class LightningEnv private constructor(
    val accountStore: LightningAccountStore,
) {
    val activeAccount: LightningAccount? get() = accountStore.activeAccount

    /** Build a live LndHubClient for an account, resolving its sealed password.
     *  Null when the account has no stored password (re-import needed). */
    fun clientFor(account: LightningAccount): LndHubClient? {
        val pw = accountStore.password(account.id) ?: return null
        return LndHubClient(account, pw)
    }

    companion object {
        @Volatile private var instance: LightningEnv? = null

        fun get(context: Context): LightningEnv {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: LightningEnv(LightningAccountStore(context.applicationContext))
                    .also { instance = it }
            }
        }
    }
}

// MARK: -- formatting helpers (mirror the iOS sat / host display helpers)

/** Group a satoshi count with thousands separators: 1234567 -> "1,234,567". */
internal fun formatSats(sats: Long): String {
    val negative = sats < 0
    val digits = (if (negative) -sats else sats).toString()
    val sb = StringBuilder()
    val rem = digits.length % 3
    for (i in digits.indices) {
        if (i != 0 && (i - rem) % 3 == 0) sb.append(',')
        sb.append(digits[i])
    }
    return if (negative) "-$sb" else sb.toString()
}

/** The host portion of a server URL, for compact account subtitles. Falls back
 *  to the raw string when the URL is unparseable (mirrors iOS URL(string:)?.host). */
internal fun hostOf(serverURL: String): String =
    runCatching { URI(serverURL).host }.getOrNull() ?: serverURL

/** "username · host" subtitle used in the account picker + rows. */
internal fun accountSubtitle(a: LightningAccount): String {
    val parts = arrayListOf("${a.username}@${hostOf(a.serverURL)}")
    if (a.allowInsecureTLS) parts.add("insecure TLS")
    return parts.joinToString(" · ")
}

internal fun copyToClipboard(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

internal fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
}

/** Lightweight LUD-16 Lightning Address shape check (user@domain.tld). */
internal fun isLightningAddress(s: String): Boolean {
    val t = s.trim()
    if (!t.contains("@") || t.contains(" ")) return false
    val parts = t.split("@", limit = 2)
    return parts.size == 2 && parts[0].isNotEmpty() && parts[1].contains(".")
}

/** True if the pasted string plausibly pays (BOLT11 / LNURL / Lightning Address). */
internal fun isPlausiblePayable(s: String): Boolean {
    val lower = s.lowercase()
    return lower.startsWith("lnbc") ||
        lower.startsWith("lntb") ||
        lower.startsWith("lnurl") ||
        lower.startsWith("lightning:") ||
        isLightningAddress(s)
}
