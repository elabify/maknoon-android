// The master-identity derivation, composed from the two foundation pieces:
//   - elabify-core (pure Kotlin): BIP-39 entropy <-> 24 words and the
//     PBKDF2-HMAC-SHA512 master-seed derivation (iOS-faithful).
//   - pq-crypto-core (native AAR via UniFFI): ML-DSA-65 keygen/sign/verify.
//
// This reproduces the iOS IdentitySandwich master derivation on Android:
//   entropy -> 24 words -> mldsa_seed (PBKDF2[0..32]) -> ML-DSA-65 pubkey.
// The holder DID fingerprint (rpo256Tagged(0x03, pubkey)[..20]) is built
// on top of this in the identity layer (F2).

package com.elabify.musnad.crypto

import com.elabify.core.Bip39
import uniffi.pq_crypto_core.mldsa65PublicKey
import uniffi.pq_crypto_core.mldsa65Sign
import uniffi.pq_crypto_core.mldsa65VerifySignature

/** The ML-DSA-65 master key material derived from a 24-word mnemonic plus
 *  passphrase. The 32-byte seed never leaves memory longer than needed. */
object MasterKey {

    /** 24-word mnemonic + passphrase -> 1952-byte ML-DSA-65 public key,
     *  identical to the iOS holder's master public key for the same inputs. */
    fun publicKey(words: List<String>, passphrase: String): ByteArray {
        val seed = Bip39.masterSeed(words, passphrase)
        return try {
            mldsa65PublicKey(seed)
        } finally {
            seed.fill(0)
        }
    }

    /** Sign with the master key derived from the mnemonic + passphrase. */
    fun sign(words: List<String>, passphrase: String, message: ByteArray): ByteArray {
        val seed = Bip39.masterSeed(words, passphrase)
        return try {
            mldsa65Sign(seed, message)
        } finally {
            seed.fill(0)
        }
    }

    /** Verify an ML-DSA-65 signature against a raw public key + message. */
    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean =
        mldsa65VerifySignature(publicKey, signature, message)
}
