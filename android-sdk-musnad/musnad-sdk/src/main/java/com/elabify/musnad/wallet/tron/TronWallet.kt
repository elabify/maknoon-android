// One per-(descriptor, network, sandwich) facade for Tron wallet
// operations, ported from iOS TronWallet.swift. The iOS type is an
// `actor`; on Android the calls are synchronous + blocking (the app
// layer wraps them in coroutines / Dispatchers.IO, matching the
// Bitcoin + Solana Android ports), so there is no actor isolation here.
//
// Read paths: address / balance / recent transactions / now-block.
// Send paths: native TRX + TRC-20, software (seed) and a hardware hook.
//
// Software signing on Android takes the server-built-unsigned +
// splice-signature route because the WalletCore binding lacks the JSON
// output the iOS build uses; see TronDescriptors for the rationale.

package com.elabify.musnad.wallet.tron

import com.elabify.musnad.identity.IdentitySandwich

class TronWallet(
    val descriptor: TronWalletDescriptor,
    val network: TronNetwork,
    rpcURL: String,
    private val sandwich: IdentitySandwich?,
) {
    private val rpc: TronRPCClient = TronRPCClient(rpcURL)

    /** Latest sun balance, or null if not yet fetched. */
    var sun: Long? = null
        private set
    var lastSyncAtEpochMs: Long? = null
        private set
    private var cachedAddress: String? = null

    // MARK: -- read

    fun address(): String {
        return when (val kind = descriptor.kind) {
            is TronWalletKind.Software -> {
                val s = sandwich ?: throw TronSigningException("Identity master unavailable")
                TronDescriptors.addressFromSandwich(s, kind.account)
            }
            is TronWalletKind.Hardware -> kind.addressBase58Check
        }
    }

    /** Cache the derived address across a single dashboard refresh so the
     *  biometric prompt only fires once per screen open. */
    fun resolvedAddress(): String {
        cachedAddress?.let { return it }
        val a = address()
        cachedAddress = a
        return a
    }

    fun refreshBalance(): Long {
        val a = resolvedAddress()
        val b = rpc.getBalance(a)
        sun = b
        lastSyncAtEpochMs = System.currentTimeMillis()
        return b
    }

    fun recentTransactions(limit: Int = 10): List<TronRPCClient.TxRecord> {
        val a = resolvedAddress()
        return rpc.getTransactionsByAddress(a, limit)
    }

    /** Block reference pass-through (kept for parity with iOS; the
     *  create*Transaction server path now folds the block ref in). */
    fun nowBlock(): TronRPCClient.NowBlock = rpc.getNowBlock()

    /** Read a TRC-20 balance held by this wallet's address. Returns the
     *  raw on-chain integer as a base-10 string. */
    fun trc20Balance(contractBase58: String, rpcURL: String): String =
        TronTRC20TransferBuilder.balance(resolvedAddress(), contractBase58, rpcURL)

    // MARK: -- send (software)

    /** Send native TRX. Returns the txid (hex). The [feeLimitSun] cap
     *  protects against runaway energy burn; for a pure transfer 1 TRX
     *  is plenty. */
    fun sendNative(
        recipient: String,
        sunAmount: Long,
        feeLimitSun: Long = 1_000_000,
    ): String {
        val s = sandwich ?: throw TronSigningException("Identity master unavailable")
        val account = (descriptor.kind as? TronWalletKind.Software)?.account
            ?: throw TronSigningException("Hardware Tron send not implemented in this build")
        if (!TronAddressCodec.isValid(recipient)) {
            throw TronSigningException("Invalid Tron address: $recipient")
        }
        val sender = resolvedAddress()
        val unsigned = rpc.createNativeTransaction(
            senderBase58 = sender,
            recipientBase58 = recipient,
            sunAmount = sunAmount,
            feeLimitSun = feeLimitSun,
        )
        val signed = TronDescriptors.signUnsignedFromSandwich(s, account, unsigned)
        return rpc.broadcastWithSignature(signed.envelopeJSON, signed.signatureRSV)
    }

    /** TRC-20 token transfer. `rawAmount` is the on-chain integer as a
     *  base-10 string (so "1.00 USDT" with 6 decimals is "1000000").
     *  Returns the txid. */
    fun sendTRC20(
        contractAddress: String,
        rawAmount: String,
        recipient: String,
        feeLimitSun: Long = 100_000_000,
    ): String {
        val s = sandwich ?: throw TronSigningException("Identity master unavailable")
        val account = (descriptor.kind as? TronWalletKind.Software)?.account
            ?: throw TronSigningException("Hardware Tron send not implemented in this build")
        if (!TronAddressCodec.isValid(contractAddress)) {
            throw TronSigningException("Invalid TRC-20 contract address: $contractAddress")
        }
        if (!TronAddressCodec.isValid(recipient)) {
            throw TronSigningException("Invalid recipient address: $recipient")
        }
        val sender = resolvedAddress()
        val unsigned = rpc.createTRC20Transaction(
            senderBase58 = sender,
            contractAddressBase58 = contractAddress,
            recipientBase58 = recipient,
            rawAmount = rawAmount,
            feeLimitSun = feeLimitSun,
        )
        val signed = TronDescriptors.signUnsignedFromSandwich(s, account, unsigned)
        return rpc.broadcastWithSignature(signed.envelopeJSON, signed.signatureRSV)
    }

    // MARK: -- send (hardware Ledger)
    //
    // Tron is Ledger-only on hardware (Trezor firmware does not
    // implement Tron). The signing transport (BLE APDU) is a later
    // phase; these methods expose the prepare/broadcast split so the
    // UI can show a "signed, awaiting broadcast" interstitial, mirroring
    // iOS prepareHardwareNative/prepareHardwareTRC20 +
    // broadcastHardwareSignature.

    /** Build the unsigned native transfer the Ledger Tron app signs.
     *  The caller hands raw_data to the device, receives r/s/v, then
     *  assembles + broadcasts. */
    fun prepareHardwareNative(
        recipient: String,
        sunAmount: Long,
        senderBase58: String,
    ): TronRPCClient.UnsignedTransaction {
        if (!TronAddressCodec.isValid(recipient)) {
            throw TronSigningException("Invalid Tron address: $recipient")
        }
        // Native TRX transfers don't need a fee_limit (sender pays
        // bandwidth/energy); pass null so the server uses its default.
        return rpc.createNativeTransaction(
            senderBase58 = senderBase58,
            recipientBase58 = recipient,
            sunAmount = sunAmount,
            feeLimitSun = null,
        )
    }

    /** Build the unsigned TRC-20 transfer the Ledger Tron app signs. The
     *  Ledger Tron app may require the user to enable "Custom contracts"
     *  if the token isn't on its known-allowlist. */
    fun prepareHardwareTRC20(
        contractAddress: String,
        recipient: String,
        rawAmount: String,
        feeLimitSun: Long,
        senderBase58: String,
    ): TronRPCClient.UnsignedTransaction {
        if (!TronAddressCodec.isValid(contractAddress)) {
            throw TronSigningException("Invalid TRC-20 contract address: $contractAddress")
        }
        if (!TronAddressCodec.isValid(recipient)) {
            throw TronSigningException("Invalid recipient address: $recipient")
        }
        return rpc.createTRC20Transaction(
            senderBase58 = senderBase58,
            contractAddressBase58 = contractAddress,
            recipientBase58 = recipient,
            rawAmount = rawAmount,
            feeLimitSun = feeLimitSun,
        )
    }

    /** Broadcast a Ledger-signed transfer assembled by
     *  [TronDescriptors.assembleHardwareSignature]. */
    fun broadcastHardwareSignature(signed: TronDescriptors.TronUnsignedAndSignature): String =
        rpc.broadcastWithSignature(signed.envelopeJSON, signed.signatureRSV)

    /** Broadcast a pre-signed transaction JSON. Idempotent on the
     *  caller's side: a re-broadcast of the same tx returns "tx already
     *  in pool", which the caller can treat as success. */
    fun broadcastSignedJSON(signedJSON: String): String =
        rpc.broadcastTransaction(signedJSON)
}
