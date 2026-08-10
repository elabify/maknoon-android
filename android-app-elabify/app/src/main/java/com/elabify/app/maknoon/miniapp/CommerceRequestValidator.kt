// Holder-side authentication of a CommerceRequest (ADR-0031). Android port of
// CommerceRequestValidator.swift. Two checks:
//   1. The embedded VerifierRequest's signature (delegated to the identity
//      slice's validator) authenticates the merchant identity + the integrity
//      of the ask, and yields the trust tier (registered / self-signed / unknown).
//   2. merchantSig over paymentTerms against the same verifier pubkey, so a
//      relay/server cannot tamper with the payment terms or, crucially, swap the
//      responseKey the holder seals its response to. Without this, a malicious
//      relay could substitute its own key and read the response (defeating
//      server-blindness). With it, server-blindness holds against an active server.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import com.elabify.app.maknoon.R
import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.hexToBytes

object CommerceRequestValidator {

    /**
     * Coarse trust tier for UI styling (color/icon), distinct from [Result.ok]
     * which is purely "did the signatures verify". A self-signed merchant can be
     * ok yet must NOT render as the green "verified" tier.
     */
    enum class Tier { REGISTERED, SELF_SIGNED, UNKNOWN }

    data class Result(
        val ok: Boolean,
        val tier: Tier,
        val tierLabel: String,
        val reason: String?,
    )

    /**
     * Authenticate the request. Blocking (registry + crypto); call off the main
     * thread. [res] is only ever used to resolve the user-facing tier label and
     * block reason, which the pay sheet renders verbatim.
     */
    fun validate(
        request: CommerceRequest,
        ctx: CommerceHolderContext,
        res: Context,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): Result {
        // 1. Authenticate the verifier request (signature + expiry + trust tier).
        val decision = ctx.validateVerifierRequest(request.verifierRequest, nowSec)
        if (decision == null || !decision.isValid) {
            return Result(
                ok = false, tier = Tier.UNKNOWN,
                tierLabel = res.getString(R.string.app_merchant_tier_unverified),
                reason = res.getString(R.string.app_merchant_request_sig_invalid),
            )
        }
        val tier: Tier
        val tierLabel: String
        when (val t = decision.tier) {
            is CommerceVerifierDecision.Tier.Registered -> {
                tier = Tier.REGISTERED
                tierLabel = res.getString(R.string.app_merchant_tier_verified, t.name)
            }
            is CommerceVerifierDecision.Tier.SelfSigned -> {
                tier = Tier.SELF_SIGNED
                tierLabel = res.getString(R.string.app_merchant_tier_self_signed)
            }
            is CommerceVerifierDecision.Tier.Unknown -> {
                tier = Tier.UNKNOWN
                tierLabel = res.getString(R.string.app_merchant_tier_unverified_merchant)
            }
        }

        // 2. Verify merchantSig over paymentTerms against the verifier pubkey.
        // Use the inline pubkey (self-signed) or, for a registered merchant that
        // omits it, resolve the pubkey from the registry (same source the
        // verifierRequest tier check above used).
        val resolvedPubHex: String? = request.verifierPublicKey
            ?: ctx.registryLookup(request.verifierDid)?.verifierPublicKey

        val pub = resolvedPubHex?.let { hex(it) }
        val sig = request.merchantSig?.let { hex(it) }
        if (pub == null || sig == null) {
            return Result(ok = false, tier = tier, tierLabel = tierLabel,
                reason = res.getString(R.string.app_merchant_sig_missing))
        }
        return try {
            val msg = canonicalize(request.paymentTerms.canonicalMap())
            val valid = ctx.mldsaVerify(pub, sig, msg)
            Result(
                ok = valid, tier = tier, tierLabel = tierLabel,
                reason = if (valid) null else res.getString(R.string.app_merchant_sig_mismatch),
            )
        } catch (_: Exception) {
            Result(ok = false, tier = tier, tierLabel = tierLabel,
                reason = res.getString(R.string.app_merchant_terms_unverifiable))
        }
    }

    private fun hex(s: String): ByteArray? {
        val h = if (s.startsWith("0x")) s.substring(2) else s
        if (h.length % 2 != 0) return null
        return try { hexToBytes(h) } catch (_: Exception) { null }
    }
}
