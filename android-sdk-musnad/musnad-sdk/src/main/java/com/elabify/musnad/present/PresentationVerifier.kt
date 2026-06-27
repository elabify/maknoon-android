// Local Presentation check matrix, ported from iOS PresentationVerifier.swift.
// Runs the OFFLINE-safe subset of the verifier-server's check pipeline
// (signatures + Merkle proofs + timestamps), and explicitly marks the
// chain-dependent checks (issuerRegistered, credentialNotRevoked, rootCurrent)
// as UNVERIFIED. The in-person "Verify Other" flow uses this so a holder
// acting as a verifier can spot-check another person's presentation offline.
//
// Wire formats route through com.elabify.core.canonicalize and
// verifyMerkleProof, and ML-DSA verification through MasterKey.verify (the
// uniffi pq-crypto-core ML-DSA-65), so the local verdict matches the server's
// for the checks we run here.
//
// GMS-free.

package com.elabify.musnad.present

import com.elabify.core.MerkleProofEntry
import com.elabify.core.canonicalize
import com.elabify.core.claimLeafHash
import com.elabify.core.rpo256Tagged
import com.elabify.musnad.crypto.MasterKey
import com.elabify.musnad.crypto.toHex

object PresentationVerifier {

    // 5 minutes, matching the server-assisted QR drop validity window
    // (PresentationDrop / DropEnvelope.expiresAt). A presentation built for that
    // QR stays valid for the same period, so "Verify credential" accepts the
    // holder's timestamp within the identical 300s window rather than a tighter
    // 60s clock-skew bound that would reject a still-valid QR.
    private const val CLOCK_SKEW_TOLERANCE_SEC = 300L
    private const val OPEN_VERIFIER_DID = "did:elabify:open"

    /** RPO-256 domain tag for the holder-DID fingerprint (matches HolderDid). */
    private const val HOLDER_DID_TAG = 0x03
    private const val FINGERPRINT_LEN = 20
    private const val HOLDER_DID_PREFIX = "did:elabify:sepolia:holder:0x"

    /**
     * Run every check that does not require a chain RPC. The verdict is GRANT
     * iff all local checks pass for an anchored (issuer-signed) credential;
     * SELF_ATTESTED for a self-issued credential that passes; chain checks land
     * as UNVERIFIED; any local-check failure is DENY.
     */
    fun verifyOffline(
        p: Presentation,
        clockSkewToleranceSec: Long = CLOCK_SKEW_TOLERANCE_SEC,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): LocalCheckResultBundle {

        // 0. Self-issued detection: header.iss == holderDID(holderLongTermPk).
        val holderPk = hexFrom0xOrNull(p.holderLongTermPk)
        val selfIssued = holderPk != null && p.header.iss == holderDidFromPk(holderPk)

        // 1. Header signature.
        //    Self-issued: verify headerSig against holderLongTermPk over the
        //    canonical header (a real local pass/fail). Issuer-signed: the
        //    presentation does not carry the issuer pubkey, so UNVERIFIED.
        val headerSigValid: LocalCheckResult = run {
            val sig = hexFrom0xOrNull(p.headerSig)
            // selfIssued already implies holderPk != null (its definition), so the
            // compiler smart-casts holderPk to non-null in the else branch below; an
            // explicit holderPk == null term here is always false (KT warning).
            if (!selfIssued || sig == null) {
                LocalCheckResult.Unverified(
                    "Issuer pubkey not local; verify online for issuer-bound signature",
                )
            } else {
                val headerBytes = p.header.canonicalBytes()
                if (MasterKey.verify(holderPk, sig, headerBytes)) {
                    LocalCheckResult.Pass
                } else {
                    LocalCheckResult.Fail("Self-issued header signature does not verify")
                }
            }
        }

        // 2. Merkle inclusion: every disclosed claim chains to header.root.
        val merkleValid = verifyMerkleAll(p)

        // 3. Challenge signature, against holderLongTermPk (or the ephemeral via
        //    the delegation cert for v2).
        val challengeSigValid = verifyChallengeSig(p, nowSec)

        // 4. Clock skew on the holder timestamp.
        val timestampValid: LocalCheckResult = run {
            val drift = kotlin.math.abs(nowSec - p.timestamp)
            if (drift <= clockSkewToleranceSec) {
                LocalCheckResult.Pass
            } else {
                LocalCheckResult.Fail("Timestamp drift ${drift}s > tolerance ${clockSkewToleranceSec}s")
            }
        }

        // 5. Credential expiry.
        val expiryValid: LocalCheckResult = run {
            val exp = p.header.exp
            if (exp == null) {
                LocalCheckResult.Pass
            } else if (exp > nowSec) {
                LocalCheckResult.Pass
            } else {
                LocalCheckResult.Fail("Credential expired")
            }
        }

        // 6. Verifier-request signature is a registry/online check; never fails
        //    the offline verdict.
        val verifierRequestValid: LocalCheckResult = LocalCheckResult.Unverified(
            if (p.verifierRequest == null) {
                "No verifier request embedded"
            } else {
                "Verifier signature requires registry lookup (online)"
            },
        )

        // Chain-dependent checks. Self-issued: not anchored by any authority, so
        // NOT_APPLICABLE; otherwise UNVERIFIED until an online verifier runs them.
        val chainGate: LocalCheckResult = if (selfIssued) {
            LocalCheckResult.NotApplicable("Self-issued; not anchored by an authority")
        } else {
            LocalCheckResult.Unverified("Requires chain lookup")
        }

        val checks = LocalCheckMatrix(
            headerSigValid = headerSigValid,
            merkleValid = merkleValid,
            challengeSigValid = challengeSigValid,
            timestampValid = timestampValid,
            expiryValid = expiryValid,
            verifierRequestValid = verifierRequestValid,
            issuerRegistered = chainGate,
            credentialNotRevoked = chainGate,
            rootCurrent = chainGate,
        )

        val disclosed = p.disclosed.associate { it.key to it.value }

        val decision: LocalVerdict = when {
            !checks.overallPass -> LocalVerdict.DENY
            selfIssued -> LocalVerdict.SELF_ATTESTED
            else -> LocalVerdict.UNVERIFIED
        }

        val summary: String = if (!checks.overallPass) {
            firstFailureSummary(checks) ?: "Local checks failed"
        } else if (selfIssued) {
            "Self-issued by the holder and verified offline (Merkle + self-signature). " +
                "Not anchored by an authority."
        } else {
            "Local checks passed. Chain & issuer-pubkey checks need online verifier."
        }

        return LocalCheckResultBundle(
            decision = decision,
            summary = summary,
            disclosed = disclosed,
            checks = checks,
        )
    }

    // -- individual checks ---------------------------------------------------

    private fun verifyMerkleAll(p: Presentation): LocalCheckResult {
        val expectedRoot = hexFrom0xOrNull(p.header.root)
            ?: return LocalCheckResult.Fail("Could not parse header.root")
        if (expectedRoot.isEmpty()) return LocalCheckResult.Fail("Could not parse header.root")
        for (claim in p.disclosed) {
            try {
                val leaf = claimLeafHash(claim.key, claim.value.anyValue())
                val proof = claim.proof.mapNotNull { entry ->
                    val sib = hexFrom0xOrNull(entry.sibling) ?: return@mapNotNull null
                    MerkleProofEntry(sib, entry.isRight)
                }
                if (!com.elabify.core.verifyMerkleProof(leaf, proof, expectedRoot)) {
                    return LocalCheckResult.Fail("Merkle proof failed for claim '${claim.key}'")
                }
            } catch (e: Exception) {
                return LocalCheckResult.Fail("Merkle hash error on '${claim.key}': ${e.message}")
            }
        }
        return LocalCheckResult.Pass
    }

    private fun verifyChallengeSig(p: Presentation, nowSec: Long): LocalCheckResult {
        val masterPk = hexFrom0xOrNull(p.holderLongTermPk)
            ?: return LocalCheckResult.Fail("Could not parse holderLongTermPk")
        val challengeSig = hexFrom0xOrNull(p.challengeSig)
            ?: return LocalCheckResult.Fail("Could not parse challengeSig")

        val verifierDid = p.verifierRequest?.verifierDid ?: OPEN_VERIFIER_DID
        val challengeDict = linkedMapOf<String, Any?>(
            "cid" to p.header.cid,
            "challenge" to p.challenge,
            "timestamp" to p.timestamp,
            "verifier" to verifierDid,
        )
        val challengeBytes = try {
            canonicalize(challengeDict)
        } catch (e: Exception) {
            return LocalCheckResult.Fail("Canonicalize failed: ${e.message}")
        }

        val d = p.delegation
        if (d == null) {
            // Legacy v1: the long-term key signs the challenge directly.
            return if (MasterKey.verify(masterPk, challengeSig, challengeBytes)) {
                LocalCheckResult.Pass
            } else {
                LocalCheckResult.Fail("Holder challenge signature does not verify")
            }
        }

        // v2 Identity-Sandwich: the ephemeral key signs the challenge; the
        // delegation cert chains the ephemeral key to the master. Verifying the
        // challenge directly against the master can never pass for v2.
        val ephPk = hexFrom0xOrNull(d.ephemeralPk)
            ?: return LocalCheckResult.Fail("Could not parse delegation ephemeralPk")
        if (!MasterKey.verify(ephPk, challengeSig, challengeBytes)) {
            return LocalCheckResult.Fail("Ephemeral challenge signature does not verify")
        }

        // Delegation cert: the master signs canonicalize of the inner cert
        // (everything but delegationSig), binding the ephemeral key to the DID.
        val delegationSig = hexFrom0xOrNull(d.delegationSig)
            ?: return LocalCheckResult.Fail("Could not parse delegationSig")
        val innerBytes = try {
            d.innerCanonicalBytes()
        } catch (e: Exception) {
            return LocalCheckResult.Fail("Canonicalize failed: ${e.message}")
        }
        if (!MasterKey.verify(masterPk, delegationSig, innerBytes)) {
            return LocalCheckResult.Fail("Delegation cert not signed by holder master key")
        }
        if (nowSec < d.validFrom || nowSec > d.validUntil) {
            return LocalCheckResult.Fail("Delegation cert outside its validity window")
        }
        if (!d.scope.contains("verify")) {
            return LocalCheckResult.Fail("Delegation scope does not include 'verify'")
        }
        return LocalCheckResult.Pass
    }

    /** Holder DID derived from an ML-DSA-65 master public key, byte-identical to
     *  HolderDid.fromMasterPublicKey / iOS CredentialCanonical.holderDID. */
    private fun holderDidFromPk(pk: ByteArray): String {
        val fingerprint = rpo256Tagged(HOLDER_DID_TAG, pk).copyOfRange(0, FINGERPRINT_LEN)
        return HOLDER_DID_PREFIX + fingerprint.toHex()
    }

    private fun firstFailureSummary(checks: LocalCheckMatrix): String? {
        val ordered = listOf(
            "headerSigValid" to checks.headerSigValid,
            "merkleValid" to checks.merkleValid,
            "challengeSigValid" to checks.challengeSigValid,
            "timestampValid" to checks.timestampValid,
            "expiryValid" to checks.expiryValid,
        )
        for ((name, result) in ordered) {
            if (result is LocalCheckResult.Fail) return "$name: ${result.reason}"
        }
        return null
    }
}
