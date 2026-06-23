// Low-level Solana wire primitives that the iOS app gets "for free" from
// Trust Wallet Core but that the Maven WalletCore 0.12.8 binding cannot
// provide (it ships an older core WITHOUT a Solana coin type). We
// therefore hand-roll, against the canonical Solana docs:
//
//   - base58 decode (encode already lives in wallet.SolanaWallet)
//   - SLIP-0010 ed25519 key derivation at m/44'/501'/<account>'/0'
//     (reuses wallet.SolanaWallet's verified SLIP-0010 + base58 code)
//   - the legacy (v0) transaction message format: compact-u16 length
//     prefixes, the MessageHeader, the account-key table, the recent
//     blockhash, and compiled instructions
//   - the System Program `transfer` instruction
//   - the ComputeBudget `SetComputeUnitLimit` + `SetComputeUnitPrice`
//     instructions (priority fee)
//   - the SPL Associated Token Account address derivation (a program
//     -derived address: SHA-256 of [owner, tokenProgram, mint,
//     "ProgramDerivedAddress"], bumped off the ed25519 curve)
//   - the SPL Token `TransferChecked` instruction
//   - the Associated Token Account `Create` instruction
//
// Signing is Ed25519 over the serialized message bytes via BouncyCastle
// (Ed25519Signer), using the SLIP-0010-derived 32-byte private seed.
// See openQuestions: this replaces WalletCore's SolanaSigningInput /
// AnySigner / TransactionCompiler with a manual implementation. The wire
// format is fully specified and deterministic, but it is net-new code
// not covered by the WalletCore KAT corpus, so it must be device-verified
// against a live cluster (a getFeeForMessage / simulateTransaction round
// trip, or an actual devnet send) before shipping.

package com.elabify.musnad.wallet.solana

import com.elabify.musnad.wallet.SolanaWallet
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Well-known Solana program ids (base58). */
object SolanaProgramIds {
    const val SYSTEM = "11111111111111111111111111111111"
    const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val ASSOCIATED_TOKEN = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    const val COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
    const val SYSVAR_RENT = "SysvarRent111111111111111111111111111111111"
}

object SolanaPrimitives {

    private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    // ---- base58 ----

    fun base58Encode(input: ByteArray): String = SolanaWallet.base58Encode(input)

    /** Decode a base58 (Bitcoin alphabet) string to bytes. Returns null
     *  when a non-alphabet char appears. */
    fun base58Decode(input: String): ByteArray? {
        if (input.isEmpty()) return ByteArray(0)
        var leadingZeros = 0
        while (leadingZeros < input.length && input[leadingZeros] == '1') leadingZeros++
        // Process in base-256 via repeated multiply (input is short: 32-44 chars).
        val bytes = ArrayList<Int>()
        for (c in input) {
            val digit = BASE58_ALPHABET.indexOf(c)
            if (digit < 0) return null
            var carry = digit
            for (j in bytes.indices) {
                carry += bytes[j] * 58
                bytes[j] = carry and 0xff
                carry = carry ushr 8
            }
            while (carry > 0) {
                bytes.add(carry and 0xff)
                carry = carry ushr 8
            }
        }
        val out = ByteArray(leadingZeros + bytes.size)
        for (i in bytes.indices) {
            out[leadingZeros + (bytes.size - 1 - i)] = bytes[i].toByte()
        }
        return out
    }

    /** Decode a base58 pubkey into its raw 32 bytes. Throws on invalid. */
    fun pubkeyBytes(base58: String): ByteArray {
        val raw = base58Decode(base58)
            ?: throw IllegalArgumentException("Not valid base58: $base58")
        require(raw.size == 32) { "Solana pubkey must be 32 bytes, got ${raw.size}: $base58" }
        return raw
    }

    /** True iff `s` decodes to a 32-byte base58 pubkey (matches iOS's
     *  AnyAddress(string:coin:.solana) validity check). */
    fun isValidAddress(s: String): Boolean {
        val raw = base58Decode(s) ?: return false
        return raw.size == 32
    }

    // ---- ed25519 key derivation (SLIP-0010, m/44'/501'/<account>'/0') ----

    /** Returns the 32-byte ed25519 private seed for a software account. */
    fun privateSeed(words: List<String>, passphrase: String, account: Long): ByteArray {
        val seed = com.elabify.core.Bip39.derivedSeed(words, passphrase)
        var node = SolanaWallet.slip10MasterEd25519(seed)
        for (index in intArrayOf(44, 501, account.toInt(), 0)) {
            node = SolanaWallet.slip10DeriveHardened(node, index)
        }
        return node.key
    }

    /** 32-byte ed25519 public key for a private seed. */
    fun publicKey(privateSeed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateSeed, 0).generatePublicKey().encoded

    /** Base58 Solana address for a software account. */
    fun addressFor(words: List<String>, passphrase: String, account: Long): String =
        base58Encode(publicKey(privateSeed(words, passphrase, account)))

    /** Sign `message` with the ed25519 private seed; returns the 64-byte
     *  signature. */
    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    // ---- compact-u16 (shortvec) ----

    /** Solana's compact-u16 (a.k.a. ShortVec) length encoding. */
    fun encodeLength(len: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = len
        while (true) {
            var elem = remaining and 0x7f
            remaining = remaining ushr 7
            if (remaining == 0) {
                out.write(elem)
                break
            } else {
                elem = elem or 0x80
                out.write(elem)
            }
        }
        return out.toByteArray()
    }

    private fun le64(v: Long): ByteArray {
        val b = ByteArray(8)
        var x = v
        for (i in 0 until 8) { b[i] = (x and 0xff).toByte(); x = x ushr 8 }
        return b
    }

    private fun le32(v: Long): ByteArray {
        val b = ByteArray(4)
        var x = v
        for (i in 0 until 4) { b[i] = (x and 0xff).toByte(); x = x ushr 8 }
        return b
    }

    // ---- account meta + instruction model ----

    data class AccountMeta(val pubkey: String, val isSigner: Boolean, val isWritable: Boolean)

    /** A not-yet-compiled instruction: program id + account metas + data. */
    data class Instruction(
        val programId: String,
        val accounts: List<AccountMeta>,
        val data: ByteArray,
    )

    // ---- instruction builders ----

    /** System Program transfer: 4-byte LE instruction index (2) +
     *  8-byte LE lamports. */
    fun systemTransfer(fromBase58: String, toBase58: String, lamports: Long): Instruction {
        val data = ByteArrayOutputStream()
        data.write(le32(2L))     // SystemInstruction::Transfer index = 2
        data.write(le64(lamports))
        return Instruction(
            programId = SolanaProgramIds.SYSTEM,
            accounts = listOf(
                AccountMeta(fromBase58, isSigner = true, isWritable = true),
                AccountMeta(toBase58, isSigner = false, isWritable = true),
            ),
            data = data.toByteArray(),
        )
    }

    /** ComputeBudget SetComputeUnitLimit: tag 0x02 + u32 LE units. */
    fun computeUnitLimit(units: Long): Instruction {
        val data = ByteArrayOutputStream()
        data.write(0x02)
        data.write(le32(units))
        return Instruction(SolanaProgramIds.COMPUTE_BUDGET, emptyList(), data.toByteArray())
    }

    /** ComputeBudget SetComputeUnitPrice: tag 0x03 + u64 LE
     *  micro-lamports-per-CU. */
    fun computeUnitPrice(microLamports: Long): Instruction {
        val data = ByteArrayOutputStream()
        data.write(0x03)
        data.write(le64(microLamports))
        return Instruction(SolanaProgramIds.COMPUTE_BUDGET, emptyList(), data.toByteArray())
    }

    /** SPL Token TransferChecked: tag 12 + u64 LE amount + u8 decimals.
     *  Accounts: [source, mint, destination, owner(signer)]. */
    fun splTransferChecked(
        sourceATA: String,
        mint: String,
        destATA: String,
        owner: String,
        amount: Long,
        decimals: Int,
    ): Instruction {
        val data = ByteArrayOutputStream()
        data.write(12) // TokenInstruction::TransferChecked
        data.write(le64(amount))
        data.write(decimals and 0xff)
        return Instruction(
            programId = SolanaProgramIds.TOKEN,
            accounts = listOf(
                AccountMeta(sourceATA, isSigner = false, isWritable = true),
                AccountMeta(mint, isSigner = false, isWritable = false),
                AccountMeta(destATA, isSigner = false, isWritable = true),
                AccountMeta(owner, isSigner = true, isWritable = false),
            ),
            data = data.toByteArray(),
        )
    }

    /** Associated Token Account program `Create` (idempotent-less, the
     *  classic create). Empty instruction data. Accounts:
     *  [funder(signer,writable), ata(writable), owner, mint,
     *   systemProgram, tokenProgram]. */
    fun createAssociatedTokenAccount(
        funder: String,
        ata: String,
        owner: String,
        mint: String,
    ): Instruction = Instruction(
        programId = SolanaProgramIds.ASSOCIATED_TOKEN,
        accounts = listOf(
            AccountMeta(funder, isSigner = true, isWritable = true),
            AccountMeta(ata, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = false, isWritable = false),
            AccountMeta(mint, isSigner = false, isWritable = false),
            AccountMeta(SolanaProgramIds.SYSTEM, isSigner = false, isWritable = false),
            AccountMeta(SolanaProgramIds.TOKEN, isSigner = false, isWritable = false),
        ),
        data = ByteArray(0),
    )

    // ---- associated token address (PDA) ----

    /** Derive the Associated Token Account address for (owner, mint)
     *  under the standard SPL Token Program. ATA = the program-derived
     *  address of seeds [owner, tokenProgram, mint] under the
     *  Associated Token program. */
    fun associatedTokenAddress(owner: String, mint: String): String {
        val ownerBytes = pubkeyBytes(owner)
        val tokenProgramBytes = pubkeyBytes(SolanaProgramIds.TOKEN)
        val mintBytes = pubkeyBytes(mint)
        val programBytes = pubkeyBytes(SolanaProgramIds.ASSOCIATED_TOKEN)
        val seeds = listOf(ownerBytes, tokenProgramBytes, mintBytes)
        val (address, _) = findProgramAddress(seeds, programBytes)
        return base58Encode(address)
    }

    // ---- SNS (.sol) name registry ----

    private const val SNS_NAME_PROGRAM = "namesLPneVptA9Z5rqUDD9tMTWEJwofgaYwp8cawRkX"
    private const val SNS_SOL_TLD = "58PwtjSDuFHuUkYjH9BYnnQKHfwo9reZhC2zMJv9JPkx"
    private const val SNS_HASH_PREFIX = "SPL Name Service"

    /**
     * The SNS name-registry account key for a single-level ".sol" domain (pass
     * "bonfida" for "bonfida.sol"). The caller fetches this account's data and
     * reads the owner pubkey at byte offset 32 (NameRegistryState =
     * parentName[32] | owner[32] | class[32] | data). Subdomains are not handled.
     */
    fun solDomainAccountKey(name: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(SNS_HASH_PREFIX.toByteArray(Charsets.UTF_8))
        md.update(name.toByteArray(Charsets.UTF_8))
        val hashedName = md.digest()
        val classKey = ByteArray(32) // PublicKey.default (no name class)
        val parentKey = pubkeyBytes(SNS_SOL_TLD)
        val programId = pubkeyBytes(SNS_NAME_PROGRAM)
        val (key, _) = findProgramAddress(listOf(hashedName, classKey, parentKey), programId)
        return base58Encode(key)
    }

    /** Solana's findProgramAddress: walk bump 255..0 and return the
     *  first off-curve SHA-256(seeds || bump || programId || "PDA"). */
    private fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): Pair<ByteArray, Int> {
        var bump = 255
        while (bump >= 0) {
            val md = MessageDigest.getInstance("SHA-256")
            seeds.forEach { md.update(it) }
            md.update(byteArrayOf(bump.toByte()))
            md.update(programId)
            md.update("ProgramDerivedAddress".toByteArray(Charsets.US_ASCII))
            val candidate = md.digest()
            if (!isOnCurve(candidate)) return candidate to bump
            bump--
        }
        throw IllegalStateException("Unable to find a viable program address bump")
    }

    /** True iff the 32 bytes decode to a point ON the ed25519 curve, using
     *  Solana's PDA definition: "the compressed Y decompresses to a valid curve
     *  point" (curve25519-dalek `CompressedEdwardsY::decompress().is_some()`).
     *  We MUST use validatePublicKeyPartial (decode/on-curve only), NOT
     *  validatePublicKeyFull: the full check additionally rejects small-order /
     *  non-prime-subgroup points, which ARE on the curve for Solana's purposes.
     *  Using the full check made findProgramAddress treat such a bump candidate
     *  as off-curve and pick a NON-CANONICAL bump, yielding the wrong ATA for
     *  the (rare) owners whose canonical bump sits below a small-order candidate
     *  (e.g. a recipient whose canonical ATA bump is 250 while bump 255 is a
     *  small-order curve point). That broke SPL sends with "provided seeds do
     *  not result in a valid address". */
    private fun isOnCurve(point: ByteArray): Boolean = try {
        Ed25519.validatePublicKeyPartial(point, 0)
    } catch (e: Exception) {
        false
    }

    // ---- message assembly + signing ----

    /** Compile the instruction set into a legacy (v0/unversioned) Solana
     *  message and produce the wire-ready signed transaction as base64.
     *  `feePayerSigner` is the only signer (the wallet); its 64-byte
     *  signature is produced over the serialized message. */
    fun buildSignedTransaction(
        feePayerBase58: String,
        instructions: List<Instruction>,
        recentBlockhashBase58: String,
        signWith: (message: ByteArray) -> ByteArray,
    ): String {
        val message = serializeMessage(feePayerBase58, instructions, recentBlockhashBase58)
        val signature = signWith(message)
        require(signature.size == 64) { "Signature was ${signature.size} bytes; expected 64" }
        val tx = ByteArrayOutputStream()
        tx.write(encodeLength(1)) // one signature
        tx.write(signature)
        tx.write(message)
        return java.util.Base64.getEncoder().encodeToString(tx.toByteArray())
    }

    /** Assemble a signed tx from an externally-produced signature
     *  (hardware path). */
    fun assembleSigned(
        feePayerBase58: String,
        instructions: List<Instruction>,
        recentBlockhashBase58: String,
        signature: ByteArray,
    ): String {
        require(signature.size == 64) { "Signature was ${signature.size} bytes; expected 64" }
        val message = serializeMessage(feePayerBase58, instructions, recentBlockhashBase58)
        val tx = ByteArrayOutputStream()
        tx.write(encodeLength(1))
        tx.write(signature)
        tx.write(message)
        return java.util.Base64.getEncoder().encodeToString(tx.toByteArray())
    }

    /** Produce the raw message bytes the wallet (or a Ledger) signs. */
    fun serializeMessage(
        feePayerBase58: String,
        instructions: List<Instruction>,
        recentBlockhashBase58: String,
    ): ByteArray {
        // 1. Collect all account metas, dedup by pubkey, fold flags
        //    (writable/signer OR-ed across appearances). The fee payer is
        //    always the first signer + writable.
        val metaByKey = LinkedHashMap<String, AccountMeta>()
        fun fold(m: AccountMeta) {
            val existing = metaByKey[m.pubkey]
            metaByKey[m.pubkey] = if (existing == null) m
            else AccountMeta(m.pubkey, existing.isSigner || m.isSigner, existing.isWritable || m.isWritable)
        }
        fold(AccountMeta(feePayerBase58, isSigner = true, isWritable = true))
        for (ix in instructions) {
            ix.accounts.forEach { fold(it) }
            // The program id is a read-only, non-signer account.
            fold(AccountMeta(ix.programId, isSigner = false, isWritable = false))
        }

        // 2. Order: signer+writable, signer+readonly, nonsigner+writable,
        //    nonsigner+readonly. Fee payer must be index 0.
        val all = metaByKey.values.toMutableList()
        all.sortWith(Comparator { a, b ->
            if (a.pubkey == feePayerBase58) return@Comparator -1
            if (b.pubkey == feePayerBase58) return@Comparator 1
            val ra = rank(a)
            val rb = rank(b)
            ra - rb
        })

        val numRequiredSignatures = all.count { it.isSigner }
        val numReadonlySigned = all.count { it.isSigner && !it.isWritable }
        val numReadonlyUnsigned = all.count { !it.isSigner && !it.isWritable }

        val keyIndex = HashMap<String, Int>()
        all.forEachIndexed { i, m -> keyIndex[m.pubkey] = i }

        val out = ByteArrayOutputStream()
        // MessageHeader
        out.write(numRequiredSignatures)
        out.write(numReadonlySigned)
        out.write(numReadonlyUnsigned)
        // Account keys
        out.write(encodeLength(all.size))
        for (m in all) out.write(pubkeyBytes(m.pubkey))
        // Recent blockhash (32 bytes)
        out.write(pubkeyBytes(recentBlockhashBase58))
        // Instructions
        out.write(encodeLength(instructions.size))
        for (ix in instructions) {
            out.write(keyIndex[ix.programId]!!)
            out.write(encodeLength(ix.accounts.size))
            for (acc in ix.accounts) out.write(keyIndex[acc.pubkey]!!)
            out.write(encodeLength(ix.data.size))
            out.write(ix.data)
        }
        return out.toByteArray()
    }

    /** Account ordering rank: lower sorts first. */
    private fun rank(m: AccountMeta): Int = when {
        m.isSigner && m.isWritable -> 0
        m.isSigner && !m.isWritable -> 1
        !m.isSigner && m.isWritable -> 2
        else -> 3
    }
}
