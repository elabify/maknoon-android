// Builds + signs a CommerceRequest entirely on-device, no server (ADR-0031).
// Android port of CommerceRequestFactory.swift.
//
// Mirrors the iOS hostVerifierRequest's self-signed request signing but
// generates the challenge + requestId locally (serverless), and adds a
// merchantSig over the payment terms. The holder validates the verifierRequest
// via the identity slice's self-signed tier (inline pubkey + signature).
//
// The verifier-request construction + signing is delegated to the identity
// slice (CommerceHolderContext.buildSignedVerifierRequest) so its
// canonicalization stays byte-identical to the verifier server. This factory
// owns the payment-terms signing (the merchant key, canonicalize, ML-DSA).

package com.elabify.app.maknoon.miniapp

import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.toHex
import java.security.SecureRandom

class CommerceRequestFactoryException(override val message: String) : Exception(message)

object CommerceRequestFactory {

    /** Built request + the ephemeral keypair the holder seals its response to. */
    data class Built(val request: CommerceRequest, val responseKeypair: TransportHolder)

    /**
     * Build + sign a CommerceRequest. Blocking (the registry lookup + signing
     * touch the network/crypto); call off the main thread.
     */
    fun build(
        ctx: CommerceHolderContext,
        installedAppId: String,
        merchantName: String,
        schema: String?,
        requiredClaims: List<String>,
        issuers: List<String>?,
        identityMaxAgeSec: Long?,
        fiatAmount: String,
        fiatCode: String,
        acceptedRails: List<PaymentRail>,
        reference: String?,
        floorMinor: Long?,
        lane: CommerceLane,
        ttlSec: Long = 300,
    ): Built {
        // Stable merchant identity (provisioned on first use), NOT the holder's
        // consumer identity, so the customer's wallet shows a consistent merchant.
        val merchantDid = ctx.merchantIdentity.ensureProvisioned(installedAppId)
        val merchantPkHex = ctx.merchantIdentity.publicKeyHex(installedAppId)
            ?: throw CommerceRequestFactoryException("Unlock your identity to issue a charge.")

        val now = System.currentTimeMillis() / 1000

        // Omit the inline pubkey only when this merchant DID is registered (the
        // green "Verified" tier); else inline it (self-signed). Never omit an
        // unregistered key, or the holder rejects the request as unknown.
        val registered = ctx.registryLookup(merchantDid) != null
        val inlinePk: String? = if (registered) null else merchantPkHex

        // The identity slice builds + signs the verifier request (its
        // canonicalization must match the verifier server). We fix the
        // challenge + requestId here so they do not re-randomize.
        val verifierRequest = ctx.buildSignedVerifierRequest(
            installedAppId = installedAppId,
            merchantName = merchantName,
            schema = schema,
            requiredClaims = requiredClaims,
            issuers = issuers,
            challengeHex = "0x" + randomHex(32),
            requestId = java.util.UUID.randomUUID().toString(),
            issuedAt = now,
            expiresAt = now + ttlSec,
            inlinePublicKeyHex = inlinePk,
        )

        // Ephemeral keypair the holder seals its response to (server stays
        // blind). The public key rides in the signed paymentTerms; the private
        // key stays on the merchant device to decrypt the polled response.
        val responseKeypair = ctx.newTransportHolder()
        val terms = PaymentTerms(
            fiatAmount = fiatAmount, fiatCode = fiatCode, acceptedRails = acceptedRails,
            reference = reference, nonce = "0x" + randomHex(16),
            floorMinor = floorMinor, expiresAt = now + ttlSec,
            responseKey = responseKeypair.publicKeyBase64,
        )
        val termsSig = signTerms(terms, ctx, installedAppId)

        val request = CommerceRequest(
            v = 1, verifierRequest = verifierRequest, paymentTerms = terms, lane = lane,
            merchantName = merchantName, identityMaxAgeSec = identityMaxAgeSec, merchantSig = termsSig,
        )
        return Built(request, responseKeypair)
    }

    /**
     * Canonicalize the payment terms (signature field never present) and
     * ML-DSA-sign with the merchant key, returning 0x-hex. Byte-identical to the
     * holder-side validator's canonicalization (CanonicalJson sorts keys + NFC).
     */
    private fun signTerms(terms: PaymentTerms, ctx: CommerceHolderContext, installedAppId: String): String {
        val msg = canonicalize(terms.canonicalMap())
        val sig = ctx.merchantIdentity.sign(installedAppId, msg)
        return "0x" + sig.toHex()
    }

    private fun randomHex(byteCount: Int): String {
        val b = ByteArray(byteCount).also { SecureRandom().nextBytes(it) }
        return b.toHex()
    }
}
