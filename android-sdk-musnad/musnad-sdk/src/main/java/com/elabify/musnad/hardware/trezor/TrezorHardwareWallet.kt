// Trezor hardware-wallet client. Ported 1:1 from iOS
// Maknoon/HardwareWallet/TrezorBLE.swift (the HardwareWallet half).
//
// The transport (BLE or USB) is injected; this class owns the THP
// session lifecycle through the trezor-core Rust TrezorClient, maps the
// vendor-agnostic HardwareWallet contract onto the Rust calls, and
// applies the hidden (passphrase) wallet + custom derivation path
// state. Tron is supported (Trezor Safe firmware): the three Tron
// methods call through to the Rust client's sign_tron_tx / tron_address
// / tron_pubkey, same as the other chains.
//
// Session pinning (beginSession/endSession) is reference-counted: while
// a pin is held, resetSession is a no-op so the BLE/USB connection AND
// the cached Rust client (which holds the pinned, paired+seeded THP
// session) survive a compound flow (identity enroll, multi-account
// discovery, multi-step send). The device flags TRANSPORT_BUSY on rapid
// channel churn otherwise.

package com.elabify.musnad.hardware.trezor

import android.util.Base64
import com.elabify.musnad.hardware.EcdsaSignature
import com.elabify.musnad.hardware.HardwareWallet
import com.elabify.musnad.hardware.HardwareWalletException
import com.elabify.musnad.hardware.HardwareWalletKind
import kotlinx.coroutines.delay
import uniffi.trezor_core.PairingCodeProvider
import uniffi.trezor_core.PassphraseSpec
import uniffi.trezor_core.Secp256k1Signature
import uniffi.trezor_core.TrezorClient
import uniffi.trezor_core.TrezorException
import uniffi.trezor_core.TrezorTransport

/** Result of a full CodeEntry pairing run; persist [credential]. */
data class TrezorPairedSession(
    val serial: String,
    val credential: ByteArray,
    val blePeripheralId: String?,
)

/**
 * @param transport the injected byte pipe ([TrezorBleTransport] or
 *   [TrezorUsbTransport]); this class drives THP through it via the Rust
 *   client. The same instance is reused across a pinned session.
 * @param credentialStore persists the THP host key + reconnect credential.
 */
class TrezorHardwareWallet(
    private val transport: TrezorTransport,
    private val credentialStore: TrezorCredentialStore,
) : HardwareWallet {

    override val kind: HardwareWalletKind = HardwareWalletKind.TREZOR

    override val currentBlePeripheralId: String?
        get() = (transport as? TrezorBleTransport)?.currentAddress

    /**
     * Which wallet seed-deriving ops target: the standard wallet, or a
     * passphrase (hidden) wallet. Set by a discovery/add flow before the
     * ops run; defaults to the standard wallet.
     */
    @Volatile
    var pendingPassphrase: PassphraseSpec = PassphraseSpec.Standard

    /**
     * Map a model-layer [PassphraseChoice] onto the UniFFI
     * [PassphraseSpec] the Rust client wants, keeping the trezor-core
     * type out of the UI / model layer. Call this before a discovery
     * sweep or a signing op so the right THP session is opened.
     */
    fun applyPassphraseMode(choice: PassphraseChoice) {
        pendingPassphrase = when (choice) {
            PassphraseChoice.Standard -> PassphraseSpec.Standard
            PassphraseChoice.OnDevice -> PassphraseSpec.OnDevice
            is PassphraseChoice.HostTyped -> PassphraseSpec.Host(choice.passphrase)
        }
    }

    /**
     * Custom BIP32 path for the next seed-deriving op(s); null = the
     * chain's standard path from `account`. Set by add/discover/sign
     * flows for custom- or alternative-path wallets.
     */
    @Volatile
    var pendingDerivationPath: String? = null

    override fun setDerivationPathOverride(path: String?) {
        pendingDerivationPath = path
    }

    // Lazily-built Rust client, reset on session teardown.
    private var client: TrezorClient? = null

    private fun trezorClient(): TrezorClient {
        client?.let { return it }
        return TrezorClient(transport).also { client = it }
    }

    // MARK: -- session pinning

    private var sessionPinCount = 0

    override fun beginSession() {
        sessionPinCount += 1
    }

    override fun endSession() {
        sessionPinCount = maxOf(0, sessionPinCount - 1)
        if (sessionPinCount == 0) forceReset()
    }

    /**
     * Tear down the session unless a pin is held. Called after every op;
     * a no-op mid-pin so the connection + Rust session are reused by the
     * next op.
     */
    private fun resetSession() {
        if (sessionPinCount > 0) return
        forceReset()
    }

    /**
     * Unconditional teardown. Dropping `client` releases the Rust
     * TrezorClient and its pinned connection; the next call rebuilds
     * against a fresh transport connection.
     */
    private fun forceReset() {
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        client = null
        when (transport) {
            is TrezorBleTransport -> transport.teardown()
            is TrezorUsbTransport -> transport.teardown()
        }
    }

    // MARK: -- credential helpers

    private data class Creds(val credential: ByteArray, val hostKey: ByteArray)

    private fun credentialAndHostKey(): Creds {
        val credential = credentialStore.loadCredential()
            ?: throw HardwareWalletException.Transport(
                "Register your Trezor first so Maknoon has a pairing credential."
            )
        return Creds(credential, credentialStore.hostStaticKey())
    }

    /**
     * Retry an op that hits THP TRANSPORT_BUSY (the device still tearing
     * down a prior channel on a rapid reconnect): drop the link, back
     * off, and retry (bounded). A stopgap until session pinning removes
     * the per-op reconnect churn entirely.
     */
    private suspend fun <T> withBusyRetry(op: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return op()
            } catch (e: TrezorException.TransportBusy) {
                if (attempt >= 3) throw mapTrezorException(e)
                attempt += 1
                resetSession()
                delay(attempt * 500L)
            } catch (e: TrezorException) {
                throw mapTrezorException(e)
            }
        }
    }

    /**
     * Map a Rust TrezorException onto the vendor-agnostic contract error.
     *
     * The classification decides whether the shared connection helper
     * (`withHardwareDevice`) retries: only [HardwareWalletException.Transport]
     * is retried (a stale / busy BLE link), and only [TransportBusy] is
     * retried inside [withBusyRetry]. Everything DETERMINISTIC, where the
     * device spoke and refused or the input was invalid, must be a
     * non-retryable [HardwareWalletException.DeviceRejected] so we do not
     * re-prompt the user 3x for the same guaranteed failure:
     *
     *   - DeviceRejected: the device returned a `Failure` (e.g. code=3
     *     "Input does not match scriptPubKey" on a key-origin / scriptPubKey
     *     mismatch). Re-signing the identical PSBT fails identically.
     *   - InvalidInput: a malformed PSBT / derivation path / envelope.
     *   - Pairing: a rejected / missing credential (re-pair, do not retry).
     *   - Thp / Protocol: a handshake / protocol-layer error; the device
     *     answered, so a blind retry of the same op just re-fails.
     *
     * Transport / TransportBusy stay retryable.
     */
    private fun mapTrezorException(e: TrezorException): HardwareWalletException = when (e) {
        is TrezorException.UserCanceled -> HardwareWalletException.UserCancelled()
        is TrezorException.NotImplemented -> HardwareWalletException.NotImplemented(kind)
        is TrezorException.DeviceRejected ->
            HardwareWalletException.DeviceRejected(deviceRejectionMessage(e.code, e.reason))
        is TrezorException.InvalidInput ->
            HardwareWalletException.DeviceRejected("Trezor could not process the request: ${e.reason}")
        is TrezorException.Pairing ->
            HardwareWalletException.DeviceRejected("Trezor pairing problem: ${e.reason}. Re-add the device under Settings, Devices.")
        is TrezorException.Thp ->
            HardwareWalletException.DeviceRejected("Trezor protocol error: ${e.reason}")
        is TrezorException.Protocol ->
            HardwareWalletException.DeviceRejected("Trezor protocol error: ${e.reason}")
        else -> HardwareWalletException.Transport(e.message ?: e.toString())
    }

    /** A clean one-line message for a device `Failure`, special-casing the
     *  common Bitcoin signing rejection so the send screen explains it. */
    private fun deviceRejectionMessage(code: Int, reason: String): String =
        if (reason.contains("does not match scriptPubKey", ignoreCase = true)) {
            "Trezor rejected the transaction: the input addresses do not match this wallet's " +
                "derivation (account / script type / network). Re-add this hardware wallet so its " +
                "derivation is recorded correctly, then try again."
        } else {
            "Trezor rejected the request (failure $code): $reason"
        }

    // MARK: -- HardwareWallet conformance

    /**
     * Returns the device's stable `device_id` (the same value
     * registration stored as the serial), so every device-match check
     * recognises this Trezor. Reconnects with the stored credential (no
     * code entry), so the device must already be registered/paired.
     */
    override suspend fun identifyDevice(): String {
        try {
            val creds = credentialAndHostKey()
            return withBusyRetry {
                trezorClient().identifyPaired(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                )
            }
        } finally {
            resetSession()
        }
    }

    /**
     * Full THP CodeEntry pairing: connect, handshake, pair (prompting for
     * the on-device code via [codeProvider]), reach ENCRYPTED_TRANSPORT,
     * and read Features. Returns the device's real `device_id` (a stable
     * serial), the reconnection credential, and the BLE peripheral id.
     * Pass a non-null [storedCredential] to skip on-device pairing on a
     * known device. Mirrors iOS establishPairedSession.
     */
    suspend fun establishPairedSession(
        hostStaticPriv: ByteArray,
        codeProvider: PairingCodeProvider,
        storedCredential: ByteArray?,
    ): TrezorPairedSession {
        // A fresh cold connect can drop mid-handshake (stale/zombie BLE link) or
        // come back TransportBusy because a prior aborted THP channel is still
        // open on the device. Both happen BEFORE the on-device code is entered,
        // so retrying with a clean teardown + a backoff long enough for the
        // device's channel to time out is safe (no double code prompt).
        var attempt = 0
        while (true) {
            try {
                android.util.Log.d("TrezorBLE", "establishPairedSession: start attempt=${attempt + 1} hasStoredCred=${storedCredential != null}")
                val result = trezorClient().establishPairedSession(
                    hostStaticPriv = hostStaticPriv,
                    hostName = "Maknoon",
                    appName = "Maknoon Android",
                    storedCredential = storedCredential,
                    codeProvider = codeProvider,
                )
                android.util.Log.d("TrezorBLE", "establishPairedSession: ok deviceId=${result.deviceId}")
                // Read the peripheral id before the trailing resetSession tears down.
                val session = TrezorPairedSession(
                    serial = result.deviceId,
                    credential = result.credential,
                    blePeripheralId = currentBlePeripheralId,
                )
                resetSession()
                return session
            } catch (e: TrezorException) {
                val msg = e.message ?: ""
                val retryable = e is TrezorException.TransportBusy ||
                    msg.contains("disconnect", ignoreCase = true) ||
                    msg.contains("busy", ignoreCase = true)
                android.util.Log.w("TrezorBLE", "establishPairedSession: ${e.javaClass.simpleName}: $msg (attempt ${attempt + 1}, retryable=$retryable)")
                resetSession()
                if (retryable && attempt < 3) {
                    attempt += 1
                    delay(2_500L * attempt) // let the device's prior THP channel clear
                    continue
                }
                throw mapTrezorException(e)
            } catch (e: Throwable) {
                android.util.Log.w("TrezorBLE", "establishPairedSession: ${e.javaClass.simpleName}: ${e.message}", e)
                resetSession()
                throw e
            }
        }
    }

    /**
     * Identity-sandwich attestor secp256k1 pubkey. Reconnects with the
     * stored credential (no code entry) and reads the Ethereum public key
     * at m/44'/60'/0'/0/0.
     */
    override suspend fun pair(): ByteArray {
        try {
            val creds = credentialAndHostKey()
            return withBusyRetry {
                trezorClient().getAttestorPubkey(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                )
            }
        } finally {
            resetSession()
        }
    }

    /**
     * Deterministic EIP-191 signature (R||S) with the attestor key, for
     * identity-sandwich attestation + the AES-GCM wrap challenge.
     */
    override suspend fun signMessage(message: ByteArray): ByteArray {
        try {
            val creds = credentialAndHostKey()
            return withBusyRetry {
                trezorClient().signMessageEth(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    message = message,
                )
            }
        } finally {
            resetSession()
        }
    }

    // -- Bitcoin --

    /**
     * Account-level xpub for the current [pendingPassphrase] wallet, used
     * to build a watch-only BDK descriptor host-side. Within a pinned
     * discovery the seeded session is reused across accounts.
     */
    override suspend fun getBitcoinAccountXpub(account: Long, networkCoinType: Long): String {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            return withBusyRetry {
                trezorClient().getBitcoinAccountXpub(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    account = account.toUInt(),
                    networkCoinType = networkCoinType.toUInt(),
                    path = path,
                )
            }
        } finally {
            resetSession()
        }
    }

    /**
     * Hex-encoded 4-byte BIP32 master root fingerprint for the current
     * [pendingPassphrase] wallet. The Trezor reports it independent of
     * coin type, so `networkCoinType` is unused; it stays in the
     * signature to satisfy the vendor-agnostic contract.
     */
    override suspend fun getBitcoinMasterFingerprint(networkCoinType: Long): String {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val fp = withBusyRetry {
                trezorClient().getBitcoinMasterFingerprint(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                )
            }
            return fp.joinToString("") { "%02x".format(it) }
        } finally {
            resetSession()
        }
    }

    /**
     * Sign a PSBT for the current [pendingPassphrase] wallet. The
     * contract passes only the raw PSBT bytes + the coin type; the Rust
     * client derives the spend paths from the PSBT itself (fingerprint /
     * account xpub are contract-parity args, empty here). The PSBT bytes
     * are base64 in / base64 out, decoded back to the signed PSBT bytes
     * the caller (BDK) finalises and broadcasts.
     *
     * The richer [signBitcoinPSBT] is the entry point the multi-account
     * discovery / send flow calls directly (matching iOS signBitcoinPSBT)
     * when it already holds the fingerprint + account xpub.
     */
    override suspend fun signPsbt(psbt: ByteArray, networkCoinType: Long): ByteArray {
        val signedBase64 = signBitcoinPSBT(
            unsignedBase64 = Base64.encodeToString(psbt, Base64.NO_WRAP),
            fingerprintHex = "",
            accountXpub = "",
            account = 0L,
            coinType = networkCoinType,
        )
        return Base64.decode(signedBase64, Base64.NO_WRAP)
    }

    /**
     * Sign a PSBT for the current [pendingPassphrase] wallet. Drives
     * Trezor's SignTx streaming exchange in Rust and returns the signed
     * PSBT base64 with partial_sigs merged in, matching the Ledger
     * contract so the BDK finalize + broadcast path is reused unchanged.
     * `fingerprintHex` / `accountXpub` / `account` are accepted for that
     * contract parity; the spend paths come from the PSBT itself.
     */
    suspend fun signBitcoinPSBT(
        unsignedBase64: String,
        fingerprintHex: String,
        accountXpub: String,
        account: Long,
        coinType: Long,
    ): String {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            return withBusyRetry {
                trezorClient().signPsbt(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    psbtBase64 = unsignedBase64,
                    fingerprintHex = fingerprintHex,
                    accountXpub = accountXpub,
                    account = account.toUInt(),
                    coinType = coinType.toUInt(),
                    path = path,
                )
            }
        } finally {
            resetSession()
        }
    }

    // -- Ethereum / EVM --

    /**
     * EIP-55 address for BIP44 account `account` on the current
     * [pendingPassphrase] wallet. Reconnects with the stored credential;
     * within a pinned discovery the seeded session is reused.
     */
    override suspend fun getEthereumAddress(account: Long): String {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            return withBusyRetry {
                trezorClient().getEthereumAddress(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    account = account.toUInt(),
                    path = path,
                )
            }
        } finally {
            resetSession()
        }
    }

    /**
     * Sign an EIP-1559 transaction for BIP44 account `account` on the
     * current [pendingPassphrase] wallet. The `envelope` is the same
     * 0x02-prefixed unsigned RLP the Ledger path builds; the Rust client
     * decodes it, drives the on-device confirmation, and returns the
     * parity-bit V plus 32-byte R / S. `erc20Descriptor` is Ledger-CAL
     * specific and ignored: Trezor renders token transfers from its own
     * token definitions.
     */
    override suspend fun signEthereumTransaction(
        envelope: ByteArray,
        account: Long,
        erc20Descriptor: ByteArray?,
    ): EcdsaSignature {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            val sig = withBusyRetry {
                trezorClient().signEthereumTx(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    envelope = envelope,
                    account = account.toUInt(),
                    path = path,
                )
            }
            return sig.toEcdsa()
        } finally {
            resetSession()
        }
    }

    // -- Solana --

    /**
     * Base58 ed25519 address for SLIP-0010 account `account` on the
     * current [pendingPassphrase] wallet.
     */
    override suspend fun getSolanaAddress(account: Long): String {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            return withBusyRetry {
                trezorClient().getSolanaAddress(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    account = account.toUInt(),
                    path = path,
                )
            }
        } finally {
            resetSession()
        }
    }

    /**
     * Sign a serialized Solana message for the current [pendingPassphrase]
     * wallet; returns the 64-byte ed25519 signature the caller prepends
     * to the transaction.
     */
    override suspend fun signSolanaTransaction(unsignedTx: ByteArray, account: Long): ByteArray {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            return withBusyRetry {
                trezorClient().signSolanaTx(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    unsignedTx = unsignedTx,
                    account = account.toUInt(),
                    path = path,
                )
            }
        } finally {
            resetSession()
        }
    }

    // -- Tron (Ledger + Trezor; Trezor Safe firmware added Tron support) --

    override suspend fun getTronAddress(account: Long): String {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            return withBusyRetry {
                trezorClient().getTronAddress(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    account = account.toUInt(),
                    path = path,
                )
            }
        } finally {
            resetSession()
        }
    }

    override suspend fun getTronPubkey(account: Long): ByteArray {
        try {
            return withBusyRetry { trezorClient().getTronPubkey(account.toUInt()) }
        } finally {
            resetSession()
        }
    }

    override suspend fun signTronTransaction(rawTxProto: ByteArray, account: Long): EcdsaSignature {
        try {
            val creds = credentialAndHostKey()
            val passphrase = pendingPassphrase
            val path = pendingDerivationPath
            val sig = withBusyRetry {
                trezorClient().signTronTx(
                    hostStaticPriv = creds.hostKey,
                    credential = creds.credential,
                    passphrase = passphrase,
                    rawTxProto = rawTxProto,
                    account = account.toUInt(),
                    path = path,
                )
            }
            return sig.toEcdsa()
        } finally {
            resetSession()
        }
    }

    private fun Secp256k1Signature.toEcdsa(): EcdsaSignature =
        EcdsaSignature(v = v.toInt(), r = r, s = s)
}
