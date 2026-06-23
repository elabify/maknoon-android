// Solana software-wallet primitives, ported 1:1 from iOS
// SolanaDescriptors.swift: BIP44 + SLIP-0010 Ed25519 derivation at
// m/44'/501'/<account>'/0' plus host-side signing for native SOL
// transfers.
//
// The iOS code routes derivation + signing + wire framing through Trust
// Wallet Core (HDWallet.getKeyByCurve + AnySigner + TransactionCompiler).
// The Maven WalletCore 0.12.8 binding has no Solana coin type, so the
// equivalent logic is hand-rolled in SolanaPrimitives (ed25519 sign +
// legacy message serialization) and orchestrated here. Method names and
// signatures match the iOS enum's static funcs so the UI port can call
// the same shapes.

package com.elabify.musnad.wallet.solana

import com.elabify.musnad.identity.IdentitySandwich

class SolanaDescriptorException(message: String) : Exception(message)

object SolanaDescriptors {

    /** Standard SLIP-0010 Ed25519 BIP44 path for Solana. The address
     *  index is omitted (each account's primary key IS the address). */
    fun derivationPath(account: Long): String = "m/44'/501'/$account'/0'"

    /** Derive the base58 Solana address for `account` from the sandwich
     *  seed. Mirrors iOS addressFromSandwich.
     *
     *  `passphrase` is the BIP39 recovery passphrase used to derive the
     *  seed. The Android IdentitySandwich keeps its passphrase private,
     *  so the UI/holder layer must pass it (it already holds it after a
     *  biometric unlock). Defaults to "" for the common passphrase-free
     *  identity. See openQuestions. */
    fun addressFromSandwich(
        sandwich: IdentitySandwich,
        account: Long,
        passphrase: String = "",
    ): String {
        return SolanaPrimitives.addressFor(sandwich.recoveryWords(), passphrase, account)
    }

    /** Validate a Solana address as a 32-byte base58 pubkey. Returns
     *  null if malformed (mirrors iOS parseAddress). */
    fun parseAddress(s: String): String? = if (SolanaPrimitives.isValidAddress(s)) s else null

    /** Sign a native SOL transfer under the sandwich seed. Returns the
     *  wire-ready signed transaction as base64, for sendTransaction.
     *
     *  `lamports` is the integer SOL amount (1 SOL = 10^9 lamports).
     *  `priorityFeeMicroLamports` (>0) adds a ComputeBudget
     *  SetComputeUnitPrice instruction. Mirrors iOS
     *  signTransferFromSandwich. */
    fun signTransferFromSandwich(
        sandwich: IdentitySandwich,
        account: Long,
        recipientBase58: String,
        lamports: Long,
        recentBlockhashBase58: String,
        priorityFeeMicroLamports: Long,
        passphrase: String = "",
    ): String {
        if (parseAddress(recipientBase58) == null) {
            throw SolanaDescriptorException("Not a valid Solana address: $recipientBase58")
        }
        val seed = SolanaPrimitives.privateSeed(sandwich.recoveryWords(), passphrase, account)
        val signerBase58 = SolanaPrimitives.base58Encode(SolanaPrimitives.publicKey(seed))
        val instructions = transferInstructions(
            signerBase58 = signerBase58,
            recipientBase58 = recipientBase58,
            lamports = lamports,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
        )
        return SolanaPrimitives.buildSignedTransaction(
            feePayerBase58 = signerBase58,
            instructions = instructions,
            recentBlockhashBase58 = recentBlockhashBase58,
            signWith = { msg -> SolanaPrimitives.sign(seed, msg) },
        )
    }

    /** Build the instruction list for a native SOL transfer: optional
     *  ComputeBudget price instruction (priority fee) then the System
     *  transfer. Used by both software + hardware paths. */
    private fun transferInstructions(
        signerBase58: String,
        recipientBase58: String,
        lamports: Long,
        priorityFeeMicroLamports: Long,
    ): List<SolanaPrimitives.Instruction> {
        val ixs = ArrayList<SolanaPrimitives.Instruction>()
        if (priorityFeeMicroLamports > 0) {
            ixs.add(SolanaPrimitives.computeUnitPrice(priorityFeeMicroLamports))
        }
        ixs.add(SolanaPrimitives.systemTransfer(signerBase58, recipientBase58, lamports))
        return ixs
    }

    /** Build the unsigned message bytes for a native SOL transfer. This
     *  is exactly what gets fed to the Ledger Solana app's SIGN_MESSAGE
     *  APDU. Use assembleSignedTransfer to stitch the resulting 64-byte
     *  signature into a wire-ready tx. Mirrors iOS unsignedMessageForTransfer. */
    fun unsignedMessageForTransfer(
        signerBase58: String,
        recipientBase58: String,
        lamports: Long,
        recentBlockhashBase58: String,
        priorityFeeMicroLamports: Long,
    ): ByteArray {
        if (parseAddress(recipientBase58) == null) {
            throw SolanaDescriptorException("Not a valid Solana address: $recipientBase58")
        }
        val instructions = transferInstructions(
            signerBase58, recipientBase58, lamports, priorityFeeMicroLamports,
        )
        return SolanaPrimitives.serializeMessage(signerBase58, instructions, recentBlockhashBase58)
    }

    /** Combine an externally-produced 64-byte Ed25519 signature with the
     *  transfer parameters to produce a wire-ready signed tx (base64).
     *  Mirrors iOS assembleSignedTransfer (the signerPublicKey arg is no
     *  longer needed: the signature alone slots into the tx framing). */
    fun assembleSignedTransfer(
        signerBase58: String,
        recipientBase58: String,
        lamports: Long,
        recentBlockhashBase58: String,
        priorityFeeMicroLamports: Long,
        signature: ByteArray,
        signerPublicKey: ByteArray? = null,
    ): String {
        if (signature.size != 64) {
            throw SolanaDescriptorException("Signature was ${signature.size} bytes; expected 64")
        }
        signerPublicKey?.let {
            if (it.size != 32) throw SolanaDescriptorException("Signer public key was ${it.size} bytes; expected 32")
        }
        val instructions = transferInstructions(
            signerBase58, recipientBase58, lamports, priorityFeeMicroLamports,
        )
        return SolanaPrimitives.assembleSigned(
            feePayerBase58 = signerBase58,
            instructions = instructions,
            recentBlockhashBase58 = recentBlockhashBase58,
            signature = signature,
        )
    }

}
