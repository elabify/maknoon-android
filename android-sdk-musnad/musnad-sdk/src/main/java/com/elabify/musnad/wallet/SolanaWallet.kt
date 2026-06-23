// Solana wallet. The Maven WalletCore binding lacks Solana, so we derive it
// directly with the same scheme WalletCore/the iOS app use: SLIP-0010 ed25519
// over the BIP-39 seed, path m/44'/501'/0'/0' (all hardened), then the
// Ed25519 public key base58-encoded is the Solana address. The SLIP-0010
// derivation is verified against the spec's canonical test vectors
// (SolanaWalletDeviceTest), so this matches WalletCore's output.

package com.elabify.musnad.wallet

import com.elabify.core.Bip39
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

object SolanaWallet {

    /** Solana address (base58 of the Ed25519 public key) for the given seed. */
    fun address(words: List<String>, passphrase: String = ""): String {
        val seed = Bip39.derivedSeed(words, passphrase) // 64-byte BIP-39 seed
        // m/44'/501'/0'/0'
        var node = slip10MasterEd25519(seed)
        for (index in intArrayOf(44, 501, 0, 0)) {
            node = slip10DeriveHardened(node, index)
        }
        val pub = Ed25519PrivateKeyParameters(node.key, 0).generatePublicKey().encoded
        return base58Encode(pub)
    }

    // ---- SLIP-0010 (ed25519) ----

    /** A derived node: 32-byte key + 32-byte chain code. Exposed for KAT tests. */
    data class Node(val key: ByteArray, val chainCode: ByteArray)

    fun slip10MasterEd25519(seed: ByteArray): Node {
        val i = hmacSha512("ed25519 seed".toByteArray(Charsets.US_ASCII), seed)
        return Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
    }

    /** Hardened-only child derivation (ed25519 has no public derivation). */
    fun slip10DeriveHardened(parent: Node, index: Int): Node {
        val hardened = index.toLong() or 0x80000000L
        val data = ByteArray(1 + 32 + 4)
        data[0] = 0x00
        System.arraycopy(parent.key, 0, data, 1, 32)
        data[33] = ((hardened ushr 24) and 0xff).toByte()
        data[34] = ((hardened ushr 16) and 0xff).toByte()
        data[35] = ((hardened ushr 8) and 0xff).toByte()
        data[36] = (hardened and 0xff).toByte()
        val i = hmacSha512(parent.chainCode, data)
        return Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
    }

    private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(key, "HmacSHA512")) }.doFinal(data)

    // ---- base58 (Bitcoin alphabet) ----

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun base58Encode(input: ByteArray): String {
        var leadingZeros = 0
        while (leadingZeros < input.size && input[leadingZeros].toInt() == 0) leadingZeros++
        var num = BigInteger(1, input)
        val sb = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (num > BigInteger.ZERO) {
            val (q, r) = num.divideAndRemainder(base)
            sb.append(ALPHABET[r.toInt()])
            num = q
        }
        repeat(leadingZeros) { sb.append(ALPHABET[0]) }
        return sb.reverse().toString()
    }
}
