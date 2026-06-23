// Ledger (BLE) HardwareWallet for Android, ported 1:1 from
// LedgerBLE.swift. Owns one LedgerBleTransport (the Android GATT APDU
// channel) and wires the four ledger AAR clients (btc/eth/sol/tron)
// through per-chain Transport adapters. Each public method mirrors the
// iOS method's semantics, custom-path routing (the *AtPath variants
// when a derivation override is set), per-op session teardown (a no-op
// while a session is pinned), and error mapping to HardwareWalletException.
//
// Accounts/coin-types arrive as Long per the contract; the Rust clients
// take UInt, so we narrow at the boundary.

package com.elabify.musnad.hardware.ledger

import android.content.Context
import com.elabify.musnad.hardware.EcdsaSignature
import com.elabify.musnad.hardware.HardwareWallet
import com.elabify.musnad.hardware.HardwareWalletException
import com.elabify.musnad.hardware.HardwareWalletKind
import com.elabify.musnad.wallet.bitcoin.Bip32Path
import uniffi.ledger_btc_core.LedgerBitcoinClient
import uniffi.ledger_btc_core.LedgerException as BtcLedgerException
import uniffi.ledger_btc_core.WalletPolicy
import uniffi.ledger_eth_core.LedgerEthClient
import uniffi.ledger_eth_core.LedgerEthException
import uniffi.ledger_sol_core.LedgerSolException
import uniffi.ledger_sol_core.LedgerSolanaClient
import uniffi.ledger_tron_core.LedgerTronClient
import uniffi.ledger_tron_core.LedgerTronException

/**
 * Ledger Nano X over BLE.
 *
 * Construct with the application [Context] (used by the BLE transport to
 * reach the system BluetoothManager). Bind to a specific physical device
 * by setting [LedgerBleTransport.targetAddress] via [bindToDevice] before
 * the first op; otherwise the first matching Ledger on the air wins.
 */
class LedgerHardwareWallet(
    appContext: Context,
) : HardwareWallet {

    private val ble = LedgerBleTransport(appContext.applicationContext)

    // Lazily-built SDK clients. Survive across method calls so we don't
    // pay UniFFI handle-allocation per APDU; dropped by forceTeardown so
    // a reconnect rebuilds against the fresh connection.
    private var bitcoinClient: LedgerBitcoinClient? = null
    private var ethereumClient: LedgerEthClient? = null
    private var solanaClient: LedgerSolanaClient? = null
    private var tronClient: LedgerTronClient? = null

    /** Custom BIP32 path for the next seed-deriving op(s); null = the
     *  chain's standard path from `account`. When set, per-chain methods
     *  route to the SDK's *AtPath variants. */
    @Volatile
    private var pendingDerivationPath: String? = null

    override val kind: HardwareWalletKind get() = HardwareWalletKind.LEDGER

    override val currentBlePeripheralId: String? get() = ble.connectedAddress

    /** Pin reconnects to a specific physical Ledger by its MAC. */
    fun bindToDevice(address: String?) {
        ble.targetAddress = address
    }

    override fun setDerivationPathOverride(path: String?) {
        pendingDerivationPath = path
    }

    override fun beginSession() = ble.beginSession()

    override fun endSession() {
        ble.endSession()
        // The pin dropped to zero inside endSession when the count hits
        // zero; drop our SDK clients too so a reconnect rebuilds them.
        dropClients()
    }

    private fun resetSession() {
        ble.resetSession()
        dropClients()
    }

    private fun dropClients() {
        bitcoinClient?.let { runCatching { it.destroy() } }
        ethereumClient?.let { runCatching { it.destroy() } }
        solanaClient?.let { runCatching { it.destroy() } }
        tronClient?.let { runCatching { it.destroy() } }
        bitcoinClient = null
        ethereumClient = null
        solanaClient = null
        tronClient = null
    }

    private fun bitcoinSdk(): LedgerBitcoinClient =
        bitcoinClient ?: LedgerBitcoinClient(BitcoinTransportAdapter(ble)).also { bitcoinClient = it }

    private fun ethereumSdk(): LedgerEthClient =
        ethereumClient ?: LedgerEthClient(EthereumTransportAdapter(ble)).also { ethereumClient = it }

    private fun solanaSdk(): LedgerSolanaClient =
        solanaClient ?: LedgerSolanaClient(SolanaTransportAdapter(ble)).also { solanaClient = it }

    private fun tronSdk(): LedgerTronClient =
        tronClient ?: LedgerTronClient(TronTransportAdapter(ble)).also { tronClient = it }

    // ------------------------------------------------------------------
    // Identity / pairing
    // ------------------------------------------------------------------

    /** Connect just long enough to learn the device's stable BLE
     *  identifier (the MAC). Ledger does not expose its serial via APDU. */
    override suspend fun identifyDevice(): String {
        try {
            // App-INDEPENDENT identify: just bring up the BLE link, send NO
            // APDU. The Ledger exposes no serial, so its stable identity is the
            // BLE MAC, captured as a side effect of connecting. We must NOT send
            // a dashboard command here: this Ledger does not answer
            // GET_APP_AND_VERSION (B0 01) over BLE, so sending it timed out the
            // serial guard and broke discover + sign for every Ledger flow. The
            // earlier Ethereum GET_PUBLIC_KEY was wrong too (forced the Ethereum
            // app). The Ethereum app is used ONLY for the second-factor
            // promotion (see pair()).
            ble.connect()
            return ble.connectedAddress
                ?: throw HardwareWalletException.Transport("Could not connect to Ledger over BLE")
        } catch (e: HardwareWalletException) {
            throw e
        } catch (e: Throwable) {
            throw HardwareWalletException.Transport(
                "Could not identify the Ledger over BLE: ${e.message}",
            )
        } finally {
            resetSession()
        }
    }

    /** Proof-of-possession: the device's stable secp256k1 pubkey from
     *  the Ethereum app's GET_PUBLIC_KEY, compressed to 33 bytes. */
    override suspend fun pair(): ByteArray {
        try {
            val addr = ethereumSdk().getAddressForAccount(0u, false)
            return compressSecp256k1Pubkey(addr.pubkey)
        } catch (e: Throwable) {
            throw mapEthError(e, "GET_PUBLIC_KEY (pair)")
        } finally {
            resetSession()
        }
    }

    /** Sign an arbitrary message via the Ethereum app
     *  SIGN_PERSONAL_MESSAGE. Returns R(32) || S(32); the recovery byte
     *  is dropped (the server has the pubkey). */
    override suspend fun signMessage(message: ByteArray): ByteArray {
        try {
            val sig = ethereumSdk().signPersonalMessageForAccount(0u, message)
            return sig.r + sig.s
        } catch (e: Throwable) {
            throw mapEthError(e, "SIGN_PERSONAL_MESSAGE")
        } finally {
            resetSession()
        }
    }

    // ------------------------------------------------------------------
    // Bitcoin
    // ------------------------------------------------------------------

    override suspend fun getBitcoinAccountXpub(account: Long, networkCoinType: Long): String {
        try {
            // Custom path overrides the standard BIP84 account path.
            val path = pendingDerivationPath ?: "m/84'/$networkCoinType'/$account'"
            return bitcoinSdk().getExtendedPubkey(path, false)
        } catch (e: Throwable) {
            throw mapBtcError(e, "GET_EXTENDED_PUBKEY")
        } finally {
            resetSession()
        }
    }

    override suspend fun getBitcoinMasterFingerprint(networkCoinType: Long): String {
        try {
            val fp = bitcoinSdk().getMasterFingerprint()
            return fp.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Throwable) {
            throw mapBtcError(e, "GET_MASTER_FINGERPRINT")
        } finally {
            resetSession()
        }
    }

    /** Mirrors LedgerBLE.signPSBT: the bare PSBT-only entry needs
     *  descriptor context, so it throws. Callers use [signBitcoinPsbt]. */
    override suspend fun signPsbt(psbt: ByteArray, networkCoinType: Long): ByteArray {
        throw HardwareWalletException.Transport(
            "signPsbt(psbt, networkCoinType) needs descriptor context; " +
                "call signBitcoinPsbt(unsignedBase64, fingerprintHex, accountXpub, account, coinType) instead",
        )
    }

    /**
     * Sign a PSBT against a single-sig policy selected by the active
     * derivation path's purpose (BIP44 pkh / BIP49 sh(wpkh) / BIP84
     * wpkh; standard wallet = BIP84). Returns the signed PSBT base64
     * with PSBT_IN_PARTIAL_SIG entries merged in. Mirrors
     * LedgerBLE.signBitcoinPSBT.
     */
    suspend fun signBitcoinPsbt(
        unsignedBase64: String,
        fingerprintHex: String,
        accountXpub: String,
        account: Long,
        coinType: Long,
    ): String {
        try {
            val custom = pendingDerivationPath
            val originPath: String
            val template: String
            if (custom != null) {
                originPath = if (custom.startsWith("m/")) custom.substring(2) else custom
                template = when (Bip32Path.bitcoinScriptType(custom) ?: Bip32Path.BitcoinScriptType.NATIVE_SEGWIT) {
                    Bip32Path.BitcoinScriptType.LEGACY -> "pkh(@0/**)"
                    Bip32Path.BitcoinScriptType.NESTED_SEGWIT -> "sh(wpkh(@0/**))"
                    Bip32Path.BitcoinScriptType.NATIVE_SEGWIT -> "wpkh(@0/**)"
                }
            } else {
                originPath = "84'/$coinType'/$account'"
                template = "wpkh(@0/**)"
            }
            val keyOrigin = "[$fingerprintHex/$originPath]$accountXpub"
            val policy = WalletPolicy(
                name = "",
                descriptorTemplate = template,
                keys = listOf(keyOrigin),
                hmac = null,
            )
            return bitcoinSdk().signPsbt(unsignedBase64, policy)
        } catch (e: Throwable) {
            throw mapBtcError(e, "SIGN_PSBT")
        } finally {
            resetSession()
        }
    }

    // ------------------------------------------------------------------
    // Ethereum / EVM
    // ------------------------------------------------------------------

    override suspend fun getEthereumAddress(account: Long): String {
        try {
            val sdk = ethereumSdk()
            val path = pendingDerivationPath
            val addr = if (path != null) sdk.getAddressAtPath(path, false)
            else sdk.getAddressForAccount(account.toUInt(), false)
            return addr.address
        } catch (e: Throwable) {
            throw mapEthError(e, "GET_PUBLIC_KEY")
        } finally {
            resetSession()
        }
    }

    override suspend fun signEthereumTransaction(
        envelope: ByteArray,
        account: Long,
        erc20Descriptor: ByteArray?,
    ): EcdsaSignature {
        try {
            val sdk = ethereumSdk()
            // Provide the CAL token blob (best-effort) so the device
            // clear-signs ERC-20 transfers instead of 0x6A80. If the
            // device rejects it (unknown token / old app) fall through.
            if (erc20Descriptor != null) {
                runCatching { sdk.provideErc20TokenInformation(erc20Descriptor) }
            }
            val path = pendingDerivationPath
            val sig = if (path != null) sdk.signTransactionAtPath(path, envelope)
            else sdk.signTransactionForAccount(account.toUInt(), envelope)
            return EcdsaSignature(v = sig.v.toInt(), r = sig.r, s = sig.s)
        } catch (e: Throwable) {
            throw mapEthError(e, "SIGN_TRANSACTION")
        } finally {
            resetSession()
        }
    }

    // ------------------------------------------------------------------
    // Solana
    // ------------------------------------------------------------------

    override suspend fun getSolanaAddress(account: Long): String {
        try {
            val sdk = solanaSdk()
            val path = pendingDerivationPath
            val addr = if (path != null) sdk.getAddressAtPath(path, false)
            else sdk.getAddressForAccount(account.toUInt(), false)
            return addr.base58
        } catch (e: Throwable) {
            throw mapSolError(e, "GET_PUBKEY")
        } finally {
            resetSession()
        }
    }

    override suspend fun signSolanaTransaction(unsignedTx: ByteArray, account: Long): ByteArray {
        try {
            val sdk = solanaSdk()
            val path = pendingDerivationPath
            val sig = if (path != null) sdk.signTransactionAtPath(path, unsignedTx)
            else sdk.signTransactionForAccount(account.toUInt(), unsignedTx)
            return sig.bytes
        } catch (e: Throwable) {
            throw mapSolError(e, "SIGN_MESSAGE")
        } finally {
            resetSession()
        }
    }

    // ------------------------------------------------------------------
    // Tron
    // ------------------------------------------------------------------

    override suspend fun getTronAddress(account: Long): String {
        try {
            val sdk = tronSdk()
            val path = pendingDerivationPath
            val addr = if (path != null) sdk.getAddressAtPath(path, false)
            else sdk.getAddressForAccount(account.toUInt(), false)
            return addr.base58check
        } catch (e: Throwable) {
            throw mapTronError(e, "GET_PUBLIC_KEY")
        } finally {
            resetSession()
        }
    }

    override suspend fun getTronPubkey(account: Long): ByteArray {
        try {
            val addr = tronSdk().getAddressForAccount(account.toUInt(), false)
            return addr.pubkey
        } catch (e: Throwable) {
            throw mapTronError(e, "GET_PUBLIC_KEY")
        } finally {
            resetSession()
        }
    }

    override suspend fun signTronTransaction(rawTxProto: ByteArray, account: Long): EcdsaSignature {
        try {
            val sdk = tronSdk()
            val path = pendingDerivationPath
            val sig = if (path != null) sdk.signTransactionAtPath(path, rawTxProto)
            else sdk.signTransactionForAccount(account.toUInt(), rawTxProto)
            return EcdsaSignature(v = sig.v.toInt(), r = sig.r, s = sig.s)
        } catch (e: Throwable) {
            throw mapTronError(e, "SIGN")
        } finally {
            resetSession()
        }
    }

    // ------------------------------------------------------------------
    // Error mapping (mirrors LedgerBLE's mapXSDKError + diagnose helpers)
    // ------------------------------------------------------------------

    private fun mapBtcError(e: Throwable, command: String): HardwareWalletException = when (e) {
        is HardwareWalletException -> e
        is BtcLedgerException.UserCanceled -> HardwareWalletException.UserCancelled()
        is BtcLedgerException.DeviceRejected ->
            HardwareWalletException.Transport(diagnoseBitcoinSw(e.statusWord.toInt() and 0xFFFF, command))
        is BtcLedgerException -> HardwareWalletException.Transport("$command failed: ${e.message}")
        else -> HardwareWalletException.Transport("$command failed: ${e.message ?: e.toString()}")
    }

    private fun mapEthError(e: Throwable, command: String): HardwareWalletException = when (e) {
        is HardwareWalletException -> e
        is LedgerEthException.UserCanceled -> HardwareWalletException.UserCancelled()
        is LedgerEthException.DeviceRejected ->
            HardwareWalletException.Transport(diagnoseEthSw(e.statusWord.toInt() and 0xFFFF, command))
        is LedgerEthException -> HardwareWalletException.Transport("$command failed: ${e.message}")
        else -> HardwareWalletException.Transport("$command failed: ${e.message ?: e.toString()}")
    }

    private fun mapSolError(e: Throwable, command: String): HardwareWalletException = when (e) {
        is HardwareWalletException -> e
        is LedgerSolException.UserCanceled -> HardwareWalletException.UserCancelled()
        is LedgerSolException.DeviceRejected ->
            HardwareWalletException.Transport(diagnoseSolanaSw(e.statusWord.toInt() and 0xFFFF, command))
        is LedgerSolException -> HardwareWalletException.Transport("$command failed: ${e.message}")
        else -> HardwareWalletException.Transport("$command failed: ${e.message ?: e.toString()}")
    }

    private fun mapTronError(e: Throwable, command: String): HardwareWalletException = when (e) {
        is HardwareWalletException -> e
        is LedgerTronException.UserCanceled -> HardwareWalletException.UserCancelled()
        is LedgerTronException.DeviceRejected ->
            HardwareWalletException.Transport(diagnoseTronSw(e.statusWord.toInt() and 0xFFFF, command))
        is LedgerTronException -> HardwareWalletException.Transport("$command failed: ${e.message}")
        else -> HardwareWalletException.Transport("$command failed: ${e.message ?: e.toString()}")
    }
}

/** Compress an uncompressed secp256k1 pubkey (0x04 || X || Y) to the
 *  33-byte form (0x02/0x03 || X). Mirrors LedgerBLE.compressSecp256k1Pubkey. */
private fun compressSecp256k1Pubkey(raw: ByteArray): ByteArray {
    if (raw.size != 65 || raw[0].toInt() != 0x04) return raw
    val x = raw.copyOfRange(1, 33)
    val yIsEven = (raw[64].toInt() and 0x01) == 0
    return byteArrayOf(if (yIsEven) 0x02 else 0x03) + x
}

private fun diagnoseBitcoinSw(sw: Int, command: String): String = when (sw) {
    0x6511, 0x6D02 -> "$command failed: Ledger is on the dashboard. Unlock the device and open the Bitcoin app, then retry."
    0x6E00 -> "$command failed: the Bitcoin app didn't recognise the request. Install/open the current v2 Bitcoin app from Ledger Live. Status 0x6E00."
    0x6D00 -> "$command failed: the open Bitcoin app does not support this instruction. Update via Ledger Live. Status 0x6D00."
    0x6985 -> "$command failed: you declined the on-device confirmation. Approve on the Ledger and retry."
    0x6700, 0x6A80, 0x6A86, 0x6A87 -> "$command failed: Ledger rejected the APDU as malformed (status 0x${sw.toString(16)})."
    else -> "$command failed: Ledger returned status 0x${sw.toString(16)}. Open the Bitcoin app and retry."
}

private fun diagnoseEthSw(sw: Int, command: String): String = when (sw) {
    0x6511, 0x6D02 -> "$command failed: Ledger is on the dashboard. Unlock and open the Ethereum app, then retry."
    0x6E00 -> "$command failed: a different app is open. Switch to the Ethereum app on the device and retry."
    0x6985 -> "$command failed: you declined the on-device confirmation. Approve on the Ledger and retry."
    0x6A80 ->
        if (command.startsWith("SIGN_TRANSACTION"))
            "$command failed (0x6A80). For ERC-20 transfers enable Blind Signing: Ethereum app, Settings, Blind signing, Enabled, then retry."
        else "$command failed: status 0x6A80 (incorrect data). Confirm the Ethereum app is open + up to date, then retry."
    else -> "$command failed: Ledger returned status 0x${sw.toString(16)}. Wake + unlock the device, open the Ethereum app, then retry."
}

private fun diagnoseSolanaSw(sw: Int, command: String): String = when (sw) {
    0x6511, 0x6D02 -> "$command failed: Ledger is on the dashboard. Unlock and open the Solana app, then retry."
    0x6E00 -> "$command failed: the Solana app didn't recognise the request. Install/open the current Solana app from Ledger Live. Status 0x6E00."
    0x6D00 -> "$command failed: the open Solana app does not support this instruction. Update via Ledger Live. Status 0x6D00."
    0x6985 -> "$command failed: you declined the on-device confirmation. Approve on the Ledger and retry."
    0x6700, 0x6A80, 0x6A86, 0x6A87 -> "$command failed: Ledger rejected the APDU as malformed (status 0x${sw.toString(16)})."
    else -> "$command failed: Ledger returned status 0x${sw.toString(16)}. Open the Solana app and retry."
}

private fun diagnoseTronSw(sw: Int, command: String): String = when (sw) {
    0x6511, 0x6D02 -> "$command failed: Ledger is on the dashboard. Unlock and open the TRON app, then retry."
    0x6E00 -> "$command failed: the Tron app didn't recognise the request. Install/open the current Tron app from Ledger Live. Status 0x6E00."
    0x6D00 -> "$command failed: the open Tron app does not support this instruction. Update via Ledger Live. Status 0x6D00."
    0x6985 -> "$command failed: you declined the on-device confirmation. Approve on the Ledger and retry."
    0x6A8D -> "$command failed: the Tron app blocked this TRC-20 transfer (0x6A8D). On the Ledger, open the Tron app, Settings, and enable signing of custom/unrecognised contracts, then retry."
    0x6700, 0x6A80, 0x6A86, 0x6A87 -> "$command failed: Ledger rejected the APDU as malformed (status 0x${sw.toString(16)})."
    else -> "$command failed: Ledger returned status 0x${sw.toString(16)}. Open the Tron app and retry."
}
