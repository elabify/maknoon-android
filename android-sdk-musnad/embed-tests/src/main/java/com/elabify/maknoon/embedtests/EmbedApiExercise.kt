package com.elabify.maknoon.embedtests

import android.content.Context
import android.net.Uri
import com.elabify.maknoon.Asset
import com.elabify.maknoon.Chain
import com.elabify.maknoon.CredentialDetail
import com.elabify.maknoon.CredentialID
import com.elabify.maknoon.CredentialStatus
import com.elabify.maknoon.CredentialSummary
import com.elabify.maknoon.Delegation
import com.elabify.maknoon.DelegationPolicy
import com.elabify.maknoon.DelegationScope
import com.elabify.maknoon.DeviceKind
import com.elabify.maknoon.DeviceRef
import com.elabify.maknoon.DiscoveredDevice
import com.elabify.maknoon.EnrollOptions
import com.elabify.maknoon.IdentityStatus
import com.elabify.maknoon.IssuancePreview
import com.elabify.maknoon.IssuerStatus
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonLogSink
import com.elabify.maknoon.MaknoonSDK
import com.elabify.maknoon.MusnadDeepLink
import com.elabify.maknoon.Network
import com.elabify.maknoon.NetworkKind
import com.elabify.maknoon.PassportFields
import com.elabify.maknoon.PassportReadSession
import com.elabify.maknoon.PresentationRequest
import com.elabify.maknoon.PresentationVerdict
import com.elabify.maknoon.ReceiveAddress
import com.elabify.maknoon.ReceivedPresentation
import com.elabify.maknoon.SendRequest
import com.elabify.maknoon.SignedTx
import com.elabify.maknoon.Signer
import com.elabify.maknoon.TxHash
import com.elabify.maknoon.UnsignedTx
import com.elabify.maknoon.VerificationResult
import com.elabify.maknoon.WalletAccount
import kotlin.time.Duration.Companion.hours

/**
 * Compile-time proof that the entire MaknoonSDK facade is consumable from a module that depends
 * ONLY on the SDK (this module has no visibility into `com.elabify.musnad` internals). Every
 * public method is referenced here; if the facade were incomplete, or leaked an internal type
 * into a public signature, this module would not compile. It also doubles as copy-paste
 * integrator documentation. It is not executed as a unit test: on-device behaviour is exercised
 * via instrumented tests on a real device.
 */
object EmbedApiExercise {

    fun sampleConfig(): MaknoonConfig = MaknoonConfig(
        mode = MaknoonConfig.Mode.SOFTWARE_ONLY,
        allowedIssuerHosts = setOf("issuer.example"),
        pinnedFingerprintsByHost = mapOf("issuer.example" to setOf(ByteArray(32))),
        chainRpcUrl = "https://sepolia.example/rpc",
        logSink = MaknoonLogSink { },
    )

    @Suppress("UNUSED_VARIABLE")
    suspend fun exerciseEverything(context: Context) {
        val sdk: MaknoonSDK = MaknoonSDK.init(context, sampleConfig())
        val version: String = MaknoonSDK.version
        val link: MusnadDeepLink = sdk.classify(Uri.parse("https://issuer.example/pickup/abc"))

        // ----- Identity (Musnad) -----
        val did: String = sdk.identity.holderDid()
        val hasDelegation: Boolean =
            sdk.identity.hasActiveDelegation(DelegationPolicy(setOf(DelegationScope.PRESENT_CREDENTIALS)))
        val delegation: Delegation = sdk.identity.createDelegation(24.hours, listOf(DelegationScope.SIGN_CHALLENGE))
        sdk.identity.revokeActiveDelegation()
        val status: IdentityStatus = sdk.identity.currentStatus()

        // ----- Credentials (Musnad) -----
        val summaries: List<CredentialSummary> = sdk.credentials.listCredentials()
        val cid: CredentialID = summaries.firstOrNull()?.id ?: CredentialID("cid")
        val detail: CredentialDetail = sdk.credentials.credentialDetail(cid)
        val disclosedCount: Int = sdk.credentials.disclose(cid, setOf("name", "dob")) { claims -> claims.size }
        val credStatus: CredentialStatus = sdk.credentials.refreshStatus(cid)

        // ----- Issuance (Musnad, e-passport) -----
        val preview: IssuancePreview = sdk.issuance.resolvePickup(Uri.parse("https://issuer.example/pickup/abc"))
        val accepted: CredentialID = sdk.issuance.acceptIssuance(preview)
        val passport: PassportFields = sdk.issuance.readPassport(object : PassportReadSession {})
        val passportCid: CredentialID =
            sdk.issuance.submitPassportIssuance("did:elabify:sepolia:issuer:musnad", passport)

        // ----- Presentation (Musnad) + tiered verification -----
        val request: PresentationRequest = sdk.presentation.resolveRequest(Uri.parse("https://verifier.example/req/1"))
        val verdict: PresentationVerdict = sdk.presentation.present(request, setOf("name"), includePiiHandoff = false)
        val verification: VerificationResult = sdk.presentation.verifyReceived(ReceivedPresentation("{}"))

        // ----- Chain reads (Musnad) -----
        val issuer: IssuerStatus = sdk.chain.issuerStatus(did)
        val onchainStatus: CredentialStatus = sdk.chain.credentialStatus(did, ByteArray(32))

        // ----- Wallet (Maknoon): every network, one shape -----
        val baseSepolia = Network.Ethereum(Chain(id = "84532", label = "Base Sepolia", isTestnet = true))
        val evmChains: List<Chain> = sdk.wallet.chains(NetworkKind.ETHEREUM)
        val accounts: List<WalletAccount> = sdk.wallet.accounts(baseSepolia)
        val account: WalletAccount = accounts.first()
        val assets: List<Asset> = sdk.wallet.assets(account)
        val receive: ReceiveAddress = sdk.wallet.receiveAddress(account)
        val unsigned: UnsignedTx = sdk.wallet.buildSend(SendRequest(account, toAddress = "0xabc", amount = "1000"))
        val txHash: TxHash = sdk.wallet.signAndBroadcast(unsigned, Signer.Software)
        // The other four networks are expressed identically (Network + concrete Chain):
        val otherNetworks: List<Network> = listOf(
            Network.Bitcoin(Chain("testnet3", "Testnet3", isTestnet = true)),
            Network.Solana(Chain("devnet", "Devnet", isTestnet = true)),
            Network.Tron(Chain("nile", "Nile", isTestnet = true)),
            Network.Lightning(Chain("mainnet", "Mainnet", isTestnet = false)),
        )

        // ----- Hardware (Maknoon) -----
        val devices: List<DeviceRef> = sdk.hardware.enrolledDevices()
        val discovered = sdk.hardware.discover(DeviceKind.LEDGER, com.elabify.maknoon.Transport.BLE)
        val enrolled: DeviceRef = sdk.hardware.enroll(
            DiscoveredDevice(DeviceKind.LEDGER, com.elabify.maknoon.Transport.BLE, "Ledger Nano X", "ble-handle"),
            EnrollOptions(label = "My Ledger"),
        )
        val signed: SignedTx = sdk.hardware.sign(unsigned, enrolled)
        // A device becomes an identity second factor via the Musnad-identity op, not a hardware op:
        sdk.identity.enrollSecondFactor(enrolled)
    }
}
