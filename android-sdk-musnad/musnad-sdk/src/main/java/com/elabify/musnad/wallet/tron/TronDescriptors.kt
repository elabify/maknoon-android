// Tron key derivation + address + signing, ported from iOS
// TronDescriptors.swift. BIP44 path is `m/44'/195'/<account>'/0/0`
// with secp256k1; the address is the 34-char base58check form starting
// with `T`.
//
// IMPORTANT divergence from iOS, forced by the Android WalletCore
// binding (0.12.8): its Tron `SigningOutput` exposes only
// {id, signature, refBlockBytes, refBlockHash}, NOT the broadcast JSON
// the iOS build reads from `output.json`. So Android software signing
// uses the SAME server-built-unsigned + splice-signature route the iOS
// build reserves for the Ledger hardware path:
//
//   1. ask TronGrid (`/wallet/createtransaction` or
//      `/wallet/triggersmartcontract`) to build the canonical
//      transaction and return raw_data_hex + the JSON envelope,
//   2. sign SHA256(raw_data) locally with the derived secp256k1 key
//      (recoverable 65-byte R||S||V, exactly what a Ledger returns),
//   3. splice the signature into the envelope and broadcast.
//
// The resulting wire bytes are byte-identical to what the iOS
// AnySigner JSON path produces, because both end up POSTing the same
// canonical raw_data + a recoverable signature over its SHA256 hash.

package com.elabify.musnad.wallet.tron

import com.elabify.musnad.identity.IdentitySandwich
import java.security.MessageDigest
import wallet.core.jni.Curve
import wallet.core.jni.HDWallet
import wallet.core.jni.PrivateKey

class TronSigningException(message: String) : Exception(message)

object TronDescriptors {

    @Volatile private var nativeLoaded = false

    private fun ensureNative() {
        if (!nativeLoaded) {
            System.loadLibrary("TrustWalletCore")
            nativeLoaded = true
        }
    }

    /** BIP44 derivation path for the given account index. */
    fun derivationPath(account: Long): String = "m/44'/195'/$account'/0/0"

    /** Derive the T-prefixed base58check address from the holder seed. */
    fun addressFromSandwich(sandwich: IdentitySandwich, account: Long): String {
        val priv = privateKey(sandwich, account)
        return wallet.core.jni.CoinType.TRON.deriveAddress(priv)
    }

    /** Derive the secp256k1 private key for the given account index from
     *  the holder seed via WalletCore HDWallet. Mirror of iOS
     *  `wallet.getKeyByCurve(.secp256k1, path)`. */
    fun privateKey(sandwich: IdentitySandwich, account: Long): PrivateKey {
        ensureNative()
        val words = sandwich.recoveryWords().joinToString(" ")
        // Fold the identity passphrase into derivation, matching iOS (ADR-0064).
        // bip39Passphrase() is "" for a passphrase-free identity, so this is the
        // standard no-passphrase seed in that case.
        val wallet = HDWallet(words, sandwich.bip39Passphrase())
        return wallet.getKey(derivationPath(account))
    }

    /** Carries the createtransaction envelope + the 65-byte R||S||V
     *  signature from the sign step to the broadcast call. Mirror of iOS
     *  `TronUnsignedAndSignature`. */
    data class TronUnsignedAndSignature(
        val envelopeJSON: String,
        val signatureRSV: ByteArray,
        val txID: String?,
    )

    /** Sign a server-built unsigned transaction with the holder seed.
     *  Returns the envelope + recoverable signature ready for
     *  `TronRPCClient.broadcastWithSignature`. */
    fun signUnsignedFromSandwich(
        sandwich: IdentitySandwich,
        account: Long,
        unsigned: TronRPCClient.UnsignedTransaction,
    ): TronUnsignedAndSignature {
        val priv = privateKey(sandwich, account)
        val rsv = signRawData(priv, unsigned.rawData)
        return TronUnsignedAndSignature(
            envelopeJSON = unsigned.envelopeJSON,
            signatureRSV = rsv,
            txID = unsigned.txID,
        )
    }

    /** Sign Tron raw_data bytes: secp256k1 recoverable signature over
     *  SHA256(raw_data). Returns 65 bytes R(32) || S(32) || V(1). */
    fun signRawData(priv: PrivateKey, rawData: ByteArray): ByteArray {
        ensureNative()
        val digest = MessageDigest.getInstance("SHA-256").digest(rawData)
        val sig = priv.sign(digest, Curve.SECP256K1)
        if (sig == null || sig.size != 65) {
            throw TronSigningException("secp256k1 sign returned ${sig?.size ?: 0} bytes, expected 65")
        }
        return sig
    }

    /** Splice a Ledger/hardware 65-byte (r||s||v) signature alongside
     *  an unsigned envelope into the broadcast carrier. The hardware
     *  device signs SHA256(raw_data) on its secure element. */
    fun assembleHardwareSignature(
        unsigned: TronRPCClient.UnsignedTransaction,
        r: ByteArray,
        s: ByteArray,
        v: ByteArray,
    ): TronUnsignedAndSignature {
        if (r.size != 32 || s.size != 32) {
            throw TronSigningException("Ledger returned malformed signature components")
        }
        val rsv = r + s + v
        return TronUnsignedAndSignature(
            envelopeJSON = unsigned.envelopeJSON,
            signatureRSV = rsv,
            txID = unsigned.txID,
        )
    }
}
