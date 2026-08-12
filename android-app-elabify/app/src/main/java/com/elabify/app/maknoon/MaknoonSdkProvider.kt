package com.elabify.app.maknoon

import android.content.Context
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonLogSink
import com.elabify.maknoon.MaknoonSDK

/**
 * Single process-wide [MaknoonSDK] instance for the reference app.
 *
 * This is the migration seam: call sites move off the internal `com.elabify.musnad.*` types
 * onto `MaknoonSdkProvider.sdk(context).identity/credentials/wallet/...`. It is lazily built,
 * so adding it changes no behaviour until a call site actually uses it. Config uses CA TLS
 * (the SDK's MaknoonHttp default; pins are opt-in hardening) and the app's default issuer host
 * and Sepolia read RPC; a production embedder would supply its own [MaknoonConfig].
 */
object MaknoonSdkProvider {

    /**
     * Issuer hosts this app will accept an issuance pickup from.
     *
     * Both entries are load-bearing. The facade's `resolvePickup` rejects any
     * host not listed, which is a real gate the pre-facade code did not have:
     * PendingPickupsStore polled whatever URL the issuer handed back. Listing
     * only the primary host would therefore have turned migrating that store
     * into a silent regression, because the app also picks up from issuer-1.
     *
     * A production embedder supplies its own set. Keep this in step with the
     * issuer deployments the reference app is pointed at.
     */
    private val ISSUER_HOSTS = setOf(
        "musnad-issuer.elabify.com",
        "musnad-issuer1.elabify.com",
    )
    private const val DEFAULT_SEPOLIA_RPC = "https://ethereum-sepolia.publicnode.com"

    @Volatile
    private var instance: MaknoonSDK? = null

    fun sdk(context: Context): MaknoonSDK =
        instance ?: synchronized(this) {
            instance ?: MaknoonSDK.init(
                context.applicationContext,
                MaknoonConfig(
                    mode = MaknoonConfig.Mode.SOFTWARE_ONLY,
                    allowedIssuerHosts = ISSUER_HOSTS,
                    pinnedFingerprintsByHost = emptyMap(), // CA TLS; pinning is opt-in in MaknoonHttp
                    chainRpcUrl = DEFAULT_SEPOLIA_RPC,
                    logSink = MaknoonLogSink { },
                ),
            ).also { instance = it }
        }
}
