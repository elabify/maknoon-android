// Maknoon Pay, the client-side peer-to-peer "verify and pay" protocol
// (ADR-0031). Android port of Commerce.swift.
//
// These types COMPOSE around the canonical verifier request and presentation
// rather than mutating them, so the byte-for-byte canonicalization contract
// with the verifier server stays intact. The commerce exchange is
// Maknoon-to-Maknoon and serverless: the verifier server is NOT in this path.
//
// PORTING NOTE: Swift used Codable structs. The Android identity/verifier slice
// has not landed the typed VerifierRequest / Presentation yet, and the wire
// canonicalization must survive untouched, so we carry the embedded verifier
// request and the presentation as raw org.json JSONObjects (round-tripped
// verbatim) and only type the fields the commerce layer itself reads. Our own
// commerce envelopes (PaymentRail, PaymentTerms, CommerceRequest,
// CommerceResponse, CommercePayment) are full data classes with explicit
// org.json (de)serialization so they round-trip losslessly across platforms.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import org.json.JSONArray
import org.json.JSONObject

/**
 * One acceptable way for a merchant to be paid. The merchant fully specifies a
 * payable rail (it computes the fiat to crypto conversion and the RPC
 * endpoint), so the holder needs no rate logic and no network table: it signs
 * [amount] of [asset] to [address] and the merchant broadcasts via [rpcURL].
 */
data class PaymentRail(
    val chain: String,            // "bitcoin" | "ethereum" | "solana" | "tron" | "lightning"
    val network: String?,         // chain-specific network id, e.g. "sepolia" (null for Lightning)
    val asset: String,            // "ETH" | "USDC" | "BTC" | "SOL" | "TRX" | "sat" ...
    val address: String,          // receiving address (or Lightning account ref)
    val amount: String? = null,   // exact crypto amount to pay on this rail (merchant-computed)
    val assetContract: String? = null, // ERC-20 / SPL / TRC-20 contract; null = native coin
    val assetDecimals: Int? = null,    // token decimals; null defaults to 18 (native EVM)
    val rpcURL: String? = null,        // JSON-RPC endpoint for serverless signing + broadcast
) {
    /**
     * Human network label for display: a built-in EVM network's name when the
     * id matches, else the raw id (custom RPCs), else the capitalized chain.
     */
    val displayNetwork: String
        get() {
            val n = network
            if (!n.isNullOrEmpty()) {
                return EthereumNetwork.fromRawValue(n)?.displayName ?: n
            }
            return chain.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("chain", chain)
        put("network", network ?: JSONObject.NULL)
        put("asset", asset)
        put("address", address)
        put("amount", amount ?: JSONObject.NULL)
        put("assetContract", assetContract ?: JSONObject.NULL)
        put("assetDecimals", assetDecimals ?: JSONObject.NULL)
        put("rpcURL", rpcURL ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): PaymentRail = PaymentRail(
            chain = o.optString("chain", ""),
            network = o.optStringOrNull("network"),
            asset = o.optString("asset", ""),
            address = o.optString("address", ""),
            amount = o.optStringOrNull("amount"),
            assetContract = o.optStringOrNull("assetContract"),
            assetDecimals = if (o.isNull("assetDecimals")) null else o.optInt("assetDecimals").takeIf { o.has("assetDecimals") },
            rpcURL = o.optStringOrNull("rpcURL"),
        )

        fun listFromJson(arr: JSONArray?): List<PaymentRail> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
        }
    }
}

/**
 * What the merchant wants paid, presented alongside the identity ask. Signed by
 * the merchant (see [CommerceRequest.merchantSig]) and bound to the identity
 * request via the shared [nonce], which the holder echoes in its response.
 */
data class PaymentTerms(
    val fiatAmount: String,         // decimal notional, e.g. "12.50"
    val fiatCode: String,           // "USD" | "AED" ...
    val acceptedRails: List<PaymentRail>,
    val reference: String?,         // merchant order reference
    val nonce: String,              // 0x hex anti-replay token, echoed in the response
    val floorMinor: Long?,          // per-tap auto-approve ceiling, minor units of fiatCode
    val expiresAt: Long,            // unix seconds
    // Merchant's ephemeral X-Wing public key (base64) the holder seals its
    // response to, so the relay server stays blind (ADR-0031). Signed as part
    // of paymentTerms via merchantSig. null = legacy plaintext relay.
    val responseKey: String? = null,
) {
    /**
     * Whether a meaningful (non-zero) fiat notional was provided. Testnets have
     * no fiat rate, so the merchant sends an empty amount and we show crypto only.
     */
    val hasFiatValue: Boolean
        get() {
            val a = fiatAmount.trim()
            return a.isNotEmpty() && (a.toDoubleOrNull() ?: 0.0) > 0
        }

    /**
     * Canonicalization-ready ordered map (signature field intentionally absent).
     * Byte-identical to the iOS validator's view of paymentTerms: every key the
     * Codable would emit, with null for absent optionals, so canonicalize()
     * produces the same bytes the merchant signed.
     */
    fun canonicalMap(): Map<String, Any?> = linkedMapOf(
        "fiatAmount" to fiatAmount,
        "fiatCode" to fiatCode,
        "acceptedRails" to acceptedRails.map { it.canonicalMap() },
        "reference" to reference,
        "nonce" to nonce,
        "floorMinor" to floorMinor,
        "expiresAt" to expiresAt,
        "responseKey" to responseKey,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("fiatAmount", fiatAmount)
        put("fiatCode", fiatCode)
        put("acceptedRails", JSONArray().also { acceptedRails.forEach { r -> it.put(r.toJson()) } })
        put("reference", reference ?: JSONObject.NULL)
        put("nonce", nonce)
        put("floorMinor", floorMinor ?: JSONObject.NULL)
        put("expiresAt", expiresAt)
        put("responseKey", responseKey ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): PaymentTerms = PaymentTerms(
            fiatAmount = o.optString("fiatAmount", ""),
            fiatCode = o.optString("fiatCode", ""),
            acceptedRails = PaymentRail.listFromJson(o.optJSONArray("acceptedRails")),
            reference = o.optStringOrNull("reference"),
            nonce = o.optString("nonce", ""),
            floorMinor = if (o.isNull("floorMinor")) null else o.optLong("floorMinor").takeIf { o.has("floorMinor") },
            expiresAt = o.optLong("expiresAt", 0),
            responseKey = o.optStringOrNull("responseKey"),
        )
    }
}

private fun PaymentRail.canonicalMap(): Map<String, Any?> = linkedMapOf(
    "chain" to chain,
    "network" to network,
    "asset" to asset,
    "address" to address,
    "amount" to amount,
    "assetContract" to assetContract,
    "assetDecimals" to assetDecimals,
    "rpcURL" to rpcURL,
)

/** Which lane the merchant is offering for this transaction. */
enum class CommerceLane(val rawValue: String) {
    TAP("tap"),     // compact sanctions attribute + offline-signed pay, single NFC tap
    FULL("full");   // full attribute review, one confirmation, over QR/BLE

    companion object {
        fun fromRaw(raw: String?): CommerceLane =
            entries.firstOrNull { it.rawValue == raw } ?: FULL
    }
}

/**
 * Merchant to holder. Wraps the (already self/registry-signed) verifier request
 * and adds the payment ask. [merchantSig] is an ML-DSA-65 signature over
 * canonicalize(paymentTerms) by the merchant key, so the payment ask is
 * independently verifiable and bound to the verifier request's requestId.
 *
 * The embedded verifier request is carried as a raw [verifierRequest] JSON
 * object so the signed bytes are never reshaped (cross-platform canonicalize).
 */
data class CommerceRequest(
    val v: Int,                          // 1
    val verifierRequest: JSONObject,
    val paymentTerms: PaymentTerms,
    val lane: CommerceLane,
    val merchantName: String?,
    // Max age (seconds) for a freshness-gated attribute (e.g. a sanctions
    // screening's sanctionsScreenedAt); null disables the freshness gate.
    val identityMaxAgeSec: Long?,
    val merchantSig: String?,
) {
    /** Verifier-request fields the commerce layer reads, parsed lazily. */
    val verifierDid: String get() = verifierRequest.optString("verifierDid", "")
    val verifierPublicKey: String? get() = verifierRequest.optStringOrNull("verifierPublicKey")
    val challenge: String get() = verifierRequest.optString("challenge", "")
    val requestId: String get() = verifierRequest.optString("requestId", "")

    /** filter.requiredClaims as a list (empty when absent). */
    val requiredClaims: List<String>
        get() {
            val filter = verifierRequest.optJSONObject("filter") ?: return emptyList()
            val arr = filter.optJSONArray("requiredClaims") ?: return emptyList()
            return (0 until arr.length()).map { arr.optString(it) }
        }

    /** filter.schemas { mode, list } as a typed view, or null when absent. */
    val schemasClause: SchemasClause?
        get() {
            val filter = verifierRequest.optJSONObject("filter") ?: return null
            val sc = filter.optJSONObject("schemas") ?: return null
            val list = sc.optJSONArray("list")?.let { a -> (0 until a.length()).map { a.optString(it) } }
            return SchemasClause(sc.optStringOrNull("mode"), list)
        }

    data class SchemasClause(val mode: String?, val list: List<String>?)

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", v)
        put("verifierRequest", verifierRequest)
        put("paymentTerms", paymentTerms.toJson())
        put("lane", lane.rawValue)
        put("merchantName", merchantName ?: JSONObject.NULL)
        put("identityMaxAgeSec", identityMaxAgeSec ?: JSONObject.NULL)
        put("merchantSig", merchantSig ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): CommerceRequest = CommerceRequest(
            v = o.optInt("v", 1),
            verifierRequest = o.optJSONObject("verifierRequest") ?: JSONObject(),
            paymentTerms = PaymentTerms.fromJson(o.optJSONObject("paymentTerms") ?: JSONObject()),
            lane = CommerceLane.fromRaw(o.optStringOrNull("lane")),
            merchantName = o.optStringOrNull("merchantName"),
            identityMaxAgeSec = if (o.isNull("identityMaxAgeSec")) null
            else o.optLong("identityMaxAgeSec").takeIf { o.has("identityMaxAgeSec") },
            merchantSig = o.optStringOrNull("merchantSig"),
        )
    }
}

/**
 * What the holder committed to pay. [signedTx] (offline raw tx the merchant
 * broadcasts) and [settlementRef] (an already-produced on-chain txHash or a
 * paid BOLT11) are mutually exclusive.
 */
data class CommercePayment(
    val rail: PaymentRail,
    val signedTx: String? = null,
    val settlementRef: String? = null,
)

/**
 * Holder to merchant. The identity presentation plus the payment commitment,
 * bound back to the request via [nonce]. The presentation is carried as a raw
 * JSON object so its signed bytes survive verbatim for offline verification.
 */
data class CommerceResponse(
    val v: Int,                  // 1
    val presentation: JSONObject,
    val payment: CommercePayment,
    val nonce: String,           // echoes PaymentTerms.nonce
)
