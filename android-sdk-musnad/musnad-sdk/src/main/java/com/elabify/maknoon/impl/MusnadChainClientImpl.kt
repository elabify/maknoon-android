package com.elabify.maknoon.impl

import com.elabify.maknoon.CredentialStatus
import com.elabify.maknoon.IssuerStatus
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MusnadChainClient
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.present.OnChainTier
import com.elabify.musnad.present.OnChainVerifier
import com.elabify.musnad.present.RegistryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [MusnadChainClient] over [OnChainVerifier]: lazy, read-only registry queries. */
internal class MusnadChainClientImpl(
    private val config: MaknoonConfig,
) : MusnadChainClient {

    private fun registry(): RegistryConfig {
        val rpc = config.chainRpcUrl
            ?: throw MaknoonError.Configuration("No chain RPC configured for status reads")
        val base = RegistryConfig.sepolia(rpc)
        return base.copy(
            identityRegistry = config.identityRegistryAddress ?: base.identityRegistry,
            revocationRegistry = config.revocationRegistryAddress ?: base.revocationRegistry,
        )
    }

    override suspend fun issuerStatus(did: String): IssuerStatus = withContext(Dispatchers.IO) {
        // No anchor chain passed, so verifyReference only reads isActive + the issuer pubkey.
        val ref = OnChainVerifier.verifyReference(
            config = registry(),
            did = did,
            cid = "",
            iat = 0L,
            cscaCertIdHex = null,
            anchorBatchRoot = null,
        )
        IssuerStatus(
            did = did,
            registered = ref.issuerPubkey != null && ref.issuerPubkey!!.isNotEmpty(),
            active = ref.verdict.issuerRegistered is OnChainTier.Pass,
            currentEpoch = null,
        )
    }

    override suspend fun credentialStatus(issuerDid: String, cid: ByteArray): CredentialStatus =
        withContext(Dispatchers.IO) {
            val rpc = config.chainRpcUrl
                ?: throw MaknoonError.Configuration("No chain RPC configured for status reads")
            val ref = OnChainVerifier.verifyReference(
                config = registry(),
                did = issuerDid,
                cid = "0x" + cid.toHex(),
                iat = 0L,
                cscaCertIdHex = null,
                anchorBatchRoot = null,
                anchorRPCURL = rpc,
            )
            CredentialStatus(
                issuerActive = when (ref.verdict.issuerRegistered) {
                    is OnChainTier.Pass -> true
                    is OnChainTier.Fail -> false
                    is OnChainTier.Unknown -> null
                },
                revoked = when (ref.verdict.notRevoked) {
                    is OnChainTier.Pass -> false
                    is OnChainTier.Fail -> true
                    is OnChainTier.Unknown -> null
                },
                rootCurrent = null, // no batch root supplied for a bare reference read
                checkedAtSec = System.currentTimeMillis() / 1000L,
                online = ref.verdict.reachedChain,
            )
        }
}
