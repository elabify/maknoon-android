// Single source of truth for building a signed Presentation, ported from
// iOS PresentationFactory.swift. The interactive present screen and the
// mini-app identity bridge both sign through this exact path so the two
// surfaces never drift on canonicalization / Merkle proofs / signature shape.
//
// The caller decides where the challenge comes from:
//   - interactive scan of a VerifierRequest -> request.challenge + its DID
//   - open self-presentation                -> a fresh self nonce + did:elabify:open
//   - server-issued challenge (mini-app POS) -> /v1/challenge value + did:elabify:open
// and passes the matching verifierDid used in the signed challenge message.
// `pendingRequest`, when present, is echoed into the presentation so a
// verifier can confirm it is answering its own request.
//
// GMS-free. Signing inputs route through com.elabify.core.canonicalize and
// the MerkleTree byte-equal with iOS / the verifier-server.

package com.elabify.musnad.present

import com.elabify.core.MerkleTree
import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.identity.IdentitySandwich
import java.security.SecureRandom

object PresentationBuilder {

    /** The open / self / server-challenge verifier DID, used in the signed
     *  challenge message when there is no scanned VerifierRequest. */
    const val OPEN_VERIFIER_DID = "did:elabify:open"

    /**
     * Build and sign a v2 Presentation disclosing `selectedClaims` from
     * `credential`, binding it to `challenge` (signed together with
     * `verifierDid`).
     *
     * The challenge is signed with the ephemeral key (IdentitySandwich.
     * signChallenge) when a delegation cert is present (the v2 Identity
     * Sandwich path, the verifier checks the ephemeral sig against the cert),
     * otherwise it falls back to the master key (legacy v1, signWithMaster).
     * The delegation cert is embedded so the verifier's delegationValid check
     * can run.
     *
     * @param credential      the parsed stored credential to disclose from
     * @param selectedClaims  the claim keys the holder chose to reveal
     * @param challenge       the verifier-supplied (or self) challenge hex
     * @param verifierDid     the DID bound into the signed challenge message
     * @param pendingRequest  the scanned VerifierRequest, echoed back (or null)
     * @param sandwich        the unlocked Identity Sandwich (signs the challenge)
     * @param nowSec          the holder timestamp (unix seconds); defaults to now
     */
    fun build(
        credential: ParsedCredential,
        selectedClaims: Set<String>,
        challenge: String,
        verifierDid: String,
        pendingRequest: VerifierRequest?,
        sandwich: IdentitySandwich,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): Presentation {
        val holderPk = sandwich.masterPublicKey

        // Challenge message: { cid, challenge, timestamp, verifier }. canonicalize
        // sorts keys, so the field insertion order here is cosmetic, but it must
        // carry exactly these four fields with these names + types.
        val challengeMsgDict = linkedMapOf<String, Any?>(
            "cid" to credential.cid,
            "challenge" to challenge,
            "timestamp" to nowSec,
            "verifier" to verifierDid,
        )
        val msgBytes = canonicalize(challengeMsgDict)

        // v2 Identity Sandwich: the ephemeral software key signs the challenge
        // (fast path, no master reconstruction); the cert chains it to the
        // master. On Android the sandwich always carries a delegation cert, so
        // this is always the ephemeral path (the legacy v1 master-only path is
        // not reachable here, but the verifier still accepts it for iOS parity).
        val delegationCert = sandwich.delegation
        val challengeSig: ByteArray = sandwich.signChallenge(msgBytes)

        // Recompute the issuer Merkle tree over the sorted claim set so the
        // disclosed-claim proofs chain to header.root exactly as the issuer's do.
        val tree = MerkleTree(credential.merkleEntries())

        val requested = selectedClaims.toList().sorted()
        val disclosed = ArrayList<DisclosedClaim>()
        for (key in requested) {
            val idx = credential.merkleTree.sortedKeys.indexOf(key)
            val value = credential.claims[key]
            if (idx < 0 || value == null) continue
            val proof = tree.proof(idx).map { entry ->
                ProofEntry(sibling = entry.sibling.to0xHex(), isRight = entry.isRight)
            }
            disclosed.add(DisclosedClaim(key = key, value = value, leafIndex = idx, proof = proof))
        }

        // Embed the delegation cert so the verifier's delegationValid check runs.
        val delegation = PresentationDelegation(
            ephemeralPk = delegationCert.ephemeralPk,
            validFrom = delegationCert.validFrom,
            validUntil = delegationCert.validUntil,
            scope = delegationCert.scope,
            delegationSig = delegationCert.delegationSig,
        )

        // Android self-issued credentials are key-only (no App Attest binding),
        // so selfIssuerAttestation is always omitted here (ADR-0028 scope).
        return Presentation(
            v = 2,
            header = credential.header,
            headerSig = credential.headerSig,
            challenge = challenge,
            challengeSig = challengeSig.to0xHex(),
            disclosed = disclosed,
            timestamp = nowSec,
            holderLongTermPk = holderPk.to0xHex(),
            anchor = credential.anchor,
            verifierRequest = pendingRequest,
            delegation = delegation,
            hardwareAttestation = null,
            selfIssuerAttestation = null,
        )
    }

    /** 32-byte random nonce as lowercase hex (no 0x) for open self-presentations. */
    fun selfNonceHex(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.toHex()
    }
}
