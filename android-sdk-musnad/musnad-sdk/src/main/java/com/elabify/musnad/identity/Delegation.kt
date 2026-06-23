// 24-hour delegation certificate: the master ML-DSA-65 key signs a short-
// lived ephemeral key so day-to-day presentations don't reconstruct the
// master. Wire-identical to iOS IdentitySandwich.DelegationCert: the master
// signs ElabifyCore.canonicalize({ephemeralPk, validFrom, validUntil, scope})
// (sorted keys), and the verifier checks that signature against the master
// public key.
//
// The canonical-message builder is pure (JVM-testable); signing/verifying
// use the native ML-DSA via MasterKey (device / instrumented).

package com.elabify.musnad.identity

import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.crypto.hexToBytes
import com.elabify.musnad.crypto.toHex

data class DelegationCert(
    val ephemeralPk: String,   // "0x"-prefixed hex
    val validFrom: Long,
    val validUntil: Long,
    val scope: List<String>,
    val delegationSig: String, // "0x"-prefixed hex of the master ML-DSA-65 sig
)

object Delegation {
    /** 24-hour cert lifetime (matches iOS defaultValidityWindowSec). */
    const val VALIDITY_WINDOW_SEC = 24L * 3600L

    /** Refresh once within 1 hour of validUntil (matches iOS). */
    const val RENEWAL_LEAD_SEC = 3600L

    /** Permitted scope; kept in sync with the verifier's accepted-scopes set. */
    val SCOPE = listOf("verify")

    /** The exact bytes the master signs / the verifier checks. Sorted-key
     *  canonical JSON over the inner cert (everything but delegationSig). */
    fun canonicalMessage(
        ephemeralPkHex: String,
        validFrom: Long,
        validUntil: Long,
        scope: List<String> = SCOPE,
    ): ByteArray {
        val inner = linkedMapOf<String, Any?>(
            "ephemeralPk" to ephemeralPkHex,
            "validFrom" to validFrom,
            "validUntil" to validUntil,
            "scope" to scope,
        )
        return canonicalize(inner)
    }

    /** Sign a fresh 24h delegation. Uses the native master key (device). */
    fun sign(
        ephemeralPublicKey: ByteArray,
        words: List<String>,
        passphrase: String,
        nowSec: Long,
    ): DelegationCert {
        val ephHex = "0x" + ephemeralPublicKey.toHex()
        val validFrom = nowSec
        val validUntil = nowSec + VALIDITY_WINDOW_SEC
        val message = canonicalMessage(ephHex, validFrom, validUntil)
        val sig = MasterKey.sign(words, passphrase, message)
        return DelegationCert(ephHex, validFrom, validUntil, SCOPE, "0x" + sig.toHex())
    }

    /** Verify a delegation cert against the master public key (device). */
    fun verify(masterPublicKey: ByteArray, cert: DelegationCert): Boolean {
        val message = canonicalMessage(cert.ephemeralPk, cert.validFrom, cert.validUntil, cert.scope)
        return MasterKey.verify(masterPublicKey, hexToBytes(cert.delegationSig), message)
    }

    /** True once the cert is within the renewal lead time of expiry. */
    fun needsRenewal(cert: DelegationCert, nowSec: Long): Boolean =
        nowSec > cert.validUntil - RENEWAL_LEAD_SEC
}
