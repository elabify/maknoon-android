package com.elabify.maknoon.impl

import android.content.Context
import android.net.Uri
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MusnadPresentation
import com.elabify.maknoon.PresentationRequest
import com.elabify.maknoon.PresentationVerdict
import com.elabify.maknoon.ReceivedPresentation
import com.elabify.maknoon.VerificationResult
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.net.ChallengeContext
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.VerifierClient
import com.elabify.musnad.present.HavidResult
import com.elabify.musnad.present.HavidState
import com.elabify.musnad.present.HavidVerifier
import com.elabify.musnad.present.OnChainTier
import com.elabify.musnad.present.OnChainVerdict
import com.elabify.musnad.present.OnChainVerifier
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.Presentation
import com.elabify.musnad.present.PresentationBuilder
import com.elabify.musnad.present.PresentationVerifier
import com.elabify.musnad.present.RegistryConfig
import com.elabify.musnad.present.VerifierRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MusnadPresentation] over the SDK's presentation stack: [PresentationBuilder] to sign,
 * [VerifierClient] to POST, and [PresentationVerifier] + [OnChainVerifier] + [HavidVerifier]
 * for the tiered verify-received verdict.
 */
internal class MusnadPresentationImpl(
    private val appContext: Context,
    private val config: MaknoonConfig,
) : MusnadPresentation {

    // Resolved verifier requests, keyed by the raw scanned URI, so present() can bind to the
    // same challenge/verifier the host resolved. Scoped to this SDK instance.
    private val resolved = ConcurrentHashMap<String, VerifierRequest>()

    override suspend fun resolveRequest(uri: Uri): PresentationRequest = withContext(Dispatchers.IO) {
        val raw = uri.toString()
        val vr = VerifierRequest.parse(raw)
            ?: uri.getQueryParameter("request_uri")?.let { ru ->
                runCatching { VerifierRequest.parse(MaknoonHttp().getJson(ru)) }.getOrNull()
            }
            ?: throw MaknoonError.InvalidRequest("Not a valid verifier request")
        resolved[raw] = vr
        PresentationRequest(
            verifierDid = vr.verifierDid,
            requestedClaims = vr.filter.requiredClaims.toSet(),
            purpose = vr.verifierName,
            raw = uri,
        )
    }

    override suspend fun present(
        request: PresentationRequest,
        disclose: Set<String>,
        includePiiHandoff: Boolean,
    ): PresentationVerdict = withContext(Dispatchers.IO) {
        val vr = resolved[request.raw.toString()]
            ?: throw MaknoonError.InvalidRequest("Resolve the request before presenting")
        val required = vr.filter.requiredClaims.toSet()

        // Pick the first stored credential that can satisfy the required claims and any
        // issuer/schema filter. (The reference app's MatchingEngine has richer ranking;
        // this is the facade's default policy, documented in the contract.)
        val dao = MaknoonStore.open(appContext).credentials()
        val chosen = dao.all().asSequence()
            .mapNotNull { e -> runCatching { ParsedCredential.parse(e.credentialJson) }.getOrNull() }
            .firstOrNull { pc ->
                pc.claims.keys.containsAll(required) &&
                    filterAllows(vr, pc)
            } ?: throw MaknoonError.NoMatchingCredential()

        val sandwich = IdentitySandwich.load(IdentityStore(appContext))
            ?: throw MaknoonError.Configuration("No identity present")

        val presentation = PresentationBuilder.build(
            credential = chosen,
            selectedClaims = disclose.ifEmpty { required },
            challenge = vr.challenge,
            verifierDid = vr.verifierDid,
            pendingRequest = vr,
            sandwich = sandwich,
        )

        val base = verifierBaseUrl(vr, request.raw)
            ?: throw MaknoonError.InvalidRequest("Verifier request carries no callback endpoint")
        val resp = VerifierClient(base).verify(
            ChallengeContext(vr.requestId, vr.issuedAt, vr.expiresAt),
            presentation,
        )
        PresentationVerdict(
            decision = if (resp.decision.equals("GRANT", ignoreCase = true))
                PresentationVerdict.Decision.GRANT else PresentationVerdict.Decision.DENY,
            reason = resp.reason,
            requestId = vr.requestId,
            online = true,
        )
    }

    override suspend fun verifyReceived(presentation: ReceivedPresentation): VerificationResult =
        withContext(Dispatchers.IO) {
            val p: Presentation = runCatching { Presentation.parse(presentation.presentationJson) }
                .getOrNull() ?: throw MaknoonError.InvalidRequest("Malformed presentation")

            // 1. Local crypto (header sig where possible, Merkle, challenge, timestamp, expiry).
            val local = PresentationVerifier.verifyOffline(p)

            // 2. On-chain tiers (issuer registered / not revoked / root current) when an RPC
            //    is configured; otherwise UNKNOWN.
            val onChain: OnChainVerdict? = config.chainRpcUrl?.let { rpc ->
                val base = RegistryConfig.sepolia(rpc).copy(
                    identityRegistry = config.identityRegistryAddress
                        ?: RegistryConfig.sepolia(rpc).identityRegistry,
                    revocationRegistry = config.revocationRegistryAddress
                        ?: RegistryConfig.sepolia(rpc).revocationRegistry,
                )
                val anchor = p.anchor.selected()
                runCatching {
                    OnChainVerifier.verify(
                        config = base,
                        header = p.header,
                        headerSig = p.headerSig,
                        cscaCertIdHex = null,
                        anchorBatchRoot = anchor.rootOrNull(),
                        anchorRPCURL = rpc,
                        anchorRevocationRegistry = anchor.registryOrNull(),
                        anchorBatchTxHash = anchor.txHashOrNull(),
                    )
                }.getOrNull()
            }

            // 3. HAVID issuer-cert <-> DID cross-endorsement.
            val havid: HavidResult = runCatching {
                HavidVerifier.verify(
                    candidateBaseUrls = config.allowedIssuerHosts.map { "https://$it" },
                    header = p.header,
                    headerSig = p.headerSig,
                )
            }.getOrNull() ?: HavidResult(HavidState.NOT_RESOLVABLE)

            VerificationResult(
                localCryptoValid = local.checks.overallPass,
                onChain = VerificationResult.OnChainTiers(
                    issuerRegistered = onChain?.issuerRegistered.toTier(),
                    credentialNotRevoked = onChain?.notRevoked.toTier(),
                    rootCurrent = onChain?.rootCurrent.toTier(),
                ),
                cscaProvenance = onChain?.cscaProvenance.toTier(),
                havid = havid.state.toTier(),
            )
        }

    private fun filterAllows(vr: VerifierRequest, pc: ParsedCredential): Boolean {
        val iss = vr.filter.issuers
        if (iss?.list != null && iss.mode.equals("allow", ignoreCase = true) &&
            !iss.list!!.contains(pc.header.iss)
        ) return false
        val sch = vr.filter.schemas
        if (sch?.list != null && sch.mode.equals("allow", ignoreCase = true) &&
            !sch.list!!.contains(pc.header.schema)
        ) return false
        return true
    }

    private fun verifierBaseUrl(vr: VerifierRequest, raw: Uri): String? {
        vr.response.callbackUrl?.let { cb ->
            // Trim a trailing /v1/verify if the directive already points at the endpoint.
            return cb.removeSuffix("/v1/verify").removeSuffix("/")
        }
        val host = raw.host ?: return null
        val scheme = raw.scheme?.takeIf { it.startsWith("http") } ?: "https"
        return "$scheme://$host"
    }
}

private fun OnChainTier?.toTier(): VerificationResult.Tier = when (this) {
    is OnChainTier.Pass -> VerificationResult.Tier.PASS
    is OnChainTier.Fail -> VerificationResult.Tier.FAIL
    else -> VerificationResult.Tier.UNKNOWN
}

private fun HavidState.toTier(): VerificationResult.Tier = when (this) {
    HavidState.CROSS_ENDORSED -> VerificationResult.Tier.PASS
    HavidState.KEY_ALIGNMENT_FAILURE,
    HavidState.INTEGRITY_FAILURE,
    HavidState.EXPIRED_REVOKED -> VerificationResult.Tier.FAIL
    HavidState.NO_ENDORSEMENT,
    HavidState.NOT_RESOLVABLE -> VerificationResult.Tier.UNKNOWN
}
