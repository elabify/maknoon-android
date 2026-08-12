package com.elabify.maknoon.impl

import android.content.Context
import com.elabify.maknoon.Delegation
import com.elabify.maknoon.DelegationPolicy
import com.elabify.maknoon.DelegationScope
import com.elabify.maknoon.DeviceRef
import com.elabify.maknoon.IdentityStatus
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MusnadIdentity
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.identity.DelegationCert
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * [MusnadIdentity] over the internal Identity Sandwich (`com.elabify.musnad.identity`).
 * A facade boundary only: no new crypto. The internal delegation model is a fixed 24h
 * cert with scope `["verify"]`, so `createDelegation` renews that cert and the lifetime/
 * scope arguments are advisory (documented divergence from the spec's arbitrary-lifetime
 * shape; the wire format is fixed).
 */
internal class MusnadIdentityImpl(
    private val appContext: Context,
    private val config: MaknoonConfig,
) : MusnadIdentity {

    private val store: IdentityStore by lazy { IdentityStore(appContext) }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    private fun loadOrThrow(): IdentitySandwich =
        IdentitySandwich.load(store) ?: throw MaknoonError.Configuration("No identity present")

    override suspend fun holderDid(): String = withContext(Dispatchers.Default) {
        loadOrThrow().holderDid
    }

    override suspend fun hasActiveDelegation(policy: DelegationPolicy?): Boolean =
        withContext(Dispatchers.Default) {
            val cert = store.loadDelegation() ?: return@withContext false
            val now = nowSec()
            if (now >= cert.validUntil) return@withContext false
            val minRemaining = policy?.minRemaining
            if (minRemaining != null && (cert.validUntil - now) < minRemaining.inWholeSeconds) {
                return@withContext false
            }
            // The internal wire scope is fixed to "verify"; any requested scope maps to it.
            val required = policy?.requiredScopes.orEmpty()
            if (required.isNotEmpty() && !cert.scope.contains("verify")) return@withContext false
            true
        }

    override suspend fun createDelegation(
        lifetime: Duration,
        scope: List<DelegationScope>,
    ): Delegation = withContext(Dispatchers.Default) {
        // Renew (re-sign) the ephemeral key with a fresh 24h window. Requires the root
        // entropy; when the second factor is on and locked, the internal call throws a
        // SecondFactorRequiredException the caller routes through a key tap.
        val sandwich = loadOrThrow()
        sandwich.renewDelegation(nowSec(), store)
        sandwich.delegation.toPublic()
    }

    override suspend fun revokeActiveDelegation() = withContext(Dispatchers.Default) {
        // V1 has no infrastructure-side revocation list (spec section 5.4; 24h lifetime
        // bounds blast radius). Local revoke: expire the stored cert so no active
        // delegation remains and the ephemeral key stops validating.
        val cert = store.loadDelegation() ?: return@withContext
        store.saveDelegation(cert.copy(validUntil = cert.validFrom))
    }

    override suspend fun currentStatus(): IdentityStatus = withContext(Dispatchers.Default) {
        val sandwich = loadOrThrow()
        val cert = store.loadDelegation()
        val now = nowSec()
        IdentityStatus(
            holderDid = sandwich.holderDid,
            mode = config.mode,
            hasActiveDelegation = cert != null && now < cert.validUntil,
            delegationExpiresAtSec = cert?.validUntil,
            secondFactorEnrolled = store.secondFactorEnabled(),
        )
    }

    override suspend fun hasIdentity(): Boolean = withContext(Dispatchers.Default) {
        store.hasIdentity()
    }

    override suspend fun hasPassphrase(): Boolean = withContext(Dispatchers.Default) {
        loadOrThrow().hasPassphrase()
    }

    override suspend fun createIdentity(passphrase: String): IdentityStatus =
        withContext(Dispatchers.Default) {
            // Fresh entropy generated + sealed inside the SDK; the returned entropy is discarded
            // here (reveal via revealRecoveryWords under biometric).
            IdentitySandwich.generateFresh(passphrase, nowSec(), store)
            currentStatus()
        }

    override suspend fun restoreFromMnemonic(words: List<String>, passphrase: String): IdentityStatus =
        withContext(Dispatchers.Default) {
            IdentitySandwich.restoreFromMnemonic(words, passphrase, nowSec(), store)
            currentStatus()
        }

    override suspend fun restoreFromEncryptedBackup(blob: ByteArray, passphrase: String): IdentityStatus =
        withContext(Dispatchers.Default) {
            IdentitySandwich.restoreFromEncryptedBackup(blob, passphrase, nowSec(), store)
            currentStatus()
        }

    override suspend fun reset() = withContext(Dispatchers.Default) {
        store.wipe()
    }

    override suspend fun <T> revealRecoveryWords(body: suspend (List<String>) -> T): T =
        withContext(Dispatchers.Default) {
            // The mnemonic is materialized only for the closure; the reference is dropped on
            // return. (JVM Strings are immutable so byte-zeroization is not possible; the words
            // become GC-eligible immediately.) A 2FA-locked identity throws
            // SecondFactorRequiredException here until the user taps a key.
            val words = loadOrThrow().recoveryWords()
            body(words)
        }

    override suspend fun exportEncryptedBackup(): ByteArray = withContext(Dispatchers.Default) {
        loadOrThrow().exportEncryptedBackup()
    }

    override suspend fun signWithMaster(message: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        loadOrThrow().signWithMaster(message)
    }

    override suspend fun enrollSecondFactor(device: DeviceRef) {
        // Enrolling a device as an Identity Sandwich second factor needs a hardware tap to
        // derive the device hmac-secret, then IdentitySandwich.sealForSecondFactorEnroll.
        // That tap flow is owned by the MaknoonHardware facade; this seam is wired once the
        // hardware impl lands (Phase 1A hardware increment). Tracked, not shipped.
        throw MaknoonError.Configuration("enrollSecondFactor is wired with the MaknoonHardware facade")
    }

    private fun DelegationCert.toPublic(): Delegation =
        Delegation(
            certBytes = hexToBytes(delegationSig),
            issuedAtSec = validFrom,
            expiresAtSec = validUntil,
        )
}
