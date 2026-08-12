package com.elabify.maknoon.impl

import android.content.Context
import com.elabify.maknoon.ClaimValue
import com.elabify.maknoon.CredentialDetail
import com.elabify.maknoon.CredentialID
import com.elabify.maknoon.CredentialStatus
import com.elabify.maknoon.CredentialSummary
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MusnadCredentials
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.OnChainVerifier
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.RegistryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MusnadCredentials] over the SQLCipher-encrypted credential store
 * (`data.MaknoonStore` / `CredentialDao`) and the credential wire parser
 * (`present.ParsedCredential`). Claim plaintext lives inside the encrypted DB; the
 * facade exposes it only inside [disclose]. On-chain status reuses [OnChainVerifier].
 */
internal class MusnadCredentialsImpl(
    private val appContext: Context,
    private val config: MaknoonConfig,
) : MusnadCredentials {

    private suspend fun dao() = MaknoonStore.open(appContext).credentials()

    private suspend fun entity(id: CredentialID): CredentialEntity =
        dao().all().firstOrNull { it.cid == id.value }
            ?: throw MaknoonError.InvalidRequest("No credential ${id.value}")

    override suspend fun listCredentials(): List<CredentialSummary> =
        withContext(Dispatchers.Default) {
            dao().all().map { e ->
                val header = runCatching { ParsedCredential.parse(e.credentialJson).header }.getOrNull()
                CredentialSummary(
                    id = CredentialID(e.cid),
                    issuerDid = e.issuerDid,
                    schema = header?.schema ?: e.schema,
                    // `iat` is already seconds; `createdAt` is MILLISECONDS, so
                    // the fallback needs converting. Used raw it dated every
                    // header-less credential roughly fifty thousand years out.
                    issuedAtSec = header?.iat ?: (e.createdAt / 1000L),
                    expiresAtSec = header?.exp,
                    title = e.nickname,
                )
            }
        }

    override suspend fun credentialDetail(id: CredentialID): CredentialDetail =
        withContext(Dispatchers.Default) {
            val e = entity(id)
            val parsed = ParsedCredential.parse(e.credentialJson)
            CredentialDetail(
                summary = CredentialSummary(
                    id = CredentialID(e.cid),
                    issuerDid = e.issuerDid,
                    schema = parsed.header.schema,
                    issuedAtSec = parsed.header.iat,
                    expiresAtSec = parsed.header.exp,
                    title = e.nickname,
                ),
                claimKeys = parsed.claims.keys.toSet(),
                // Detail does not force a network round-trip; refreshStatus() does the read.
                status = CredentialStatus(
                    issuerActive = null,
                    revoked = null,
                    rootCurrent = null,
                    checkedAtSec = System.currentTimeMillis() / 1000L,
                    online = false,
                ),
            )
        }

    override suspend fun <T> disclose(
        id: CredentialID,
        keys: Set<String>,
        body: suspend (Map<String, ClaimValue>) -> T,
    ): T = withContext(Dispatchers.Default) {
        val parsed = ParsedCredential.parse(entity(id).credentialJson)
        // Filter to the requested keys and hand a fresh map to the closure. JVM Strings are
        // immutable so byte-level zeroization is not possible; we drop the map reference on
        // exit so plaintext is unreachable and GC-eligible immediately after `body` returns.
        val disclosed: MutableMap<String, ClaimValue> = LinkedHashMap()
        for (k in keys) parsed.claims[k]?.let { disclosed[k] = it.toClaimValue() }
        try {
            body(disclosed)
        } finally {
            disclosed.clear()
        }
    }

    override suspend fun refreshStatus(id: CredentialID): CredentialStatus =
        withContext(Dispatchers.IO) {
            val parsed = ParsedCredential.parse(entity(id).credentialJson)
            val rpc = config.chainRpcUrl
                ?: throw MaknoonError.Configuration("No chain RPC configured for status reads")
            val registry = RegistryConfig.sepolia(rpc).copy(
                identityRegistry = config.identityRegistryAddress
                    ?: RegistryConfig.sepolia(rpc).identityRegistry,
                revocationRegistry = config.revocationRegistryAddress
                    ?: RegistryConfig.sepolia(rpc).revocationRegistry,
            )
            val anchor = parsed.anchor.selected()
            val verdict = OnChainVerifier.verify(
                config = registry,
                header = parsed.header,
                headerSig = parsed.headerSig,
                cscaCertIdHex = null,
                anchorBatchRoot = anchor.rootOrNull(),
                anchorRPCURL = rpc,
                anchorRevocationRegistry = anchor.registryOrNull(),
                anchorBatchTxHash = anchor.txHashOrNull(),
            )
            CredentialStatus(
                issuerActive = verdict.issuerRegistered.toBool(),
                revoked = verdict.notRevoked.toRevoked(),
                rootCurrent = verdict.rootCurrent.toBool(),
                checkedAtSec = System.currentTimeMillis() / 1000L,
                online = verdict.reachedChain,
            )
        }
}

/** JsonValue (internal wire type) -> ClaimValue (public facade type). */
internal fun JsonValue.toClaimValue(): ClaimValue = when (this) {
    is JsonValue.Str -> ClaimValue.Text(value)
    is JsonValue.IntVal -> ClaimValue.Number(value.toDouble())
    is JsonValue.DoubleVal -> ClaimValue.Number(value)
    is JsonValue.Bool -> ClaimValue.Bool(value)
    is JsonValue.Null -> ClaimValue.Null
    is JsonValue.Arr -> ClaimValue.Items(value.map { it.toClaimValue() })
    is JsonValue.Obj -> ClaimValue.Nested(value.mapValues { it.value.toClaimValue() })
}

internal fun com.elabify.musnad.present.OnChainTier.toBool(): Boolean? = when (this) {
    is com.elabify.musnad.present.OnChainTier.Pass -> true
    is com.elabify.musnad.present.OnChainTier.Fail -> false
    is com.elabify.musnad.present.OnChainTier.Unknown -> null
}

internal fun com.elabify.musnad.present.OnChainTier.toRevoked(): Boolean? = when (this) {
    is com.elabify.musnad.present.OnChainTier.Pass -> false      // Pass == not revoked
    is com.elabify.musnad.present.OnChainTier.Fail -> true       // Fail == revoked
    is com.elabify.musnad.present.OnChainTier.Unknown -> null
}
