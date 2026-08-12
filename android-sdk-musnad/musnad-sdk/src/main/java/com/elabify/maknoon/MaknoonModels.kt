package com.elabify.maknoon

import android.net.Uri
import java.util.UUID
import kotlin.time.Duration

// ---------------------------------------------------------------------------
// Public value types for the facade. These are the stable, host-facing surface;
// they are intentionally decoupled from the SDK's internal Room entities and
// engine types (which stay in com.elabify.musnad.* and are not exported).
// ---------------------------------------------------------------------------

// ----- Identity (Musnad) ---------------------------------------------------

/** A scope a delegation is authorized for. */
enum class DelegationScope { PRESENT_CREDENTIALS, SIGN_CHALLENGE }

/** Optional binding a caller can require of the active delegation. */
data class DelegationPolicy(
    val requiredScopes: Set<DelegationScope> = emptySet(),
    val minRemaining: Duration? = null,
)

/** Public view of a delegation. Carries the public cert bytes; never the wrapped SK. */
data class Delegation(
    val certBytes: ByteArray,
    val issuedAtSec: Long,
    val expiresAtSec: Long,
)

/** UI-facing snapshot. Never returns secret-key material. */
data class IdentityStatus(
    val holderDid: String,
    val mode: MaknoonConfig.Mode,
    val hasActiveDelegation: Boolean,
    val delegationExpiresAtSec: Long?,
    val secondFactorEnrolled: Boolean,
)

// ----- Credentials (Musnad) ------------------------------------------------

/** Opaque credential identifier (the credential `cid`). */
@JvmInline
value class CredentialID(val value: String)

/** A single disclosed claim value. */
sealed interface ClaimValue {
    data class Text(val value: String) : ClaimValue
    data class Number(val value: Double) : ClaimValue
    data class Bool(val value: Boolean) : ClaimValue
    data class Nested(val value: Map<String, ClaimValue>) : ClaimValue
    data class Items(val value: List<ClaimValue>) : ClaimValue
    data object Null : ClaimValue
}

/** Listing row; metadata only, no claim plaintext. */
data class CredentialSummary(
    val id: CredentialID,
    val issuerDid: String,
    val schema: String?,
    val issuedAtSec: Long,
    val expiresAtSec: Long?,
    val title: String?,
)

/** Redacted detail; does not decrypt claim values (use [MusnadCredentials.disclose]). */
data class CredentialDetail(
    val summary: CredentialSummary,
    /** Claim keys present in the encrypted envelope, without their values. */
    val claimKeys: Set<String>,
    val status: CredentialStatus,
)

/** On-chain-aware status of a credential. */
data class CredentialStatus(
    val issuerActive: Boolean?,
    val revoked: Boolean?,
    val rootCurrent: Boolean?,
    val checkedAtSec: Long,
    val online: Boolean,
)

// ----- Issuance (Musnad, e-passport only) ----------------------------------

/** Host-provided platform session for an NFC passport read (Activity + NFC lifecycle). */
interface PassportReadSession

/** A resolved issued-credential pickup awaiting host confirmation. */
data class IssuancePreview(
    val issuerDid: String,
    val schema: String?,
    val claimKeys: Set<String>,
    val cid: CredentialID,
)

/** Fields read from the passport chip (ICAO 9303) after CSCA passive authentication. */
data class PassportFields(
    val documentNumber: String,
    val fullNameLatin: String,
    val nationality: String,
    val dateOfBirth: String,
    val dateOfExpiry: String,
    val sex: String?,
    val passiveAuthPassed: Boolean,
    val cscaCountryCode: String?,
    /** DG2 facial image bytes (JPEG/JP2), when read for face match. */
    val faceImage: ByteArray?,
)

// ----- Presentation (Musnad) -----------------------------------------------

data class PresentationRequest(
    val verifierDid: String,
    val requestedClaims: Set<String>,
    val purpose: String?,
    val raw: Uri,
)

data class PresentationVerdict(
    val decision: Decision,
    val reason: String?,
    val requestId: String?,
    val online: Boolean,
) {
    enum class Decision { GRANT, DENY }
}

/** A presentation received from another holder, to be verified locally + on-chain.
 *  Constructed by the host from the assembled QR/drop payload (the raw presentation JSON). */
data class ReceivedPresentation(val presentationJson: String)

/** Tiered verification result: local crypto, on-chain, CSCA provenance, HAVID. */
data class VerificationResult(
    val localCryptoValid: Boolean,
    val onChain: OnChainTiers,
    val cscaProvenance: Tier,
    val havid: Tier,
) {
    /** issuer registered / not revoked / root current. */
    data class OnChainTiers(
        val issuerRegistered: Tier,
        val credentialNotRevoked: Tier,
        val rootCurrent: Tier,
    )
    enum class Tier { PASS, FAIL, UNKNOWN }
}

// ----- Chain reads (Musnad) ------------------------------------------------

data class IssuerStatus(
    val did: String,
    val registered: Boolean,
    val active: Boolean,
    val currentEpoch: Long?,
)

// ----- Deep links (Musnad) -------------------------------------------------

sealed interface MusnadDeepLink {
    data class IssuancePickup(val uri: Uri) : MusnadDeepLink
    data class PresentationRequest(val uri: Uri) : MusnadDeepLink
    data object Unknown : MusnadDeepLink
}

// ----- Wallet (Maknoon) ----------------------------------------------------

/**
 * A ledger ecosystem discriminator: Bitcoin, Ethereum, Solana, Tron, Lightning. This is what
 * the UI's "Add Network" picker chooses. Used to ask the wallet which concrete [Chain]s are
 * available before building a [Network]. (CAIP-2 calls this level the "namespace".)
 */
enum class NetworkKind { BITCOIN, ETHEREUM, SOLANA, TRON, LIGHTNING }

/**
 * A concrete chain within a network: the deployment the wallet actually talks to (Base,
 * Arbitrum, Sepolia; Bitcoin mainnet/testnet3/signet; Solana devnet; Tron Nile; ...). This is
 * the level EIP-155 calls a "Chain ID" and CAIP-2 calls the `reference` (`eip155:8453` = Base),
 * so it is named "Chain" to match the interop standards. [id] is the ecosystem-native
 * identifier (EVM chain id as a string; "devnet"/"nile"/"testnet3"/... elsewhere).
 */
data class Chain(
    val id: String,
    val label: String,
    val isTestnet: Boolean,
    val rpcUrl: String? = null,
)

/**
 * A ledger ecosystem bound to a concrete [chain], e.g. `Network.Ethereum(chain = base)`.
 * "Network" is the ecosystem (the UI's "Add Network" choice); "Chain" is the concrete
 * deployment inside it, aligning the concrete level with EIP-155 / CAIP-2 "chain". Every
 * variant carries a [chain] so "which chain" is expressed identically across ecosystems
 * (Lightning exposes only mainnet today but is modelled the same way).
 */
sealed interface Network {
    val chain: Chain
    val kind: NetworkKind
    data class Bitcoin(override val chain: Chain) : Network {
        override val kind get() = NetworkKind.BITCOIN
    }
    data class Ethereum(override val chain: Chain) : Network {
        override val kind get() = NetworkKind.ETHEREUM
    }
    data class Solana(override val chain: Chain) : Network {
        override val kind get() = NetworkKind.SOLANA
    }
    data class Tron(override val chain: Chain) : Network {
        override val kind get() = NetworkKind.TRON
    }
    data class Lightning(override val chain: Chain) : Network {
        override val kind get() = NetworkKind.LIGHTNING
    }
}

data class WalletAccount(
    val id: UUID,
    val network: Network,
    val label: String,
    val address: String,
)

/** A balance entry: native or a token (erc20/spl/trc20). */
data class Asset(
    val symbol: String,
    val name: String,
    val kind: Kind,
    /** Contract/mint address; null for native. */
    val contract: String?,
    val decimals: Int,
    /** Smallest-unit balance as a decimal string. */
    val balance: String,
) {
    enum class Kind { NATIVE, ERC20, SPL, TRC20 }
}

data class ReceiveAddress(val address: String, val uri: String?)

/** A requested send; fee + a human summary come back on the [UnsignedTx]. */
data class SendRequest(
    val account: WalletAccount,
    val toAddress: String,
    /** Smallest-unit amount as a decimal string. */
    val amount: String,
    /** Token contract for a token send; null for native. */
    val tokenContract: String? = null,
)

data class UnsignedTx(
    val account: WalletAccount,
    /** Opaque, chain-specific payload the SDK will sign; not host-interpretable. */
    val payload: ByteArray,
    val feeEstimate: String,
    /** Human-readable summary shown on the approval sheet. */
    val summary: String,
)

data class SignedTx(val account: WalletAccount, val rawBytes: ByteArray)

@JvmInline
value class TxHash(val value: String)

/** Who signs: the software key or an enrolled hardware device. */
sealed interface Signer {
    data object Software : Signer
    data class Device(val device: DeviceRef) : Signer
}

// ----- Hardware (Maknoon) --------------------------------------------------

enum class DeviceKind { LEDGER, TREZOR, YUBIKEY, SEEDSIGNER }

enum class Transport { BLE, USB, NFC, RELAY, QR }

/** A device seen during discovery, not yet enrolled. */
data class DiscoveredDevice(
    val kind: DeviceKind,
    val transport: Transport,
    val displayName: String,
    /** Transport-scoped handle (e.g. BLE peripheral id); opaque to the host. */
    val transportHandle: String,
)

/** Options for enrolling a device (hidden/passphrase wallet, derivation path override). */
data class EnrollOptions(
    val label: String? = null,
    val passphrase: String? = null,
    val derivationPathOverride: String? = null,
)

/** A stable reference to an enrolled device. */
data class DeviceRef(
    val id: UUID,
    val kind: DeviceKind,
    val label: String,
    val serial: String?,
)
