// Turns a thrown exception into something a holder can read, in their language.
//
// The app's habit was `catch (e: Exception) { error = e.message ?: fallback }`.
// The elvis is backwards for anything user-facing: the localized fallback only
// renders when the exception has NO message, and platform exceptions almost
// always have one. So the English always won and the translated string was
// effectively dead code, in all 31 locales.
//
// What that looked like on a phone set to Arabic:
//
//   Unable to resolve host "musnad-issuer.elabify.com": No address associated
//   with hostname
//
// wrapped in an Arabic sentence and laid out right-to-left, so it read as
// scrambled. That text is Android's own UnknownHostException message from
// libcore. The platform ships it English-only and we cannot translate it. But
// we should never have shown it: a device that is offline, on a captive
// portal, or pointed at a host that is briefly down is an EVERYDAY condition,
// not a rare edge, and the raw message tells a holder nothing they can act on.
//
// So the common transport failures get our own copy, and the raw text is kept
// only for the genuinely unrecognized case, where showing something beats
// showing nothing.
//
// Deliberately NOT localized: the raw text in the final branch, and the host
// name interpolated into the messages. Both are diagnostics.

package com.elabify.app.maknoon.ui.common

import android.content.Context
import com.elabify.app.maknoon.R
import com.elabify.musnad.net.NetworkException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Marks an exception whose `message` is ALREADY localized, so [userMessage]
 * may show it as-is.
 *
 * This has to be opt-in rather than assumed. Our own exception types are split:
 * IDDocumentIssuanceException resolves its text from a @StringRes, while
 * SanctionsScreeningException and CommerceTransportException were English
 * literals until they were converted. Preferring `message` whenever an
 * exception is "ours" would have kept shipping English from the two that were
 * not, which is the bug this whole file exists to end.
 */
interface LocalizedThrowable

/**
 * Localized, actionable copy for [this] failure.
 *
 * Resolution order, and the order is the point:
 *   1. a known transport failure -> our own copy for that condition
 *   2. a [LocalizedThrowable] -> its message, which is already translated
 *   3. [fallback] -> the caller's specific copy for this operation
 *   4. the raw message -> only now, as the genuine edge case
 *   5. a generic sentence
 *
 * Step 4 sits BELOW the fallback deliberately. The old idiom,
 * `e.message ?: fallback`, had it first, so the localized string only rendered
 * when an exception carried no message at all, which platform exceptions almost
 * never lack. The translation was unreachable in practice.
 *
 * @param host server the call was aimed at, so we can say WHICH thing is
 *   unreachable. A hostname is a proper noun and substitutes safely.
 * @param fallback the caller's own localized copy for this operation, which is
 *   more useful than a generic sentence because it names what was being done.
 */
fun Throwable.userMessage(
    context: Context,
    host: String? = null,
    fallback: String? = null,
): String {
    // A hostname is a proper noun and substitutes into a sentence safely. A
    // stand-in common noun does not, so when there is no host we pick a
    // different SENTENCE rather than interpolating "the server" into this one.
    // See the note beside these strings: two translation batches independently
    // hit the grammar problem that pattern creates.
    val h = host?.takeIf { it.isNotBlank() }
    fun pick(withHost: Int, generic: Int): String =
        if (h != null) context.getString(withHost, h) else context.getString(generic)

    return when (this) {
        // No DNS answer. Overwhelmingly "the device has no working network"
        // rather than "this host does not exist", so lead with the connection.
        is UnknownHostException -> context.getString(R.string.err_no_connection)
        is ConnectException ->
            pick(R.string.err_server_unreachable, R.string.err_server_unreachable_generic)
        is SocketTimeoutException -> context.getString(R.string.err_timed_out)
        is SSLException ->
            pick(R.string.err_secure_connection, R.string.err_secure_connection_generic)
        // The server answered, so the network is fine and the fault is remote.
        is NetworkException ->
            pick(R.string.err_server_problem, R.string.err_server_problem_generic)
        // Any other transport failure (socket reset, abrupt close, no route).
        is IOException ->
            pick(R.string.err_server_unreachable, R.string.err_server_unreachable_generic)
        // Ours, and already translated.
        is LocalizedThrowable -> message?.takeIf { it.isNotBlank() }
            ?: fallback ?: context.getString(R.string.err_unexpected)
        else -> fallback
            // The true edge case: an exception we do not recognize, for which
            // the caller offered no copy. Raw text beats saying nothing.
            ?: message?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.err_unexpected)
    }
}

/** Host of a URL, for [userMessage]. Null when it cannot be parsed. */
fun hostOf(url: String?): String? = url?.let {
    runCatching { java.net.URI(it).host }.getOrNull()
}
