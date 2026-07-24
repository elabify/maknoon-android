// Per-(descriptor, network, sandwich) facade for Solana wallet
// operations, ported 1:1 from iOS SolanaWallet.swift (the `actor
// SolanaWallet`). Owns the RPC client + an in-memory balance cache;
// persistent state lives in SolanaWalletStore + SolanaSettings.
//
// iOS isolates this as a Swift `actor` so the UI can `await` from the
// main thread. On Android the equivalent contract is: call these on a
// background dispatcher (the calls are blocking OkHttp round trips). The
// app's ViewModel layer wraps each in withContext(Dispatchers.IO).
//
// Lives in com.elabify.musnad.wallet.solana. Distinct from the address
// helper com.elabify.musnad.wallet.SolanaWallet (SLIP-0010 derivation),
// which this builds on via SolanaDescriptors / SolanaPrimitives.

package com.elabify.musnad.wallet.solana

import com.elabify.musnad.identity.IdentitySandwich

/** Hardware signing seam. A Ledger/Trezor transport implements this to
 *  sign the unsigned Solana message bytes and return the 64-byte
 *  Ed25519 signature. Mirrors iOS `ledger.signSolanaTransaction`. The
 *  on-device transport is a later phase; this engine only defines the
 *  contract + assembles the signed tx around it. */
interface SolanaHardwareSigner {
    /** Sign the serialized Solana message for the given BIP44 account
     *  index, returning the raw 64-byte signature. */
    fun signSolanaTransaction(unsignedMessage: ByteArray, account: Long): ByteArray
}

class SolanaWallet(
    val descriptor: SolanaWalletDescriptor,
    val network: SolanaNetwork,
    rpcURL: String,
    private val sandwich: IdentitySandwich?,
) {
    private val rpc: SolanaRPCClient =
        SolanaRPCClient(endpoint = rpcURL.ifBlank { network.defaultRpcURL })

    /** Latest lamport balance, or null if not yet fetched. */
    var lamports: Long? = null
        private set
    var lastSyncEpochMs: Long? = null
        private set

    private var cachedAddress: String? = null

    // MARK: -- read

    /** Resolve the wallet's public address. Software wallets derive from
     *  the sandwich seed; hardware wallets read the cached pubkey. */
    fun address(): String = when (val k = descriptor.kind) {
        is SolanaWalletKind.Software -> {
            val s = sandwich ?: throw SolanaDescriptorException("Identity master unavailable")
            SolanaDescriptors.addressFromSandwich(s, k.account)
        }
        is SolanaWalletKind.Hardware -> k.publicKeyBase58
    }

    /** Cache the address once derived per session so the dashboard
     *  doesn't re-derive twice for one screen open. */
    fun resolvedAddress(): String {
        cachedAddress?.let { return it }
        val a = address()
        cachedAddress = a
        return a
    }

    /** Refresh balance from the RPC, updating the cached lamport count. */
    fun refreshBalance(): Long {
        val a = resolvedAddress()
        val bal = rpc.getBalance(a)
        lamports = bal
        lastSyncEpochMs = System.currentTimeMillis()
        return bal
    }

    /** Recent signatures + metadata for the dashboard's tx list. */
    fun recentSignatures(limit: Int = 10): List<SolanaRPCClient.SignatureRecord> {
        val a = resolvedAddress()
        return rpc.getSignaturesForAddress(a, limit = limit)
    }

    /** SOL delta for a single signature, for the tx-list rows. */
    fun transactionDelta(signature: String): SolanaRPCClient.TransactionDelta? {
        val a = resolvedAddress()
        return rpc.getTransactionDelta(signature, a)
    }

    /** Every SPL token account this wallet owns (standard SPL Token
     *  Program only; Token-2022 excluded for v1). */
    fun tokenAccounts(): List<SolanaRPCClient.TokenAccount> {
        val a = resolvedAddress()
        return rpc.getTokenAccountsByOwner(a)
    }

    // MARK: -- send (software)

    /** Build, sign, and broadcast a native SOL transfer. Returns the
     *  signature (the canonical tx id). 1 SOL = 1_000_000_000 lamports. */
    fun sendSoftware(
        recipient: String,
        lamports: Long,
        priorityFeeMicroLamports: Long,
    ): String = broadcastSignedBase64(prepareSoftware(recipient, lamports, priorityFeeMicroLamports))

    /** Sign-only software native step: returns the signed tx (base64) WITHOUT
     *  broadcasting, so the UI can confirm + broadcast separately via
     *  [broadcastSignedBase64] (ADR-0033). */
    fun prepareSoftware(
        recipient: String,
        lamports: Long,
        priorityFeeMicroLamports: Long,
    ): String {
        val s = sandwich ?: throw SolanaDescriptorException("Identity master unavailable")
        val k = descriptor.kind as? SolanaWalletKind.Software
            ?: throw SolanaDescriptorException("Wallet is hardware-backed; software send not applicable")
        val bh = rpc.getLatestBlockhash()
        return SolanaDescriptors.signTransferFromSandwich(
            sandwich = s,
            account = k.account,
            recipientBase58 = recipient,
            lamports = lamports,
            recentBlockhashBase58 = bh.blockhash,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
        )
    }

    /** Build, sign, and broadcast an SPL token transfer. The builder
     *  probes the recipient's ATA existence on chain and picks
     *  transfer-only vs create-then-transfer automatically. `rawAmount`
     *  is in base units (e.g. "1.5 USDC" with 6 decimals = 1_500_000). */
    fun sendSPLToken(
        mint: String,
        decimals: Int,
        rawAmount: Long,
        recipient: String,
        priorityFeeMicroLamports: Long,
    ): String = broadcastSignedBase64(prepareSoftwareSPLToken(mint, decimals, rawAmount, recipient, priorityFeeMicroLamports))

    /** Sign-only software SPL step: returns the signed tx (base64) WITHOUT
     *  broadcasting (ADR-0033). */
    fun prepareSoftwareSPLToken(
        mint: String,
        decimals: Int,
        rawAmount: Long,
        recipient: String,
        priorityFeeMicroLamports: Long,
    ): String {
        val s = sandwich ?: throw SolanaDescriptorException("Identity master unavailable")
        val k = descriptor.kind as? SolanaWalletKind.Software
            ?: throw SolanaDescriptorException("Wallet is hardware-backed; SPL software send not applicable")
        val recipientATA = SolanaSPLTransferBuilder.associatedTokenAddress(recipient, mint)
        val recipientHasATA = runCatching { rpc.accountExists(recipientATA) }.getOrDefault(false)
        val bh = rpc.getLatestBlockhash()
        return SolanaSPLTransferBuilder.sign(
            sandwich = s,
            account = k.account,
            mintBase58 = mint,
            decimals = decimals,
            rawAmount = rawAmount,
            recipientOwnerBase58 = recipient,
            recipientHasATA = recipientHasATA,
            recentBlockhashBase58 = bh.blockhash,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
        )
    }

    // MARK: -- send (hardware)

    /** Hardware-backed native SOL transfer: build the unsigned message,
     *  ship it to the device, get the 64-byte signature, assemble the
     *  wire-ready tx, and broadcast. */
    fun sendHardware(
        recipient: String,
        lamports: Long,
        priorityFeeMicroLamports: Long,
        ledger: SolanaHardwareSigner,
        signerBase58: String,
        signerPublicKey: ByteArray,
        account: Long,
    ): String {
        val signedBase64 = prepareHardwareNative(
            recipient, lamports, priorityFeeMicroLamports, ledger, signerBase58, signerPublicKey, account,
        )
        return rpc.sendTransaction(signedBase64)
    }

    /** Sign-only step of the hardware native send. Returns the
     *  wire-ready signed tx (base64); the caller broadcasts separately
     *  via broadcastSignedBase64. */
    fun prepareHardwareNative(
        recipient: String,
        lamports: Long,
        priorityFeeMicroLamports: Long,
        ledger: SolanaHardwareSigner,
        signerBase58: String,
        signerPublicKey: ByteArray,
        account: Long,
    ): String {
        val bh = rpc.getLatestBlockhash()
        val unsignedMessage = SolanaDescriptors.unsignedMessageForTransfer(
            signerBase58 = signerBase58,
            recipientBase58 = recipient,
            lamports = lamports,
            recentBlockhashBase58 = bh.blockhash,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
        )
        val signature = ledger.signSolanaTransaction(unsignedMessage, account)
        return SolanaDescriptors.assembleSignedTransfer(
            signerBase58 = signerBase58,
            recipientBase58 = recipient,
            lamports = lamports,
            recentBlockhashBase58 = bh.blockhash,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
            signature = signature,
            signerPublicKey = signerPublicKey,
        )
    }

    /** Hardware SPL token transfer (build + sign + broadcast). */
    fun sendHardwareSPLToken(
        mint: String,
        decimals: Int,
        rawAmount: Long,
        recipient: String,
        priorityFeeMicroLamports: Long,
        ledger: SolanaHardwareSigner,
        signerBase58: String,
        signerPublicKey: ByteArray,
        account: Long,
    ): String {
        val signedBase64 = prepareHardwareSPLToken(
            mint, decimals, rawAmount, recipient, priorityFeeMicroLamports,
            ledger, signerBase58, signerPublicKey, account,
        )
        return rpc.sendTransaction(signedBase64)
    }

    /** Sign-only step of the hardware SPL send. Returns base64 signed tx
     *  for broadcastSignedBase64. */
    fun prepareHardwareSPLToken(
        mint: String,
        decimals: Int,
        rawAmount: Long,
        recipient: String,
        priorityFeeMicroLamports: Long,
        ledger: SolanaHardwareSigner,
        signerBase58: String,
        signerPublicKey: ByteArray,
        account: Long,
    ): String {
        val recipientATA = SolanaSPLTransferBuilder.associatedTokenAddress(recipient, mint)
        val recipientHasATA = runCatching { rpc.accountExists(recipientATA) }.getOrDefault(false)
        val bh = rpc.getLatestBlockhash()
        val unsignedMessage = SolanaSPLTransferBuilder.unsignedMessage(
            signerBase58 = signerBase58,
            mintBase58 = mint,
            decimals = decimals,
            rawAmount = rawAmount,
            recipientOwnerBase58 = recipient,
            recipientHasATA = recipientHasATA,
            recentBlockhashBase58 = bh.blockhash,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
        )
        val signature = ledger.signSolanaTransaction(unsignedMessage, account)
        val assembled = SolanaSPLTransferBuilder.assembleSigned(
            signerBase58 = signerBase58,
            mintBase58 = mint,
            decimals = decimals,
            rawAmount = rawAmount,
            recipientOwnerBase58 = recipient,
            recipientHasATA = recipientHasATA,
            recentBlockhashBase58 = bh.blockhash,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
            signature = signature,
            signerPublicKey = signerPublicKey,
        )
        return assembled
    }

    /** Broadcast a pre-signed base64 Solana transaction. */
    fun broadcastSignedBase64(signedBase64: String): String =
        rpc.sendTransaction(signedBase64)

    /** Poll for confirmation after a sendTransaction. Returns the status
     *  (or null if the network hasn't seen it yet). */
    fun signatureStatus(signature: String): SolanaRPCClient.SignatureStatus? =
        rpc.getSignatureStatuses(listOf(signature)).firstOrNull()

    // MARK: -- rent-exempt guard

    /** Pre-flight guard for a native SOL transfer: a transfer that would
     *  create a brand-new recipient account must leave it at or above the
     *  rent-exempt minimum, or the cluster rejects the whole tx. Throws a
     *  clear error in that case. No-op once the recipient exists;
     *  fail-open if the existence probe errors. Mirrors iOS
     *  assertRentExemptForNativeTransfer. */
    fun assertRentExemptForNativeTransfer(recipient: String, lamports: Long) {
        if (lamports >= RENT_EXEMPT_MINIMUM_LAMPORTS) return
        // Fail-open: if the existence probe errors, assume the account exists
        // (default true) so a network hiccup never blocks a legitimate send.
        val exists = runCatching { rpc.accountExists(recipient) }.getOrDefault(true)
        if (!rentExemptBlocksNativeTransfer(lamports, exists)) return
        throw SolanaDescriptorException(
            "${recipient.take(6)}… is a brand-new account. Solana requires at least " +
                "0.00089088 SOL to create it (rent exemption). Send at least that much, " +
                "or fund the address another way first."
        )
    }

    companion object {
        /** Rent-exempt minimum (lamports) for a plain 0-byte system
         *  account. 0.00089088 SOL. Network-invariant. */
        const val RENT_EXEMPT_MINIMUM_LAMPORTS: Long = 890_880L
    }
}

/** Pure rent-exempt decision for a native SOL transfer, split out so the
 *  branches are unit-testable without an RPC. Blocks only when the recipient
 *  is a brand-new account (does not yet exist) AND the amount is below the
 *  rent-exempt minimum. A funded-enough amount, or an already-existing
 *  recipient, never blocks. The caller supplies `recipientExists` with a
 *  fail-open default (true) so an RPC error does not block a legitimate send.
 *  Mirrors iOS SolanaWallet.rentExemptBlocksNativeTransfer. */
internal fun rentExemptBlocksNativeTransfer(lamports: Long, recipientExists: Boolean): Boolean =
    lamports < SolanaWallet.RENT_EXEMPT_MINIMUM_LAMPORTS && !recipientExists
