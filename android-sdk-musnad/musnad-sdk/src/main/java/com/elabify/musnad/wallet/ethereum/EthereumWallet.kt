// On-chain operations for a single Ethereum wallet (one descriptor + network).
// 1:1 port of EthereumWallet.swift, plus the full software send flow that ties
// the chain reads to EthereumDescriptors signing.
//
// Like iOS, this does NOT cache an inner wallet object: EVM chains are stateless
// from the holder's perspective. The class is just the place where chain reads +
// the send pipeline happen. Calls are blocking (run them off the main thread);
// the UI phase wraps them in coroutines, matching the iOS `await` boundaries.
//
// Secrets handling matches iOS: the seed comes from IdentitySandwich.recoveryWords()
// at send time, is handed to EthereumDescriptors.signTransaction, and is not retained.

package com.elabify.musnad.wallet.ethereum

import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.identity.IdentitySandwich

class EthereumWalletException(message: String) : Exception(message) {
    companion object {
        const val MISSING_ADDRESS = "Wallet has no derived address"
        const val RPC_URL_INVALID = "Configured RPC URL is not a valid URL"
        const val HARDWARE_NOT_IMPLEMENTED =
            "Hardware-wallet Ethereum signing is not yet shipped. Use a software wallet for now."
        // Orphaned wallet (ADR-0063): the descriptor's cached address is not
        // derivable from the CURRENT identity seed, so signing would produce a
        // different (unfunded) address and the node rejects with a cryptic
        // "insufficient funds ... have 0". Fail fast with a clear message.
        const val WRONG_IDENTITY =
            "This wallet belongs to a different identity. Restore the backup that created it to use it."
    }
}

/** Signs an unsigned EIP-1559 envelope on a hardware device (Ledger/Trezor). */
interface EthereumHardwareSigner {
    /**
     * Sign the keccak256 digest of [unsignedEnvelope] at the wallet's path.
     * Return the 65-byte recoverable signature (r||s||v) with v = recid {0,1}.
     */
    fun signEip1559(plan: EthereumTxPlan, unsignedEnvelope: ByteArray, descriptor: EthereumWalletDescriptor): ByteArray
}

class EthereumWallet(val descriptor: EthereumWalletDescriptor) {

    val address: String? get() = descriptor.address

    // ---- reads ----

    /** Native-coin balance for the wallet's address. */
    fun balance(rpcURL: String): EthereumWeiValue {
        val addr = requireAddress()
        val client = requireClient(rpcURL)
        return client.getBalance(addr)
    }

    /** ERC-20 balance for [token] via balanceOf -> eth_call. */
    fun tokenBalance(token: EthereumToken, rpcURL: String): EthereumWeiValue {
        val addr = requireAddress()
        val client = requireClient(rpcURL)
        val data = EthereumABI.balanceOfData(addr)
            ?: throw EthereumWalletException("Holder address is not a valid 20-byte hex: '$addr'")
        val hex = client.ethCall(token.contractAddress, data)
        return EthereumABI.parseUint256(hex) ?: EthereumWeiValue.ZERO
    }

    /** Probe a contract for symbol() + decimals(); null if not an ERC-20. */
    fun probeTokenMetadata(contract: String, rpcURL: String): Pair<String, Int>? {
        val client = requireClient(rpcURL)
        return try {
            val symHex = client.ethCall(contract, EthereumABI.symbolData())
            val decHex = client.ethCall(contract, EthereumABI.decimalsData())
            val s = EthereumABI.parseSymbol(symHex) ?: return null
            val d = EthereumABI.parseDecimals(decHex) ?: return null
            s to d
        } catch (_: Exception) {
            null
        }
    }

    /** Pending nonce for the next outbound send. */
    fun pendingNonce(rpcURL: String): Long {
        val addr = requireAddress()
        return requireClient(rpcURL).transactionCount(addr, block = "pending")
    }

    /** Estimate gas units; falls back to 21000 (native) / 100000 (ERC-20). */
    fun estimateGasUnits(to: String, value: EthereumWeiValue, data: ByteArray?, rpcURL: String): Long {
        val from = requireAddress()
        val client = requireClient(rpcURL)
        return try {
            client.estimateGas(from, to, value, data)
        } catch (_: Exception) {
            if (data == null) 21000 else 100000
        }
    }

    /** Broadcast a signed raw tx; returns the tx hash. */
    fun broadcast(rawTx: String, rpcURL: String): String =
        requireClient(rpcURL).sendRawTransaction(rawTx)

    /** ERC-20 transfer events for the address (used by auto-discover). */
    fun recentTokenTransfers(
        explorerAPIURL: String?,
        apiKey: String?,
        chainId: Long,
        perPage: Int = 100,
    ): List<EthereumTokenTransfer> {
        val addr = requireAddress()
        val client = EthereumExplorerClient.orNull(explorerAPIURL, apiKey, chainId) ?: return emptyList()
        return client.recentTokenTransfers(addr, perPage = perPage)
    }

    /** Recent native transactions via the configured Etherscan-family API. */
    fun recentTransactions(
        explorerAPIURL: String?,
        apiKey: String?,
        chainId: Long,
        perPage: Int = 25,
    ): List<EthereumTx> {
        val addr = requireAddress()
        val client = EthereumExplorerClient.orNull(explorerAPIURL, apiKey, chainId) ?: return emptyList()
        return client.recentTransactions(addr, perPage = perPage)
    }

    // ---- send flow (software) ----

    /**
     * Build, sign (software seed from the sandwich), and broadcast an EIP-1559
     * transaction. [account] / [derivationPath] come from the descriptor's kind.
     * For ERC-20: pass [payload] = Erc20(recipient), [to] = token contract,
     * [value] = token amount. Returns the broadcast tx hash.
     */
    fun sendSoftware(
        sandwich: IdentitySandwich,
        account: Long,
        to: String,
        value: EthereumWeiValue,
        gasLimit: Long,
        maxFeePerGas: EthereumWeiValue,
        maxPriorityFeePerGas: EthereumWeiValue,
        chainId: Long,
        nonce: Long,
        payload: EthereumTxPlan.Payload,
        rpcURL: String,
        derivationPath: String? = descriptor.derivationPath,
    ): String = broadcast(
        prepareSoftware(
            sandwich, account, to, value, gasLimit, maxFeePerGas, maxPriorityFeePerGas,
            chainId, nonce, payload, derivationPath,
        ),
        rpcURL,
    )

    /**
     * Sign-only software step: build + sign the EIP-1559 tx with the seed and
     * return the signed raw tx (0x-hex) WITHOUT broadcasting, so the UI can show
     * a "signed, ready to broadcast" confirmation (ADR-0033) and broadcast on a
     * separate user action via [broadcast].
     */
    fun prepareSoftware(
        sandwich: IdentitySandwich,
        account: Long,
        to: String,
        value: EthereumWeiValue,
        gasLimit: Long,
        maxFeePerGas: EthereumWeiValue,
        maxPriorityFeePerGas: EthereumWeiValue,
        chainId: Long,
        nonce: Long,
        payload: EthereumTxPlan.Payload,
        derivationPath: String? = descriptor.derivationPath,
    ): String {
        // Fold the identity passphrase into derivation, matching iOS (ADR-0064).
        // "" for a passphrase-free identity (the standard no-passphrase seed).
        val passphrase = sandwich.bip39Passphrase()
        // Orphan guard (ADR-0063): the signer is derived from the CURRENT seed;
        // if it doesn't match the descriptor's cached address, this wallet was
        // created under a different identity (e.g. a mismatched backup restore)
        // and cannot be signed for. Fail fast with a clear message instead of a
        // downstream "insufficient funds ... have 0" from the node.
        descriptor.cachedAddress?.takeIf { it.isNotEmpty() }?.let { cached ->
            val signer = EthereumDescriptors.address(
                words = sandwich.recoveryWords(),
                passphrase = passphrase,
                account = account,
                derivationPath = derivationPath,
            )
            if (!signer.equals(cached, ignoreCase = true)) {
                throw EthereumWalletException(EthereumWalletException.WRONG_IDENTITY)
            }
        }
        val plan = EthereumTxPlan(
            chainId = chainId,
            nonce = nonce,
            toAddress = to,
            value = value,
            gasLimit = gasLimit,
            maxFeePerGas = maxFeePerGas,
            maxPriorityFeePerGas = maxPriorityFeePerGas,
            payload = payload,
        )
        return EthereumDescriptors.signTransaction(
            words = sandwich.recoveryWords(),
            passphrase = passphrase,
            account = account,
            plan = plan,
            derivationPath = derivationPath,
        )
    }

    /**
     * Hardware send hook: build the unsigned envelope, hand it to [signer] for an
     * on-device signature, reassemble + broadcast. Mirrors the iOS Ledger/Trezor
     * route. The signer implementation is supplied by the hardware layer.
     */
    fun sendHardware(
        signer: EthereumHardwareSigner,
        to: String,
        value: EthereumWeiValue,
        gasLimit: Long,
        maxFeePerGas: EthereumWeiValue,
        maxPriorityFeePerGas: EthereumWeiValue,
        chainId: Long,
        nonce: Long,
        payload: EthereumTxPlan.Payload,
        rpcURL: String,
    ): String = broadcast(
        prepareHardware(signer, to, value, gasLimit, maxFeePerGas, maxPriorityFeePerGas, chainId, nonce, payload),
        rpcURL,
    )

    /**
     * Sign-only hardware step: build the unsigned envelope, get the on-device
     * signature, reassemble, and return the signed raw tx (0x-hex) WITHOUT
     * broadcasting. The UI confirms then broadcasts via [broadcast] (ADR-0033).
     */
    fun prepareHardware(
        signer: EthereumHardwareSigner,
        to: String,
        value: EthereumWeiValue,
        gasLimit: Long,
        maxFeePerGas: EthereumWeiValue,
        maxPriorityFeePerGas: EthereumWeiValue,
        chainId: Long,
        nonce: Long,
        payload: EthereumTxPlan.Payload,
    ): String {
        val plan = EthereumTxPlan(
            chainId, nonce, to, value, gasLimit, maxFeePerGas, maxPriorityFeePerGas, payload,
        )
        val unsigned = EthereumTxEncoder.unsignedEnvelope(plan)
        val sig = signer.signEip1559(plan, unsigned, descriptor)
        require(sig.size == 65) { "hardware signature must be 65 bytes (r||s||v)" }
        val r = sig.copyOfRange(0, 32)
        val s = sig.copyOfRange(32, 64)
        val v = sig[64].toInt() and 0xff
        val signed = EthereumTxEncoder.signedEnvelope(plan, v, r, s)
        return "0x" + signed.toHex()
    }

    // ---- helpers ----

    private fun requireAddress(): String =
        descriptor.address?.takeIf { it.isNotEmpty() }
            ?: throw EthereumWalletException(EthereumWalletException.MISSING_ADDRESS)

    private fun requireClient(rpcURL: String): EthereumRPCClient =
        EthereumRPCClient.orNull(rpcURL) ?: throw EthereumWalletException(EthereumWalletException.RPC_URL_INVALID)

    companion object {
        /**
         * Activity probe for wallet auto-discovery: true if the address has a
         * non-zero balance OR any tx history. Mirrors EthereumWallet.probeActivity.
         */
        fun probeActivity(
            address: String,
            rpcURL: String,
            explorerAPIURL: String?,
            apiKey: String?,
            chainId: Long,
        ): Pair<Boolean, Int> {
            val rpc = EthereumRPCClient.orNull(rpcURL)
                ?: throw EthereumWalletException(EthereumWalletException.RPC_URL_INVALID)
            val bal = runCatching { rpc.getBalance(address) }.getOrDefault(EthereumWeiValue.ZERO)
            val hasBal = bal > EthereumWeiValue.ZERO
            var txCount = 0
            EthereumExplorerClient.orNull(explorerAPIURL, apiKey, chainId)?.let { exp ->
                val txs = runCatching { exp.recentTransactions(address, page = 1, perPage = 1) }.getOrDefault(emptyList())
                txCount = if (txs.isEmpty()) 0 else 1
            }
            return hasBal to txCount
        }
    }
}
