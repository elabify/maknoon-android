package com.elabify.maknoon

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * The embeddable Maknoon SDK: the single umbrella a host app (e.g. a bank) constructs once
 * at launch to get Musnad identity AND the Maknoon self-custody wallet.
 *
 * This interface, and the facades it exposes, are the ONLY supported public surface. The
 * canonical contract both platforms implement is the wire-format spec;
 * changes require an ADR touch (ADR-0016).
 *
 * The concrete implementation and the `init(context, config)` factory are provided by the
 * SDK; hosts depend only on these interfaces and the value types in this package.
 */
interface MaknoonSDK {
    // Identity (Musnad)
    val identity: MusnadIdentity
    val credentials: MusnadCredentials
    val issuance: MusnadIssuance
    val presentation: MusnadPresentation
    val chain: MusnadChainClient

    // Self-custody wallet + device layer (Maknoon)
    val wallet: MaknoonWallet
    val hardware: MaknoonHardware

    /**
     * Classify an incoming URL. Scheme, host-allowlist, and parameter validation happen
     * inside the SDK before any cryptographic operation. The SDK never registers intent
     * filters itself; hosts forward their own deep links here first.
     */
    fun classify(uri: Uri): MusnadDeepLink

    companion object {
        val version: String get() = MaknoonSdkVersion.VALUE

        /**
         * Construct the SDK. Retain one instance for the app's lifetime (typically in
         * `Application.onCreate`). Re-initializing across configurations invalidates
         * in-memory ephemeral key handles. Throws [MaknoonError.Configuration] when the
         * trust-contract invariants are not met (e.g. an empty pin set).
         */
        fun init(context: Context, config: MaknoonConfig): MaknoonSDK =
            com.elabify.maknoon.impl.MaknoonSdkFactory.create(context, config)
    }
}

/** Long-term identity key, the Identity Sandwich delegation lifecycle, and the 2FA seam. */
interface MusnadIdentity {
    suspend fun holderDid(): String
    suspend fun hasActiveDelegation(policy: DelegationPolicy? = null): Boolean
    suspend fun createDelegation(lifetime: Duration, scope: List<DelegationScope>): Delegation
    suspend fun revokeActiveDelegation()
    suspend fun currentStatus(): IdentityStatus

    // ----- lifecycle -----
    suspend fun hasIdentity(): Boolean
    suspend fun hasPassphrase(): Boolean

    /** Create a brand-new identity (fresh entropy). Returns status; the mnemonic is NEVER
     *  returned - reveal it under biometric via [revealRecoveryWords]. */
    suspend fun createIdentity(passphrase: String): IdentityStatus
    suspend fun restoreFromMnemonic(words: List<String>, passphrase: String): IdentityStatus
    suspend fun restoreFromEncryptedBackup(blob: ByteArray, passphrase: String): IdentityStatus

    /** Reset (wipe) the identity and drop the wrap key. */
    suspend fun reset()

    // ----- secret-safe operations (secrets never leave the SDK) -----

    /** Reveal the recovery phrase to [body], biometric-gated; the words are dropped on return.
     *  Mirrors [MusnadCredentials.disclose]: the raw mnemonic never escapes the closure. */
    suspend fun <T> revealRecoveryWords(body: suspend (List<String>) -> T): T

    /** The ENCRYPTED (safe) backup blob. The raw entropy never leaves the SDK. */
    suspend fun exportEncryptedBackup(): ByteArray

    /** Sign a message with the long-term master key: a signing oracle. The key never leaves. */
    suspend fun signWithMaster(message: ByteArray): ByteArray

    /**
     * Enroll a hardware device (obtained from [MaknoonHardware]) as an Identity Sandwich
     * second factor. This is the ONLY identity<->hardware seam; [MaknoonHardware] itself
     * stays identity-agnostic (see the facade contract, section 0.1).
     */
    suspend fun enrollSecondFactor(device: DeviceRef)
}

/** Credential storage + selective disclosure (was the spec's misnamed `MusnadWallet`). */
interface MusnadCredentials {
    suspend fun listCredentials(): List<CredentialSummary>
    suspend fun credentialDetail(id: CredentialID): CredentialDetail

    /**
     * Decrypt and yield the requested claim values to [body], gated by a Class-3
     * BiometricPrompt. The values are zeroized in a `finally` before this returns; a host
     * that copies them out of [body] has taken plaintext into its own memory and owns that.
     */
    suspend fun <T> disclose(
        id: CredentialID,
        keys: Set<String>,
        body: suspend (Map<String, ClaimValue>) -> T,
    ): T

    suspend fun refreshStatus(id: CredentialID): CredentialStatus
}

/** Issuance. E-passport only at this stage (NFC chip + CSCA passive auth). */
interface MusnadIssuance {
    suspend fun resolvePickup(uri: Uri): IssuancePreview
    suspend fun acceptIssuance(preview: IssuancePreview): CredentialID
    suspend fun readPassport(session: PassportReadSession): PassportFields
    suspend fun submitPassportIssuance(issuerDid: String, passport: PassportFields): CredentialID
}

/** Selective-disclosure presentation + tiered verification (on-chain + HAVID, 0.6.4). */
interface MusnadPresentation {
    suspend fun resolveRequest(uri: Uri): PresentationRequest
    suspend fun present(
        request: PresentationRequest,
        disclose: Set<String>,
        includePiiHandoff: Boolean,
    ): PresentationVerdict
    suspend fun verifyReceived(presentation: ReceivedPresentation): VerificationResult
}

/** Lazy, cached on-chain identity status reads. */
interface MusnadChainClient {
    suspend fun issuerStatus(did: String): IssuerStatus
    suspend fun credentialStatus(issuerDid: String, cid: ByteArray): CredentialStatus
}

/** The Maknoon multi-chain self-custody digital-asset wallet. */
interface MaknoonWallet {
    suspend fun accounts(network: Network): List<WalletAccount>
    suspend fun assets(account: WalletAccount): List<Asset>
    suspend fun receiveAddress(account: WalletAccount): ReceiveAddress
    suspend fun buildSend(request: SendRequest): UnsignedTx
    suspend fun signAndBroadcast(tx: UnsignedTx, signer: Signer): TxHash
    /** The concrete chains available within a network ecosystem (e.g. EVM: Base, Arbitrum, ...). */
    suspend fun chains(kind: NetworkKind): List<Chain>
}

/** The hardware-device layer (Ledger / Trezor / YubiKey). Identity-agnostic plumbing. */
interface MaknoonHardware {
    suspend fun enrolledDevices(): List<DeviceRef>
    fun discover(kind: DeviceKind, transport: Transport): Flow<DiscoveredDevice>
    suspend fun enroll(device: DiscoveredDevice, options: EnrollOptions): DeviceRef
    suspend fun sign(tx: UnsignedTx, device: DeviceRef): SignedTx
}

/** SDK version, kept in one place so the umbrella and manifests agree. */
internal object MaknoonSdkVersion {
    const val VALUE: String = "0.1.0"
}
