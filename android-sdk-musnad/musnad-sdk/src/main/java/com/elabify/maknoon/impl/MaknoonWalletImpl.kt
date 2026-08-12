package com.elabify.maknoon.impl

import android.content.Context
import com.elabify.maknoon.Asset
import com.elabify.maknoon.Chain
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MaknoonWallet
import com.elabify.maknoon.Network
import com.elabify.maknoon.NetworkKind
import com.elabify.maknoon.ReceiveAddress
import com.elabify.maknoon.SendRequest
import com.elabify.maknoon.Signer
import com.elabify.maknoon.TxHash
import com.elabify.maknoon.UnsignedTx
import com.elabify.maknoon.WalletAccount
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.wallet.PrefsBitcoinStore
import com.elabify.musnad.wallet.PrefsEthereumStore
import com.elabify.musnad.wallet.PrefsSolanaStore
import com.elabify.musnad.wallet.bitcoin.BitcoinFeeEstimator
import com.elabify.musnad.wallet.bitcoin.BitcoinFeeMode
import com.elabify.musnad.wallet.bitcoin.BitcoinSettings
import com.elabify.musnad.wallet.bitcoin.BitcoinSigningHelpers
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletStore
import com.elabify.musnad.wallet.ethereum.EthereumABI
import com.elabify.musnad.wallet.ethereum.EthereumGasEstimator
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumTokenStore
import com.elabify.musnad.wallet.ethereum.EthereumTxPlan
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumWalletException
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.ethereum.EthereumWeiValue
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.lightning.LightningAccountStore
import com.elabify.musnad.wallet.lightning.LndHubClient
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.solana.SolanaWallet
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import com.elabify.musnad.wallet.solana.SolanaWalletStore
import com.elabify.musnad.wallet.solana.SolanaSettings
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronSettings
import com.elabify.musnad.wallet.tron.TronWallet
import com.elabify.musnad.wallet.tron.TronTRC20TokenStore
import com.elabify.musnad.wallet.tron.TronWalletKind
import com.elabify.musnad.wallet.tron.TronWalletStore
import com.elabify.musnad.wallet.walletPrefs
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [MaknoonWallet] over the SDK's per-chain engines. Reads the same wallet state the app
 * manages: the stores are backed by `walletPrefs(context)` ("maknoon.wallets.v1"), SDK-owned,
 * so the SDK and the app observe one source of truth (transparent migration).
 *
 * "Which chain" is uniform: a [Network] carries a [Chain] (the concrete deployment), e.g.
 * `Network.Ethereum(chain = baseSepolia)` (ADR-0055).
 *
 * Wired (all five networks): EVM (native + ERC-20 assets, native + ERC-20 sends), Solana
 * (native + SPL balances, native send), Tron (native), Bitcoin (UTXO/PSBT: sync balance,
 * fresh receive address, PSBT build + software sign + Electrum broadcast), Lightning
 * (custodial balance, bolt11 receive, pay-invoice send). Remaining follow-ups on the same
 * four methods: token *sends* (SPL/TRC-20), TRC-20 balances, and device (hardware) signing.
 */
internal class MaknoonWalletImpl(
    private val appContext: Context,
) : MaknoonWallet {

    private fun sandwich(): IdentitySandwich? = IdentitySandwich.load(IdentityStore(appContext))

    private fun notYet(): Nothing = throw MaknoonError.Configuration(
        "This MaknoonWallet path lands in a following increment (token sends, device signing)",
    )

    // ---- dispatch --------------------------------------------------------

    override suspend fun accounts(network: Network): List<WalletAccount> = withContext(Dispatchers.IO) {
        when (network) {
            is Network.Ethereum -> evmAccounts(network)
            is Network.Solana -> solAccounts(network)
            is Network.Tron -> tronAccounts(network)
            is Network.Lightning -> lnAccounts(network)
            is Network.Bitcoin -> btcAccounts(network)
        }
    }

    override suspend fun assets(account: WalletAccount): List<Asset> = withContext(Dispatchers.IO) {
        when (account.network) {
            is Network.Ethereum -> evmAssets(account)
            is Network.Solana -> solAssets(account)
            is Network.Tron -> tronAssets(account)
            is Network.Lightning -> lnAssets(account)
            is Network.Bitcoin -> btcAssets(account)
        }
    }

    override suspend fun receiveAddress(account: WalletAccount): ReceiveAddress =
        withContext(Dispatchers.IO) {
            when (account.network) {
                is Network.Lightning -> lnReceive(account) // issues a bolt11 invoice
                is Network.Bitcoin -> btcReceive(account)
                is Network.Ethereum -> schemeReceive(account, "ethereum")
                is Network.Solana -> schemeReceive(account, "solana")
                is Network.Tron -> schemeReceive(account, "tron")
            }
        }

    private fun schemeReceive(account: WalletAccount, scheme: String): ReceiveAddress {
        val addr = account.address.ifEmpty {
            throw MaknoonError.InvalidRequest("Wallet ${account.id} has no address")
        }
        return ReceiveAddress(address = addr, uri = "$scheme:$addr")
    }

    override suspend fun chains(kind: NetworkKind): List<Chain> = withContext(Dispatchers.Default) {
        when (kind) {
            NetworkKind.ETHEREUM -> {
                val s = evmSettings()
                EthereumNetwork.displayOrdered.map {
                    Chain(it.chainId.toString(), it.displayName, it.isTestnet, s.rpcURL(it))
                }
            }
            NetworkKind.SOLANA -> {
                val s = solSettings()
                SolanaNetwork.entries.map {
                    Chain(it.rawValue, it.displayName, it != SolanaNetwork.MAINNET, s.rpcURL(it))
                }
            }
            NetworkKind.TRON -> {
                val s = tronSettings()
                TronNetwork.entries.map {
                    Chain(it.rawValue, it.displayName, it != TronNetwork.MAINNET, s.rpcURL(it))
                }
            }
            NetworkKind.BITCOIN -> BitcoinNetwork.entries.map {
                Chain(it.rawValue, it.displayName, it != BitcoinNetwork.MAINNET, null)
            }
            NetworkKind.LIGHTNING -> listOf(Chain("mainnet", "Mainnet", isTestnet = false, rpcUrl = null))
        }
    }

    override suspend fun buildSend(request: SendRequest): UnsignedTx = withContext(Dispatchers.IO) {
        if (request.tokenContract != null &&
            (request.account.network is Network.Lightning || request.account.network is Network.Bitcoin)
        ) {
            throw MaknoonError.InvalidRequest("Token sends are not applicable to this network")
        }
        when (request.account.network) {
            is Network.Ethereum -> evmBuildSend(request)      // native + ERC-20
            is Network.Solana -> solBuildSend(request)        // native + SPL
            is Network.Tron -> tronBuildSend(request)         // native + TRC-20
            is Network.Lightning -> intentSend(request, "lightning") // toAddress carries the bolt11
            is Network.Bitcoin -> btcBuildSend(request)
        }
    }

    override suspend fun signAndBroadcast(tx: UnsignedTx, signer: Signer): TxHash =
        withContext(Dispatchers.IO) {
            if (signer is Signer.Device) {
                throw MaknoonError.Configuration("Device signing lands with the MaknoonHardware facade")
            }
            when (tx.account.network) {
                is Network.Ethereum -> evmSignAndBroadcast(tx)
                is Network.Solana -> solSignAndBroadcast(tx)
                is Network.Tron -> tronSignAndBroadcast(tx)
                is Network.Lightning -> lnSignAndBroadcast(tx)
                is Network.Bitcoin -> btcSignAndBroadcast(tx)
            }
        }

    /** Shared shape for the account-based sends whose engines build+sign+broadcast atomically
     *  (Solana fetches a fresh blockhash, Tron builds the contract) at sign time: buildSend
     *  only records intent. */
    private fun intentSend(request: SendRequest, kind: String): UnsignedTx {
        val plan = JSONObject()
            .put("kind", kind)
            .put("walletId", request.account.id.toString())
            .put("chainRaw", request.account.network.chain.id)
            .put("recipient", request.toAddress)
            .put("amount", request.amount)
        return UnsignedTx(
            account = request.account,
            payload = plan.toString().toByteArray(Charsets.UTF_8),
            feeEstimate = if (kind == "solana") "5000" else "0", // SOL base fee ~5000 lamports; Tron uses bandwidth/energy
            summary = "Send ${request.amount} (smallest units) to ${request.toAddress}",
        )
    }

    // ---- EVM -------------------------------------------------------------

    private fun evmStore() = EthereumWalletStore(PrefsEthereumStore(walletPrefs(appContext)))
    private fun evmSettings() = EthereumSettings(PrefsEthereumStore(walletPrefs(appContext)))

    private fun evmNetwork(chain: Chain): EthereumNetwork {
        val chainId = chain.id.toLongOrNull()
            ?: throw MaknoonError.InvalidRequest("Bad EVM chain id '${chain.id}'")
        return EthereumNetwork.fromChainId(chainId)
            ?: throw MaknoonError.InvalidRequest("Unsupported EVM chainId $chainId")
    }

    private fun evmRpc(chain: Chain, ethNet: EthereumNetwork): String =
        chain.rpcUrl?.takeIf { it.isNotBlank() } ?: evmSettings().rpcURL(ethNet)

    private fun evmAccounts(network: Network.Ethereum): List<WalletAccount> =
        evmStore().wallets.map { d ->
            WalletAccount(id = d.id, network = network, label = d.label, address = d.address.orEmpty())
        }

    private fun evmAssets(account: WalletAccount): List<Asset> {
        val network = account.network as Network.Ethereum
        val ethNet = evmNetwork(network.chain)
        val rpc = evmRpc(network.chain, ethNet)
        val descriptor = evmStore().wallets.firstOrNull { it.id == account.id }
            ?: throw MaknoonError.InvalidRequest("No EVM wallet ${account.id}")
        val ethWallet = EthereumWallet(descriptor)
        val native = Asset(
            ethNet.ticker, ethNet.displayName, Asset.Kind.NATIVE, null, 18,
            ethWallet.balance(rpc).bigInteger.toString(),
        )
        // Wallet-scoped, not chain-wide (ADR-0060). The chain-wide overload
        // still exists and still compiles, but it now returns only the curated
        // defaults, and EthereumTokenCatalog.firstRunSeed() was changed to
        // return an empty list, so this facade reported ZERO tokens for every
        // EVM account. A silent wrong answer, not a build error.
        val tokens = EthereumTokenStore(PrefsEthereumStore(walletPrefs(appContext)))
            .tokens(ethNet, descriptor.id)
            .map { t ->
                val bal = runCatching { ethWallet.tokenBalance(t, rpc).bigInteger.toString() }.getOrDefault("0")
                Asset(t.symbol, t.name, Asset.Kind.ERC20, t.contractAddress, t.decimals, bal)
            }
        return listOf(native) + tokens
    }

    private fun evmBuildSend(request: SendRequest): UnsignedTx {
        val network = request.account.network as Network.Ethereum
        val ethNet = evmNetwork(network.chain)
        val rpc = evmRpc(network.chain, ethNet)
        val descriptor = evmStore().wallets.firstOrNull { it.id == request.account.id }
            ?: throw MaknoonError.InvalidRequest("No EVM wallet ${request.account.id}")
        val ethWallet = EthereumWallet(descriptor)

        val isToken = request.tokenContract != null
        val value = EthereumWeiValue.fromDecimal(BigDecimal(request.amount))
        val to = if (isToken) request.tokenContract!! else request.toAddress
        val estData: ByteArray? = if (isToken) EthereumABI.transferData(request.toAddress, value) else null
        val estValue = if (isToken) EthereumWeiValue.ZERO else value
        val gasUnits = ethWallet.estimateGasUnits(to, estValue, estData, rpc)
        val std = EthereumGasEstimator.estimate(rpc).let { it.getOrNull(1) ?: it.first() }
        val nonce = ethWallet.pendingNonce(rpc)

        val plan = JSONObject()
            .put("kind", "evm")
            .put("chainId", ethNet.chainId)
            .put("to", to)
            .put("valueHex", value.hex)
            .put("gasLimit", gasUnits)
            .put("maxFeeHex", std.maxFeePerGas.hex)
            .put("maxPriorityHex", std.maxPriorityFeePerGas.hex)
            .put("nonce", nonce)
            .put("rpc", rpc)
            .apply { if (isToken) put("tokenRecipient", request.toAddress) }
        val feeWei = std.maxFeePerGas.bigInteger.multiply(BigInteger.valueOf(gasUnits))
        return UnsignedTx(
            account = request.account,
            payload = plan.toString().toByteArray(Charsets.UTF_8),
            feeEstimate = feeWei.toString(),
            summary = "Send ${request.amount} (smallest units) to ${request.toAddress} on ${ethNet.displayName}",
        )
    }

    private fun evmSignAndBroadcast(tx: UnsignedTx): TxHash {
        val plan = JSONObject(String(tx.payload, Charsets.UTF_8))
        val descriptor = evmStore().wallets.firstOrNull { it.id == tx.account.id }
            ?: throw MaknoonError.InvalidRequest("No EVM wallet ${tx.account.id}")
        val accountIndex = when (val k = descriptor.kind) {
            is EthereumWalletKind.Software -> k.account
            is EthereumWalletKind.Hardware ->
                throw MaknoonError.InvalidRequest("Software signer cannot sign for a hardware wallet")
        }
        val sw = sandwich() ?: throw MaknoonError.Configuration("No identity present")
        val tokenRecipient = if (plan.has("tokenRecipient")) plan.getString("tokenRecipient") else null
        val payload: EthereumTxPlan.Payload =
            tokenRecipient?.let { EthereumTxPlan.Payload.Erc20(it) } ?: EthereumTxPlan.Payload.Native
        val rpc = plan.getString("rpc")
        val ethWallet = EthereumWallet(descriptor)
        // EthereumWalletException is an SDK-internal type, so letting it escape
        // would put an internal class in the facade's effective contract and
        // hand a partner an error their `catch (e: MaknoonError)` never sees.
        // Its messages are already written for a human, so carry them through.
        //
        // ADR-0063's orphan-wallet guard (WRONG_IDENTITY) reaches this path:
        // prepareSoftware now re-derives the address from the current seed and
        // refuses when it does not match the descriptor's cached one.
        val rawTx = try {
            ethWallet.prepareSoftware(
                sandwich = sw,
                account = accountIndex,
                to = plan.getString("to"),
                value = EthereumWeiValue.fromHex(plan.getString("valueHex")),
                gasLimit = plan.getLong("gasLimit"),
                maxFeePerGas = EthereumWeiValue.fromHex(plan.getString("maxFeeHex")),
                maxPriorityFeePerGas = EthereumWeiValue.fromHex(plan.getString("maxPriorityHex")),
                chainId = plan.getLong("chainId"),
                nonce = plan.getLong("nonce"),
                payload = payload,
            )
        } catch (e: EthereumWalletException) {
            throw MaknoonError.InvalidRequest(e.message ?: "Ethereum signing refused")
        }
        return TxHash(try { ethWallet.broadcast(rawTx, rpc) } catch (e: EthereumWalletException) {
            throw MaknoonError.Network(e.message ?: "Ethereum broadcast failed", e)
        })
    }

    // ---- Solana ----------------------------------------------------------

    private fun solStore() = SolanaWalletStore(PrefsSolanaStore(walletPrefs(appContext)))
    private fun solSettings() = SolanaSettings(PrefsSolanaStore(walletPrefs(appContext)))
    private fun solNetwork(chain: Chain): SolanaNetwork =
        SolanaNetwork.fromRawValue(chain.id) ?: throw MaknoonError.InvalidRequest("Unknown Solana chain '${chain.id}'")

    private fun solAccounts(network: Network.Solana): List<WalletAccount> {
        val net = solNetwork(network.chain)
        val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: solSettings().rpcURL(net)
        val sw = sandwich()
        return solStore().wallets.map { d ->
            val addr = runCatching { SolanaWallet(d, net, rpc, sw).resolvedAddress() }.getOrDefault("")
            WalletAccount(id = d.id, network = network, label = d.label, address = addr)
        }
    }

    private fun solAssets(account: WalletAccount): List<Asset> {
        val network = account.network as Network.Solana
        val net = solNetwork(network.chain)
        val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: solSettings().rpcURL(net)
        val d = solStore().wallets.firstOrNull { it.id == account.id }
            ?: throw MaknoonError.InvalidRequest("No Solana wallet ${account.id}")
        val wallet = SolanaWallet(d, net, rpc, sandwich())
        val native = Asset("SOL", "Solana", Asset.Kind.NATIVE, null, 9, wallet.refreshBalance().toString())
        val spl = runCatching { wallet.tokenAccounts() }.getOrDefault(emptyList()).map { ta ->
            Asset(ta.mint.take(6), ta.mint, Asset.Kind.SPL, ta.mint, ta.decimals, ta.amount.toString())
        }
        return listOf(native) + spl
    }

    private fun solBuildSend(request: SendRequest): UnsignedTx {
        val network = request.account.network as Network.Solana
        val plan = JSONObject()
            .put("recipient", request.toAddress)
            .put("amount", request.amount)
        if (request.tokenContract != null) {
            // Resolve SPL decimals from the holder's on-chain token account for this mint.
            val net = solNetwork(network.chain)
            val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: solSettings().rpcURL(net)
            val d = solStore().wallets.firstOrNull { it.id == request.account.id }
                ?: throw MaknoonError.InvalidRequest("No Solana wallet ${request.account.id}")
            val decimals = SolanaWallet(d, net, rpc, sandwich()).tokenAccounts()
                .firstOrNull { it.mint == request.tokenContract }?.decimals
                ?: throw MaknoonError.InvalidRequest("No SPL token account for mint ${request.tokenContract}")
            plan.put("tokenMint", request.tokenContract).put("tokenDecimals", decimals)
        }
        return UnsignedTx(
            account = request.account,
            payload = plan.toString().toByteArray(Charsets.UTF_8),
            feeEstimate = "5000", // SOL base fee ~5000 lamports
            summary = "Send ${request.amount} (smallest units) to ${request.toAddress}",
        )
    }

    private fun solSignAndBroadcast(tx: UnsignedTx): TxHash {
        val plan = JSONObject(String(tx.payload, Charsets.UTF_8))
        val network = tx.account.network as Network.Solana
        val net = solNetwork(network.chain)
        val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: solSettings().rpcURL(net)
        val d = solStore().wallets.firstOrNull { it.id == tx.account.id }
            ?: throw MaknoonError.InvalidRequest("No Solana wallet ${tx.account.id}")
        if (d.kind !is SolanaWalletKind.Software) {
            throw MaknoonError.InvalidRequest("Software signer cannot sign for a hardware wallet")
        }
        val wallet = SolanaWallet(d, net, rpc, sandwich())
        val recipient = plan.getString("recipient")
        val raw = plan.getString("amount").toLongOrNull()
            ?: throw MaknoonError.InvalidRequest("Bad amount")
        val sig = if (plan.has("tokenMint")) {
            wallet.sendSPLToken(plan.getString("tokenMint"), plan.getInt("tokenDecimals"), raw, recipient, 0)
        } else {
            wallet.sendSoftware(recipient, raw, priorityFeeMicroLamports = 0)
        }
        return TxHash(sig)
    }

    // ---- Tron ------------------------------------------------------------

    private fun tronStore() = TronWalletStore(walletPrefs(appContext))
    private fun tronSettings() = TronSettings(walletPrefs(appContext))
    private fun tronNetwork(chain: Chain): TronNetwork =
        TronNetwork.fromRawValue(chain.id) ?: throw MaknoonError.InvalidRequest("Unknown Tron chain '${chain.id}'")

    private fun tronAccounts(network: Network.Tron): List<WalletAccount> {
        val net = tronNetwork(network.chain)
        val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: tronSettings().rpcURL(net)
        val sw = sandwich()
        return tronStore().wallets.map { d ->
            val addr = runCatching { TronWallet(d, net, rpc, sw).resolvedAddress() }.getOrDefault("")
            WalletAccount(id = d.id, network = network, label = d.label, address = addr)
        }
    }

    private fun tronAssets(account: WalletAccount): List<Asset> {
        val network = account.network as Network.Tron
        val net = tronNetwork(network.chain)
        val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: tronSettings().rpcURL(net)
        val d = tronStore().wallets.firstOrNull { it.id == account.id }
            ?: throw MaknoonError.InvalidRequest("No Tron wallet ${account.id}")
        val wallet = TronWallet(d, net, rpc, sandwich())
        val native = Asset("TRX", "Tron", Asset.Kind.NATIVE, null, 6, wallet.refreshBalance().toString())
        val trc20 = TronTRC20TokenStore(walletPrefs(appContext)).tokens(net).map { t ->
            val bal = runCatching { wallet.trc20Balance(t.contract, rpc) }.getOrDefault("0")
            Asset(t.symbol, t.name, Asset.Kind.TRC20, t.contract, t.decimals, bal)
        }
        return listOf(native) + trc20
    }

    private fun tronBuildSend(request: SendRequest): UnsignedTx {
        val plan = JSONObject()
            .put("recipient", request.toAddress)
            .put("amount", request.amount)
        if (request.tokenContract != null) plan.put("tokenContract", request.tokenContract)
        return UnsignedTx(
            account = request.account,
            payload = plan.toString().toByteArray(Charsets.UTF_8),
            feeEstimate = "0", // Tron uses bandwidth/energy, resolved at send time
            summary = "Send ${request.amount} (smallest units) to ${request.toAddress}",
        )
    }

    private fun tronSignAndBroadcast(tx: UnsignedTx): TxHash {
        val plan = JSONObject(String(tx.payload, Charsets.UTF_8))
        val network = tx.account.network as Network.Tron
        val net = tronNetwork(network.chain)
        val rpc = network.chain.rpcUrl?.takeIf { it.isNotBlank() } ?: tronSettings().rpcURL(net)
        val d = tronStore().wallets.firstOrNull { it.id == tx.account.id }
            ?: throw MaknoonError.InvalidRequest("No Tron wallet ${tx.account.id}")
        if (d.kind !is TronWalletKind.Software) {
            throw MaknoonError.InvalidRequest("Software signer cannot sign for a hardware wallet")
        }
        val wallet = TronWallet(d, net, rpc, sandwich())
        val recipient = plan.getString("recipient")
        val txid = if (plan.has("tokenContract")) {
            wallet.sendTRC20(plan.getString("tokenContract"), plan.getString("amount"), recipient)
        } else {
            val sun = plan.getString("amount").toLongOrNull()
                ?: throw MaknoonError.InvalidRequest("Bad sun amount")
            wallet.sendNative(recipient, sun)
        }
        return TxHash(txid)
    }

    // ---- Bitcoin ---------------------------------------------------------
    // UTXO model via BDK. A descriptor is network-specific (mainnet/testnet3/signet), so
    // accounts() filters by the requested chain. receiveAddress derives a fresh unused address
    // (non-advancing peek). A send is a PSBT: buildSend builds the unsigned PSBT (coin
    // selection + fee), signAndBroadcast signs it (transient seed wallet) and broadcasts over
    // Electrum. The BDK SQLite cache lives under appContext.filesDir; a fresh path just
    // re-derives watch-only from the cached xpub and re-syncs (sharing the app's exact BDK dir
    // is a migration detail).

    private fun btcStore() = BitcoinWalletStore(PrefsBitcoinStore(walletPrefs(appContext))).also { it.reload() }
    private fun btcSettings() = BitcoinSettings(PrefsBitcoinStore(walletPrefs(appContext))).also { it.reload() }
    private fun btcNetwork(chain: Chain): BitcoinNetwork =
        BitcoinNetwork.fromRawValue(chain.id) ?: throw MaknoonError.InvalidRequest("Unknown Bitcoin chain '${chain.id}'")

    /** Words for descriptor derivation / signing; null when the identity is locked or absent
     *  (watch-only opens from the cached xpub still work). */
    private fun btcWords(): List<String>? = runCatching { sandwich()?.recoveryWords() }.getOrNull()

    private fun btcEngine(d: BitcoinWalletDescriptor, words: List<String>?): BitcoinWalletEngine =
        BitcoinWalletEngine.open(d, appContext.filesDir.absolutePath, words, null)

    private fun btcAccounts(network: Network.Bitcoin): List<WalletAccount> {
        val net = btcNetwork(network.chain)
        val words = btcWords()
        return btcStore().wallets.filter { it.network == net }.map { d ->
            val addr = runCatching { btcEngine(d, words).nextUnusedReceiveAddress().address.toString() }
                .getOrDefault("")
            WalletAccount(id = d.id, network = network, label = d.label, address = addr)
        }
    }

    private fun btcAssets(account: WalletAccount): List<Asset> {
        val network = account.network as Network.Bitcoin
        val net = btcNetwork(network.chain)
        val d = btcStore().wallets.firstOrNull { it.id == account.id }
            ?: throw MaknoonError.InvalidRequest("No Bitcoin wallet ${account.id}")
        val engine = btcEngine(d, btcWords())
        // Best-effort sync so the balance reflects chain state; a failed sync returns the last
        // cached balance rather than throwing.
        runCatching { engine.sync(btcSettings().electrumURL(net)) }
        val sats = engine.balance().total.toSat().toString()
        return listOf(Asset(net.ticker, "Bitcoin", Asset.Kind.NATIVE, null, 8, sats))
    }

    private fun btcReceive(account: WalletAccount): ReceiveAddress {
        val network = account.network as Network.Bitcoin
        val d = btcStore().wallets.firstOrNull { it.id == account.id }
            ?: throw MaknoonError.InvalidRequest("No Bitcoin wallet ${account.id}")
        val addr = btcEngine(d, btcWords()).nextUnusedReceiveAddress().address.toString()
        return ReceiveAddress(address = addr, uri = "bitcoin:$addr")
    }

    private fun btcBuildSend(request: SendRequest): UnsignedTx {
        val network = request.account.network as Network.Bitcoin
        val net = btcNetwork(network.chain)
        val d = btcStore().wallets.firstOrNull { it.id == request.account.id }
            ?: throw MaknoonError.InvalidRequest("No Bitcoin wallet ${request.account.id}")
        val amountSat = request.amount.toLongOrNull()
            ?: throw MaknoonError.InvalidRequest("Bad satoshi amount")
        val feeRate = BitcoinFeeEstimator.fetch(btcSettings().mempoolURL(net))
            .satsPerVb(BitcoinFeeMode.HALF_HOUR).coerceAtLeast(1)
        val unsigned = btcEngine(d, btcWords())
            .buildUnsignedPSBT(request.toAddress, amountSat, feeRate, enableRbf = true, selectedUtxoOutpoints = null)
        val plan = JSONObject()
            .put("kind", "bitcoin")
            .put("chainRaw", net.rawValue)
            .put("unsignedPsbt", unsigned)
            .put("electrum", btcSettings().electrumURL(net))
        return UnsignedTx(
            account = request.account,
            payload = plan.toString().toByteArray(Charsets.UTF_8),
            feeEstimate = "$feeRate sat/vB",
            summary = "Send ${request.amount} sat to ${request.toAddress} on ${net.displayName}",
        )
    }

    private fun btcSignAndBroadcast(tx: UnsignedTx): TxHash {
        val plan = JSONObject(String(tx.payload, Charsets.UTF_8))
        val network = tx.account.network as Network.Bitcoin
        val net = btcNetwork(network.chain)
        val d = btcStore().wallets.firstOrNull { it.id == tx.account.id }
            ?: throw MaknoonError.InvalidRequest("No Bitcoin wallet ${tx.account.id}")
        val account = (d.kind as? BitcoinWalletKind.Software)?.account
            ?: throw MaknoonError.InvalidRequest("Software signer cannot sign for a hardware wallet")
        val words = sandwich()?.recoveryWords()
            ?: throw MaknoonError.Configuration("No identity present (unlock to sign)")
        val unsigned = plan.getString("unsignedPsbt")
        val signed = BitcoinSigningHelpers.signSoftware(unsigned, words, null, account, net)
        val txid = btcEngine(d, words).importSignedPSBTAndBroadcast(signed, unsigned, plan.getString("electrum"))
        return TxHash(txid)
    }

    // ---- Lightning -------------------------------------------------------
    // Custodial LNDHub accounts. There is no on-chain address: receive issues a bolt11
    // invoice, and a send pays a bolt11 (carried in SendRequest.toAddress). The "chain" is
    // nominally mainnet.

    private fun lnStore() = LightningAccountStore(appContext)

    private fun lnClient(store: LightningAccountStore, accountId: java.util.UUID): LndHubClient {
        val account = store.accounts.firstOrNull { it.id == accountId }
            ?: throw MaknoonError.InvalidRequest("No Lightning account $accountId")
        val pw = store.password(accountId)
            ?: throw MaknoonError.Configuration("Lightning account password unavailable")
        return LndHubClient(account, pw)
    }

    private fun lnAccounts(network: Network.Lightning): List<WalletAccount> =
        lnStore().accounts.map { a ->
            WalletAccount(id = a.id, network = network, label = a.label, address = "")
        }

    private fun lnAssets(account: WalletAccount): List<Asset> {
        val sats = lnClient(lnStore(), account.id).balanceSat()
        return listOf(Asset("sat", "Lightning", Asset.Kind.NATIVE, null, 0, sats.toString()))
    }

    private fun lnReceive(account: WalletAccount): ReceiveAddress {
        // A zero-amount bolt11 invoice the payer completes.
        val bolt11 = lnClient(lnStore(), account.id).addInvoice(0, "")
        return ReceiveAddress(address = bolt11, uri = "lightning:$bolt11")
    }

    private fun lnSignAndBroadcast(tx: UnsignedTx): TxHash {
        val plan = JSONObject(String(tx.payload, Charsets.UTF_8))
        val bolt11 = plan.getString("recipient")
        val amountSat = plan.getString("amount").toLongOrNull()?.takeIf { it > 0 }
        val result = lnClient(lnStore(), tx.account.id).payInvoice(bolt11, amountSat)
        return TxHash(result.preimage)
    }
}
