// Server-blind sealing for Verify & Pay (ADR-0031). Android port of
// CommerceSeal.swift. The holder seals its response to the merchant's ephemeral
// X-Wing public key (published in the request) so the relay server only stores
// ciphertext. Reuses the BLE transport's HPKE (XWingMLKEM768X25519 +
// AES-256-GCM): here the holder is the HPKE *sender* and the merchant is the
// *recipient* (owns the keypair).
//
// CROSS-AGENT DEPENDENCY: the X-Wing HPKE transport (the Android analog of the
// iOS TransportVerifier / TransportHolder) is owned by the transport/BLE slice
// and is injected here through [TransportHolder] / [TransportSenderFactory].
// This file defines only those seams plus the requestId-bound (de)serialization
// so the commerce layer compiles and integrates the moment that slice lands.
// The HPKE info binding (sessionId = requestId, serviceUuid = "commerce") must
// match iOS exactly for an iOS merchant to open an Android holder's seal.

package com.elabify.app.maknoon.miniapp

import android.util.Base64
import org.json.JSONObject

/** What the holder POSTs and the merchant polls, opaque to the server. */
data class CommerceSealedEnvelope(
    val requestId: String,
    val encapsulatedKey: String,  // base64
    val sealed: String,           // base64 (HPKE ciphertext)
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("requestId", requestId)
        put("encapsulatedKey", encapsulatedKey)
        put("sealed", sealed)
    }

    companion object {
        fun fromJson(o: JSONObject): CommerceSealedEnvelope = CommerceSealedEnvelope(
            requestId = o.optString("requestId", ""),
            encapsulatedKey = o.optString("encapsulatedKey", ""),
            sealed = o.optString("sealed", ""),
        )
    }
}

/** A live HPKE sender (holder side): seals one or more plaintexts to a recipient. */
interface TransportSender {
    /** The X-Wing encapsulated key (KEM output) to publish alongside the ciphertext. */
    val encapsulatedKey: ByteArray
    fun seal(plaintext: ByteArray): ByteArray
}

/** A live HPKE recipient (merchant side): opens ciphertexts sealed to its keypair. */
interface TransportRecipient {
    fun open(ciphertext: ByteArray): ByteArray
}

/**
 * The merchant's ephemeral X-Wing keypair. The public key rides in the signed
 * paymentTerms (base64); the private key stays on the merchant device to open
 * the polled response. Provided by the transport slice (X-Wing HPKE).
 */
interface TransportHolder {
    val publicKeyBase64: String
    fun makeRecipient(encapsulatedKey: ByteArray, sessionId: String, serviceUuid: String): TransportRecipient
}

/**
 * Builds an HPKE sender to a published recipient public key. The Android analog
 * of iOS TransportVerifier.makeSender. Injected by the transport slice; the
 * commerce layer never owns the crypto.
 */
fun interface TransportSenderFactory {
    fun makeSender(recipientPublicKeyBase64: String, sessionId: String, serviceUuid: String): TransportSender
}

object CommerceSeal {
    /** serviceUuid slot of the HPKE info; fixed for the commerce channel. Matches iOS. */
    private const val CONTEXT = "commerce"

    /**
     * Holder: seal a JSON value to the merchant's published pubkey, binding the
     * HPKE context to the requestId. [json] is the already-serialized response.
     */
    fun seal(
        json: JSONObject,
        toPublicKeyBase64: String,
        requestId: String,
        senderFactory: TransportSenderFactory,
    ): CommerceSealedEnvelope {
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        val sender = senderFactory.makeSender(toPublicKeyBase64, requestId, CONTEXT)
        val ciphertext = sender.seal(plaintext)
        return CommerceSealedEnvelope(
            requestId = requestId,
            encapsulatedKey = b64(sender.encapsulatedKey),
            sealed = b64(ciphertext),
        )
    }

    /** Merchant: open a sealed envelope with the ephemeral keypair it generated. */
    fun open(env: CommerceSealedEnvelope, keypair: TransportHolder): JSONObject {
        val encKey = Base64.decode(env.encapsulatedKey, Base64.NO_WRAP)
        val ciphertext = Base64.decode(env.sealed, Base64.NO_WRAP)
        val recipient = keypair.makeRecipient(encKey, env.requestId, CONTEXT)
        val plaintext = recipient.open(ciphertext)
        return JSONObject(String(plaintext, Charsets.UTF_8))
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
}
