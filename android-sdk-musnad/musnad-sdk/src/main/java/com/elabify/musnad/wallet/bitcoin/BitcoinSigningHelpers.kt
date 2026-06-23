// Shared signing-dispatch helpers used by both the regular Send flow and
// the RBF fee-bump flow. Ported 1:1 from iOS BitcoinSigningHelpers.swift.
// The two paths use identical signing protocols; only the source of the
// unsigned PSBT differs.
//
// signSoftware(...) is fully implemented here (BDK transient
// secret-descriptor wallet, in-memory, discarded after signing).
//
// signOverBLE(...) is the hardware-signing HOOK. The Ledger/Trezor BLE
// transports land in a later phase (mirroring the iOS LedgerBLE /
// TrezorBLE actors); until then this throws the same
// "BLE signing not yet implemented" error iOS surfaces, and callers route
// hardware wallets through the offline-PSBT path
// (BitcoinWalletEngine.buildUnsignedPSBT -> external sign ->
// importSignedPSBTAndBroadcast). The hook's signature is final so the UI
// layer can wire the transports in without touching the engine.

package com.elabify.musnad.wallet.bitcoin

import org.bitcoindevkit.Psbt
import org.bitcoindevkit.SignOptions

object BitcoinSigningHelpers {

    /** Software-wallet sign: build a transient secret-descriptor BDK
     *  wallet from the holder's recovery words, sign the PSBT, return the
     *  signed base64. The transient wallet is in-memory and discarded
     *  immediately after.
     *
     *  The caller unlocks the Identity Sandwich at the UI (BiometricPrompt)
     *  and passes `sandwich.recoveryWords()` + passphrase here. */
    @Throws(BitcoinWalletException::class)
    fun signSoftware(
        unsignedBase64: String,
        recoveryWords: List<String>,
        passphrase: String?,
        account: Long,
        network: BitcoinNetwork,
    ): String {
        val transient = BitcoinDescriptors.transientSignerWallet(
            mnemonicWords = recoveryWords,
            passphrase = passphrase,
            account = account,
            network = network,
        )
        val psbt = Psbt(unsignedBase64)
        // `trustWitnessUtxo = true` is required when the PSBT only carries
        // witness_utxo for its inputs (no full prev tx). BDK's
        // TxBuilder.finish() emits witness-utxo-only PSBTs for segwit
        // inputs by default; the default SignOptions has
        // trustWitnessUtxo=false and silently signs nothing on such
        // inputs, leaving no partial_sigs and breaking the downstream
        // finalize with "Missing pubkey for a pkh/wpkh". Safe here because
        // we built the PSBT ourselves and trust our own witness_utxo.
        val opts = SignOptions(
            /* trustWitnessUtxo = */ true,
            /* assumeHeight = */ null,
            /* allowAllSighashes = */ false,
            /* tryFinalize = */ true,
            /* signWithTapInternalKey = */ true,
            /* allowGrinding = */ true,
        )
        val signed = transient.sign(psbt, opts)
        // BDK returns true only when fully finalized. false can still mean
        // "partial_sigs were added" for multisig, but for our single-sig
        // wallets it always means "nothing matched": surface loudly
        // instead of letting the user hit "Missing pubkey" on broadcast.
        val inputs = psbt.input()
        val totalSigs = inputs.sumOf { it.partialSigs.size }
        if (!signed && totalSigs == 0) {
            val derivs = inputs.mapIndexed { idx, inp ->
                val keys = inp.bip32Derivation.keys.joinToString(",") { it.take(12) }
                "in[$idx] derivs=[$keys]"
            }.joinToString(" | ")
            throw BitcoinWalletException.SendFailed(
                "Software signer produced no signatures (descriptor / fingerprint mismatch?). $derivs",
            )
        }
        return psbt.serialize()
    }

    /** Hardware-BLE sign HOOK. Connect to the paired device over its
     *  vendor transport (Ledger BLE SIGN_PSBT v2 / Trezor SignTx), return
     *  the signed PSBT base64 with partial signatures merged in.
     *
     *  The BLE transports are a later phase; until they land this throws
     *  the same error iOS surfaces and callers use the offline-PSBT path.
     *  The signature mirrors iOS `signOverBLE(...)`: the UI passes the
     *  device id, account fingerprint hex, account xpub, network, optional
     *  hidden-wallet (Trezor passphrase) ref, optional custom derivation
     *  path, and the host-entered passphrase. */
    @Throws(BitcoinWalletException::class)
    fun signOverBLE(
        unsignedBase64: String,
        deviceId: String,
        deviceKind: String,
        fingerprintHex: String,
        accountXpub: String,
        network: BitcoinNetwork,
        hidden: org.json.JSONObject? = null,
        derivationPath: String? = null,
        hostEnteredPassphrase: String? = null,
    ): String {
        // coinType = network == mainnet ? 0 : 1 (kept for the transport
        // wiring in the later phase; mirrors iOS).
        @Suppress("UNUSED_VARIABLE")
        val coinType: Long = if (network == BitcoinNetwork.MAINNET) 0 else 1
        throw BitcoinWalletException.BleSigningNotYetImplemented
    }
}
