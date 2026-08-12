package com.elabify.maknoon.impl

import android.content.Context
import android.net.Uri
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MaknoonHardware
import com.elabify.maknoon.MaknoonSDK
import com.elabify.maknoon.MaknoonWallet
import com.elabify.maknoon.MusnadChainClient
import com.elabify.maknoon.MusnadCredentials
import com.elabify.maknoon.MusnadDeepLink
import com.elabify.maknoon.MusnadIdentity
import com.elabify.maknoon.MusnadIssuance
import com.elabify.maknoon.MusnadPresentation
import com.elabify.musnad.present.VerifierRequest

/**
 * Concrete [MaknoonSDK]. Lazily assembles the facade impls over the shared app context +
 * config. The five identity-side facades are fully wired; [wallet] and [hardware] are the
 * pending impls until the wallet+hardware migration pass (they take ownership of wallet
 * persistence and device flows the reference app currently owns).
 */
internal class MaknoonSdkImpl(
    private val appContext: Context,
    private val config: MaknoonConfig,
) : MaknoonSDK {

    override val identity: MusnadIdentity by lazy { MusnadIdentityImpl(appContext, config) }
    override val credentials: MusnadCredentials by lazy { MusnadCredentialsImpl(appContext, config) }
    override val issuance: MusnadIssuance by lazy { MusnadIssuanceImpl(appContext, config) }
    override val presentation: MusnadPresentation by lazy { MusnadPresentationImpl(appContext, config) }
    override val chain: MusnadChainClient by lazy { MusnadChainClientImpl(config) }
    override val wallet: MaknoonWallet by lazy { MaknoonWalletImpl(appContext) }
    override val hardware: MaknoonHardware by lazy { MaknoonHardwareImpl(appContext) }

    override fun classify(uri: Uri): MusnadDeepLink {
        // A verifier request is a self-describing JSON payload; issuance pickups are links to
        // an allowed issuer host. Validation happens before any crypto runs.
        if (VerifierRequest.parse(uri.toString()) != null) {
            return MusnadDeepLink.PresentationRequest(uri)
        }
        val host = uri.host
        if (host != null && config.allowedIssuerHosts.any { it.equals(host, ignoreCase = true) }) {
            return MusnadDeepLink.IssuancePickup(uri)
        }
        return MusnadDeepLink.Unknown
    }
}

/** Builds + validates a [MaknoonSDK]. Referenced by the public `MaknoonSDK.init` factory. */
internal object MaknoonSdkFactory {
    fun create(context: Context, config: MaknoonConfig): MaknoonSDK {
        // Certificate pinning is opt-in hardening (the SDK's MaknoonHttp defaults to CA TLS for
        // the live hosts). Pins are therefore optional; but a host LISTED with zero fingerprints
        // is a misconfiguration (it would neither pin nor fall back predictably).
        val badPin = config.pinnedFingerprintsByHost.entries.firstOrNull { it.value.isEmpty() }
        if (badPin != null) {
            throw MaknoonError.Configuration(
                "Pinned host '${badPin.key}' has no fingerprints; omit the host for CA TLS or add a pin",
            )
        }
        if (config.allowedIssuerHosts.isEmpty()) {
            throw MaknoonError.Configuration("allowedIssuerHosts must be non-empty (deep-link validation)")
        }
        return MaknoonSdkImpl(context.applicationContext, config)
    }
}
