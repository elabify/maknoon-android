// YubiKey client for the Identity-Sandwich hardware wrap, ported to match
// the iOS Maknoon/YubiKey scheme but built on Yubico's official
// yubikit-android (GMS-free: it talks to NFC / USB directly, no Play
// services). The hand-rolled CTAP2 + PIN-protocol stack the iOS app
// carries (CTAP2/CTAP2Client.swift, CTAP2/PINProtocolV1.swift) is NOT
// ported: yubikit-android's Ctap2Session exposes raw makeCredential /
// getAssertions with arbitrary extensions (so we can drive hmac-secret),
// and its ClientPin class does the getInfo-driven PIN handling and the
// PIN/UV-auth-protocol crypto (ECDH shared secret, AES-CBC salt encrypt,
// HMAC saltAuth) for us. So we get correct behaviour by construction
// instead of re-implementing CTAP2 by hand.
//
// Transport: this client is transport-agnostic. The app hands it a
// yubikit `YubiKeyDevice` (an NfcYubiKeyDevice over NFC, or a
// UsbYubiKeyDevice over USB-C). The app owns the NFC reader-mode /
// foreground-dispatch radio ownership so it never collides with the
// passport reader; see the app's YubiKeyNfcController.
//
// Wrap derivation (MUST stay byte-for-byte with iOS YubiKeyClient.swift
// enrollHMACSecretOverNFC + getAssertionHMACSecret so a key enrolled on
// either platform unlocks on the other):
//
//   rpId            = "maknoon.elabify.com"
//   clientDataHash  = SHA-256("maknoon-wrap-v1" || salt || deviceSerial)
//   hmac-secret salt = the 32-byte wrap salt (verbatim)
//   wrap secret      = the 32-byte hmac-secret output (decrypted), used
//                      as-is. iOS returns assertResult.hmacSecretOutput
//                      directly as `secret`; we return the same bytes.
//
// Note the two iOS code paths: the legacy v1 path SHA-256s a raw FIDO2
// signature (broken because the FIDO2 signature counter drifts). The v2
// path (enrollHMACSecretOverNFC) uses the hmac-secret extension and is
// the one we mirror here, since hmac-secret is deterministic across
// calls for the same (credential, salt). wrapProtocolVersion = 2.

package com.elabify.musnad.hardware.yubikey

import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.core.util.Result
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocol
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.webauthn.AuthenticatorData
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives a YubiKey over a yubikit [YubiKeyDevice]: reads the serial
 * (Management application) and runs the FIDO2 hmac-secret enroll /
 * recompute used by the Identity-Sandwich hardware wrap.
 *
 * One instance is cheap; the device handle is passed per call because
 * NFC taps produce a fresh [YubiKeyDevice] each time.
 */
class YubiKeyClient {

    /** Fixed RP id for the Maknoon Identity Sandwich wrap. Matches iOS
     *  YubiKeyClient.rpId verbatim. */
    val rpId: String = RP_ID

    /** What the YubiKey returned from an enroll: the FIDO2 credential id
     *  (hex) to persist in IdentityPromotion, plus the 32-byte wrap
     *  secret the caller HKDFs into the AES-GCM wrap key. Mirrors the
     *  iOS (credentialIdHex, secret) tuple. */
    data class EnrollResult(
        val credentialIdHex: String,
        val secret: ByteArray,
        /** The 32-byte hmac-secret salt (hex) that produced [secret]. The
         *  second-factor wrap (ADR-0032) needs this to persist alongside the
         *  credential so the same secret recomputes at unlock; enroll
         *  generates the salt, so it MUST be surfaced here. */
        val saltHex: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is EnrollResult &&
                credentialIdHex == other.credentialIdHex &&
                secret.contentEquals(other.secret) &&
                saltHex == other.saltHex

        override fun hashCode(): Int {
            var h = credentialIdHex.hashCode()
            h = 31 * h + secret.contentHashCode()
            h = 31 * h + saltHex.hashCode()
            return h
        }
    }

    /** PIN handling outcome from inspecting getInfo, mirroring the iOS
     *  fix: only prompt for a PIN when one is actually set. */
    sealed class PinRequirement {
        /** No PIN, no UV needed; enroll can proceed without a PIN. */
        object NotRequired : PinRequirement()

        /** A clientPin is set on the key; the caller must collect it and
         *  pass it to [enroll] / [recomputeSecret]. */
        object PinSet : PinRequirement()

        /** The authenticator needs user verification for hmac-secret but
         *  has no clientPin set. We MUST NOT silently prompt for a
         *  non-existent PIN (the iOS bug). The caller surfaces this so
         *  the user sets a PIN on the key first. */
        object PinMustBeSet : PinRequirement()
    }

    class YubiKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // MARK: -- PIN inspection (correct getInfo-driven handling)

    /**
     * Inspect the authenticator and decide the PIN requirement, fixing
     * the iOS bug (which prompted for a PIN even on keys that have none).
     *
     * Logic, per CTAP2 getInfo.options:
     *   - options["clientPin"] == true  -> a PIN is SET   -> [PinRequirement.PinSet]
     *   - options["clientPin"] == false/absent (no PIN) and the key needs
     *     UV for hmac-secret -> [PinRequirement.PinMustBeSet]
     *   - otherwise -> [PinRequirement.NotRequired]
     *
     * In practice every YubiKey 5 that advertises hmac-secret enforces
     * UV (a PIN) for the extension, so the no-PIN case resolves to
     * PinMustBeSet. We still read the options rather than assume.
     */
    fun pinRequirement(session: Ctap2Session): PinRequirement {
        val info = session.cachedInfo ?: session.info
        val options = info.options
        val clientPin = options["clientPin"] as? Boolean
        if (clientPin == true) return PinRequirement.PinSet
        // No clientPin set. hmac-secret on YubiKey 5 always requires UV,
        // and the only UV modality these keys expose is the FIDO2 PIN.
        // So enrollment cannot proceed without one being set first.
        return PinRequirement.PinMustBeSet
    }

    /**
     * Open ONE FIDO2 connection to the tapped key and run [block] with a live
     * [Ctap2Session]. This is the critical NFC pattern (yubikit 3.x): the
     * connection is opened, the session built on it, [block] runs, and the
     * connection is closed when [block] returns. ALL CTAP work for an operation
     * (pinRequirement + enroll, or recompute) MUST happen inside one [block] /
     * one tap. The earlier code created a session and then issued clientPin /
     * makeCredential on it after the opening call had returned; on device that
     * produced getInfo-succeeds-but-clientPin-fails (0x6D00) and "Transceive
     * failed", because the connection lifecycle did not span the whole flow.
     * Keeping everything inside one open connection fixes that.
     */
    fun <T> useSession(device: YubiKeyDevice, block: (Ctap2Session) -> T): T {
        val latch = CountDownLatch(1)
        val value = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>(null)
        device.requestConnection(SmartCardConnection::class.java) { connResult ->
            try {
                val connection = connResult.value // SmartCardConnection, or throws IOException
                val session = Ctap2Session(connection)
                value.set(block(session))
            } catch (e: Throwable) {
                failure.set(e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure.get()?.let { e ->
            if (e is YubiKeyException) throw e
            throw YubiKeyException("YubiKey error: ${e.message}", e)
        }
        return value.get()
    }

    /** Verify the tapped key advertises the hmac-secret extension we need to
     *  derive a stable wrap key. Logs the full getInfo for diagnostics. Call
     *  this first inside [useSession] before pinRequirement / enroll. */
    fun verifyHmacSecret(session: Ctap2Session) {
        val info = session.cachedInfo ?: session.info
        android.util.Log.d(
            "YubiKeyClient",
            "getInfo: versions=${info.versions} " +
                "aaguid=${runCatching { info.aaguid?.joinToString("") { b -> "%02x".format(b) } }.getOrNull()} " +
                "options=${info.options} " +
                "pinUvAuthProtocols=${info.pinUvAuthProtocols} " +
                "extensions=${info.extensions}",
        )
        if (!info.extensions.contains("hmac-secret")) {
            throw YubiKeyException(
                "This security key's firmware does not advertise the hmac-secret extension. " +
                    "Maknoon needs hmac-secret to derive a stable wrap key; try a YubiKey 5 " +
                    "series with firmware 5.2 or newer.",
            )
        }
    }

    // MARK: -- enroll (makeCredential + getAssertion, hmac-secret)

    /**
     * Enroll a YubiKey for the Identity-Sandwich wrap via FIDO2
     * hmac-secret. Mirrors iOS enrollHMACSecretOverNFC: makeCredential
     * (extensions = {hmac-secret: true}) then getAssertion with the
     * 32-byte salt, returning the credential id (hex) and the 32-byte
     * hmac-secret output.
     *
     * @param session a [Ctap2Session] for which hmac-secret is verified
     *   present (use [openFido]).
     * @param label the user-facing credential name (FIDO2 user.name).
     * @param salt exactly 32 bytes; the persisted wrap salt.
     * @param deviceSerial the Management-application serial; folded into
     *   the clientDataHash so the wrap is bound to this physical device.
     * @param pin the FIDO2 PIN when one is set ([PinRequirement.PinSet]);
     *   null only when [PinRequirement.NotRequired]. Passing null on a
     *   key that needs UV will fail at the authenticator rather than us
     *   silently faking a PIN.
     */
    fun enroll(
        session: Ctap2Session,
        label: String,
        salt: ByteArray,
        deviceSerial: String,
        pin: CharArray?,
    ): EnrollResult {
        require(salt.size == 32) { "wrap salt must be 32 bytes" }

        val cdh = wrapClientDataHash(salt, deviceSerial)
        val protocol = selectPinUvAuthProtocol(session)

        // PIN/UV auth: obtain a pinUvAuthToken and the ECDH shared secret.
        // ClientPin does the getInfo-driven protocol selection + crypto.
        val pinAuth = preparePinUvAuth(session, protocol, pin)

        // makeCredential with hmac-secret: true. pinUvAuthParam =
        // protocol.authenticate(pinToken, clientDataHash).
        val rp = mapOf("id" to rpId, "name" to "Maknoon Identity")
        val userId = randomBytes(16)
        val user = mapOf<String, Any>(
            "id" to userId,
            "name" to label,
            "displayName" to label,
        )
        val pubKeyCredParams = listOf(mapOf("type" to "public-key", "alg" to -7)) // ES256
        val makeExtensions = mapOf("hmac-secret" to true)

        val credentialData = session.makeCredential(
            cdh,
            rp,
            user,
            pubKeyCredParams,
            null, // excludeList
            makeExtensions,
            null, // options (defaults: rk=false, uv handled by pinUvAuthParam)
            pinAuth?.let { protocol.authenticate(it.token, cdh) },
            pinAuth?.let { protocol.version },
            null, // enterpriseAttestation
            CommandState(),
        )
        val authData = AuthenticatorData.parseFrom(ByteBuffer.wrap(credentialData.authenticatorData))
        val credentialId = authData.attestedCredentialData?.credentialId
            ?: throw YubiKeyException("YubiKey attestation did not include a credential id.")

        val secret = assertHmacSecret(
            session = session,
            credentialId = credentialId,
            clientDataHash = cdh,
            salt = salt,
            protocol = protocol,
            pinAuth = pinAuth,
        )
        return EnrollResult(credentialIdHex = credentialId.toHex(), secret = secret, saltHex = salt.toHex())
    }

    /**
     * Recompute the hmac-secret output for an already-enrolled YubiKey at
     * unlock time. Same (credential, salt) -> same 32 bytes, by design of
     * the hmac-secret extension. Mirrors iOS recomputeHMACSecretOverNFC.
     */
    fun recomputeSecret(
        session: Ctap2Session,
        credentialIdHex: String,
        salt: ByteArray,
        deviceSerial: String,
        pin: CharArray?,
    ): ByteArray {
        require(salt.size == 32) { "wrap salt must be 32 bytes" }
        val credentialId = credentialIdHex.hexToBytes()
            ?: throw YubiKeyException("Stored credential id is not hex.")
        val cdh = wrapClientDataHash(salt, deviceSerial)
        val protocol = selectPinUvAuthProtocol(session)
        val pinAuth = preparePinUvAuth(session, protocol, pin)
        return assertHmacSecret(
            session = session,
            credentialId = credentialId,
            clientDataHash = cdh,
            salt = salt,
            protocol = protocol,
            pinAuth = pinAuth,
        )
    }

    // MARK: -- internals

    private class PinUvAuth(
        /** pinUvAuthToken (decrypted) for authenticate(token, message). */
        val token: ByteArray,
        /** platform COSE keyAgreement map to transmit in the extension. */
        val keyAgreement: Map<Int, *>,
        /** ECDH shared secret used to encrypt the salt + decrypt output. */
        val sharedSecret: ByteArray,
    )

    /**
     * Run getAssertion with the hmac-secret extension and return the
     * decrypted 32-byte output. The extension input map is
     * {1: platformKeyAgreement, 2: saltEnc, 3: saltAuth, 4: protocolVersion}
     * exactly as the CTAP2 hmac-secret spec (and iOS getAssertionHMACSecret)
     * requires. yubikit's PinUvAuthProtocol.encrypt/authenticate do the
     * AES-CBC + HMAC; the output sits in the assertion's extension results
     * under "hmac-secret" and we decrypt it with the same shared secret.
     */
    private fun assertHmacSecret(
        session: Ctap2Session,
        credentialId: ByteArray,
        clientDataHash: ByteArray,
        salt: ByteArray,
        protocol: PinUvAuthProtocol,
        pinAuth: PinUvAuth?,
    ): ByteArray {
        // hmac-secret needs the platform key agreement + shared secret
        // even when (rarely) there is no PIN token; reuse the one prepared
        // for the PIN flow if present, else negotiate a fresh shared secret.
        val (keyAgreement, sharedSecret) = if (pinAuth != null) {
            pinAuth.keyAgreement to pinAuth.sharedSecret
        } else {
            val cp = ClientPin(session, protocol)
            val shared = cp.sharedSecret
            @Suppress("UNCHECKED_CAST")
            (shared.first as Map<Int, *>) to (shared.second as ByteArray)
        }

        val saltEnc = protocol.encrypt(sharedSecret, salt)
        val saltAuth = protocol.authenticate(sharedSecret, saltEnc)
        val hmacGetSecretInput = mapOf<Int, Any?>(
            1 to keyAgreement,
            2 to saltEnc,
            3 to saltAuth,
            4 to protocol.version,
        )
        val extensions = mapOf("hmac-secret" to hmacGetSecretInput)

        val allowList = listOf(mapOf("type" to "public-key", "id" to credentialId))
        val assertions = session.getAssertions(
            rpId,
            clientDataHash,
            allowList,
            extensions,
            null, // options (defaults: up=true, uv via pinUvAuthParam)
            pinAuth?.let { protocol.authenticate(it.token, clientDataHash) },
            pinAuth?.let { protocol.version },
            CommandState(),
        )
        val assertion = assertions.firstOrNull()
            ?: throw YubiKeyException("YubiKey returned no assertion for the enrolled credential.")

        val authData = AuthenticatorData.parseFrom(ByteBuffer.wrap(assertion.authenticatorData))
        val extOutputs = authData.extensions
            ?: throw YubiKeyException("YubiKey assertion carried no extension outputs (hmac-secret missing).")
        val encrypted = extOutputs["hmac-secret"] as? ByteArray
            ?: throw YubiKeyException("YubiKey assertion did not contain an hmac-secret output.")
        val decrypted = protocol.decrypt(sharedSecret, encrypted)
        // For a single 32-byte salt the hmac-secret output is 32 bytes,
        // and iOS uses it verbatim as the wrap secret.
        return decrypted
    }

    /**
     * Establish the PIN/UV auth token + ECDH shared secret via ClientPin.
     * Returns null when [pin] is null (no-PIN path). ClientPin handles the
     * getInfo-driven protocol details so this matches the authenticator's
     * configuration exactly.
     */
    private fun preparePinUvAuth(
        session: Ctap2Session,
        protocol: PinUvAuthProtocol,
        pin: CharArray?,
    ): PinUvAuth? {
        if (pin == null) return null
        val clientPin = ClientPin(session, protocol)
        val shared = clientPin.sharedSecret
        @Suppress("UNCHECKED_CAST")
        val keyAgreement = shared.first as Map<Int, *>
        val sharedSecret = shared.second as ByteArray
        // PIN token scoped to MakeCredential + GetAssertion permissions on
        // our RP id (CTAP2.1). On older keys ClientPin falls back to the
        // unscoped getPinToken internally.
        val token = clientPin.getPinToken(
            pin,
            ClientPin.PIN_PERMISSION_MC or ClientPin.PIN_PERMISSION_GA,
            rpId,
        )
        return PinUvAuth(token = token, keyAgreement = keyAgreement, sharedSecret = sharedSecret)
    }

    /** Pick the PIN/UV auth protocol. We hard-code **v1**, matching the
     *  proven iOS YubiKeyClient. On device we saw that some keys advertise
     *  protocol 2 in getInfo but their NFC firmware rejects the v2
     *  `authenticatorClientPIN` subcommand at the ISO layer with 0x6D00
     *  ("INS not supported"), even though `getInfo` over the identical
     *  sendCbor path succeeds. Protocol v1 is mandatory on every CTAP2
     *  authenticator and supports hmac-secret, so it works everywhere and
     *  keeps Android byte-identical with iOS. The `session` is kept for the
     *  signature (callers pass it) but no longer drives the choice. */
    @Suppress("UNUSED_PARAMETER")
    private fun selectPinUvAuthProtocol(session: Ctap2Session): PinUvAuthProtocol =
        PinUvAuthProtocolV1()

    /** Domain-separated client data hash used at FIDO2 wrap time. MUST
     *  match iOS wrapClientDataHash byte-for-byte:
     *  SHA-256("maknoon-wrap-v1" || salt || deviceSerial-utf8). */
    private fun wrapClientDataHash(salt: ByteArray, deviceSerial: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("maknoon-wrap-v1".toByteArray(Charsets.UTF_8))
        md.update(salt)
        md.update(deviceSerial.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    private fun randomBytes(count: Int): ByteArray =
        ByteArray(count).also { SecureRandom().nextBytes(it) }

    companion object {
        const val RP_ID = "maknoon.elabify.com"

        /** Generate a fresh 32-byte wrap salt (caller persists it next to
         *  the sealed material). */
        fun newWrapSalt(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

/** Lenient hex decode (spaces tolerated, even length required). Returns
 *  null on malformed input, mirroring iOS Data(hexString:). */
private fun String.hexToBytes(): ByteArray? {
    val s = replace(" ", "")
    if (s.length % 2 != 0) return null
    val out = ByteArray(s.length / 2)
    var i = 0
    while (i < s.length) {
        val hi = Character.digit(s[i], 16)
        val lo = Character.digit(s[i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}
