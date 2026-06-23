// EIP-1193 "eth" namespace handler (window.ethereum), pinned to Sepolia.
// Android port of the iOS Web3BridgeHandler.swift, adapted to the Android
// mini-app bridge (string-in / string-out JSON over MiniAppNamespaceHandler).
//
// Reads (chainId, balances, calls, gas) proxy straight to the Sepolia
// EthereumRPCClient with no approval. Privileged calls each route through the
// shared ApprovalGate for explicit consent before any key is touched:
//   * eth_requestAccounts -> connect approval, returns the active address
//   * personal_sign / eth_sign -> EIP-191 message signing
//   * eth_sendTransaction -> native Sepolia send (build plan, sign, broadcast)
//
// Scope, by design for the demo: Sepolia only; software wallets only
// (hardware EVM signing is a separate, unshipped path); native-value sends
// only (arbitrary contract calldata is refused rather than blind-signed).
// Anything else returns EIP-1193 4200.
//
// Device-auth (BiometricPrompt) is the sheet's responsibility, the same way
// the iOS coordinator runs Face ID inside its approve() before resuming. This
// handler only blocks on the gate and trusts that an approved result means the
// user authenticated. Nothing here ever hands key material back to JS.

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumGasEstimator
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumRPCClient
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provides the engine pieces the handler needs, mirroring the iOS HolderStore
 * surface (sandwich, ethereumSettings, ethereumWalletStore). The app wires a
 * concrete instance through the MiniAppHandlerFactory; keeping it an interface
 * keeps this handler free of an Android Context dependency.
 */
interface MiniAppWeb3Environment {
    /** The user's active Ethereum wallet, or null when none exists. */
    val activeWallet: EthereumWalletDescriptor?

    /** Per-network RPC / explorer overrides plus defaults. */
    val settings: EthereumSettings

    /**
     * The unlocked identity seed source. Null when no identity exists yet or
     * the wallet is locked. Mirrors iOS `store.sandwich`.
     */
    fun sandwich(): IdentitySandwich?

    companion object {
        /**
         * Default environment over a live [EthereumWalletStore] + [EthereumSettings]
         * and a sandwich-loading lambda. The app builds this in the factory where
         * the stores + Context are already in scope.
         */
        fun of(
            walletStore: EthereumWalletStore,
            settings: EthereumSettings,
            sandwichLoader: () -> IdentitySandwich?,
        ): MiniAppWeb3Environment = object : MiniAppWeb3Environment {
            override val activeWallet: EthereumWalletDescriptor? get() = walletStore.activeWallet
            override val settings: EthereumSettings = settings
            override fun sandwich(): IdentitySandwich? = sandwichLoader()
        }
    }
}

/**
 * The "eth" namespace handler. Construct one per WebView in the
 * MiniAppHandlerFactory, gated by the "evm" grant.
 */
class Web3BridgeHandler(
    private val env: MiniAppWeb3Environment,
    private val gate: ApprovalGate,
    private val appTitle: String,
) : MiniAppNamespaceHandler {

    override val namespace = "eth"
    override val requiredPermission: String? = "evm"

    // Demo is pinned to Sepolia.
    private val network: EthereumNetwork = EthereumNetwork.SEPOLIA

    /** One connect approval per WebView session, like the iOS `connected` flag. */
    @Volatile private var connected = false

    private val rpcURL: String get() = env.settings.rpcURL(network)
    private val chainIdHex: String get() = "0x" + network.chainId.toString(16)

    override suspend fun handle(method: String, argsJson: String): String {
        val params = parseParamsArray(argsJson)
        return when (method) {
            // --- chain identity (no approval) ---
            "eth_chainId" -> JSONObject.quote(chainIdHex)
            "net_version" -> JSONObject.quote(network.chainId.toString())

            // --- accounts ---
            "eth_accounts" -> if (connected) {
                JSONArray().put(activeAddress()).toString()
            } else {
                JSONArray().toString()
            }
            "eth_requestAccounts" -> requestAccounts()

            // --- reads (no approval) ---
            "eth_blockNumber" -> JSONObject.quote("0x" + rpc().blockNumber().toString(16))
            "eth_getBalance" -> {
                val addr = (params.optStringOrNull(0)) ?: activeAddress()
                JSONObject.quote("0x" + io { rpc().getBalance(addr) }.hex)
            }
            "eth_gasPrice" -> JSONObject.quote("0x" + io { rpc().gasPrice() }.hex)
            "eth_getTransactionCount" -> {
                val addr = (params.optStringOrNull(0)) ?: activeAddress()
                JSONObject.quote("0x" + io { rpc().transactionCount(addr, "pending") }.toString(16))
            }
            "eth_call" -> {
                val tx = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("eth_call requires { to, data }")
                val to = tx.optStringOrNull("to")
                    ?: throw MiniAppBridgeError.invalidParams("eth_call requires { to, data }")
                val data = dataFromHex(tx.optStringOrNull("data")) ?: ByteArray(0)
                JSONObject.quote(io { rpc().ethCall(to, data) })
            }
            "eth_estimateGas" -> {
                val tx = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("eth_estimateGas requires { to }")
                val to = tx.optStringOrNull("to")
                    ?: throw MiniAppBridgeError.invalidParams("eth_estimateGas requires { to }")
                val value = weiOrZero(tx.optStringOrNull("value"))
                val data = dataFromHex(tx.optStringOrNull("data"))
                val from = tx.optStringOrNull("from") ?: activeAddress()
                val units = io { rpc().estimateGas(from, to, value, data) }
                JSONObject.quote("0x" + units.toString(16))
            }

            // --- signing (approval + device auth in the sheet) ---
            "personal_sign" -> personalSign(params.optStringOrNull(0)) // [message, address]
            "eth_sign" -> personalSign(params.optStringOrNull(1))      // [address, message]
            "eth_sendTransaction" -> {
                val tx = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("eth_sendTransaction requires a tx object")
                sendTransaction(tx)
            }

            "wallet_switchEthereumChain" -> {
                val req = params.optJSONObject(0)
                    ?: throw MiniAppBridgeError.invalidParams("wallet_switchEthereumChain requires { chainId }")
                val want = req.optStringOrNull("chainId")?.lowercase()
                    ?: throw MiniAppBridgeError.invalidParams("wallet_switchEthereumChain requires { chainId }")
                if (want == chainIdHex) {
                    "null"
                } else {
                    throw MiniAppBridgeError(4902, "This demo wallet only supports Sepolia ($chainIdHex).")
                }
            }

            else -> throw MiniAppBridgeError.unsupported("eth.$method")
        }
    }

    // ---- privileged flows ----

    private suspend fun requestAccounts(): String {
        val addr = activeAddress()
        if (!connected) {
            gate.request(
                kind = "web3",
                payloadJson = JSONObject()
                    .put("action", "connect")
                    .put("address", addr)
                    .toString(),
                appTitle = appTitle,
            )
            connected = true
        }
        return JSONArray().put(addr).toString()
    }

    private suspend fun personalSign(messageParam: String?): String {
        val raw = messageParam
            ?: throw MiniAppBridgeError.invalidParams("message must be a string")
        val message = dataFromHex(raw) ?: raw.toByteArray(Charsets.UTF_8)
        val account = activeSoftwareAccount()
        val sandwich = env.sandwich()
            ?: throw MiniAppBridgeError.unauthorized("wallet is locked")

        val preview = String(message.take(200).toByteArray(), Charsets.UTF_8)
        gate.request(
            kind = "web3",
            payloadJson = JSONObject()
                .put("action", "signMessage")
                .put("preview", preview.ifEmpty { raw })
                .toString(),
            appTitle = appTitle,
        )

        val signed = io {
            try {
                EthereumDescriptors.signPersonalMessage(
                    words = sandwich.recoveryWords(),
                    account = account,
                    message = message,
                    derivationPath = activeDescriptor().derivationPath,
                )
            } catch (e: Throwable) {
                throw MiniAppBridgeError.internalError(e.message ?: "personal_sign failed")
            }
        }
        return JSONObject.quote(signed)
    }

    private suspend fun sendTransaction(tx: JSONObject): String {
        val to = tx.optStringOrNull("to")?.takeIf { it.isNotEmpty() }
            ?: throw MiniAppBridgeError.invalidParams("eth_sendTransaction requires `to`")
        // Native sends only. Reject arbitrary calldata rather than blind-sign.
        val dataBytes = dataFromHex(tx.optStringOrNull("data"))
        if (dataBytes != null && dataBytes.isNotEmpty()) {
            throw MiniAppBridgeError.unsupported(
                "contract calldata (this demo signs native Sepolia sends only)",
            )
        }
        val value = weiOrZero(tx.optStringOrNull("value"))
        val account = activeSoftwareAccount()
        val sandwich = env.sandwich()
            ?: throw MiniAppBridgeError.unauthorized("wallet is locked")

        // Approval before any chain prep / signing.
        gate.request(
            kind = "web3",
            payloadJson = JSONObject()
                .put("action", "sendTransaction")
                .put("to", to)
                .put("amountEth", value.display(ticker = "ETH", maxDecimals = 6))
                .put("network", network.displayName)
                .toString(),
            appTitle = appTitle,
        )

        return io {
            val descriptor = activeDescriptor()
            val wallet = EthereumWallet(descriptor)
            val nonce = wallet.pendingNonce(rpcURL)
            val gasLimit = providedGasLimit(tx.optStringOrNull("gas"))
                ?: wallet.estimateGasUnits(to = to, value = value, data = null, rpcURL = rpcURL)
            val fees = EthereumGasEstimator.estimate(rpcURL)
            val std = fees.firstOrNull { it.tier == EthereumGasEstimator.Tier.STANDARD } ?: fees[0]

            try {
                wallet.sendSoftware(
                    sandwich = sandwich,
                    account = account,
                    to = to,
                    value = value,
                    gasLimit = gasLimit,
                    maxFeePerGas = std.maxFeePerGas,
                    maxPriorityFeePerGas = std.maxPriorityFeePerGas,
                    chainId = network.chainId,
                    nonce = nonce,
                    payload = EthereumTxPlan.Payload.Native,
                    rpcURL = rpcURL,
                    derivationPath = descriptor.derivationPath,
                ).let { hash -> JSONObject.quote(hash) }
            } catch (e: MiniAppBridgeError) {
                throw e
            } catch (e: Throwable) {
                throw MiniAppBridgeError.internalError(e.message ?: "eth_sendTransaction failed")
            }
        }
    }

    // ---- helpers ----

    private fun rpc(): EthereumRPCClient =
        EthereumRPCClient.orNull(rpcURL)
            ?: throw MiniAppBridgeError.internalError("Sepolia RPC URL is invalid")

    private fun activeDescriptor(): EthereumWalletDescriptor =
        env.activeWallet
            ?: throw MiniAppBridgeError.unauthorized("no Ethereum wallet in this app")

    private fun activeAddress(): String {
        val addr = activeDescriptor().address
        if (addr.isNullOrEmpty()) {
            throw MiniAppBridgeError.unauthorized("active Ethereum wallet has no address")
        }
        return addr
    }

    private fun activeSoftwareAccount(): Long {
        val kind = activeDescriptor().kind
        return (kind as? EthereumWalletKind.Software)?.account
            ?: throw MiniAppBridgeError.unsupported("hardware-wallet signing in mini apps")
    }

    private fun providedGasLimit(gasHex: String?): Long? {
        val h = gasHex ?: return null
        val s = if (h.startsWith("0x") || h.startsWith("0X")) h.substring(2) else h
        return runCatching { java.lang.Long.parseUnsignedLong(s, 16) }.getOrNull()
    }

    private fun weiOrZero(hex: String?): EthereumWeiValue =
        runCatching { EthereumWeiValue.fromHex(hex ?: "0x0") }.getOrDefault(EthereumWeiValue.ZERO)

    /** Run a blocking engine call off the dispatch thread. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: MiniAppBridgeError) {
            throw e
        } catch (e: Throwable) {
            throw MiniAppBridgeError.internalError(e.message ?: "RPC error")
        }
    }

    /**
     * The JS `params` for an EIP-1193 request is an array. The shim serializes
     * `args.params` straight through, so argsJson is that array (or "null").
     */
    private fun parseParamsArray(argsJson: String): JSONArray {
        val trimmed = argsJson.trim()
        if (trimmed.isEmpty() || trimmed == "null") return JSONArray()
        return runCatching { JSONArray(trimmed) }.getOrDefault(JSONArray())
    }

    private fun JSONArray.optStringOrNull(index: Int): String? {
        if (index < 0 || index >= length() || isNull(index)) return null
        val v = opt(index)
        return v as? String
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }

    /** Decode a 0x-hex string to bytes. Returns empty for "0x"/"" and null on bad hex. */
    private fun dataFromHex(s: String?): ByteArray? {
        var h = s ?: return null
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2)
        if (h.isEmpty()) return ByteArray(0)
        if (h.length % 2 != 0) return null
        val out = ByteArray(h.length / 2)
        var i = 0
        while (i < h.length) {
            val hi = Character.digit(h[i], 16)
            val lo = Character.digit(h[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
