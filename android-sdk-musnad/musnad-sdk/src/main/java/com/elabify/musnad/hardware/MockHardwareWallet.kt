// Demo mock hardware wallet, ported 1:1 from iOS MockHardwareWallet.swift.
// Lets the emulator / CI exercise every hardware-touching flow end-to-end:
// device pairing, identity-sandwich wrap/unwrap, chain attestations,
// transaction signing, without a physical device on the wire.
//
// The mock kind is `mock-secp256k1`. The verifier-server has a matching
// special-case path that accepts mock attestations on the basis of
// structural validity only (`kind`, `masterPubkey`, `attestorPubkey` are
// well-formed). Real `trezor-secp256k1` and `ledger-secp256k1` kinds go
// through proper secp256k1 ECDSA verification.
//
// `signMessage` is deterministic per (pubkey, message) so the Identity
// Sandwich wrap path works: HKDF of the same signature produces the same
// wrap key on the next unlock attempt, mirroring Ledger's RFC6979-
// deterministic personal_sign. All chain getters / signers are likewise
// deterministic per (input, account).

package com.elabify.musnad.hardware

import com.elabify.musnad.crypto.hexToBytes
import java.security.MessageDigest
import kotlinx.coroutines.delay

class MockHardwareWallet : HardwareWallet {

    override val kind: HardwareWalletKind = HardwareWalletKind.MOCK

    /** Mock has no BLE transport. */
    override val currentBlePeripheralId: String? = null

    override suspend fun identifyDevice(): String {
        delay(300)
        return DEMO_SERIAL
    }

    override suspend fun pair(): ByteArray {
        // Simulate the device's user-confirmation latency.
        delay(700)
        return DEMO_PUBKEY.copyOf()
    }

    /** Deterministic pseudo-signature: SHA-256(pubkey || msg) ||
     *  SHA-256(msg || pubkey). 64 bytes, byte-identical for repeat calls
     *  with the same message. Matches the RFC6979 contract real Ledger /
     *  Trezor devices honour for personal_sign, which is what the Identity
     *  Sandwich wrap path relies on. */
    override suspend fun signMessage(message: ByteArray): ByteArray {
        delay(500)
        return sha256(DEMO_PUBKEY + message) + sha256(message + DEMO_PUBKEY)
    }

    // Session pinning is a no-op for the mock: there is no BLE connection
    // to keep alive across a discover sweep.
    override fun beginSession() {}
    override fun endSession() {}

    // The mock derives off the demo pubkey, not a real BIP32 tree, so a
    // custom derivation path has nothing to override. Accepted for protocol
    // parity and ignored (mirrors the iOS no-op default for Mock).
    override fun setDerivationPathOverride(path: String?) {}

    // -- Bitcoin --

    /** Deterministic, structurally-plausible tpub/xpub per account so the
     *  Bitcoin add-from-device flow lands on a stable, account-distinct
     *  string. Not a valid BIP32 extended key (the mock has no chain), but
     *  the demo only displays / persists it. */
    override suspend fun getBitcoinAccountXpub(account: Long, networkCoinType: Long): String {
        delay(300)
        val prefix = if (networkCoinType == 1L) "tpub" else "xpub"
        val digest = sha256(DEMO_PUBKEY + accountBytes(account) + coinTypeBytes(networkCoinType))
        return prefix + "MOCK" + digest.toHexUpper()
    }

    /** Deterministic 8-char lowercase-hex master fingerprint. Stable across
     *  networks (the real fingerprint is at the root, network-independent);
     *  `networkCoinType` is ignored beyond diagnostics, as on the device. */
    override suspend fun getBitcoinMasterFingerprint(networkCoinType: Long): String {
        delay(200)
        return sha256(DEMO_PUBKEY).copyOf(4).toHexLower()
    }

    /** The mock has no device to clear-sign on; it echoes the PSBT back
     *  unchanged so the demo's finalise/broadcast pipeline runs (and then
     *  obviously fails on a real RPC, exactly like the other mock signers). */
    override suspend fun signPsbt(psbt: ByteArray, networkCoinType: Long): ByteArray {
        delay(600)
        return psbt.copyOf()
    }

    // -- Ethereum / EVM --

    /** Deterministic Ethereum address per account index. Real Ledger runs
     *  keccak256(pubkey).last(20); the mock skips the secp256k1 step and
     *  hashes the demo pubkey + account so the emulator demo lands on a
     *  stable, account-distinct address. EIP-55 checksumming is intentional
     *  so the displayed address looks like the real thing. */
    override suspend fun getEthereumAddress(account: Long): String {
        delay(300)
        val hash = sha256(DEMO_PUBKEY + accountBytes(account))
        val last20 = hash.copyOfRange(hash.size - 20, hash.size)
        return eip55(last20)
    }

    /** Deterministic V/R/S for SIGN_TRANSACTION. SHA-256(pubkey || input)
     *  and SHA-256(input || pubkey) split across R and S, where input is
     *  envelope || account(BE). V alternates 0/1 by parity of the envelope
     *  length. The verifier server special-cases mock signatures so the
     *  broadcast obviously fails on real RPCs (the demo signer isn't a valid
     *  secp256k1 key on chain) but the entire pre-broadcast pipeline can be
     *  demoed on the emulator. `erc20Descriptor` is accepted for protocol
     *  parity and ignored (the mock has no device to clear-sign on). */
    override suspend fun signEthereumTransaction(
        envelope: ByteArray,
        account: Long,
        erc20Descriptor: ByteArray?,
    ): EcdsaSignature {
        delay(600)
        val input = envelope + accountBytes(account)
        return EcdsaSignature(
            v = (envelope.size and 0x01),
            r = sha256(DEMO_PUBKEY + input),
            s = sha256(input + DEMO_PUBKEY),
        )
    }

    // -- Solana --

    /** Deterministic base58 32-byte pubkey (which IS the address) per
     *  account. Hashes the demo pubkey + account to a stable 32 bytes and
     *  base58-encodes them, matching the real getSolanaAddress contract. */
    override suspend fun getSolanaAddress(account: Long): String {
        delay(300)
        return base58(sha256(DEMO_PUBKEY + accountBytes(account)))
    }

    /** Deterministic 64-byte Ed25519-shaped signature: two SHA-256 halves
     *  over (pubkey || input) / (input || pubkey), input = tx || account. */
    override suspend fun signSolanaTransaction(unsignedTx: ByteArray, account: Long): ByteArray {
        delay(600)
        val input = unsignedTx + accountBytes(account)
        return sha256(DEMO_PUBKEY + input) + sha256(input + DEMO_PUBKEY)
    }

    /** Deterministic OCMS-shaped result for the simulator: the mock address +
     *  a stable 64-byte "signature". Not a real ed25519 signature (the mock
     *  pubkey isn't a real key), so it won't verify; it only lets the
     *  simulator exercise the sign-message UI end-to-end. */
    override suspend fun signSolanaMessage(
        message: String,
        account: Long,
    ): uniffi.ledger_sol_core.SolanaSignedMessage {
        delay(600)
        val address = base58(sha256(DEMO_PUBKEY + accountBytes(account)))
        val input = message.toByteArray(Charsets.UTF_8) + accountBytes(account)
        val sig = sha256(DEMO_PUBKEY + input) + sha256(input + DEMO_PUBKEY)
        return uniffi.ledger_sol_core.SolanaSignedMessage(address = address, signature = base58(sig))
    }

    // -- Tron --

    /** Deterministic base58check `T...` address per account. The mock hashes
     *  the demo pubkey + account to 20 bytes, prepends Tron's 0x41 version
     *  byte, and base58check-encodes, so the demo address parses as a valid
     *  Tron address even though it isn't backed by a real key. */
    override suspend fun getTronAddress(account: Long): String {
        delay(300)
        val h = sha256(DEMO_PUBKEY + accountBytes(account))
        val body = ByteArray(21)
        body[0] = 0x41
        System.arraycopy(h, h.size - 20, body, 1, 20)
        val checksum = sha256(sha256(body)).copyOf(4)
        return base58(body + checksum)
    }

    /** Deterministic uncompressed 65-byte secp256k1-shaped pubkey: 0x04
     *  prefix + 64 bytes from two SHA-256 halves. Not a real curve point,
     *  but the right shape for the TWC TransactionCompiler signer field. */
    override suspend fun getTronPubkey(account: Long): ByteArray {
        delay(200)
        val a = sha256(DEMO_PUBKEY + accountBytes(account))
        val b = sha256(accountBytes(account) + DEMO_PUBKEY)
        return byteArrayOf(0x04) + a + b
    }

    /** Deterministic V/R/S over the Tron raw_data proto, same construction
     *  as the Ethereum signer (Tron signs Ethereum-style secp256k1). */
    override suspend fun signTronTransaction(rawTxProto: ByteArray, account: Long): EcdsaSignature {
        delay(600)
        val input = rawTxProto + accountBytes(account)
        return EcdsaSignature(
            v = (rawTxProto.size and 0x01),
            r = sha256(DEMO_PUBKEY + input),
            s = sha256(input + DEMO_PUBKEY),
        )
    }

    // -- helpers --

    private fun ByteArray.toHexLower(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            out.append(HEX_LOWER[v ushr 4])
            out.append(HEX_LOWER[v and 0x0f])
        }
        return out.toString()
    }

    private fun ByteArray.toHexUpper(): String = toHexLower().uppercase()

    /** Inline EIP-55 mixed-case checksum so the mock doesn't need a keccak
     *  dependency. Lowercase hex of the 20 address bytes, hashed via SHA-256
     *  (instead of keccak256: this is a mock, the address string FORMAT is
     *  the demo property, not the hash). The real device path uses keccak. */
    private fun eip55(addressHex20: ByteArray): String {
        val lower = addressHex20.toHexLower()
        val hashHex = sha256(lower.toByteArray(Charsets.UTF_8)).toHexLower()
        val out = StringBuilder("0x")
        for (i in lower.indices) {
            val ch = lower[i]
            val nibble = Character.digit(hashHex[i], 16)
            if (nibble >= 8 && ch.isLetter()) out.append(ch.uppercaseChar()) else out.append(ch)
        }
        return out.toString()
    }

    companion object {
        private val HEX_LOWER = "0123456789abcdef".toCharArray()

        /** Fixed 33-byte compressed-format secp256k1 pubkey for the demo
         *  "device". Stable so the user sees the same pubkey reappear across
         *  pair / unpair cycles (matches the real-device experience where a
         *  Trezor's master pubkey doesn't change across pairings). */
        val DEMO_PUBKEY: ByteArray =
            hexToBytes("02deadbeefcafebabe000000000000000000000000000000000000000000000001")

        /** Stable identifier returned by identifyDevice. Mirrors the real-
         *  device contract: the same physical unit reports the same serial
         *  across pair / unpair cycles. The Identity Sandwich wrap layer uses
         *  this as authenticated data so a sealed blob can't be opened by a
         *  different mock instance. */
        const val DEMO_SERIAL: String = "MOCK-DEMO-DEVICE-0001"

        private fun sha256(input: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(input)

        /** account index as 4-byte big-endian, mirroring the iOS
         *  `account.bigEndian` append. */
        private fun accountBytes(account: Long): ByteArray {
            val v = account.toInt()
            return byteArrayOf(
                (v ushr 24).toByte(),
                (v ushr 16).toByte(),
                (v ushr 8).toByte(),
                v.toByte(),
            )
        }

        private fun coinTypeBytes(coinType: Long): ByteArray {
            val v = coinType.toInt()
            return byteArrayOf(
                (v ushr 24).toByte(),
                (v ushr 16).toByte(),
                (v ushr 8).toByte(),
                v.toByte(),
            )
        }

        private const val B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

        /** Bitcoin/Base58 (no checksum) encode. Used for Solana addresses and
         *  as the inner step of base58check for Tron. */
        private fun base58(input: ByteArray): String {
            var leadingZeros = 0
            while (leadingZeros < input.size && input[leadingZeros].toInt() == 0) leadingZeros++
            val digits = ArrayList<Int>()
            for (i in input.indices) {
                var carry = input[i].toInt() and 0xff
                for (j in digits.indices) {
                    carry += digits[j] shl 8
                    digits[j] = carry % 58
                    carry /= 58
                }
                while (carry > 0) {
                    digits.add(carry % 58)
                    carry /= 58
                }
            }
            val sb = StringBuilder()
            repeat(leadingZeros) { sb.append(B58[0]) }
            for (k in digits.indices.reversed()) sb.append(B58[digits[k]])
            return sb.toString()
        }
    }
}
