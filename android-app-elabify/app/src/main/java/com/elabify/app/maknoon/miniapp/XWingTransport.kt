// X-Wing HPKE transport for Verify & Pay (the Android analog of iOS
// TransportVerifier / TransportHolder). Implements the seams CommerceSeal.kt
// declares, backed by pq-crypto-core's X-Wing HPKE (uniffi), which is proven
// byte-exact with Apple CryptoKit's
// HPKE.Ciphersuite.XWingMLKEM768X25519_SHA256_AES_GCM_256 (both directions,
// pq-crypto-rs/tests/xwing_parity.rs).
//
// Roles:
//   - Holder (Android) seals its presentation + payment proof to the
//     merchant's published X-Wing public key -> XWingSender (HPKE sender).
//   - Merchant opens the holder's sealed response with the ephemeral keypair
//     it generated -> XWingKeyPair (TransportHolder) + XWingRecipient.
//
// The HPKE `info` binds the session: UTF-8 "elabify-engage-1|<sessionId>|
// <serviceUuid>", matching iOS TransportCiphersuite.info exactly. For commerce
// CommerceSeal passes sessionId = requestId and serviceUuid = "commerce".

package com.elabify.app.maknoon.miniapp

import android.util.Base64
import uniffi.pq_crypto_core.XWingSealed
import uniffi.pq_crypto_core.xwingGenerateSecretKey
import uniffi.pq_crypto_core.xwingOpen
import uniffi.pq_crypto_core.xwingPublicKey
import uniffi.pq_crypto_core.xwingSeal

/** Engagement version baked into the HPKE info. Must match iOS Models.swift
 *  TransportEngagement.version byte-for-byte or seals will not open. */
private const val ENGAGE_VERSION = "elabify-engage-1"

private fun hpkeInfo(sessionId: String, serviceUuid: String): ByteArray =
    "$ENGAGE_VERSION|$sessionId|$serviceUuid".toByteArray(Charsets.UTF_8)

// Public keys cross the wire as standard base64 with padding, no line breaks
// (matches iOS rawRepresentation.base64EncodedString()).
private fun b64Decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
private fun b64Encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

/**
 * Holder side (HPKE sender). Single-shot: `seal` encapsulates to the
 * recipient's public key, runs the HPKE Base-mode key schedule over `info`,
 * and AES-256-GCM seals the plaintext, caching the encapsulated key for the
 * `encapsulatedKey` property (which CommerceSeal reads after `seal`).
 */
class XWingSender(
    private val recipientPublicKey: ByteArray,
    private val info: ByteArray,
) : TransportSender {
    private var cachedEncapsulatedKey: ByteArray? = null

    override val encapsulatedKey: ByteArray
        get() = cachedEncapsulatedKey
            ?: error("XWingSender.seal() must be called before reading encapsulatedKey")

    override fun seal(plaintext: ByteArray): ByteArray {
        val sealed: XWingSealed = xwingSeal(recipientPublicKey, info, plaintext)
        cachedEncapsulatedKey = sealed.encapsulatedKey
        return sealed.ciphertext
    }
}

/** Builds an HPKE sender to a published recipient public key (base64). */
val XWingSenderFactory = TransportSenderFactory { recipientPublicKeyBase64, sessionId, serviceUuid ->
    XWingSender(b64Decode(recipientPublicKeyBase64), hpkeInfo(sessionId, serviceUuid))
}

/**
 * Merchant side. An ephemeral X-Wing keypair: the 32-byte secret stays on the
 * device; the 1216-byte public key (base64) rides in the signed payment terms.
 * `makeRecipient` opens the holder's sealed response.
 */
class XWingKeyPair private constructor(private val secretKey: ByteArray) : TransportHolder {
    override val publicKeyBase64: String = b64Encode(xwingPublicKey(secretKey))

    override fun makeRecipient(
        encapsulatedKey: ByteArray,
        sessionId: String,
        serviceUuid: String,
    ): TransportRecipient =
        XWingRecipient(secretKey, encapsulatedKey, hpkeInfo(sessionId, serviceUuid))

    companion object {
        /** Fresh ephemeral keypair (OS randomness via the native getrandom backend). */
        fun generate(): XWingKeyPair = XWingKeyPair(xwingGenerateSecretKey())

        /** Reconstruct from a previously generated 32-byte secret (e.g. to open a
         *  response after a process restart, if the secret was persisted). */
        fun fromSecretKey(secretKey: ByteArray): XWingKeyPair = XWingKeyPair(secretKey)
    }
}

/** A live HPKE recipient bound to one (secretKey, encapsulatedKey, info). */
class XWingRecipient(
    private val secretKey: ByteArray,
    private val encapsulatedKey: ByteArray,
    private val info: ByteArray,
) : TransportRecipient {
    override fun open(ciphertext: ByteArray): ByteArray =
        xwingOpen(secretKey, encapsulatedKey, info, ciphertext)
}
