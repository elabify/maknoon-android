// The cross-slice seam the commerce/payment/merchant surface depends on.
//
// WHY THIS EXISTS: the iOS commerce files reach into a fat `HolderStore`
// (sandwich, credentials, ethereum wallet store, ethereum settings, the
// presentation factory, the matching engine, the presentation verifier, the
// verifier registry client, the merchant identity, and the X-Wing transport).
// On Android those pieces live in DIFFERENT agent slices (identity, wallet,
// transport) and some have not landed yet. Rather than reimplement any of them
// (which would fork the crypto + signing contract), the commerce slice declares
// exactly what it needs as a single injected interface. The app wires a concrete
// implementation over the real stores at host-construction time, the same way
// MiniAppHandlerFactory / MiniAppApprovalSheetHost are wired.
//
// Everything sensitive (key reveal, biometric gating) stays behind these
// methods in the owning slice; the commerce layer only orchestrates.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.lightning.LightningAccount
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronWalletDescriptor
import org.json.JSONObject

/** A verifier-registry entry as the commerce layer reads it. */
data class CommerceRegistryEntry(val verifierDid: String, val verifierPublicKey: String)

/** A signed-but-unbroadcast Solana tx + its base58 settlement ref. */
data class SignedSolanaTransfer(val signedBase64: String, val signatureRef: String)

/** A Solana wallet's balances: native lamports + (for an SPL rail) the raw
 *  token amount in the asset's base units (null for a native-SOL rail). */
data class SolanaBalanceResult(val lamports: Long, val tokenRaw: Long?)

/** A signed-but-unbroadcast Tron tx: the envelope + recoverable signature ready
 *  for TronRPCClient.broadcastWithSignature, plus the txID settlement ref (the
 *  txID = sha256(raw_data) is known before broadcast). */
data class SignedTronTransfer(val envelopeJSON: String, val signatureRSV: ByteArray, val txID: String)

/** A Tron wallet's balances: native sun + (for a TRC-20 rail) the raw token
 *  amount in the asset's base units (null for a native-TRX rail). */
data class TronBalanceResult(val sun: Long, val tokenRaw: Long?)

/** A signed-but-unbroadcast Bitcoin payment: the signed PSBT (base64), the
 *  pre-broadcast txid, and (for a hardware/partially-signed PSBT) the original
 *  unsigned PSBT needed to finalize at broadcast time (null for software). */
data class SignedBitcoinTransfer(
    val signedPSBTBase64: String,
    val txid: String,
    val unsignedPSBTBase64: String? = null,
)

/** Verdict + trust tier for an authenticated verifier request (from the identity slice). */
data class CommerceVerifierDecision(
    val isValid: Boolean,
    val tier: Tier,
) {
    sealed interface Tier {
        data class Registered(val name: String) : Tier
        object SelfSigned : Tier
        object Unknown : Tier
    }
}

/**
 * The identity/wallet/transport capabilities the commerce slice needs. Provided
 * by the app over the real stores. All methods are blocking (call them off the
 * main thread); the handlers/sheets already run on coroutines.
 */
interface CommerceHolderContext {

    /** The per-install merchant verifier identity (owned here, in this slice). */
    val merchantIdentity: MerchantIdentityStore

    /** The relay/registry origin (HolderStore.elabifyDropHost on iOS). */
    val dropHost: String

    /** True when the holder's consumer identity is unlocked and can present. */
    fun identityUnlocked(): Boolean

    // ---- merchant side: build + authenticate ----

    /**
     * Build + self/registry-sign a VerifierRequest as a raw JSON object, using
     * the merchant verifier key for [installedAppId]. Owned by the identity
     * slice because the canonicalization of the verifier request must match the
     * verifier server byte-for-byte. Returns the signed verifier-request JSON.
     */
    fun buildSignedVerifierRequest(
        installedAppId: String,
        merchantName: String,
        schema: String?,
        requiredClaims: List<String>,
        issuers: List<String>?,
        challengeHex: String,
        requestId: String,
        issuedAt: Long,
        expiresAt: Long,
        inlinePublicKeyHex: String?,
    ): JSONObject

    /** Curated-registry lookup; null when the DID is not registered. */
    fun registryLookup(did: String): CommerceRegistryEntry?

    /**
     * Authenticate a raw verifier-request JSON (signature + expiry + trust tier),
     * delegating to the identity slice's VerifierRequestValidator.
     */
    fun validateVerifierRequest(verifierRequestJson: JSONObject, nowSec: Long): CommerceVerifierDecision?

    /** ML-DSA-65 verify (raw pubkey, signature, message). Identity-slice crypto. */
    fun mldsaVerify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean

    // ---- holder side: present + verify ----

    /**
     * Match the holder's credentials against the verifier-request filter and
     * return the best disclosable credential as a raw JSON object, or null.
     */
    fun matchCredential(verifierRequestJson: JSONObject): JSONObject?

    /**
     * Read a disclosable claim's display text for [credentialJson] at [key]
     * (handles the sdnScreen object form), or "" when absent.
     */
    fun claimDisplayText(credentialJson: JSONObject, key: String): String

    /**
     * Build a signed Presentation (raw JSON) disclosing [selectedClaims] of
     * [credentialJson], bound to the verifier request. Identity-slice signing
     * (uses the consumer IdentitySandwich; may require biometric).
     */
    fun buildPresentation(
        credentialJson: JSONObject,
        selectedClaims: Set<String>,
        verifierRequestJson: JSONObject,
    ): JSONObject

    /** Offline cryptographic verdict for a presentation (signatures, proofs, expiry). */
    fun verifyPresentationOffline(presentationJson: JSONObject, nowSec: Long): Boolean

    // ---- wallet side: pay ----

    /** The holder's Ethereum wallet descriptors (read-only view). */
    fun ethereumWallets(): List<EthereumWalletDescriptor>

    /** The configured RPC URL for [network]. */
    fun ethereumRpcURL(network: EthereumNetwork): String

    /**
     * Sign (and return the raw 0x-hex EIP-1559 tx) a payment from [descriptor]
     * to [recipient] for [amount] of the EVM [asset] on [rpcURL]. Routes
     * software (sandwich) and hardware (Ledger/Trezor) signing in the owning
     * slice; the commerce layer only asks for the signed bytes.
     */
    fun signEvmTransfer(
        descriptor: EthereumWalletDescriptor,
        rpcURLString: String,
        recipient: String,
        amount: String,
        asset: CommerceEVMPayment.Asset,
        biometricReason: String,
        /** Host-entered Trezor passphrase for a hidden hardware wallet (null
         *  for software, on-device entry, or standard wallets). */
        hostPassphrase: String? = null,
    ): String

    /**
     * keccak256 of [data]. The pre-broadcast EIP-1559 tx hash is keccak256 of
     * the signed raw tx, computed locally so the merchant can be handed the
     * txHash before any money moves. Routed here because WalletCore's Hash lives
     * in the SDK module, not the app.
     */
    fun keccak256(data: ByteArray): ByteArray

    // ---- wallet side: pay (Solana) ----

    /** The holder's Solana wallet descriptors (read-only view). */
    fun solanaWallets(): List<SolanaWalletDescriptor>

    /** The configured RPC URL for [network]. */
    fun solanaRpcURL(network: SolanaNetwork): String

    /**
     * Read [descriptor]'s native lamports and, for an SPL rail ([mint] != null),
     * the raw token balance in its base units. Routed here because the address
     * (software wallets) derives from the consumer sandwich.
     */
    fun solanaBalance(
        descriptor: SolanaWalletDescriptor,
        network: SolanaNetwork,
        rpcURLString: String,
        mint: String?,
    ): SolanaBalanceResult

    /**
     * Sign (NOT broadcast) a native SOL or SPL transfer ([mint] != null) from
     * [descriptor], returning the signed wire tx + its pre-broadcast base58
     * signature ref so the merchant gets identity + ref before money moves.
     * Software (sandwich) + hardware (Ledger/Trezor over BLE) both supported.
     */
    fun signSolanaTransfer(
        descriptor: SolanaWalletDescriptor,
        network: SolanaNetwork,
        rpcURLString: String,
        recipient: String,
        amount: String,
        mint: String?,
        decimals: Int,
        biometricReason: String,
        /** Host-entered Trezor passphrase for a hidden hardware wallet (null
         *  for software, on-device entry, or standard wallets). */
        hostPassphrase: String? = null,
    ): SignedSolanaTransfer

    // ---- wallet side: pay (Tron) ----

    /** The holder's Tron wallet descriptors (read-only view). */
    fun tronWallets(): List<TronWalletDescriptor>

    /** The configured RPC URL for [network]. */
    fun tronRpcURL(network: TronNetwork): String

    /**
     * Read [descriptor]'s native sun balance and, for a TRC-20 rail
     * ([tokenContract] != null), the raw token balance in its base units.
     */
    fun tronBalance(
        descriptor: TronWalletDescriptor,
        network: TronNetwork,
        rpcURLString: String,
        tokenContract: String?,
    ): TronBalanceResult

    /**
     * Sign (NOT broadcast) a native TRX or TRC-20 transfer
     * ([tokenContract] != null) from [descriptor], returning the signed envelope
     * + recoverable signature + the pre-broadcast txID ref so the merchant gets
     * identity + ref before money moves. Software-only (Tron hardware commerce is
     * a later add).
     */
    fun signTronTransfer(
        descriptor: TronWalletDescriptor,
        network: TronNetwork,
        rpcURLString: String,
        recipient: String,
        amount: String,
        tokenContract: String?,
        tokenDecimals: Int,
        biometricReason: String,
        /** Host-entered Trezor passphrase for a hidden hardware wallet (null
         *  for software, on-device entry, or standard wallets). */
        hostPassphrase: String? = null,
    ): SignedTronTransfer

    // ---- wallet side: pay (Bitcoin on-chain) ----

    /** The holder's Bitcoin wallet descriptors (read-only view). */
    fun bitcoinWallets(): List<BitcoinWalletDescriptor>

    /** The configured Electrum URL for [network]. */
    fun bitcoinElectrumURL(network: BitcoinNetwork): String

    /**
     * Open + sync [descriptor]'s BDK wallet and return its total balance in
     * satoshis. (Bitcoin needs a synced UTXO set; this also primes it for a
     * subsequent sign.)
     */
    fun bitcoinBalanceSats(descriptor: BitcoinWalletDescriptor): Long

    /**
     * Sign (NOT broadcast) a native BTC payment from [descriptor]: build the
     * unsigned PSBT, sign it with the consumer sandwich, and return the
     * finalized PSBT + the pre-broadcast txid so the merchant gets identity +
     * ref before money moves. Software-only (Bitcoin hardware commerce is a
     * later add).
     */
    fun signBitcoinTransfer(
        descriptor: BitcoinWalletDescriptor,
        recipient: String,
        amountSat: Long,
        biometricReason: String,
        /** Host-entered Trezor passphrase for a hidden hardware wallet (null
         *  for software, on-device entry, or standard wallets). */
        hostPassphrase: String? = null,
    ): SignedBitcoinTransfer

    // ---- wallet side: pay (Lightning, custodial LNDHub) ----

    /** The holder's Lightning accounts (read-only view). */
    fun lightningAccounts(): List<LightningAccount>

    /** The account's spendable balance in satoshis (LNDHub /balance). */
    fun lightningBalanceSat(account: LightningAccount): Long

    /**
     * Pay the merchant-minted BOLT11 [bolt11] from [account] (LNDHub
     * /payinvoice); returns the payment preimage. Lightning has no local
     * "sign": this is the "broadcast" step, run AFTER the identity post. The
     * settlement ref posted to the merchant is the BOLT11 itself (the merchant
     * matches the invoice it issued). No retry on failure (double-pay risk).
     */
    fun payLightningBolt11(account: LightningAccount, bolt11: String): String

    /**
     * Merchant side: mint a BOLT11 invoice for [amountSat] on the merchant's
     * Lightning account [accountId] (LNDHub addinvoice). Used when building a
     * Lightning commerce rail, since a Lightning destination is an invoice (not
     * a reusable address). Returns the BOLT11.
     */
    fun mintLightningInvoice(accountId: String, amountSat: Long, memo: String): String

    // ---- server-blind transport (X-Wing HPKE) ----

    /** Generate a fresh ephemeral merchant keypair to seal responses to. */
    fun newTransportHolder(): TransportHolder

    /** Build an HPKE sender to a published recipient public key. */
    val transportSenderFactory: TransportSenderFactory
}
