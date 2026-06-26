// Trust Wallet Core HDWallet wrapper for Ethereum + the EIP-1559 transaction
// codec. Ports EthereumDescriptors.swift (EthereumTxPlan, EthereumTxEncoder,
// address derivation, signTransaction, personal_sign).
//
// SIGNING DIVERGENCE FROM iOS, with identical wire output:
//   iOS uses TWC AnySigner + the typed Ethereum protobuf (txMode = .enveloped,
//   maxFeePerGas / maxInclusionFeePerGas) to produce the EIP-1559 envelope.
//   The WalletCore 0.12.8 Maven AAR only ships the LEGACY flat Ethereum proto
//   (gasPrice + amount + payload, no EIP-1559 fields), so that path is
//   unavailable on Android. Instead we hand-build the EIP-1559 RLP envelope
//   (EthereumTxEncoder, ported from the iOS Ledger hardware path) and sign its
//   keccak256 digest with PrivateKey.sign(digest, SECP256K1). The 65-byte
//   recoverable signature is reassembled into 0x02 || rlp(payload || v || r || s),
//   which is byte-for-byte what eth_sendRawTransaction expects. Derivation stays
//   identical to iOS (HDWallet.getKey at m/44'/60'/<account>'/0/0).

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.crypto.toHex
import wallet.core.jni.CoinType
import wallet.core.jni.Curve
import wallet.core.jni.HDWallet
import wallet.core.jni.Hash
import wallet.core.jni.PrivateKey
import wallet.core.jni.PublicKey

class EthereumDescriptorException(message: String) : Exception(message)

/**
 * Plain-data input to the signer. Built by the wallet layer after nonce / gas /
 * fee estimation. For ERC-20 transfers `toAddress` is the token contract,
 * `payload` carries the recipient, and `value` is the token amount.
 */
data class EthereumTxPlan(
    val chainId: Long,
    val nonce: Long,
    val toAddress: String,
    val value: EthereumWeiValue,
    val gasLimit: Long,
    val maxFeePerGas: EthereumWeiValue,
    val maxPriorityFeePerGas: EthereumWeiValue,
    val payload: Payload,
) {
    sealed interface Payload {
        /** Native coin transfer: `value` wei to `toAddress`. */
        object Native : Payload

        /** ERC-20 transfer: `value` token-units to `recipient` via the contract. */
        data class Erc20(val recipient: String) : Payload
    }
}

/** Wire-format codec for EIP-1559 (type-2) transactions. 1:1 with iOS. */
object EthereumTxEncoder {

    private fun payload(plan: EthereumTxPlan, callData: ByteArray): List<EthereumRLP.Item> = listOf(
        EthereumRLP.Item.uint(plan.chainId),
        EthereumRLP.Item.uint(plan.nonce),
        EthereumRLP.Item.wei(plan.maxPriorityFeePerGas),
        EthereumRLP.Item.wei(plan.maxFeePerGas),
        EthereumRLP.Item.uint(plan.gasLimit),
        EthereumRLP.Item.address(plan.toAddress),
        EthereumRLP.Item.wei(ethValueWei(plan)),
        EthereumRLP.Item.Bytes(callData),
        EthereumRLP.Item.RLPList(emptyList()), // accessList
    )

    /** Native value goes in the tx; ERC-20 value belongs only in calldata. */
    private fun ethValueWei(plan: EthereumTxPlan): EthereumWeiValue = when (plan.payload) {
        is EthereumTxPlan.Payload.Native -> plan.value
        is EthereumTxPlan.Payload.Erc20 -> EthereumWeiValue.ZERO
    }

    /** 0x02 || rlp([chainId, nonce, ..., accessList]). */
    fun unsignedEnvelope(plan: EthereumTxPlan): ByteArray {
        val rlp = EthereumRLP.encode(EthereumRLP.Item.RLPList(payload(plan, callData(plan))))
        return byteArrayOf(0x02) + rlp
    }

    /** 0x02 || rlp([..., v, r, s]). v is 0/1 for type-2 (parity bit). */
    fun signedEnvelope(plan: EthereumTxPlan, v: Int, r: ByteArray, s: ByteArray): ByteArray {
        val items = payload(plan, callData(plan)).toMutableList()
        items.add(EthereumRLP.Item.uint(v.toLong()))
        items.add(EthereumRLP.Item.Bytes(stripLeadingZeros(r)))
        items.add(EthereumRLP.Item.Bytes(stripLeadingZeros(s)))
        val rlp = EthereumRLP.encode(EthereumRLP.Item.RLPList(items))
        return byteArrayOf(0x02) + rlp
    }

    fun callData(plan: EthereumTxPlan): ByteArray = when (val p = plan.payload) {
        is EthereumTxPlan.Payload.Native -> ByteArray(0)
        is EthereumTxPlan.Payload.Erc20 ->
            EthereumABI.transferData(p.recipient, plan.value) ?: ByteArray(0)
    }

    private fun stripLeadingZeros(d: ByteArray): ByteArray {
        var start = 0
        while (start < d.size - 1 && d[start].toInt() == 0) start++
        val out = d.copyOfRange(start, d.size)
        if (out.size == 1 && out[0].toInt() == 0) return ByteArray(0)
        return out
    }
}

object EthereumDescriptors {

    /** Standard Ethereum BIP44 path for an account: m/44'/60'/<account>'/0/0. */
    fun standardPath(account: Long): String = "m/44'/60'/$account'/0/0"

    /**
     * keccak256 of [data] via WalletCore (native ensured). Public so the
     * commerce layer can derive the pre-broadcast EIP-1559 tx hash (keccak256 of
     * the signed raw tx) without depending on WalletCore directly: wallet.core
     * is an implementation dep of this SDK module, not on the app classpath.
     */
    fun keccak256(data: ByteArray): ByteArray {
        MultiChainNative.ensure()
        return Hash.keccak256(data)
    }

    /**
     * Derive the EIP-55 Ethereum address at the given account (or custom path)
     * from the holder's recovery words + optional passphrase. Mirrors
     * EthereumDescriptors.addressFromSandwich.
     */
    fun address(
        words: List<String>,
        passphrase: String = "",
        account: Long = 0,
        derivationPath: String? = null,
    ): String {
        MultiChainNative.ensure()
        val wallet = HDWallet(words.joinToString(" "), passphrase)
        val key = deriveKey(wallet, account, derivationPath)
        return CoinType.ETHEREUM.deriveAddress(key)
    }

    /**
     * Sign an EIP-1559 transaction. Returns the 0x-prefixed signed raw tx hex
     * (what eth_sendRawTransaction expects). The private key is built inside
     * this function and not retained.
     */
    fun signTransaction(
        words: List<String>,
        passphrase: String = "",
        account: Long = 0,
        plan: EthereumTxPlan,
        derivationPath: String? = null,
    ): String {
        MultiChainNative.ensure()
        val wallet = HDWallet(words.joinToString(" "), passphrase)
        val key = deriveKey(wallet, account, derivationPath)

        val unsigned = EthereumTxEncoder.unsignedEnvelope(plan)
        val digest = Hash.keccak256(unsigned)
        val sig = key.sign(digest, Curve.SECP256K1)
            ?: throw EthereumDescriptorException("secp256k1 transaction sign failed")
        if (sig.size != 65) throw EthereumDescriptorException("unexpected signature length ${sig.size}")
        val r = sig.copyOfRange(0, 32)
        val s = sig.copyOfRange(32, 64)
        val v = sig[64].toInt() and 0xff // recid 0/1, exactly the parity bit type-2 wants
        val signed = EthereumTxEncoder.signedEnvelope(plan, v, r, s)
        return "0x" + signed.toHex()
    }

    /**
     * EIP-191 personal_sign: keccak256("Ethereum Signed Message:\n" + len
     * + message), sign with secp256k1, return 65-byte 0x-hex (r||s||v) with v in
     * {27,28}. Mirrors signPersonalMessageFromSandwich.
     */
    fun signPersonalMessage(
        words: List<String>,
        passphrase: String = "",
        account: Long = 0,
        message: ByteArray,
        derivationPath: String? = null,
    ): String {
        MultiChainNative.ensure()
        val wallet = HDWallet(words.joinToString(" "), passphrase)
        val key = deriveKey(wallet, account, derivationPath)

        val prefix = "Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8)
        val digest = Hash.keccak256(prefix + message)
        val sig = key.sign(digest, Curve.SECP256K1)
            ?: throw EthereumDescriptorException("secp256k1 personal_sign failed")
        if (sig.size != 65) throw EthereumDescriptorException("secp256k1 personal_sign failed")
        // recid (0/1) -> web3 v (27/28).
        sig[64] = (sig[64] + 27).toByte()
        return "0x" + sig.toHex()
    }

    /**
     * Recover the EIP-55 address that produced an EIP-191 `personal_sign`
     * signature over [message], or null if the signature is malformed.
     * Keyless: mirrors EthereumMessageSigning.recoverAddress on iOS.
     */
    fun recoverAddress(message: ByteArray, signature: String): String? {
        MultiChainNative.ensure()
        val trimmed = signature.trim()
        val hex = if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) trimmed.substring(2) else trimmed
        val raw = hexToBytesOrNull(hex) ?: return null
        if (raw.size != 65) return null
        // web3 encodes v as 27/28; TWC's recover wants the recovery id (0/1).
        if ((raw[64].toInt() and 0xff) >= 27) raw[64] = ((raw[64].toInt() and 0xff) - 27).toByte()
        val prefix = "Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8)
        val digest = Hash.keccak256(prefix + message)
        val pub = PublicKey.recover(raw, digest) ?: return null
        return CoinType.ETHEREUM.deriveAddressFromPublicKey(pub)
    }

    /**
     * Verify an EIP-191 `personal_sign` signature: recover the signer address
     * and compare (case-insensitively) to [address]. Keyless.
     */
    fun verifyMessage(address: String, message: ByteArray, signature: String): Boolean {
        val recovered = recoverAddress(message, signature) ?: return false
        return recovered.equals(address.trim(), ignoreCase = true)
    }

    private fun hexToBytesOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = Character.digit(hex[i], 16)
            val lo = Character.digit(hex[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    private fun deriveKey(wallet: HDWallet, account: Long, derivationPath: String?): PrivateKey {
        val path = derivationPath ?: standardPath(account)
        return wallet.getKey(path)
    }
}

/** One-shot loader for the TrustWalletCore native lib (mirrors MultiChainWallet). */
internal object MultiChainNative {
    @Volatile private var loaded = false
    fun ensure() {
        if (!loaded) {
            System.loadLibrary("TrustWalletCore")
            loaded = true
        }
    }
}
