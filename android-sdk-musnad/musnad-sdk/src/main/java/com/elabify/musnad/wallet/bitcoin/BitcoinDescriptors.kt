// Builds the BIP44/49/84 external + internal descriptors used by every
// BitcoinWallet instance. Ported 1:1 from iOS BitcoinDescriptors.swift.
//
// Three flavours, picked by `BitcoinWallet.open` based on what the
// wallet metadata has cached:
//
//   1. watchOnlyFromCachedKey(...) — wallet has cachedXpub +
//      cachedFingerprint persisted. Used on every software-wallet open
//      AFTER the first one, and on every hardware-wallet open. Returns a
//      watch-only descriptor; cannot sign. No seed access, no biometric.
//
//   2. deriveFromSeed(...) — used ONCE at wallet-creation time (and as a
//      fallback for legacy wallets whose cache is empty). Reads the BIP39
//      entropy (one biometric prompt at the UI), derives the account
//      xpub + master fingerprint, returns them so the caller can cache
//      them for the next open. Also returns a watch-only descriptor pair.
//
//   3. transientSignerWallet(...) — used ONLY at send time. Reads the
//      BIP39 entropy, builds a secret-descriptor BDK Wallet against an
//      in-memory Persister, and returns it solely to sign the PSBT the
//      watch-only main wallet built. Discarded immediately after.

package com.elabify.musnad.wallet.bitcoin

import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorPublicKey
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.DerivationPath
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Mnemonic
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Wallet

/** External + internal watch-only (or secret) descriptor pair. */
data class BitcoinDescriptorPair(
    val external: Descriptor,
    val internal: Descriptor,
)

/** Result of [BitcoinDescriptors.deriveFromSeed]: the descriptor pair
 *  plus the cacheable account-level public-key material so the caller
 *  can avoid asking for the seed again. */
data class BitcoinDescriptorSeedDerived(
    val pair: BitcoinDescriptorPair,
    val accountFingerprint: String,
    val accountXpub: String,
)

object BitcoinDescriptors {

    // MARK: -- watch-only from cached public key (no seed access)

    fun watchOnlyFromCachedKey(
        accountFingerprint: String,
        accountXpub: String,
        network: BitcoinNetwork,
        scriptType: Bip32Path.BitcoinScriptType = Bip32Path.BitcoinScriptType.NATIVE_SEGWIT,
    ): BitcoinDescriptorPair = watchOnlyFromXpub(
        xpub = accountXpub,
        fingerprint = accountFingerprint,
        network = network,
        scriptType = scriptType,
    )

    // MARK: -- one-time derive from sandwich seed

    /** Derive the account-level descriptors from the holder's BIP39
     *  recovery words + optional passphrase. The caller unlocks the
     *  Identity Sandwich at the UI and passes `sandwich.recoveryWords()`
     *  + passphrase here (mirrors iOS `recoveryMaterial`). */
    fun deriveFromSeed(
        mnemonicWords: List<String>,
        passphrase: String?,
        account: Long,
        network: BitcoinNetwork,
    ): BitcoinDescriptorSeedDerived {
        val mnemonic = Mnemonic.fromString(mnemonicWords.joinToString(" "))
        val root = DescriptorSecretKey(network.bdk, mnemonic, passphrase?.ifEmpty { null })

        // Derive the account-level secret key, take the public
        // counterpart, serialize it as the xpub string we cache.
        val path = DerivationPath("m/84'/${network.coinType}'/$account'")
        val accountSecret = root.derive(path)
        val accountPublic: DescriptorPublicKey = accountSecret.asPublic()
        val accountXpub = accountPublic.toString()
        val fingerprint = accountPublic.masterFingerprint()

        val pair = watchOnlyFromXpub(
            xpub = accountXpub,
            fingerprint = fingerprint,
            network = network,
        )
        return BitcoinDescriptorSeedDerived(
            pair = pair,
            accountFingerprint = fingerprint,
            accountXpub = accountXpub,
        )
    }

    // MARK: -- transient secret-descriptor signer (send time only)

    fun transientSignerWallet(
        mnemonicWords: List<String>,
        passphrase: String?,
        account: Long,
        network: BitcoinNetwork,
    ): Wallet {
        val mnemonic = Mnemonic.fromString(mnemonicWords.joinToString(" "))
        val root = DescriptorSecretKey(network.bdk, mnemonic, passphrase?.ifEmpty { null })

        val secret: DescriptorSecretKey = if (account == 0L) {
            root
        } else {
            root.derive(DerivationPath("m/84'/${network.coinType}'/$account'"))
        }
        val external = Descriptor.newBip84(secret, KeychainKind.EXTERNAL, network.bdk)
        val internal = Descriptor.newBip84(secret, KeychainKind.INTERNAL, network.bdk)
        return Wallet(external, internal, network.bdk, Persister.newInMemory())
    }

    // MARK: -- watch-only for a hardware wallet (explicit account origin)

    /** Build the external + internal watch-only descriptors for a hardware
     *  wallet's account xpub with the FULL key origin
     *  `[fingerprint/purpose'/coinType'/account']` baked in explicitly.
     *
     *  This is the hardware-signing path and it must NOT go through BDK's
     *  `newBipXXPublic` templates: those hardcode the account index to `0'`
     *  in the key origin (BDK `Bip84Public` etc. build `m/84'/coin'/0'`
     *  regardless of the real account). For an account > 0 wallet that wrong
     *  origin gets written into every PSBT input's bip32 derivation, so the
     *  device derives the address at account 0', it does not match the
     *  input's scriptPubKey, and the Trezor rejects the sign with "Input does
     *  not match scriptPubKey". We build the descriptor string by hand so the
     *  origin carries the wallet's real account and the device reproduces the
     *  exact scriptPubKey. Mirrors the path the iOS hardware wallet must take
     *  for non-zero accounts.
     *
     *  `coinType` is the network coin type (0 mainnet / 1 testnet, signet),
     *  matching the BIP84 account path the device derived the xpub at and the
     *  `coinType` the device signs under. */
    fun watchOnlyForHardware(
        xpub: String,
        fingerprint: String,
        account: Long,
        coinType: Long,
        network: BitcoinNetwork,
        scriptType: Bip32Path.BitcoinScriptType = Bip32Path.BitcoinScriptType.NATIVE_SEGWIT,
    ): BitcoinDescriptorPair {
        // Normalize SLIP-132 alternates (zpub/ypub/vpub/upub/...) to
        // xpub/tpub; BDK's descriptor parser rejects the alternates.
        val normalized = ExtendedKeyNormalize.toXpubLegacy(xpub)
        // Fingerprint is the 8-hex-char master fingerprint the device
        // reported; lower-cased for a canonical descriptor string.
        val fp = fingerprint.lowercase()
        val purpose = when (scriptType) {
            Bip32Path.BitcoinScriptType.LEGACY -> 44L
            Bip32Path.BitcoinScriptType.NESTED_SEGWIT -> 49L
            Bip32Path.BitcoinScriptType.NATIVE_SEGWIT -> 84L
        }
        // Key origin: [fingerprint/purpose'/coinType'/account']. The xpub is
        // the account-level (depth-3) key, so the descriptor only appends the
        // change/index wildcard suffix `/<keychain>/*`.
        val origin = "[$fp/${purpose}h/${coinType}h/${account}h]"

        fun descriptorString(keychain: KeychainKind): String {
            val chain = if (keychain == KeychainKind.EXTERNAL) 0 else 1
            val key = "$origin$normalized/$chain/*"
            return when (scriptType) {
                Bip32Path.BitcoinScriptType.LEGACY -> "pkh($key)"
                Bip32Path.BitcoinScriptType.NESTED_SEGWIT -> "sh(wpkh($key))"
                Bip32Path.BitcoinScriptType.NATIVE_SEGWIT -> "wpkh($key)"
            }
        }

        return BitcoinDescriptorPair(
            external = Descriptor(descriptorString(KeychainKind.EXTERNAL), network.bdk),
            internal = Descriptor(descriptorString(KeychainKind.INTERNAL), network.bdk),
        )
    }

    // MARK: -- watch-only from an existing xpub (hardware wallet path)

    /** Build the external + internal watch-only descriptors for the given
     *  account xpub. `scriptType` (from the wallet's path purpose) selects
     *  the BDK template: BIP84 wpkh / BIP49 sh(wpkh) / BIP44 pkh.
     *
     *  NOTE: BDK's `newBipXXPublic` templates hardcode the key-origin account
     *  index to `0'`, so this is only correct for an account-0 wallet. The
     *  hardware-signing path uses [watchOnlyForHardware] instead, which bakes
     *  the real account into the origin. This stays for the software-wallet
     *  steady-state open (where the transient secret signer matches by pubkey,
     *  not by origin) and the account-0 cache path. */
    fun watchOnlyFromXpub(
        xpub: String,
        fingerprint: String,
        network: BitcoinNetwork,
        scriptType: Bip32Path.BitcoinScriptType = Bip32Path.BitcoinScriptType.NATIVE_SEGWIT,
    ): BitcoinDescriptorPair {
        // Normalize SLIP-132 alternates (zpub/ypub/vpub/upub/...) to
        // xpub/tpub. BDK's descriptor parser rejects the alternates.
        val normalized = ExtendedKeyNormalize.toXpubLegacy(xpub)
        val pub = DescriptorPublicKey.fromString(normalized)

        fun make(keychain: KeychainKind): Descriptor = when (scriptType) {
            Bip32Path.BitcoinScriptType.LEGACY ->
                Descriptor.newBip44Public(pub, fingerprint, keychain, network.bdk)
            Bip32Path.BitcoinScriptType.NESTED_SEGWIT ->
                Descriptor.newBip49Public(pub, fingerprint, keychain, network.bdk)
            Bip32Path.BitcoinScriptType.NATIVE_SEGWIT ->
                Descriptor.newBip84Public(pub, fingerprint, keychain, network.bdk)
        }
        return BitcoinDescriptorPair(make(KeychainKind.EXTERNAL), make(KeychainKind.INTERNAL))
    }
}
