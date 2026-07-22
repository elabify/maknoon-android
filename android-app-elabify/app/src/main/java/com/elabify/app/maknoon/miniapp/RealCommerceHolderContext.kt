// Real CommerceHolderContext over the ported SDK + the M2 X-Wing transport.
// Replaces StubCommerceHolderContext: the holder side (credential match,
// presentation build, offline verify), the wallet side (EVM software signing +
// keccak), the crypto (ML-DSA verify), and the server-blind X-Wing transport
// are all wired to real implementations, so Verify & Pay works end to end.
//
// Mirrors how iOS reaches into HolderStore for the same operations
// (CommercePaySheet.swift / CommerceRequestFactory.swift / PresentationFactory):
//   - matchCredential / buildPresentation: the SAME SDK calls the plain
//     verifier-scan path uses (MatchMaknoon-equivalent filter + PresentationBuilder).
//   - signEvmTransfer: CommerceEVMPayment.buildPlan + EthereumDescriptors.signTransaction
//     (software). Hardware EVM commerce is flagged not-yet-payable (P1, matches
//     the CommercePaySheet header and the iOS "P1 software only" note).
//   - transport: XWingTransport (M2), byte-exact with iOS CryptoKit.
//
// All methods are blocking (DB / crypto / RPC); the sheets call them off the
// main thread. The consumer IdentitySandwich is loaded lazily via [sandwichLoader]
// (null = locked) so the biometric gate stays in the identity slice.

package com.elabify.app.maknoon.miniapp

import android.content.Context
import com.elabify.core.canonicalize
import com.elabify.musnad.crypto.toHex
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.present.JsonValue
import com.elabify.musnad.present.LocalCheckMatrix
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.Presentation
import com.elabify.musnad.present.PresentationBuilder
import com.elabify.musnad.present.PresentationVerifier
import com.elabify.musnad.present.VerifierFilter
import com.elabify.musnad.present.VerifierFilterClause
import com.elabify.musnad.present.VerifierRequest
import com.elabify.musnad.present.VerifierRequestValidator
import com.elabify.musnad.present.VerifierResponseDirective
import com.elabify.musnad.wallet.PrefsEthereumStore
import com.elabify.musnad.wallet.PrefsSolanaStore
import com.elabify.app.maknoon.ui.wallet.ethereum.EthereumDeviceSigner
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumNetwork
import com.elabify.musnad.wallet.ethereum.EthereumWallet
import com.elabify.musnad.wallet.ethereum.EthereumSettings
import com.elabify.musnad.wallet.ethereum.EthereumWalletDescriptor
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import com.elabify.musnad.wallet.ethereum.EthereumWalletStore
import com.elabify.musnad.wallet.solana.SolanaNetwork
import com.elabify.musnad.wallet.solana.SolanaSettings
import com.elabify.musnad.wallet.solana.SolanaWallet
import com.elabify.musnad.wallet.solana.SolanaWalletDescriptor
import com.elabify.musnad.wallet.solana.SolanaWalletKind
import com.elabify.musnad.wallet.solana.SolanaWalletStore
import com.elabify.musnad.wallet.solana.SolanaPrimitives
import com.elabify.app.maknoon.ui.wallet.solana.SolanaDeviceSigner
import com.elabify.app.maknoon.ui.wallet.common.withHardwareDevice
import com.elabify.musnad.hardware.trezor.HardwarePassphraseRef
import com.elabify.app.maknoon.ui.wallet.bitcoin.BitcoinWalletEnv
import com.elabify.app.maknoon.ui.wallet.bitcoin.signBitcoinHardwarePsbt
import com.elabify.musnad.wallet.bitcoin.BitcoinNetwork
import com.elabify.musnad.wallet.bitcoin.BitcoinSigningHelpers
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletDescriptor
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletEngine
import com.elabify.musnad.wallet.bitcoin.BitcoinWalletKind
import com.elabify.app.maknoon.ui.wallet.lightning.LightningEnv
import com.elabify.musnad.wallet.lightning.LightningAccount
import com.elabify.musnad.wallet.tron.TronDescriptors
import com.elabify.musnad.wallet.tron.TronNetwork
import com.elabify.musnad.wallet.tron.TronRPCClient
import com.elabify.musnad.wallet.tron.TronSettings
import com.elabify.musnad.wallet.tron.TronWallet
import com.elabify.musnad.wallet.tron.TronWalletDescriptor
import com.elabify.musnad.wallet.tron.TronWalletKind
import com.elabify.musnad.wallet.tron.TronWalletStore
import com.elabify.musnad.wallet.walletPrefs
import org.json.JSONObject
import uniffi.pq_crypto_core.mldsa65VerifySignature

class RealCommerceHolderContext(
    context: Context,
    override val dropHost: String,
    /** Loads the unlocked consumer sandwich, or null if locked. */
    private val sandwichLoader: () -> IdentitySandwich?,
) : CommerceHolderContext {

    private val appContext = context.applicationContext
    private val prefs = walletPrefs(appContext)

    override val merchantIdentity: MerchantIdentityStore = MerchantIdentityStore(appContext)

    override fun identityUnlocked(): Boolean = sandwichLoader() != null

    // ---- merchant side: build + authenticate ----

    override fun buildSignedVerifierRequest(
        installedAppId: String,
        merchantName: String,
        schema: String?,
        requiredClaims: List<String>,
        issuers: List<String>?,
        challengeHex: String,
        requestId: String,
        issuedAt: Long,
        expiresAt: Long,
        inlinePublicKeyHex: String?,
    ): JSONObject {
        val merchantDid = merchantIdentity.ensureProvisioned(installedAppId)
        val filter = VerifierFilter(
            issuers = issuers?.let { VerifierFilterClause(mode = "allow", list = it) },
            schemas = schema?.let { VerifierFilterClause(mode = "allow", list = listOf(it)) },
            requiredClaims = requiredClaims,
        )
        // Commerce verifier requests respond out-of-band (the holder seals its
        // presentation back over X-Wing), so there is no callback URL. Matches
        // iOS CommerceRequestFactory (VerifierResponseDirective("qrBack", nil)).
        val response = VerifierResponseDirective(mode = "qrBack", callbackUrl = null)
        val unsigned = VerifierRequest(
            v = 1,
            verifierDid = merchantDid,
            verifierName = merchantName,
            verifierPublicKey = inlinePublicKeyHex,
            requestId = requestId,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            challenge = challengeHex,
            filter = filter,
            response = response,
            signature = null,
        )
        // Sign the canonicalized request (signature field dropped), byte-identical
        // to the holder-side validator's canonicalization.
        val msg = canonicalize(unsigned.canonicalMapWithoutSignature())
        val sig = "0x" + merchantIdentity.sign(installedAppId, msg).toHex()
        return unsigned.copy(signature = sig).toJson()
    }

    // Curated registry is not shipped on Android yet (null = "not registered";
    // self-signed merchants inline their pubkey, which is all the PoS demo needs).
    override fun registryLookup(did: String): CommerceRegistryEntry? = null

    override fun validateVerifierRequest(
        verifierRequestJson: JSONObject,
        nowSec: Long,
    ): CommerceVerifierDecision? {
        // Reuse the identity slice's validator. The verifier request is inline
        // JSON (not a URL), so validate() parses + crypto-verifies it directly.
        val decision = VerifierRequestValidator.validate(
            scanned = verifierRequestJson.toString(),
            registryHost = dropHost,
            nowSec = nowSec,
        ) ?: return null
        val tier = when (val t = decision.tier) {
            is VerifierRequestValidator.TrustTier.Registered ->
                CommerceVerifierDecision.Tier.Registered(t.name)
            VerifierRequestValidator.TrustTier.SelfSigned -> CommerceVerifierDecision.Tier.SelfSigned
            VerifierRequestValidator.TrustTier.Unknown -> CommerceVerifierDecision.Tier.Unknown
        }
        return CommerceVerifierDecision(isValid = decision.isValid, tier = tier)
    }

    override fun mldsaVerify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean =
        runCatching { mldsa65VerifySignature(publicKey, signature, message) }.getOrDefault(false)

    // ---- holder side: present + verify ----

    override fun matchCredential(verifierRequestJson: JSONObject): JSONObject? {
        val req = runCatching { VerifierRequest.fromJson(verifierRequestJson) }.getOrNull() ?: return null
        val match = heldCredentials().firstOrNull { matchesFilter(it, req.filter) } ?: return null
        return JSONObject(match.credentialJson)
    }

    override fun claimDisplayText(credentialJson: JSONObject, key: String): String {
        val parsed = runCatching { ParsedCredential.fromJson(credentialJson) }.getOrNull() ?: return ""
        val value = parsed.claims[key] ?: return ""
        // sdnScreen is an object { result, screenedAt }; render a short sentence
        // (mirrors iOS ScanVerifierSheet.attrValue / CommercePaySheet).
        if (key == "sdnScreen" && value is JsonValue.Obj) {
            val obj = value.value
            val result = (obj["result"] as? JsonValue.Str)?.value ?: "?"
            val when10 = (obj["screenedAt"] as? JsonValue.Str)?.value?.take(10).orEmpty()
            return if (when10.isEmpty()) "Sanctions: $result" else "Sanctions: $result (screened $when10)"
        }
        return value.displayText()
    }

    override fun buildPresentation(
        credentialJson: JSONObject,
        selectedClaims: Set<String>,
        verifierRequestJson: JSONObject,
    ): JSONObject {
        val sandwich = sandwichLoader()
            ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
        val req = VerifierRequest.fromJson(verifierRequestJson)
        val parsed = ParsedCredential.fromJson(credentialJson)
        val presentation = PresentationBuilder.build(
            credential = parsed,
            selectedClaims = selectedClaims,
            challenge = req.challenge,
            verifierDid = req.verifierDid,
            pendingRequest = req,
            sandwich = sandwich,
        )
        // Self-verify before sealing: never seal identity + broadcast payment
        // against a presentation the merchant would reject. Surfaces the exact
        // failing cryptographic check (the merchant verifies the same matrix).
        val check = PresentationVerifier.verifyOffline(presentation)
        if (!check.checks.overallPass) {
            throw IllegalStateException(
                "Identity self-check failed [${failingChecks(check.checks)}]: ${check.summary}",
            )
        }
        return presentation.toJson()
    }

    override fun verifyPresentationOffline(presentationJson: JSONObject, nowSec: Long): Boolean {
        val p = runCatching { Presentation.fromJson(presentationJson) }.getOrNull() ?: return false
        return runCatching { PresentationVerifier.verifyOffline(p, nowSec = nowSec).checks.overallPass }
            .getOrDefault(false)
    }

    // ---- wallet side: pay ----

    override fun ethereumWallets(): List<EthereumWalletDescriptor> =
        EthereumWalletStore(PrefsEthereumStore(prefs)).also { it.reload() }.wallets

    override fun ethereumRpcURL(network: EthereumNetwork): String =
        EthereumSettings(PrefsEthereumStore(prefs)).also { it.reload() }.rpcURL(network)

    override fun signEvmTransfer(
        descriptor: EthereumWalletDescriptor,
        rpcURLString: String,
        recipient: String,
        amount: String,
        asset: CommerceEVMPayment.Asset,
        biometricReason: String,
        hostPassphrase: String?,
    ): String {
        val from = descriptor.address
            ?: throw IllegalStateException("This wallet has no resolved address.")
        val plan = CommerceEVMPayment.buildPlan(
            from = from,
            rpcURLString = rpcURLString,
            recipient = recipient,
            amount = amount,
            asset = asset,
        )
        return when (val kind = descriptor.kind) {
            is EthereumWalletKind.Software -> {
                val sandwich = sandwichLoader()
                    ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                EthereumDescriptors.signTransaction(
                    words = sandwich.recoveryWords(),
                    passphrase = sandwich.bip39Passphrase(),
                    account = kind.account,
                    plan = plan,
                    derivationPath = descriptor.derivationPath,
                )
            }
            is EthereumWalletKind.Hardware -> {
                // Ledger / Trezor over BLE. EthereumDeviceSigner.signEip1559 is
                // itself blocking (wraps runBlocking + a pinned BLE session), so it
                // composes with this blocking seam; the device prompts on-screen
                // for approval. Host-entered Trezor passphrase wallets use
                // on-device entry here (hostPassphrase = null); the
                // descriptor.hidden ref still applies the passphrase MODE inside
                // the signer. Mirrors the EthereumSendScreen hardware path.
                val device = DeviceRegistry(appContext).find(kind.deviceId)
                    ?: throw IllegalStateException(
                        "The device that holds this wallet is not registered anymore. " +
                            "Re-register it under Settings > Devices.",
                    )
                EthereumWallet(descriptor).prepareHardware(
                    signer = EthereumDeviceSigner(
                        device = device,
                        account = kind.account,
                        hostPassphrase = hostPassphrase,
                    ),
                    to = plan.toAddress,
                    value = plan.value,
                    gasLimit = plan.gasLimit,
                    maxFeePerGas = plan.maxFeePerGas,
                    maxPriorityFeePerGas = plan.maxPriorityFeePerGas,
                    chainId = plan.chainId,
                    nonce = plan.nonce,
                    payload = plan.payload,
                )
            }
        }
    }

    override fun keccak256(data: ByteArray): ByteArray = EthereumDescriptors.keccak256(data)

    // ---- wallet side: pay (Solana) ----

    override fun solanaWallets(): List<SolanaWalletDescriptor> =
        SolanaWalletStore(PrefsSolanaStore(prefs)).also { it.reload() }.wallets

    override fun solanaRpcURL(network: SolanaNetwork): String =
        SolanaSettings(PrefsSolanaStore(prefs)).rpcURL(network)

    override fun solanaBalance(
        descriptor: SolanaWalletDescriptor,
        network: SolanaNetwork,
        rpcURLString: String,
        mint: String?,
    ): SolanaBalanceResult {
        val wallet = SolanaWallet(
            descriptor = descriptor, network = network, rpcURL = rpcURLString,
            sandwich = sandwichLoader(),
        )
        val lamports = wallet.refreshBalance()
        val tokenRaw = if (mint != null && mint.isNotEmpty()) {
            wallet.tokenAccounts().firstOrNull { it.mint == mint }?.amount ?: 0L
        } else {
            null
        }
        return SolanaBalanceResult(lamports = lamports, tokenRaw = tokenRaw)
    }

    override fun signSolanaTransfer(
        descriptor: SolanaWalletDescriptor,
        network: SolanaNetwork,
        rpcURLString: String,
        recipient: String,
        amount: String,
        mint: String?,
        decimals: Int,
        biometricReason: String,
        hostPassphrase: String?,
    ): SignedSolanaTransfer {
        val isToken = mint != null && mint.isNotEmpty()
        val signed = when (val kind = descriptor.kind) {
            is SolanaWalletKind.Software -> {
                val sandwich = sandwichLoader()
                    ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                val wallet = SolanaWallet(
                    descriptor = descriptor, network = network, rpcURL = rpcURLString, sandwich = sandwich,
                )
                // Sign WITHOUT broadcasting (prepare*), so the commerce flow can
                // post identity + the settlement ref before money moves.
                if (isToken) {
                    wallet.prepareSoftwareSPLToken(
                        mint = mint!!, decimals = decimals,
                        rawAmount = CommerceSolanaPayment.baseUnits(amount, decimals),
                        recipient = recipient, priorityFeeMicroLamports = 0L,
                    )
                } else {
                    wallet.prepareSoftware(
                        recipient = recipient,
                        lamports = CommerceSolanaPayment.baseUnits(amount, 9),
                        priorityFeeMicroLamports = 0L,
                    )
                }
            }
            is SolanaWalletKind.Hardware -> {
                val device = DeviceRegistry(appContext).find(kind.deviceId)
                    ?: throw IllegalStateException(
                        "The device that holds this wallet is not registered anymore. " +
                            "Re-register it under Settings > Devices.",
                    )
                val signerBase58 = kind.publicKeyBase58
                val signerPublicKey = SolanaPrimitives.base58Decode(signerBase58)
                    ?: throw IllegalStateException("Could not decode the wallet address.")
                val ledger = SolanaDeviceSigner(
                    device = device,
                    hidden = HardwarePassphraseRef.fromJson(descriptor.hidden),
                    derivationPath = descriptor.derivationPath,
                    hostPassphrase = hostPassphrase,
                )
                val wallet = SolanaWallet(
                    descriptor = descriptor, network = network, rpcURL = rpcURLString, sandwich = sandwichLoader(),
                )
                if (isToken) {
                    wallet.prepareHardwareSPLToken(
                        mint = mint!!, decimals = decimals,
                        rawAmount = CommerceSolanaPayment.baseUnits(amount, decimals),
                        recipient = recipient, priorityFeeMicroLamports = 0L,
                        ledger = ledger, signerBase58 = signerBase58,
                        signerPublicKey = signerPublicKey, account = kind.account,
                    )
                } else {
                    wallet.prepareHardwareNative(
                        recipient = recipient,
                        lamports = CommerceSolanaPayment.baseUnits(amount, 9),
                        priorityFeeMicroLamports = 0L,
                        ledger = ledger, signerBase58 = signerBase58,
                        signerPublicKey = signerPublicKey, account = kind.account,
                    )
                }
            }
        }
        val ref = CommerceSolanaPayment.transactionSignature(signed)
            ?: throw IllegalStateException("Could not read the transaction signature.")
        return SignedSolanaTransfer(signedBase64 = signed, signatureRef = ref)
    }

    // ---- wallet side: pay (Tron) ----

    override fun tronWallets(): List<TronWalletDescriptor> =
        TronWalletStore(prefs).also { it.reload() }.wallets

    override fun tronRpcURL(network: TronNetwork): String =
        TronSettings(prefs).rpcURL(network)

    override fun tronBalance(
        descriptor: TronWalletDescriptor,
        network: TronNetwork,
        rpcURLString: String,
        tokenContract: String?,
    ): TronBalanceResult {
        val wallet = TronWallet(
            descriptor = descriptor, network = network, rpcURL = rpcURLString,
            sandwich = sandwichLoader(),
        )
        val rpc = TronRPCClient(rpcURLString)
        val sun = rpc.getBalance(wallet.resolvedAddress())
        val tokenRaw = if (tokenContract != null && tokenContract.isNotEmpty()) {
            wallet.trc20Balance(tokenContract, rpcURLString).toLongOrNull() ?: 0L
        } else {
            null
        }
        return TronBalanceResult(sun = sun, tokenRaw = tokenRaw)
    }

    override fun signTronTransfer(
        descriptor: TronWalletDescriptor,
        network: TronNetwork,
        rpcURLString: String,
        recipient: String,
        amount: String,
        tokenContract: String?,
        tokenDecimals: Int,
        biometricReason: String,
        hostPassphrase: String?,
    ): SignedTronTransfer {
        val rpc = TronRPCClient(rpcURLString)
        val isToken = tokenContract != null && tokenContract.isNotEmpty()
        val rawAmount = if (isToken) CommerceTronPayment.baseUnits(amount, tokenDecimals).toString() else null
        val sunAmount = if (isToken) 0L else CommerceTronPayment.baseUnits(amount, 6)

        val signed = when (val kind = descriptor.kind) {
            is TronWalletKind.Software -> {
                val sandwich = sandwichLoader()
                    ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                // resolvedAddress derives from the consumer sandwich.
                val sender = TronWallet(
                    descriptor = descriptor, network = network, rpcURL = rpcURLString, sandwich = sandwich,
                ).resolvedAddress()
                val unsigned = if (isToken) {
                    rpc.createTRC20Transaction(
                        senderBase58 = sender, contractAddressBase58 = tokenContract!!,
                        recipientBase58 = recipient, rawAmount = rawAmount!!, feeLimitSun = 100_000_000,
                    )
                } else {
                    rpc.createNativeTransaction(
                        senderBase58 = sender, recipientBase58 = recipient,
                        sunAmount = sunAmount, feeLimitSun = 1_000_000,
                    )
                }
                TronDescriptors.signUnsignedFromSandwich(sandwich, kind.account, unsigned)
            }
            is TronWalletKind.Hardware -> {
                val device = DeviceRegistry(appContext).find(kind.deviceId)
                    ?: throw IllegalStateException(
                        "The device that holds this wallet is not registered anymore. " +
                            "Re-register it under Settings > Devices.",
                    )
                // Build the unsigned tx against the hardware wallet's address, sign
                // raw_data on the device (Ledger OR Trezor), re-applying the hidden
                // passphrase + derivation path, then assemble the R||S||V signature.
                val wallet = TronWallet(
                    descriptor = descriptor, network = network, rpcURL = rpcURLString, sandwich = null,
                )
                val unsigned = if (isToken) {
                    wallet.prepareHardwareTRC20(
                        contractAddress = tokenContract!!, recipient = recipient,
                        rawAmount = rawAmount!!, feeLimitSun = 100_000_000,
                        senderBase58 = kind.addressBase58Check,
                    )
                } else {
                    wallet.prepareHardwareNative(
                        recipient = recipient, sunAmount = sunAmount,
                        senderBase58 = kind.addressBase58Check,
                    )
                }
                val choice = HardwarePassphraseRef.resolveChoice(
                    HardwarePassphraseRef.fromJson(descriptor.hidden), hostPassphrase,
                )
                val sig = kotlinx.coroutines.runBlocking {
                    withHardwareDevice(device, choice, descriptor.derivationPath) { w ->
                        w.signTronTransaction(unsigned.rawData, kind.account)
                    }
                }
                TronDescriptors.assembleHardwareSignature(
                    unsigned = unsigned, r = sig.r, s = sig.s, v = byteArrayOf(sig.v.toByte()),
                )
            }
        }
        val ref = signed.txID
            ?: throw IllegalStateException("Could not derive the Tron transaction id.")
        return SignedTronTransfer(
            envelopeJSON = signed.envelopeJSON,
            signatureRSV = signed.signatureRSV,
            txID = ref,
        )
    }

    // ---- wallet side: pay (Bitcoin on-chain) ----

    override fun bitcoinWallets(): List<BitcoinWalletDescriptor> =
        BitcoinWalletEnv.create(appContext).store.also { it.reload() }.wallets

    override fun bitcoinElectrumURL(network: BitcoinNetwork): String =
        BitcoinWalletEnv.create(appContext).settings.electrumURL(network)

    override fun bitcoinBalanceSats(descriptor: BitcoinWalletDescriptor): Long {
        val env = BitcoinWalletEnv.create(appContext)
        val electrumURL = env.settings.electrumURL(descriptor.network)
        val sandwich = sandwichLoader()
        val engine = BitcoinWalletEngine.open(
            descriptor, env.filesDirPath, sandwich?.recoveryWords(), sandwich?.bip39Passphrase(),
        )
        engine.sync(electrumURL)
        return engine.balance().total.toSat().toLong()
    }

    override fun signBitcoinTransfer(
        descriptor: BitcoinWalletDescriptor,
        recipient: String,
        amountSat: Long,
        biometricReason: String,
        hostPassphrase: String?,
    ): SignedBitcoinTransfer {
        val env = BitcoinWalletEnv.create(appContext)
        val electrumURL = env.settings.electrumURL(descriptor.network)
        // Live fee for a ~30-min target, falling back to a safe default so the
        // commerce flow never blocks on the fee endpoint.
        val feeRateSatsPerVb = runCatching {
            com.elabify.musnad.wallet.bitcoin.BitcoinFeeEstimator
                .fetch(env.settings.mempoolURL(descriptor.network))
                .satsPerVb(com.elabify.musnad.wallet.bitcoin.BitcoinFeeMode.HALF_HOUR)
        }.getOrDefault(
            com.elabify.musnad.wallet.bitcoin.BitcoinFeeEstimator.fallback
                .satsPerVb(com.elabify.musnad.wallet.bitcoin.BitcoinFeeMode.HALF_HOUR),
        ).coerceAtLeast(1L)

        return when (val kind = descriptor.kind) {
            is BitcoinWalletKind.Software -> {
                val sandwich = sandwichLoader()
                    ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                val words = sandwich.recoveryWords()
                val engine = BitcoinWalletEngine.open(descriptor, env.filesDirPath, words, sandwich.bip39Passphrase())
                engine.sync(electrumURL) // BDK needs the UTXO set before building the spend
                val unsigned = engine.buildUnsignedPSBT(
                    toAddressString = recipient, amountSat = amountSat,
                    feeRateSatsPerVb = feeRateSatsPerVb, enableRbf = true, selectedUtxoOutpoints = null,
                )
                val signed = BitcoinSigningHelpers.signSoftware(
                    unsignedBase64 = unsigned, recoveryWords = words, passphrase = sandwich.bip39Passphrase(),
                    account = kind.account, network = descriptor.network,
                )
                // signSoftware finalizes (tryFinalize), so the txid is derivable now.
                SignedBitcoinTransfer(
                    signedPSBTBase64 = signed,
                    txid = CommerceBitcoinPayment.txidFromSignedPSBT(signed),
                )
            }
            is BitcoinWalletKind.Hardware -> {
                val device = DeviceRegistry(appContext).find(kind.deviceId)
                    ?: throw IllegalStateException(
                        "The device that holds this wallet is not registered anymore. " +
                            "Re-register it under Settings > Devices.",
                    )
                // Hardware opens watch-only from the cached xpub (no sandwich).
                val engine = BitcoinWalletEngine.open(descriptor, env.filesDirPath, null, null)
                engine.sync(electrumURL)
                val unsigned = engine.buildUnsignedPSBT(
                    toAddressString = recipient, amountSat = amountSat,
                    feeRateSatsPerVb = feeRateSatsPerVb, enableRbf = true, selectedUtxoOutpoints = null,
                )
                // Sign on the device over BLE (the shared path the send screen uses:
                // serial guard + Trezor passphrase + derivation-path override).
                val signed = kotlinx.coroutines.runBlocking {
                    signBitcoinHardwarePsbt(
                        device = device,
                        unsignedBase64 = unsigned,
                        fingerprintHex = kind.accountFingerprint,
                        accountXpub = kind.accountXpub,
                        network = descriptor.network,
                        hidden = descriptor.hidden,
                        derivationPath = descriptor.derivationPath,
                        hostEnteredPassphrase = hostPassphrase,
                    )
                }
                // Device returns partial sigs; finalize (with the unsigned) for the
                // deterministic pre-broadcast txid + carry the unsigned for broadcast.
                SignedBitcoinTransfer(
                    signedPSBTBase64 = signed,
                    txid = CommerceBitcoinPayment.txidFromSignedPSBT(signed, unsigned),
                    unsignedPSBTBase64 = unsigned,
                )
            }
        }
    }

    // ---- wallet side: pay (Lightning, custodial LNDHub) ----

    override fun lightningAccounts(): List<LightningAccount> =
        LightningEnv.get(appContext).accountStore.accounts

    override fun lightningBalanceSat(account: LightningAccount): Long {
        val client = LightningEnv.get(appContext).clientFor(account)
            ?: throw IllegalStateException("Re-import this Lightning account (no stored password).")
        return client.balanceSat()
    }

    override fun payLightningBolt11(account: LightningAccount, bolt11: String): String {
        val client = LightningEnv.get(appContext).clientFor(account)
            ?: throw IllegalStateException("Re-import this Lightning account (no stored password).")
        return client.payInvoice(bolt11, null).preimage
    }

    override fun mintLightningInvoice(accountId: String, amountSat: Long, memo: String): String {
        val env = LightningEnv.get(appContext)
        val account = env.accountStore.accounts.firstOrNull { it.id.toString() == accountId }
            ?: throw IllegalStateException("Select a Lightning account to receive into.")
        val client = env.clientFor(account)
            ?: throw IllegalStateException("Re-import the merchant Lightning account (no stored password).")
        return client.addInvoice(amountSat, memo)
    }

    // ---- server-blind transport (X-Wing HPKE, M2) ----

    override fun newTransportHolder(): TransportHolder = XWingKeyPair.generate()

    override val transportSenderFactory: TransportSenderFactory = XWingSenderFactory

    // ---- internals ----

    // credentials().all() is a suspend DAO call; these context methods are
    // blocking (the sheets already call them off the main thread), so bridge
    // with runBlocking.
    /** Names of the cryptographic checks that FAILED in the offline matrix
     *  (for a precise self-check error). */
    private fun failingChecks(m: LocalCheckMatrix): String {
        val named = listOf(
            "headerSig" to m.headerSigValid,
            "merkle" to m.merkleValid,
            "challengeSig" to m.challengeSigValid,
            "timestamp" to m.timestampValid,
            "expiry" to m.expiryValid,
            "verifierRequest" to m.verifierRequestValid,
        )
        val fails = named.filter { it.second.isFail }.map { it.first }
        return if (fails.isEmpty()) "none" else fails.joinToString(",")
    }

    private fun heldCredentials(): List<CredentialEntity> =
        runCatching {
            kotlinx.coroutines.runBlocking { MaknoonStore.open(appContext).credentials().all() }
        }.getOrDefault(emptyList())

    /**
     * A credential matches iff it satisfies the issuer clause, the schema clause,
     * and every required claim is present (value not inspected; predicates are
     * out of scope per ADR-0028). Mirrors MatchMaknoon / iOS MatchingEngine;
     * replicated here because MatchMaknoon is internal to the ui.present package.
     */
    private fun matchesFilter(c: CredentialEntity, f: VerifierFilter): Boolean {
        f.issuers?.let { if (!clausePasses(it, c.issuerDid)) return false }
        f.schemas?.let { if (!clausePasses(it, c.schema)) return false }
        if (f.requiredClaims.isNotEmpty()) {
            val parsed = runCatching { ParsedCredential.parse(c.credentialJson) }.getOrNull() ?: return false
            for (required in f.requiredClaims) {
                if (!parsed.claims.containsKey(required)) return false
            }
        }
        return true
    }

    /** Unknown clause modes fail closed (matches iOS). */
    private fun clausePasses(clause: VerifierFilterClause, value: String): Boolean = when (clause.mode) {
        "wildcard" -> true
        "allow" -> (clause.list ?: emptyList()).contains(value)
        else -> false
    }
}
