// "poolAccess" namespace (window.maknoon.poolAccess). Android port of the iOS
// PoolAccessBridgeHandler.swift.
//
// poolAccess.grant({ issuerUrl, issuerDid, chain, gateAddress? }) performs the whole
// credential-gated access grant NATIVELY, mirroring commerce.collectAndCharge
// (disclose + sign + submit in one confirm):
//   1. match a passport, sanctions-clean credential from the holder's wallet;
//   2. gather consent + a credential pick via the identity approval sheet;
//   3. build a signed presentation (SDK PresentationBuilder);
//   4. prove control of the active EVM address with an EIP-712 WalletControl signature;
//   5. POST both to the Access Issuer's /v1/networks/{chain}/access-issuer/grant, which
//      provisions the ONCHAINID + writes the ERC-735 claim (ADR-0058).
//
// This is an ISSUER action: verifying the credential is only the gate; writing on-chain
// access is issuance, so the WalletControl proof binds the ISSUER DID and the request goes
// to the issuer server (not the verifier). The presentation and all key material stay
// native; the mini-app JS only ever receives { granted, walletAddress, txHash, expiry }.
// The server, not the page, decides GRANT/DENY (it re-verifies both).

package com.elabify.app.maknoon.miniapp

import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.net.ChallengeContext
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import com.elabify.musnad.net.VerifierClient
import com.elabify.musnad.present.MatchingEngine
import com.elabify.musnad.present.ParsedCredential
import com.elabify.musnad.present.PresentationBuilder
import com.elabify.musnad.present.VerifierFilter
import com.elabify.musnad.present.VerifierFilterClause
import com.elabify.app.maknoon.ui.wallet.ethereum.signEthereumHardwareTypedData
import com.elabify.musnad.wallet.ethereum.EthereumDescriptors
import com.elabify.musnad.wallet.ethereum.EthereumWalletKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PoolAccessBridgeHandler(
    /** EVM wallet + unlocked sandwich source (shared with the eth handler). */
    private val env: MiniAppWeb3Environment,
    private val gate: ApprovalGate,
    private val appTitle: String,
    /** Loads the holder's stored credentials (Room), like IdentityBridgeHandler. */
    private val loadCredentials: suspend () -> List<CredentialEntity>,
) : MiniAppNamespaceHandler {

    override val namespace = "poolAccess"

    // Discloses a credential, so it is gated by the identity permission (it also
    // uses the EVM wallet; the app additionally declares wallet.ethereum.*).
    override val requiredPermission: String? = "identity"

    private companion object {
        // Passport schema + sanctions claim the pool gate requires (personhood +
        // not-sanctioned; matches PASSPORT_SCHEMA_URI in the verifier's pool-access.ts).
        const val PASSPORT_SCHEMA = "elabify://schema/global/passport/v1"
        const val SDN_CLAIM = "sdnScreen"
    }

    override suspend fun handle(method: String, argsJson: String): String = when (method) {
        "poolAccess.grant" -> grant(argsJson)
        else -> throw MiniAppBridgeError.unsupported("poolAccess.$method")
    }

    private suspend fun grant(argsJson: String): String {
        val opts = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: throw MiniAppBridgeError.invalidParams("poolAccess.grant requires { issuerUrl }")
        // Issuer-centric params (ADR-0058). Legacy verifier* aliases are still accepted
        // so an already-published mini-app bundle keeps working.
        val issuerUrl = (opts.optStringOrNull("issuerUrl") ?: opts.optStringOrNull("verifierUrl"))
            ?: throw MiniAppBridgeError.invalidParams("poolAccess.grant requires { issuerUrl }")
        // Bound into both the presentation audience and the WalletControl proof; the
        // Access Issuer checks the proof against its own ELABIFY_ISSUER_DID.
        val issuerDid = opts.optStringOrNull("issuerDid")
            ?: opts.optStringOrNull("verifierDid")
            ?: PresentationBuilder.OPEN_VERIFIER_DID
        // CAIP-2 of the target chain whose ONCHAINID stack issues the access claim.
        val chain = opts.optStringOrNull("chain") ?: "eip155:84532"

        val sandwich = env.sandwich()
            ?: throw MiniAppBridgeError.unauthorized("wallet is locked")

        // 1. A passport, sanctions-screened credential must exist.
        val requiredClaims = listOf(SDN_CLAIM)
        val filter = VerifierFilter(
            issuers = null,
            schemas = VerifierFilterClause(mode = "allow", list = listOf(PASSPORT_SCHEMA)),
            requiredClaims = requiredClaims,
        )
        val candidates = withContext(Dispatchers.IO) { loadCredentials() }
            .mapNotNull { e -> runCatching { ParsedCredential.parse(e.credentialJson) }.getOrNull()?.let { e to it } }
        val matches = candidates.filter { MatchingEngine.matches(it.second, filter) }
        if (matches.isEmpty()) {
            return JSONObject().put("granted", false).put("reason", "no_passport_credential").toString()
        }

        // 2. Resolve the active EVM wallet up front so the consent sheet can show
        //    the address being shared and its permanent KYC association.
        val descriptor = env.activeWallet
            ?: throw MiniAppBridgeError.unauthorized("no active Ethereum wallet in this app")
        val walletAddress = descriptor.address?.takeIf { it.isNotEmpty() }
            ?: throw MiniAppBridgeError.unauthorized("active Ethereum wallet has no address")
        val account = when (val kind = descriptor.kind) {
            is EthereumWalletKind.Software -> kind.account
            is EthereumWalletKind.Hardware -> kind.account
        }

        // 3. Consent (+ credential pick) via the identity sheet, which now shows the
        //    recipient host, the disclosed sdnScreen value (expanded), the holder 0x
        //    per credential, and the wallet-address permanence warning.
        val recipientHost = runCatching { java.net.URI(issuerUrl).host }.getOrNull() ?: issuerUrl
        val credArr = JSONArray()
        matches.forEach { (e, parsed) ->
            val sdn = runCatching {
                when (val v = JSONObject(e.credentialJson).optJSONObject("claims")?.opt(SDN_CLAIM)) {
                    is JSONObject -> v.toString(2)
                    null -> null
                    else -> v.toString()
                }
            }.getOrNull()
            credArr.put(
                JSONObject()
                    .put("cid", e.cid)
                    .put("label", e.nickname?.takeIf { it.isNotEmpty() } ?: e.schema)
                    .put("holder", parsed.header.sub)
                    // Issue date distinguishes two passports whose label, holder
                    // and issuer are all identical.
                    .put("issuedAt", parsed.header.iat)
                    .apply { if (sdn != null) put("sdn", sdn) },
            )
        }
        val payload = JSONObject()
            .put("appTitle", appTitle)
            .put("purpose", "Verify to access the pool")
            .put("requiredClaims", JSONArray(requiredClaims))
            .put("credentials", credArr)
            .put("recipientHost", recipientHost)
            .put("walletAddress", walletAddress)
            .put("showsDisclosedValues", true)
        val sheetResult = gate.request(kind = "identity", payloadJson = payload.toString(), appTitle = appTitle)
        val chosenCid = JSONObject(sheetResult).optStringOrNull("cid")
        val chosen = (chosenCid?.let { cid -> matches.firstOrNull { it.first.cid == cid } } ?: matches.first()).second

        // 4. Server challenge -> signed presentation (kept for the grant endpoint,
        //    not sent to /v1/verify). The challenge signature must bind the DID the
        //    server minted the challenge under (challengeSig is checked against the
        //    server's verifier DID), which can differ from the issuer DID used for
        //    the wallet-control proof below.
        val ch = withContext(Dispatchers.IO) { VerifierClient(issuerUrl).challenge(requiredClaims) }
        val presentation = withContext(Dispatchers.IO) {
            PresentationBuilder.build(
                credential = chosen,
                selectedClaims = requiredClaims.toSet(),
                challenge = ch.challenge,
                verifierDid = ch.verifierDid ?: issuerDid,
                pendingRequest = null,
                sandwich = sandwich,
            )
        }

        // 5. EIP-712 WalletControl proof binding this address to the holder + nonce.
        val typedDataJson = walletControlTypedData(
            holderDid = presentation.header.sub,
            verifierDid = issuerDid,
            nonce = presentation.challenge,
            walletAddress = walletAddress,
        )
        // Signed by the active wallet, software or hardware; the Access Issuer
        // verifies it identically (recover signer == wallet address). For a
        // hardware wallet, prepare the device + collect the hidden-wallet
        // passphrase BEFORE opening BLE (a null prompt is what made a hidden
        // Trezor hang / throw "enter passphrase"); software wallets skip the sheet.
        val hwDevice = (descriptor.kind as? EthereumWalletKind.Hardware)?.let { hw ->
            env.device(hw.deviceId)
                ?: throw MiniAppBridgeError.unauthorized("the paired device for this wallet was not found")
        }
        val hostPassphrase = hwDevice?.let { gate.requestHardwareSign(it, descriptor.hidden, appTitle) }
        val signature = try {
            withContext(Dispatchers.IO) {
                try {
                    if (hwDevice == null) {
                        EthereumDescriptors.signTypedData(
                            words = sandwich.recoveryWords(),
                            passphrase = sandwich.bip39Passphrase(),
                            account = account,
                            typedDataJson = typedDataJson,
                            derivationPath = descriptor.derivationPath,
                        )
                    } else {
                        signEthereumHardwareTypedData(
                            device = hwDevice,
                            account = account,
                            typedDataJson = typedDataJson,
                            hidden = descriptor.hidden,
                            derivationPath = descriptor.derivationPath,
                            hostPassphrase = hostPassphrase,
                        )
                    }
                } catch (e: MiniAppBridgeError) {
                    throw e
                } catch (e: Throwable) {
                    throw MiniAppBridgeError.internalError(e.message ?: "wallet-control signing failed")
                }
            }
        } finally {
            // Dismiss the held "waiting for your device" sheet once the sign
            // completes or fails (no-op for the software path).
            if (hwDevice != null) gate.release()
        }

        // 6. Submit to the verifier's grant endpoint (writes the on-chain grant).
        val body = JSONObject()
            .put("v", 1)
            .put("challengeContext", ChallengeContext(ch.requestId, ch.issuedAt, ch.expiresAt).toJson())
            .put("presentation", presentation.toJson())
            .put(
                "ethProof",
                JSONObject()
                    .put("walletAddress", walletAddress)
                    .put("signature", signature)
                    .put("addressType", "eoa"),
            )
            .toString()
        // Access Issuer endpoint, per target chain. Encode the CAIP-2 colon so the whole
        // id is one path segment (the server decodeURIComponent's :caip2).
        val encodedChain = chain.replace(":", "%3A")
        val grantUrl = issuerUrl.trimEnd('/') + "/v1/networks/$encodedChain/access-issuer/grant"
        val respStr = withContext(Dispatchers.IO) {
            try {
                MaknoonHttp().postJson(grantUrl, body, readTimeoutSec = 120)
            } catch (e: NetworkException) {
                throw MiniAppBridgeError.internalError(poolAccessError(e.body) ?: "pool-access grant failed (${e.status})")
            } catch (e: MiniAppBridgeError) {
                throw e
            } catch (e: Throwable) {
                throw MiniAppBridgeError.internalError(e.message ?: "pool-access grant failed")
            }
        }
        val out = JSONObject(respStr)
        return JSONObject().apply {
            put("granted", out.optString("decision") == "GRANT")
            put("walletAddress", out.optStringOrNull("walletAddress") ?: walletAddress)
            put("txHash", if (out.isNull("txHash")) JSONObject.NULL else out.optString("txHash"))
            if (out.has("expiry") && !out.isNull("expiry")) put("expiry", out.optLong("expiry"))
        }.toString()
    }

    /** The exact eth_signTypedData_v4 JSON the issuer's verifyWalletControl
     *  re-derives: domain {name, version}, WalletControl(holderDid, verifierDid,
     *  nonce, walletAddress). walletAddress binds the signed intent to the exact
     *  address being registered (defense-in-depth); the issuer also accepts the
     *  legacy 3-field struct during rollout (ADR-0065 hardening). */
    private fun walletControlTypedData(holderDid: String, verifierDid: String, nonce: String, walletAddress: String): String {
        val types = JSONObject()
            .put(
                "EIP712Domain",
                JSONArray()
                    .put(JSONObject().put("name", "name").put("type", "string"))
                    .put(JSONObject().put("name", "version").put("type", "string")),
            )
            .put(
                "WalletControl",
                JSONArray()
                    .put(JSONObject().put("name", "holderDid").put("type", "string"))
                    .put(JSONObject().put("name", "verifierDid").put("type", "string"))
                    .put(JSONObject().put("name", "nonce").put("type", "string"))
                    .put(JSONObject().put("name", "walletAddress").put("type", "string")),
            )
        return JSONObject()
            .put("types", types)
            .put("primaryType", "WalletControl")
            .put("domain", JSONObject().put("name", "MaknoonPoolAccess").put("version", "1"))
            .put(
                "message",
                JSONObject().put("holderDid", holderDid).put("verifierDid", verifierDid)
                    .put("nonce", nonce).put("walletAddress", walletAddress),
            )
            .toString()
    }

    private fun poolAccessError(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("error")?.optStringOrNull("message") }.getOrNull()
}
