package com.elabify.maknoon

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Public configuration for the embeddable [MaknoonSDK].
 *
 * This is the Maknoon-branded umbrella config (the SDK a host embeds to get Musnad
 * identity AND the Maknoon self-custody wallet). See the wire-format spec
 *
 * Registry addresses and the chain RPC are optional: when omitted the SDK prefers values
 * discovered from the issuer well-known doc and the verifier `/v1/info` (ADR-0054), falling
 * back to bundled defaults. A host RPC override is honored when provided.
 */
data class MaknoonConfig(
    val mode: Mode,
    /** Issuer pickup/presentation hosts allowed for deep-link validation. Must be non-empty. */
    val allowedIssuerHosts: Set<String>,
    /** host -> set of RPO-256 fingerprints of the cert public key. MUST be non-empty per host. */
    val pinnedFingerprintsByHost: Map<String, Set<ByteArray>>,
    /** Optional host RPC override for on-chain status reads; else discovered/default. */
    val chainRpcUrl: String? = null,
    /** Optional; else discovered from the issuer well-known doc / verifier info. */
    val identityRegistryAddress: String? = null,
    /** Optional; else discovered. */
    val revocationRegistryAddress: String? = null,
    val defaultDelegationLifetime: Duration = 24.hours,
    val logSink: MaknoonLogSink = MaknoonLogSink { },
) {
    enum class Mode { TREZOR_BACKED, SOFTWARE_ONLY }
}

/**
 * Host-suppliable log sink. The SDK guarantees no log line ever carries plaintext claim
 * values, key bytes, or DIDs (DIDs are pseudonyms but still correlatable, so they are
 * redacted). Hosts may forward these to their own observability stack.
 */
fun interface MaknoonLogSink {
    fun log(event: MaknoonLogEvent)
}

data class MaknoonLogEvent(
    val level: Level,
    val category: Category,
    /** PII-safe message. */
    val message: String,
    /** PII-safe structured context. */
    val context: Map<String, String> = emptyMap(),
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }
    enum class Category { IDENTITY, CREDENTIALS, ISSUANCE, PRESENTATION, CHAIN, WALLET, HARDWARE }
}

/** Typed errors the facade may throw. Kotlin peer of the Swift `MaknoonError`. */
sealed class MaknoonError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The user denied a device or biometric approval. */
    class UserCancelled(message: String = "User cancelled") : MaknoonError(message)
    /** Track B requested but the Trezor firmware lacks FIPS-204 support. */
    class TrezorFirmwareUnavailable : MaknoonError("Trezor FIPS-204 firmware unavailable")
    /** A configured pin did not match the negotiated certificate (fail-closed). */
    class PinningFailure(host: String) : MaknoonError("Certificate pinning failed for $host")
    /** The deep link / request failed validation before any crypto ran. */
    class InvalidRequest(message: String) : MaknoonError(message)
    /** No credential in the wallet satisfied the requested claims/filters. */
    class NoMatchingCredential : MaknoonError("No matching credential")
    /** A network or backend call failed. */
    class Network(message: String, cause: Throwable? = null) : MaknoonError(message, cause)
    /** A configuration invariant was violated (e.g. empty pin set). */
    class Configuration(message: String) : MaknoonError(message)

    /**
     * The issuer has accepted the request but has not finished minting yet.
     *
     * Not a failure. A holder polls a pickup URL until it goes ready, so this
     * is the ordinary answer for most of that window, and a caller must be
     * able to tell it apart from a real fault: "still minting" means poll
     * again, while a genuine [Network] error means back off or surface
     * something to the user.
     *
     * Folding it into [Network] made both look the same to a poll loop, which
     * is how the app's own pending-pickup store would have mistaken every
     * not-ready answer for a transient outage.
     *
     * @param estimatedReadyAtSec issuer's own estimate, when it supplies one.
     */
    class IssuanceNotReady(val estimatedReadyAtSec: Long? = null) :
        MaknoonError("Issuance is not ready yet")
}
