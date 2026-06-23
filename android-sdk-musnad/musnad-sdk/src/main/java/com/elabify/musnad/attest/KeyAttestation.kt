// Android Key Attestation: the GMS-free analog of the iOS App Attest flow
// (AppAttest/MaknoonAppAttest.swift). iOS enrolls a Secure-Enclave key with
// challenge = holderDID and gets a CBOR attestation object from Apple's
// servers; here we generate a StrongBox EC P-256 key with the holder DID as
// the attestation challenge, and the X.509 certificate chain the keystore
// emits IS the attestation. It chains to Google's hardware attestation root
// and is verified entirely offline -- no Play Integrity, no GMS, works on
// GrapheneOS (the Titan M2's attestation key is provisioned by Google
// regardless of OS; the verified-boot fields in the extension reflect
// GrapheneOS, which the issuer policy must accept rather than reject).
//
// Enrollment  = generate key + read chain (challenge bound in the leaf cert).
// Assertion   = sign a per-request challenge with the attested key; the
//               verifier checks it against the enrolled leaf public key.

package com.elabify.musnad.attest

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.elabify.musnad.crypto.AndroidSecureStore.SecurityLevel
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence

class KeyAttestation(private val alias: String = DEFAULT_ALIAS) {

    /** Enrollment record, analogous to iOS AppAttestEnrollment. */
    data class Enrollment(
        val keyAlias: String,
        /** X.509 chain, leaf-first, DER-encoded; chains to a Google HW root. */
        val certChainDer: List<ByteArray>,
        /** The challenge we bound into the leaf (e.g. holder DID bytes). */
        val challenge: ByteArray,
        /** Hardware level the private key actually landed in (from KeyInfo). */
        val securityLevel: SecurityLevel,
        /** attestationSecurityLevel from the cert extension: 0=SW,1=TEE,2=StrongBox. */
        val attestationSecurityLevel: Int,
    )

    /** Generate a fresh StrongBox-attested EC P-256 key bound to [challenge]
     *  and return its certificate chain. Overwrites any prior key. */
    fun enroll(challenge: ByteArray): Enrollment {
        deleteKey()
        generateKey(challenge, strongBox = true)
        val ks = keystore()
        val chain = ks.getCertificateChain(alias)
            ?: error("no attestation chain produced for $alias")
        val leaf = chain[0] as X509Certificate
        val parsed = parseAttestationExtension(leaf)
        return Enrollment(
            keyAlias = alias,
            certChainDer = chain.map { it.encoded },
            challenge = challenge,
            securityLevel = levelOf(),
            attestationSecurityLevel = parsed.securityLevel,
        )
    }

    /** Per-request assertion: ECDSA-SHA256 over [message] with the attested key. */
    fun assert(message: ByteArray): ByteArray {
        val entry = keystore().getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(message)
            sign()
        }
    }

    fun publicKey(): PublicKey =
        (keystore().getEntry(alias, null) as KeyStore.PrivateKeyEntry).certificate.publicKey

    fun keyExists(): Boolean = keystore().containsAlias(alias)

    fun deleteKey() {
        keystore().takeIf { it.containsAlias(alias) }?.deleteEntry(alias)
    }

    // ---- internals ----

    private fun keystore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun generateKey(challenge: ByteArray, strongBox: Boolean) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        try {
            gen.initialize(spec)
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            if (strongBox) generateKey(challenge, strongBox = false) else throw e
        }
    }

    private fun levelOf(): SecurityLevel {
        val priv = (keystore().getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey
        val factory = KeyFactory.getInstance(priv.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(priv, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> SecurityLevel.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE
            else -> SecurityLevel.SOFTWARE
        }
    }

    private data class AttestationExtension(val securityLevel: Int, val challenge: ByteArray)

    /** Parse the key-attestation extension (KeyDescription SEQUENCE). */
    private fun parseAttestationExtension(leaf: X509Certificate): AttestationExtension {
        val extBytes = leaf.getExtensionValue(ATTESTATION_OID)
            ?: error("leaf certificate has no key-attestation extension")
        // extnValue is a DER OCTET STRING wrapping the KeyDescription SEQUENCE.
        val keyDescription = ASN1OctetString.getInstance(extBytes).octets
        val seq = ASN1Sequence.getInstance(keyDescription)
        // KeyDescription: [0]=attestationVersion, [1]=attestationSecurityLevel,
        // [2]=keymasterVersion, [3]=keymasterSecurityLevel, [4]=attestationChallenge, ...
        val securityLevel = (seq.getObjectAt(1) as ASN1Enumerated).value.toInt()
        val challenge = (seq.getObjectAt(4) as ASN1OctetString).octets
        return AttestationExtension(securityLevel, challenge)
    }

    companion object {
        const val DEFAULT_ALIAS = "maknoon.attest.key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"

        /** attestationSecurityLevel values from the extension. */
        const val LEVEL_SOFTWARE = 0
        const val LEVEL_TEE = 1
        const val LEVEL_STRONGBOX = 2
    }
}
