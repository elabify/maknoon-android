// Facade over a single BDK `Wallet`. Holds the SQLite-backed Persister,
// builds an ElectrumClient per call bound to the wallet's network, and
// exposes the operations the app needs: balance, transactions,
// addresses, sync, send (unsigned-PSBT build + import-signed-broadcast),
// RBF fee bump, and coin control.
//
// Ported 1:1 from iOS BitcoinWallet.swift. The iOS type is a Swift
// `actor`; on Android we expose a plain class and let the caller marshal
// onto a background dispatcher (BDK calls are blocking native FFI).
// Method names + behaviour match the iOS actor exactly.
//
// Software-wallet signing lives in BitcoinSigningHelpers; this engine
// only builds + broadcasts. The hardware-signing hook is the same
// import-signed-PSBT path (see importSignedPSBTAndBroadcast).

package com.elabify.musnad.wallet.bitcoin

import org.bitcoindevkit.Address
import org.bitcoindevkit.AddressInfo
import org.bitcoindevkit.Amount
import org.bitcoindevkit.BumpFeeTxBuilder
import org.bitcoindevkit.Balance
import org.bitcoindevkit.CanonicalTx
import org.bitcoindevkit.ChainPosition
import org.bitcoindevkit.ElectrumClient
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.LocalOutput
import org.bitcoindevkit.OutPoint
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.Txid
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import java.io.File

/** Errors surfaced from the Bitcoin engine, mirroring iOS
 *  `BitcoinWallet.WalletError`. */
sealed class BitcoinWalletException(message: String) : Exception(message) {
    object SandwichRequired : BitcoinWalletException("Maknoon is locked")
    class DescriptorFailed(m: String) : BitcoinWalletException("Descriptor: $m")
    class SyncFailed(m: String) : BitcoinWalletException("Sync failed: $m")
    class SendFailed(m: String) : BitcoinWalletException("Send failed: $m")
    object HardwareSigningNotImplemented : BitcoinWalletException(
        "This wallet is hardware-backed. Use the offline-PSBT path: generate the unsigned " +
            "PSBT, sign it on the device, then paste the signed PSBT back into Maknoon to broadcast.",
    )
    object BleSigningNotYetImplemented : BitcoinWalletException(
        "Bluetooth signing on Ledger / Trezor is not wired up yet. Use the offline PSBT QR path " +
            "to export the unsigned PSBT, sign it externally, then paste the signed PSBT back to broadcast.",
    )
}

class BitcoinWalletEngine private constructor(
    val descriptor: BitcoinWalletDescriptor,
    private val inner: Wallet,
    private val persister: Persister,
) {

    /** Returned from [open] so the caller can surface a local-cache
     *  rebuild event and persist a freshly-populated public-key cache.
     *  Mirrors iOS `BitcoinWallet.OpenResult`. */
    data class OpenResult(
        val wallet: BitcoinWalletEngine,
        val rebuilt: Boolean,
        val rebuildReason: String?,
        /** Non-null when this open populated the public-key cache (legacy
         *  migration); the caller should persist it back into the store. */
        val updatedDescriptor: BitcoinWalletDescriptor?,
    )

    companion object {

        /** Convenience that returns just the wallet. */
        @Throws(BitcoinWalletException::class)
        fun open(
            descriptor: BitcoinWalletDescriptor,
            filesDirPath: String,
            recoveryWords: List<String>? = null,
            passphrase: String? = null,
        ): BitcoinWalletEngine =
            openWithResult(descriptor, filesDirPath, recoveryWords, passphrase).wallet

        /** Build (or load if the persisted SQLite already exists) a wallet
         *  for the given descriptor. For software wallets whose public-key
         *  cache is empty (legacy / freshly-created), the caller must
         *  supply `recoveryWords` (+ optional `passphrase`) unlocked from
         *  the Identity Sandwich; we derive once, cache, and return the
         *  updated descriptor. */
        @Throws(BitcoinWalletException::class)
        fun openWithResult(
            descriptor: BitcoinWalletDescriptor,
            filesDirPath: String,
            recoveryWords: List<String>? = null,
            passphrase: String? = null,
        ): OpenResult {
            val descriptorPair: BitcoinDescriptorPair
            var maybeUpdatedDescriptor: BitcoinWalletDescriptor? = null

            when (val kind = descriptor.kind) {
                is BitcoinWalletKind.Software -> {
                    val fp = descriptor.cachedAccountFingerprint
                    val xpub = descriptor.cachedAccountXpub
                    if (!fp.isNullOrEmpty() && !xpub.isNullOrEmpty()) {
                        // Steady-state path: watch-only from cache, no seed.
                        descriptorPair = BitcoinDescriptors.watchOnlyFromCachedKey(
                            accountFingerprint = fp,
                            accountXpub = xpub,
                            network = descriptor.network,
                        )
                    } else {
                        // Legacy/fresh wallet: derive once from the seed.
                        if (recoveryWords == null) throw BitcoinWalletException.SandwichRequired
                        val derived = BitcoinDescriptors.deriveFromSeed(
                            mnemonicWords = recoveryWords,
                            passphrase = passphrase,
                            account = kind.account,
                            network = descriptor.network,
                        )
                        descriptorPair = derived.pair
                        maybeUpdatedDescriptor = descriptor.copy(
                            cachedAccountFingerprint = derived.accountFingerprint,
                            cachedAccountXpub = derived.accountXpub,
                        )
                    }
                }
                is BitcoinWalletKind.Hardware -> {
                    val scriptType = Bip32Path.bitcoinScriptType(descriptor.derivationPath ?: "")
                        ?: Bip32Path.BitcoinScriptType.NATIVE_SEGWIT
                    // Bake the wallet's REAL account into the watch-only key
                    // origin so the bip32 derivation BDK writes into each PSBT
                    // input matches how the device derived the funded address.
                    // BDK's newBipXXPublic templates would hardcode the origin
                    // account to 0', which the device then rejects with "Input
                    // does not match scriptPubKey" for any account > 0. Default
                    // to account 0 for legacy rows persisted before the account
                    // index was carried (those were necessarily account-0
                    // wallets, which the old 0'-origin descriptor signed fine).
                    val account = kind.account ?: 0L
                    descriptorPair = BitcoinDescriptors.watchOnlyForHardware(
                        xpub = kind.accountXpub,
                        fingerprint = kind.accountFingerprint,
                        account = account,
                        coinType = descriptor.network.coinType,
                        network = descriptor.network,
                        scriptType = scriptType,
                    )
                }
            }

            val dbPath = BitcoinWalletPaths.databaseFilePath(filesDirPath, descriptor.id)
            val fileAlreadyExists = File(dbPath).exists()
            var persister = Persister.newSqlite(dbPath)

            var bdkWallet: Wallet
            var rebuilt = false
            var rebuildReason: String? = null

            if (fileAlreadyExists) {
                try {
                    // Note: BDK-android `Wallet.load` takes (external,
                    // change, persister) and infers the network from the
                    // persisted change set (no network arg, unlike iOS).
                    bdkWallet = Wallet.load(
                        descriptorPair.external,
                        descriptorPair.internal,
                        persister,
                    )
                } catch (error: Throwable) {
                    // Stale or partial DB: wipe + re-init so the wallet is
                    // usable, signal rebuilt=true so the caller forces a
                    // full scan + shows a "local cache rebuilt" banner.
                    rebuilt = true
                    rebuildReason = error.message ?: error.toString()
                    File(dbPath).delete()
                    persister = Persister.newSqlite(dbPath)
                    bdkWallet = Wallet(
                        descriptorPair.external,
                        descriptorPair.internal,
                        descriptor.network.bdk,
                        persister,
                    )
                }
            } else {
                bdkWallet = Wallet(
                    descriptorPair.external,
                    descriptorPair.internal,
                    descriptor.network.bdk,
                    persister,
                )
            }

            // Make sure the descriptor records BDK just wrote hit disk
            // before we hand the wallet back.
            bdkWallet.persist(persister)

            val engine = BitcoinWalletEngine(
                descriptor = maybeUpdatedDescriptor ?: descriptor,
                inner = bdkWallet,
                persister = persister,
            )
            return OpenResult(engine, rebuilt, rebuildReason, maybeUpdatedDescriptor)
        }
    }

    // MARK: -- read-only accessors

    fun balance(): Balance = inner.balance()

    /** Transactions, newest first. */
    fun transactions(): List<CanonicalTx> =
        inner.transactions().sortedByDescending { timestampOf(it) }

    fun nextReceiveAddress(): AddressInfo {
        val info = inner.revealNextAddress(KeychainKind.EXTERNAL)
        // Reveal advances the keychain's next-index; persist so the user
        // does not see the same address re-revealed after a reload.
        inner.persist(persister)
        return info
    }

    /** Next receive address with no observed history, per the latest
     *  sync. Does NOT advance the keychain, so it is safe to call on
     *  every wallet refresh (e.g. to keep the address-book mirror fresh). */
    fun nextUnusedReceiveAddress(): AddressInfo =
        inner.nextUnusedAddress(KeychainKind.EXTERNAL)

    /** Walk the first `count` revealed addresses on a keychain. */
    fun revealedAddresses(keychain: KeychainKind, upTo: Long): List<AddressInfo> =
        (0 until upTo).map { idx -> inner.peekAddress(keychain, idx.toUInt()) }

    fun listUnspent(): List<LocalOutput> = inner.listUnspent()

    /** All wallet outputs, including spent ones. Used by the Addresses
     *  view to compute total-received per derivation index. */
    fun listOutput(): List<LocalOutput> = inner.listOutput()

    /** Net wallet delta for a transaction: received - sent, in satoshis.
     *  Positive = money flowed INTO the wallet; negative = OUT (incl fees). */
    fun netAmount(tx: Transaction): Long {
        val values = inner.sentAndReceived(tx)
        val received = values.received.toSat().toLong()
        val sent = values.sent.toSat().toLong()
        return received - sent
    }

    /** Build an unsigned replacement PSBT for an existing unconfirmed,
     *  RBF-eligible transaction at a higher fee rate. Wraps BDK's
     *  `BumpFeeTxBuilder(txid, feeRate).finish(wallet)`. Returns the
     *  base64-encoded PSBT ready for the regular sign + broadcast pipeline. */
    @Throws(BitcoinWalletException::class)
    fun buildBumpFeePSBT(originalTxidHex: String, newFeeRateSatsPerVb: Long): String {
        val txid = Txid.fromString(originalTxidHex)
        val feeRate = FeeRate.fromSatPerVb(newFeeRateSatsPerVb.toULong())
        val builder = BumpFeeTxBuilder(txid, feeRate)
        val psbt = builder.finish(inner)
        inner.persist(persister)
        return psbt.serialize()
    }

    // MARK: -- sync

    /** Run a full keychain scan against the given Electrum endpoint.
     *  Walks receive (chain 0) + change (chain 1) derivations until 20
     *  consecutive empty addresses are observed (BIP44 discovery rule).
     *  Use on first sync or after importing a new descriptor; subsequent
     *  refreshes call [sync] (same scan, cached keychain state). */
    @Throws(BitcoinWalletException::class)
    fun fullScan(electrumURL: String) {
        try {
            val client = ElectrumClient(electrumURL, null)
            val request = inner.startFullScan().build()
            val update = client.fullScan(
                request,
                /* stopGap = */ 20uL,
                /* batchSize = */ 10uL,
                /* fetchPrevTxouts = */ true,
            )
            inner.applyUpdate(update)
            // applyUpdate only STAGES; write to SQLite so a tab-switch or
            // app relaunch finds the same data on reopen.
            inner.persist(persister)
        } catch (e: Throwable) {
            throw BitcoinWalletException.SyncFailed(e.message ?: e.toString())
        }
    }

    /** Re-scan the keychain. Implemented as the same full-keychain scan
     *  as [fullScan], NOT a revealed-spk-only sync, so UTXOs at unvisited
     *  indices (e.g. after restore) are not missed. BDK + Electrum cache
     *  known-empty script hashes, so the cost is roughly the same. */
    @Throws(BitcoinWalletException::class)
    fun sync(electrumURL: String) = fullScan(electrumURL)

    /** Persist any currently-staged changes. Cheap no-op when nothing has
     *  been staged. Called after operations that mutate wallet state
     *  outside of sync (e.g. revealing a new receive address). */
    fun persistStaged() {
        inner.persist(persister)
    }

    // MARK: -- offline PSBT (universal hardware-wallet path)

    /** Ask BDK what the actual max-spendable sat amount is right now.
     *  Builds a draft drainTo + drainWallet PSBT at the current fee rate
     *  (honouring an optional coin-control UTXO pin) and returns the sat
     *  value of the resulting single recipient output. Mirrors real
     *  send-time selection so the user can paste it into the amount field
     *  without tripping `Insufficient funds`. */
    @Throws(BitcoinWalletException::class)
    fun previewMaxDrainSat(
        toAddressString: String,
        feeRateSatsPerVb: Long,
        selectedUtxoOutpoints: List<OutPoint>?,
    ): Long {
        val recipient = Address(toAddressString, descriptor.network.bdk)
        val recipientScript = recipient.scriptPubkey()
        val feeRate = FeeRate.fromSatPerVb(feeRateSatsPerVb.toULong())
        var builder: TxBuilder = TxBuilder()
            .drainTo(recipientScript)
            .drainWallet()
            .feeRate(feeRate)
        if (!selectedUtxoOutpoints.isNullOrEmpty()) {
            builder = builder.addUtxos(selectedUtxoOutpoints).manuallySelectedOnly()
        }
        val psbt = builder.finish(inner)
        val tx = psbt.extractTx()
        val outs = tx.output()
        val drainOut = outs.firstOrNull() ?: return 0
        return drainOut.value.toSat().toLong()
    }

    /** Build the unsigned PSBT for a planned spend. The user takes the
     *  returned base64 string to whichever signing surface they trust;
     *  this engine does not sign anything here. */
    @Throws(BitcoinWalletException::class)
    fun buildUnsignedPSBT(
        toAddressString: String,
        amountSat: Long,
        feeRateSatsPerVb: Long,
        enableRbf: Boolean,
        selectedUtxoOutpoints: List<OutPoint>?,
    ): String {
        val recipient = Address(toAddressString, descriptor.network.bdk)
        val recipientScript = recipient.scriptPubkey()
        val amount = Amount.fromSat(amountSat.toULong())
        val feeRate = FeeRate.fromSatPerVb(feeRateSatsPerVb.toULong())

        var builder: TxBuilder = TxBuilder()
            .addRecipient(recipientScript, amount)
            .feeRate(feeRate)
        if (!enableRbf) {
            builder = builder.setExactSequence(0xFFFF_FFFEu)
        }
        if (!selectedUtxoOutpoints.isNullOrEmpty()) {
            builder = builder.addUtxos(selectedUtxoOutpoints).manuallySelectedOnly()
        }
        val psbt = builder.finish(inner)
        // Persist after finish() because TxBuilder reserves + reveals
        // change addresses; without persist the same change index is
        // reused on the next build.
        inner.persist(persister)
        return psbt.serialize()
    }

    /** Import a fully-signed PSBT (base64), finalize signatures, and
     *  broadcast the extracted transaction via Electrum. Used by both the
     *  offline-signing path and the on-device BLE signing path once that
     *  ships.
     *
     *  `originalUnsignedBase64` is optional but strongly recommended for
     *  SeedSigner / Coldcard round-trips: those signers strip
     *  witness-UTXO and other input data to keep UR fragments small, so we
     *  BIP-174-merge the signed PSBT with the original before finalizing. */
    @Throws(BitcoinWalletException::class)
    fun importSignedPSBTAndBroadcast(
        signedPSBTBase64: String,
        originalUnsignedBase64: String? = null,
        electrumURL: String,
    ): String {
        var psbt = Psbt(signedPSBTBase64)

        // If every input already carries final_script_witness /
        // final_script_sig, the PSBT is fully finalized and we extract
        // directly. Calling finalize() again here would misfire with
        // "Missing pubkey for a pkh/wpkh".
        val preInputs = psbt.input()
        val allFinalized = preInputs.isNotEmpty() && preInputs.all {
            it.finalScriptWitness != null || it.finalScriptSig != null
        }
        if (allFinalized) {
            val tx = psbt.extractTx()
            return broadcastAndApply(tx, electrumURL)
        }

        // Air-gapped signer path: splice the original unsigned PSBT back
        // in so BDK has enough context to finalize.
        if (originalUnsignedBase64 != null) {
            val original = Psbt(originalUnsignedBase64)
            psbt = psbt.combine(original)
        }
        val finalized = psbt.finalize()
        if (!finalized.couldFinalize) {
            val summary = finalized.errors?.joinToString("; ") { it.toString() } ?: ""
            val inputs = psbt.input()
            val diag = inputs.mapIndexed { idx, inp ->
                val sigs = inp.partialSigs.keys.joinToString(",") { it.take(12) }
                val derivs = inp.bip32Derivation.keys.joinToString(",") { it.take(12) }
                val hasWit = inp.witnessUtxo != null
                val hasFinal = inp.finalScriptWitness != null
                "in[$idx] sigs=[$sigs] derivs=[$derivs] witUtxo=$hasWit finalWit=$hasFinal"
            }.joinToString(" | ")
            throw BitcoinWalletException.SendFailed(
                "PSBT could not be finalized. " +
                    (if (summary.isEmpty()) "No specific reason reported by BDK." else summary) +
                    " [$diag]",
            )
        }
        val tx = finalized.psbt.extractTx()
        return broadcastAndApply(tx, electrumURL)
    }

    @Throws(BitcoinWalletException::class)
    private fun broadcastAndApply(tx: Transaction, electrumURL: String): String {
        val client = ElectrumClient(electrumURL, null)
        val txid = client.transactionBroadcast(tx)
        // Apply the broadcast tx to the local view so the user sees it in
        // the recent-tx list without waiting for a sync.
        val lastSeen = (System.currentTimeMillis() / 1000).toULong()
        inner.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, lastSeen)))
        inner.persist(persister)
        return txid.toString()
    }
}

// MARK: -- helpers

/** Sort key for the newest-first tx list, mirroring iOS `timestamp(of:)`:
 *  confirmed txs sort by block confirmation time, unconfirmed sort first. */
private fun timestampOf(tx: CanonicalTx): Long =
    when (val pos = tx.chainPosition) {
        is ChainPosition.Confirmed ->
            pos.confirmationBlockTime.confirmationTime.toLong()
        is ChainPosition.Unconfirmed ->
            pos.timestamp?.toLong() ?: Long.MAX_VALUE // unconfirmed sorts first
    }
