// Signs an SPL token transfer under the sandwich seed and returns the
// base64-encoded wire-ready transaction. Ported 1:1 from iOS
// SolanaSPLTransferBuilder.swift.
//
// SPL transfers are more involved than native SOL: every wallet that
// holds a given mint owns a separate Associated Token Account (ATA), a
// Solana PDA derived deterministically from (owner, tokenProgram, mint).
// The sender's ATA holds the source balance; the recipient's ATA
// receives. If the recipient has never touched this mint before, their
// ATA doesn't exist on chain yet and the tx has to create it first.
//
// iOS routes ATA derivation + the dual-shape transaction (transfer-only
// vs create-then-transfer) through Trust Wallet Core. The Maven
// WalletCore 0.12.8 binding has no Solana coin type, so both are
// hand-rolled in SolanaPrimitives (ATA = findProgramAddress; the
// instructions are the SPL TransferChecked + AssociatedTokenAccount
// Create). The "create-and-transfer" shape is emitted as two
// instructions in one tx, exactly what TWC's SolanaCreateAndTransferToken
// produced.

package com.elabify.musnad.wallet.solana

import com.elabify.musnad.identity.IdentitySandwich

class SolanaSPLTransferException(message: String) : Exception(message)

object SolanaSPLTransferBuilder {

    /** Derive the Associated Token Account address for a (owner, mint)
     *  pair. Deterministic, no chain access. Mirrors iOS
     *  associatedTokenAddress. */
    fun associatedTokenAddress(ownerBase58: String, mintBase58: String): String {
        if (!SolanaPrimitives.isValidAddress(ownerBase58)) {
            throw SolanaSPLTransferException("Recipient address is not a valid Solana pubkey: $ownerBase58")
        }
        if (!SolanaPrimitives.isValidAddress(mintBase58)) {
            throw SolanaSPLTransferException("SPL mint is not a valid Solana pubkey: $mintBase58")
        }
        return try {
            SolanaPrimitives.associatedTokenAddress(ownerBase58, mintBase58)
        } catch (e: Exception) {
            throw SolanaSPLTransferException("Could not derive the recipient's token account.")
        }
    }

    /** Sign an SPL transfer under the sandwich seed.
     *
     *  - recipientOwnerBase58: the recipient's wallet (system) address;
     *    the builder derives their ATA from this + the mint.
     *  - recipientHasATA: whether the recipient already has an ATA for
     *    this mint on chain (caller probes via accountExists). Picks
     *    transfer-only vs create-then-transfer.
     *  - rawAmount: integer token amount in base units (caller does the
     *    decimals math). */
    fun sign(
        sandwich: IdentitySandwich,
        account: Long,
        mintBase58: String,
        decimals: Int,
        rawAmount: Long,
        recipientOwnerBase58: String,
        recipientHasATA: Boolean,
        recentBlockhashBase58: String,
        priorityFeeMicroLamports: Long,
    ): String {
        if (!SolanaPrimitives.isValidAddress(recipientOwnerBase58)) {
            throw SolanaSPLTransferException("Recipient address is not a valid Solana pubkey: $recipientOwnerBase58")
        }
        if (!SolanaPrimitives.isValidAddress(mintBase58)) {
            throw SolanaSPLTransferException("SPL mint is not a valid Solana pubkey: $mintBase58")
        }
        // Fold the identity passphrase into derivation, matching iOS (ADR-0064).
        val seed = SolanaPrimitives.privateSeed(sandwich.recoveryWords(), sandwich.bip39Passphrase(), account)
        val signerBase58 = SolanaPrimitives.base58Encode(SolanaPrimitives.publicKey(seed))
        val instructions = buildSPLInstructions(
            signerBase58 = signerBase58,
            mintBase58 = mintBase58,
            decimals = decimals,
            rawAmount = rawAmount,
            recipientOwnerBase58 = recipientOwnerBase58,
            recipientHasATA = recipientHasATA,
            priorityFeeMicroLamports = priorityFeeMicroLamports,
        )
        return SolanaPrimitives.buildSignedTransaction(
            feePayerBase58 = signerBase58,
            instructions = instructions,
            recentBlockhashBase58 = recentBlockhashBase58,
            signWith = { msg -> SolanaPrimitives.sign(seed, msg) },
        )
    }

    /** Build the unsigned SPL transfer message for hardware sign. Mirrors
     *  iOS unsignedMessage. */
    fun unsignedMessage(
        signerBase58: String,
        mintBase58: String,
        decimals: Int,
        rawAmount: Long,
        recipientOwnerBase58: String,
        recipientHasATA: Boolean,
        recentBlockhashBase58: String,
        priorityFeeMicroLamports: Long,
    ): ByteArray {
        val instructions = buildSPLInstructions(
            signerBase58, mintBase58, decimals, rawAmount,
            recipientOwnerBase58, recipientHasATA, priorityFeeMicroLamports,
        )
        return SolanaPrimitives.serializeMessage(signerBase58, instructions, recentBlockhashBase58)
    }

    /** Combine an externally-produced 64-byte Ed25519 signature with the
     *  SPL transfer parameters to produce a wire-ready signed tx (base64).
     *  Mirrors iOS assembleSigned. */
    fun assembleSigned(
        signerBase58: String,
        mintBase58: String,
        decimals: Int,
        rawAmount: Long,
        recipientOwnerBase58: String,
        recipientHasATA: Boolean,
        recentBlockhashBase58: String,
        priorityFeeMicroLamports: Long,
        signature: ByteArray,
        signerPublicKey: ByteArray? = null,
    ): String {
        if (signature.size != 64) {
            throw SolanaSPLTransferException("Signature was ${signature.size} bytes; expected 64")
        }
        signerPublicKey?.let {
            if (it.size != 32) throw SolanaSPLTransferException("Signer pubkey was ${it.size} bytes; expected 32")
        }
        val instructions = buildSPLInstructions(
            signerBase58, mintBase58, decimals, rawAmount,
            recipientOwnerBase58, recipientHasATA, priorityFeeMicroLamports,
        )
        return SolanaPrimitives.assembleSigned(
            feePayerBase58 = signerBase58,
            instructions = instructions,
            recentBlockhashBase58 = recentBlockhashBase58,
            signature = signature,
        )
    }

    /** Shared instruction list for the SPL transfer shape. `signerBase58`
     *  is both the fee payer and the source-ATA owner (signer). Emits an
     *  optional ComputeBudget price instruction, an optional CreateATA
     *  instruction (when the recipient lacks one), and the
     *  SPL TransferChecked. */
    private fun buildSPLInstructions(
        signerBase58: String,
        mintBase58: String,
        decimals: Int,
        rawAmount: Long,
        recipientOwnerBase58: String,
        recipientHasATA: Boolean,
        priorityFeeMicroLamports: Long,
    ): List<SolanaPrimitives.Instruction> {
        if (!SolanaPrimitives.isValidAddress(recipientOwnerBase58)) {
            throw SolanaSPLTransferException("Recipient address is not a valid Solana pubkey: $recipientOwnerBase58")
        }
        if (!SolanaPrimitives.isValidAddress(mintBase58)) {
            throw SolanaSPLTransferException("SPL mint is not a valid Solana pubkey: $mintBase58")
        }
        val senderATA = associatedTokenAddress(signerBase58, mintBase58)
        val recipientATA = associatedTokenAddress(recipientOwnerBase58, mintBase58)

        val ixs = ArrayList<SolanaPrimitives.Instruction>()
        if (priorityFeeMicroLamports > 0) {
            ixs.add(SolanaPrimitives.computeUnitPrice(priorityFeeMicroLamports))
        }
        if (!recipientHasATA) {
            // create-ATA-then-transfer: prepend the ATA create (funded by
            // the signer) before the TransferChecked.
            ixs.add(
                SolanaPrimitives.createAssociatedTokenAccount(
                    funder = signerBase58,
                    ata = recipientATA,
                    owner = recipientOwnerBase58,
                    mint = mintBase58,
                )
            )
        }
        ixs.add(
            SolanaPrimitives.splTransferChecked(
                sourceATA = senderATA,
                mint = mintBase58,
                destATA = recipientATA,
                owner = signerBase58,
                amount = rawAmount,
                decimals = decimals,
            )
        )
        return ixs
    }
}
