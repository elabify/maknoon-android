// The Identity tab, the primary home for verified credentials. With no identity
// it shows the onboarding wizard (OnboardingScreen); once an identity exists it
// becomes the iOS IdentityView: a CREDENTIALS-ONLY screen. It shows the unified
// Apple-Wallet stack of verified-credential cards (read from the SDK) plus saved
// ID-document cards, an EmptyState when there are none, and a toolbar with a
// LEADING gear that opens the global Settings hub and a TRAILING "+" menu of
// quick actions (Receive credential, Scan verifier, Verify someone, Tap ID
// document). Driven by the SDK IdentitySandwich plus the encrypted MaknoonStore
// credentials DAO and the IDDocumentStore.
//
// To match iOS, the invented holder-identity hero card and the recovery-phrase
// reveal are NOT here: recovery now lives only under Settings > Local Key.
// Hardware devices also moved out of this tab's + menu into Settings > Devices,
// reached via the gear.
//
// Routing is in-screen state (the existing sealed-route + BackHandler pattern),
// so the system back button pops the open sub-route instead of exiting the app.
// Pending issuance pickups mirror iOS PendingPickupsStore: a PendingPickupsStore
// (SharedPreferences-backed) holds credentials the issuer has minted but not yet
// imported, the Identity tab polls + imports them on load and every ~10s, and a
// pending row shows at the top of the hub with a cancel option. Folders remain
// iOS-only (CredentialFolderStore has no Android analog), so that one section is
// still intentionally omitted.

package com.elabify.app.maknoon.ui.identity

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elabify.app.maknoon.R
import com.elabify.app.maknoon.iddocument.CSCATrustStore
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.IDDocumentIssuanceClient
import com.elabify.app.maknoon.iddocument.IDDocumentNfcReaderMode
import com.elabify.app.maknoon.iddocument.IDDocumentReader
import com.elabify.app.maknoon.iddocument.IDDocumentReaderError
import com.elabify.app.maknoon.iddocument.IDDocumentStore
import com.elabify.app.maknoon.iddocument.IssuerSelection
import com.elabify.app.maknoon.iddocument.LocalCredentialFactory
import com.elabify.app.maknoon.iddocument.PassiveAuthResult
import com.elabify.app.maknoon.iddocument.PassportPassiveAuthVerifier
import com.elabify.app.maknoon.iddocument.SanctionsScreeningClient
import com.elabify.app.maknoon.ui.iddocument.IDDocumentDetailScreen
import com.elabify.app.maknoon.ui.iddocument.IDDocumentIssuanceOutcome
import com.elabify.app.maknoon.ui.iddocument.PassportCardDetailScreen
import com.elabify.app.maknoon.ui.iddocument.PassportPairing
import com.elabify.app.maknoon.ui.iddocument.PassportShare
import com.elabify.app.maknoon.ui.components.qrBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.elabify.app.maknoon.ui.iddocument.TapIDDocumentScreen
import com.elabify.app.maknoon.ui.iddocument.toIssuanceOutcome
import com.elabify.app.maknoon.ui.iddocument.toPassportIssuanceInput
import com.elabify.app.maknoon.ui.settings.KnownIssuersStore
import com.elabify.app.maknoon.ui.present.CredentialPresentScreen
import com.elabify.app.maknoon.ui.present.PresentMode
import com.elabify.app.maknoon.ui.present.PresentScanActions
import com.elabify.app.maknoon.ui.present.PostOutcome
import com.elabify.app.maknoon.ui.present.ScanVerifierSheet
import com.elabify.app.maknoon.ui.present.VerifyOtherActions
import com.elabify.app.maknoon.ui.present.VerifyOtherSheet
import com.elabify.app.maknoon.ui.present.VerdictBundle
import com.elabify.app.maknoon.ui.present.parsed
import com.elabify.app.maknoon.miniapp.CommerceRequest
import com.elabify.app.maknoon.miniapp.CommerceTransport
import com.elabify.app.maknoon.miniapp.MiniAppHosts
import com.elabify.app.maknoon.miniapp.RealCommerceHolderContext
import com.elabify.app.maknoon.ui.miniapp.CommercePayHost
import com.elabify.musnad.net.MaknoonHttp
import com.elabify.musnad.net.NetworkException
import com.elabify.musnad.net.PresentationDrop
import com.elabify.musnad.present.Presentation
import com.elabify.musnad.present.PresentationBuilder
import com.elabify.musnad.present.PresentationVerifier
import com.elabify.musnad.present.VerifierRequestValidator
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import android.nfc.tech.IsoDep
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.elabify.app.maknoon.ui.components.Banner
import com.elabify.app.maknoon.ui.components.BannerVariant
import com.elabify.app.maknoon.ui.components.CardPalette
import com.elabify.app.maknoon.ui.components.CredentialCard
import com.elabify.app.maknoon.ui.components.CredentialCardData
import com.elabify.app.maknoon.ui.components.CredentialCardDefaults
import com.elabify.app.maknoon.ui.components.EmptyState
import com.elabify.app.maknoon.ui.components.IdDocumentCard
import com.elabify.app.maknoon.ui.components.IdDocumentCardData
import com.elabify.app.maknoon.ui.components.SectionHeader
import com.elabify.app.maknoon.ui.components.StatusLevel
import com.elabify.app.maknoon.ui.components.expiryStatus
import com.elabify.app.maknoon.ui.BiometricGate
import com.elabify.app.maknoon.ui.onboarding.OnboardingScreen
import com.elabify.app.maknoon.ui.present.schemaLabel
import com.elabify.app.maknoon.ui.present.shortIssuerName
import com.elabify.app.maknoon.ui.settings.SettingsScreen
import com.elabify.app.maknoon.ui.theme.Radii
import com.elabify.app.maknoon.ui.theme.Spacing
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.devices.DeviceRegistry
import com.elabify.musnad.identity.IdentitySandwich
import com.elabify.musnad.identity.IdentitySession
import com.elabify.musnad.identity.IdentityStore
import com.elabify.musnad.present.ParsedCredential
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext

// The passport schema; a self-issued passport credential (issuer == holder) is
// represented by its saved ID-document card, so we hide its duplicate VC card
// the way iOS does.
private const val PASSPORT_SCHEMA = "elabify://schema/global/passport/v1"

// In-screen routes layered above the hub. Each is popped by a BackHandler.
/** scheme://host[:port] of a request URL, where the sealed response is POSTed
 *  (mirrors iOS ScanVerifierSheet.origin). */
private fun commerceResponseBaseURL(url: String): String = try {
    val u = java.net.URI(url)
    val port = if (u.port >= 0) ":${u.port}" else ""
    "${u.scheme}://${u.host}$port"
} catch (_: Exception) {
    url
}

private sealed interface IdentityRoute {
    object Hub : IdentityRoute
    object Settings : IdentityRoute
    object Receive : IdentityRoute
    object ScanVerifier : IdentityRoute
    /** Cross-device Verify & Pay: the scanned URL resolves to a CommerceRequest. */
    data class CommercePay(val url: String) : IdentityRoute
    object VerifyOther : IdentityRoute
    object TapIdDocument : IdentityRoute
    data class Credential(val cid: String) : IdentityRoute
    data class Document(val id: UUID) : IdentityRoute
}

// A unified, display-ready hub card: a verified credential or a saved passport.
// Sorting mirrors iOS (type, then nickname, then issuer, case-insensitive).
private sealed interface HubCard {
    val sortType: String
    val sortNick: String
    val sortIssuer: String

    data class Credential(
        val entity: CredentialEntity,
        val data: CredentialCardData,
        override val sortType: String,
        override val sortNick: String,
        override val sortIssuer: String,
    ) : HubCard

    data class Document(
        val document: IDDocument,
        val data: IdDocumentCardData,
        override val sortType: String,
        override val sortNick: String,
        override val sortIssuer: String,
    ) : HubCard
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityScreen(
    resetKey: Int = 0,
    /** After a Verify & Pay settles, switch to the Wallet tab + open the chain
     *  the payer paid from (chain key, e.g. "bitcoin"). */
    onNavigateToWallet: (chain: String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { IdentityStore(context) }
    val deviceRegistry = remember { DeviceRegistry(context) }
    val idDocumentStore = remember { IDDocumentStore.shared(context) }
    val knownIssuers = remember { KnownIssuersStore(context) }
    // Pending issuance pickups (iOS PendingPickupsStore): credentials minted on
    // the issuer side that have not been picked up + imported yet. Polled below.
    val pendingPickups = remember { PendingPickupsStore(context) }
    // The issuer the document flows target: the first trusted known issuer (the
    // picker lives in Settings > Identity). Null only when none is configured.
    val issuerBaseUrl = remember { IssuerSelection.resolveBaseUrl("", "", knownIssuers) }
    // Credential folders (iOS parity): a local organizer for the credential stack.
    // activeFolderId == null is the "All" root; folderVersion bumps to force the
    // hub + detail picker to re-read after any folder mutation.
    val folderStore = remember { CredentialFolderStore(context) }
    var activeFolderId by remember { mutableStateOf<String?>(null) }
    var folderVersion by remember { mutableIntStateOf(0) }

    var reloadKey by remember { mutableIntStateOf(0) }
    // Seed from the process-wide IdentitySession cache so returning to the
    // Identity tab (the screen is fully disposed on a tab switch) reuses the
    // already-derived sandwich instead of re-running the ML-DSA-65 keygen, and
    // does NOT flash a spinner when the cache is warm.
    val cachedSandwich = remember { IdentitySession.peek() }
    var holderDid by remember { mutableStateOf<String?>(cachedSandwich?.holderDid) }
    // The unlocked identity, kept for the present flows (which need the sandwich
    // to sign presentations). Null until loaded / when locked.
    var sandwich by remember { mutableStateOf<IdentitySandwich?>(cachedSandwich) }
    var busy by remember { mutableStateOf(cachedSandwich == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<IdentityRoute>(IdentityRoute.Hub) }
    // Re-tap-to-home: re-tapping the Identity tab while already on it pops any
    // pushed sub-route back to the hub (mirrors iOS clearing the nav path).
    LaunchedEffect(resetKey) {
        if (resetKey > 0 && route != IdentityRoute.Hub) route = IdentityRoute.Hub
    }
    // The self-signed credential minted on demand from a saved passport for the
    // Present (QR) flow. Non-null while the present sheet is up; the credential
    // is not persisted as a wallet card (iOS presentCredential parity).
    var presentedSelfCredential by remember { mutableStateOf<ParsedCredential?>(null) }
    // Mode the present sheet opens in. The passport navy screen's "Share QR"
    // defaults to ATTRIBUTES (all attributes, redactable); other present entry
    // points keep the privacy BADGE default.
    var presentInitialMode by remember { mutableStateOf(PresentMode.BADGE) }
    // When the present sheet was launched from the passport navy card: lean mode
    // (no Privacy-QR picker, nickname, technical, folder, or remove).
    var presentPassportMode by remember { mutableStateOf(false) }
    val activity = context as? FragmentActivity

    // Verified credentials read from the encrypted DAO. Reloaded whenever the
    // identity reloads (reloadKey) so a freshly received credential surfaces.
    var credentials by remember { mutableStateOf<List<CredentialEntity>>(emptyList()) }

    LaunchedEffect(reloadKey) {
        // Only block on the spinner for a genuine load (cold cache); a warm
        // cache resolves synchronously below with no visible pause.
        if (IdentitySession.peek() == null) busy = true
        val loaded = withContext(Dispatchers.IO) { runCatching { IdentitySession.loadCached(store) }.getOrNull() }
        sandwich = loaded
        holderDid = loaded?.holderDid
        credentials = if (loaded == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val db = MaknoonStore.open(context)
                    db.credentials().all()
                }.getOrDefault(emptyList())
            }
        }
        busy = false
    }

    val documents by idDocumentStore.documents.collectAsState()
    val pending by pendingPickups.pending.collectAsState()

    // Auto-poll the issuer for any pending pickups on Identity-tab load and then
    // every ~10s while entries remain (iOS PendingPickupsStore poll loop). A
    // Ready credential is imported into the DAO inside the store; we bump
    // reloadKey so the freshly imported card surfaces on the hub.
    LaunchedEffect(pending.isNotEmpty()) {
        // Read the live store value (not the captured snapshot) so a pickup
        // queued while this loop is already running is polled too.
        while (pendingPickups.pending.value.isNotEmpty()) {
            val imported = pendingPickups.pollOnce()
            if (imported) reloadKey++
            if (pendingPickups.pending.value.isEmpty()) break
            kotlinx.coroutines.delay(PendingPickupsStore.POLL_INTERVAL_MS)
        }
    }

    // Self-signed passport present sheet. Layered above the Document detail
    // (and the hub) with its own BackHandler so the present QR can be dismissed
    // back to the document. The minted credential is a normal ParsedCredential,
    // so the existing per-credential present surface renders + signs it: BADGE
    // mode shows a PII-safe QR, ATTRIBUTES mode signs a full disclosure with the
    // ephemeral key (no second factor). Nickname / folder / remove are no-ops
    // because this credential is ephemeral (not a saved wallet card).
    val selfCred = presentedSelfCredential
    if (selfCred != null) {
        val sw = sandwich
        if (sw == null) {
            // Sandwich locked out from under us; drop the sheet rather than crash.
            LaunchedEffect(Unit) { presentedSelfCredential = null }
            return
        }
        BackHandler { presentedSelfCredential = null }
        CredentialPresentScreen(
            credential = selfCred,
            nickname = null,
            folderName = stringResource(R.string.identity_all_credentials),
            availableFolders = emptyList(),
            currentFolderId = null,
            pendingRequest = null,
            initialMode = presentInitialMode,
            passportMode = presentPassportMode,
            sandwich = sw,
            dropHost = MiniAppHosts.DROP_HOST,
            onSetNickname = { /* self-signed credential is not a saved card */ },
            onAssignFolder = { /* folders are iOS-only; no-op on Android */ },
            onRemove = { presentedSelfCredential = null },
            onShared = { _, _, _ -> /* no verifier-history for self-presentation */ },
            onBack = { presentedSelfCredential = null },
        )
        return
    }

    when (val current = route) {
        is IdentityRoute.Settings -> {
            BackHandler { route = IdentityRoute.Hub }
            SettingsScreen(deviceRegistry = deviceRegistry, onBack = { route = IdentityRoute.Hub })
            return
        }
        is IdentityRoute.Receive -> {
            BackHandler { route = IdentityRoute.Hub }
            ReceiveCredentialScreen(
                receive = { pickupUrl, onStatus ->
                    // Derive the issuer origin from the pickup URL for the client.
                    val origin = runCatching {
                        val u = java.net.URI(pickupUrl)
                        val port = if (u.port > 0) ":${u.port}" else ""
                        "${u.scheme}://${u.host}$port"
                    }.getOrDefault(pickupUrl)
                    val client = com.elabify.musnad.net.IssuerClient(origin)
                    var attempt = 0
                    var imported = false
                    while (!imported && attempt < 30) {
                        attempt++
                        onStatus("Checking the issuer (attempt $attempt)…")
                        val outcome = withContext(Dispatchers.IO) { client.pickup(pickupUrl) }
                        when (outcome) {
                            is com.elabify.musnad.net.PickupOutcome.Ready -> {
                                val parsed = com.elabify.musnad.present.ParsedCredential.parse(outcome.credentialJson)
                                val entity = CredentialEntity(
                                    cid = parsed.header.cid,
                                    issuerDid = parsed.header.iss,
                                    subjectDid = parsed.header.sub,
                                    schema = parsed.header.schema,
                                    credentialJson = outcome.credentialJson,
                                    nickname = null,
                                    createdAt = System.currentTimeMillis(),
                                )
                                // De-dup (ADR-0037): a credential is identified by its
                                // content id (cid). If it is already saved, show a
                                // friendly message instead of silently re-importing.
                                val alreadySaved = withContext(Dispatchers.IO) {
                                    MaknoonStore.open(context).credentials().all().any { it.cid == entity.cid }
                                }
                                if (alreadySaved) {
                                    onStatus("This credential is already in your wallet.")
                                } else {
                                    withContext(Dispatchers.IO) {
                                        MaknoonStore.open(context).credentials().upsert(entity)
                                    }
                                    onStatus("Credential received")
                                }
                                imported = true
                            }
                            is com.elabify.musnad.net.PickupOutcome.Pending -> {
                                onStatus("Issuer is still anchoring. Retrying in 10s…")
                                kotlinx.coroutines.delay(10_000)
                            }
                        }
                    }
                    if (!imported) {
                        throw IllegalStateException("Timed out waiting for the issuer to anchor the credential.")
                    }
                },
                onReceived = {
                    reloadKey++
                    route = IdentityRoute.Hub
                },
                onClose = { route = IdentityRoute.Hub },
            )
            return
        }
        is IdentityRoute.ScanVerifier -> {
            BackHandler { route = IdentityRoute.Hub }
            val scanActions = remember {
                object : PresentScanActions {
                    override suspend fun heldCredentials(): List<CredentialEntity> =
                        withContext(Dispatchers.IO) { MaknoonStore.open(context).credentials().all() }

                    override suspend fun validate(scanned: String): VerifierRequestValidator.Decision? =
                        withContext(Dispatchers.IO) {
                            VerifierRequestValidator.validate(scanned, MiniAppHosts.VERIFIER_BASE_URL)
                        }

                    override suspend fun buildPresentation(
                        credential: CredentialEntity,
                        decision: VerifierRequestValidator.Decision,
                    ): Presentation {
                        val sw = withContext(Dispatchers.IO) { IdentitySandwich.load(store) }
                            ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                        val parsed = credential.parsed()
                        return withContext(Dispatchers.IO) {
                            PresentationBuilder.build(
                                credential = parsed,
                                selectedClaims = decision.request.filter.requiredClaims.toSet(),
                                challenge = decision.request.challenge,
                                verifierDid = decision.request.verifierDid,
                                pendingRequest = decision.request,
                                sandwich = sw,
                            )
                        }
                    }

                    override suspend fun postToCallback(
                        presentation: Presentation,
                        callbackUrl: String,
                    ): PostOutcome = withContext(Dispatchers.IO) {
                        try {
                            val body = MaknoonHttp().postJson(callbackUrl, presentation.toJson().toString())
                            PostOutcome(200, body.take(200))
                        } catch (e: NetworkException) {
                            PostOutcome(e.status, e.body.take(200))
                        }
                    }
                }
            }
            ScanVerifierSheet(
                actions = scanActions,
                onClose = { route = IdentityRoute.Hub },
                onCommerce = { url -> route = IdentityRoute.CommercePay(url) },
            )
            return
        }
        is IdentityRoute.CommercePay -> {
            BackHandler { route = IdentityRoute.Hub }
            val url = (route as IdentityRoute.CommercePay).url
            var request by remember(url) { mutableStateOf<CommerceRequest?>(null) }
            var loadError by remember(url) { mutableStateOf<String?>(null) }
            LaunchedEffect(url) {
                try {
                    request = withContext(Dispatchers.IO) { CommerceTransport().fetchRequest(url) }
                } catch (e: Exception) {
                    loadError = e.message ?: "Could not load the Verify & Pay request."
                }
            }
            val req = request
            when {
                loadError != null -> Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                ) {
                    Text(stringResource(R.string.identity_verify_and_pay_unavailable), style = MaterialTheme.typography.titleMedium)
                    val friendly = if (loadError!!.contains("404")) {
                        stringResource(R.string.identity_verify_and_pay_expired)
                    } else {
                        loadError!!
                    }
                    Text(
                        friendly,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = { route = IdentityRoute.Hub }) { Text(stringResource(R.string.common_close)) }
                }
                req == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val ctx = remember(url) {
                        RealCommerceHolderContext(
                            context = context,
                            dropHost = MiniAppHosts.DROP_HOST,
                            sandwichLoader = { IdentitySandwich.load(store) },
                        )
                    }
                    CommercePayHost(
                        ctx = ctx,
                        request = req,
                        responseBaseURL = commerceResponseBaseURL(url),
                        onClose = { route = IdentityRoute.Hub },
                        onNavigateToWallet = { chain ->
                            route = IdentityRoute.Hub
                            onNavigateToWallet(chain)
                        },
                    )
                }
            }
            return
        }
        is IdentityRoute.VerifyOther -> {
            BackHandler { route = IdentityRoute.Hub }
            val verifyActions = remember {
                object : VerifyOtherActions {
                    override suspend fun fetchDrop(dropId: String): Presentation {
                        if (!com.elabify.app.maknoon.ui.settings.RelaySettings.enabled) {
                            error("The presentation relay is turned off in Settings, Identity. Turn it on to fetch a shared presentation.")
                        }
                        return PresentationDrop(MiniAppHosts.DROP_HOST).fetch(dropId)
                    }

                    override fun verifyOffline(presentation: Presentation): VerdictBundle =
                        PresentationVerifier.verifyOffline(presentation)
                }
            }
            VerifyOtherSheet(actions = verifyActions, onClose = { route = IdentityRoute.Hub })
            return
        }
        is IdentityRoute.TapIdDocument -> {
            BackHandler { route = IdentityRoute.Hub }
            val activity = context as? FragmentActivity
            val reader = remember { IDDocumentReader() }
            val nfcAvailable = activity != null && IDDocumentNfcReaderMode.isAvailable(activity)
            TapIDDocumentScreen(
                nfcAvailable = nfcAvailable,
                // Drive foreground reader mode: suspend until an ICAO chip is
                // tapped to the phone, then read it. stop() releases the radio
                // whether the read succeeds or throws.
                read = { params, onProgress ->
                    val act = activity ?: throw IDDocumentReaderError.NfcUnavailable
                    val isoDep = awaitIsoDepTag(act)
                    try {
                        reader.read(isoDep, params, onProgress)
                    } finally {
                        IDDocumentNfcReaderMode.stop(act)
                    }
                },
                save = { result -> idDocumentStore.save(result).id.toString() },
                // Submit the chip-signed fields to the configured issuer for a
                // verified, ledger-anchored credential. Enabled when the chip
                // exposed an SOD and a trusted issuer is configured.
                canIssue = { savedId ->
                    issuerBaseUrl != null &&
                        idDocumentStore.document(java.util.UUID.fromString(savedId))?.sod != null
                },
                issuanceDisabledHint = { savedId ->
                    when {
                        issuerBaseUrl == null ->
                            "Add a trusted issuer in Settings > Identity to request a verified credential."
                        idDocumentStore.document(java.util.UUID.fromString(savedId))?.sod == null ->
                            "This document exposed no security object (SOD), so it can't be issuer-verified."
                        else -> null
                    }
                },
                submitIssuance = { savedId ->
                    val doc = idDocumentStore.document(java.util.UUID.fromString(savedId))
                        ?: throw IllegalStateException("Document not found.")
                    val sandwich = withContext(Dispatchers.IO) { IdentitySandwich.load(store) }
                        ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                    val ack = IDDocumentIssuanceClient()
                        .submit(sandwich, doc.toPassportIssuanceInput(), issuerBaseUrl)
                    // Queue the pending pickup so it imports + surfaces after the
                    // user returns to the hub (iOS PendingPickupsStore parity).
                    val pickupUrl = ack.pickupUrl?.takeIf { it.isNotEmpty() }
                    val credentialId = ack.credentialId?.takeIf { it.isNotEmpty() }
                    if (ack.status == "approved" && pickupUrl != null && credentialId != null) {
                        pendingPickups.add(
                            PendingPickup(
                                credentialId = credentialId,
                                pickupUrl = pickupUrl,
                                humanLabel = PendingPickupsStore.PASSPORT_LABEL,
                                schemaUri = PASSPORT_SCHEMA,
                                startedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    ack.toIssuanceOutcome()
                },
                submittingHost = issuerHostLabel(issuerBaseUrl),
                onSaved = {
                    reloadKey++
                    route = IdentityRoute.Hub
                },
                onClose = { route = IdentityRoute.Hub },
            )
            return
        }
        is IdentityRoute.Credential -> {
            BackHandler { route = IdentityRoute.Hub }
            val entity = credentials.firstOrNull { it.cid == current.cid }
            val parsed = remember(current.cid, entity) {
                entity?.let { runCatching { it.parsed() }.getOrNull() }
            }
            val sw = sandwich
            if (entity == null || parsed == null || sw == null) {
                // Deleted, unparseable, or identity locked: pop back.
                LaunchedEffect(Unit) { route = IdentityRoute.Hub }
                return
            }
            val folderCardKey = CredentialFolderStore.cardKey(entity.cid)
            val folderList = remember(folderVersion) { folderStore.folders() }
            val curFolderId = remember(folderVersion, entity.cid) { folderStore.folderId(folderCardKey) }
            CredentialPresentScreen(
                credential = parsed,
                nickname = entity.nickname,
                folderName = folderList.firstOrNull { it.id == curFolderId }?.name ?: "None",
                availableFolders = folderList.map { it.id to it.name },
                currentFolderId = curFolderId,
                pendingRequest = null,
                initialMode = PresentMode.BADGE,
                sandwich = sw,
                dropHost = MiniAppHosts.DROP_HOST,
                onSetNickname = { name ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            MaknoonStore.open(context).credentials()
                                .upsert(entity.copy(nickname = name?.trim()?.takeIf { it.isNotEmpty() }))
                        }
                        reloadKey++
                    }
                },
                onAssignFolder = { fid -> folderStore.assign(folderCardKey, fid); folderVersion++ },
                onRemove = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            MaknoonStore.open(context).credentials().delete(entity.cid)
                        }
                        reloadKey++
                        route = IdentityRoute.Hub
                    }
                },
                onShared = { _, _, _ -> /* verifier-history recording is a later step */ },
                onBack = { route = IdentityRoute.Hub },
            )
            return
        }
        is IdentityRoute.Document -> {
            BackHandler { route = IdentityRoute.Hub }
            val doc = documents.firstOrNull { it.id == current.id }
            if (doc == null) {
                LaunchedEffect(Unit) { route = IdentityRoute.Hub }
                return
            }
            var passiveAuthRunning by remember(doc.id) { mutableStateOf(false) }
            var presentError by remember(doc.id) { mutableStateOf<String?>(null) }
            // A self-signed credential's header is master-signed (needs the root
            // entropy). With the second factor ON, present must recover the
            // entropy via an enrolled key before minting; this drives that dialog.
            var presentNeedsSecondFactor by remember(doc.id) { mutableStateOf(false) }
            val photo = remember(doc.portraitJpeg) {
                doc.portraitJpeg?.let {
                    runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull()
                }
            }
            if (presentNeedsSecondFactor && activity != null) {
                com.elabify.app.maknoon.yubikey.SecondFactorRecoverDialog(
                    activity = activity,
                    registry = remember { com.elabify.musnad.devices.DeviceRegistry(context) },
                    title = stringResource(R.string.identity_unlock_second_factor_title),
                    message = stringResource(R.string.identity_unlock_second_factor_message),
                    onRecovered = { cek ->
                        scope.launch {
                            presentNeedsSecondFactor = false
                            val sw = withContext(Dispatchers.IO) {
                                runCatching { IdentitySandwich.loadWithSecondFactor(store) { cek } }.getOrNull()
                            }
                            if (sw == null) {
                                presentError = "Could not unlock with your security key. Try again."
                                return@launch
                            }
                            sandwich = sw
                            val cred = withContext(Dispatchers.IO) {
                                runCatching { LocalCredentialFactory.mint(doc, sw) }.getOrNull()
                            }
                            if (cred != null) { presentError = null; presentedSelfCredential = cred } else {
                                presentError = "Could not create the credential to present."
                            }
                        }
                    },
                    onError = { presentNeedsSecondFactor = false; presentError = it },
                    onCancel = { presentNeedsSecondFactor = false },
                )
            }
            // Passport single-card (ADR-0039): a scanned passport shows the navy
            // PassportCardDetailScreen; "Advanced options" flips to the existing
            // technical IDDocumentDetailScreen. Non-passport ID docs go straight
            // to the detail screen as before.
            val isPassport = remember(doc.id) { doc.iconName == "passport" }
            var showAdvanced by remember(doc.id) { mutableStateOf(false) }
            val matched = remember(doc.id, credentials, holderDid) {
                PassportPairing.matchedCredential(doc, credentials, holderDid)
            }
            // Mint a self-signed credential and route to the present QR sheet
            // (biometric-gated; second-factor unlock when the header needs the
            // root entropy). Shared by the navy "Share QR" and Advanced "Present".
            val doPresentQr: () -> Unit = {
                scope.launch {
                    val approved = if (activity != null) {
                        BiometricGate.authenticate(
                            activity,
                            title = "Present passport",
                            subtitle = "Create a self-signed credential to show",
                        )
                    } else { true }
                    if (!approved) {
                        presentError = "Unlock cancelled. Tap Present again to show the QR."
                        return@launch
                    }
                    try {
                        val sw = sandwich
                            ?: withContext(Dispatchers.IO) { IdentitySandwich.load(store) }?.also { sandwich = it }
                            ?: throw IllegalStateException("Could not unlock your identity. Try again.")
                        val cred = withContext(Dispatchers.IO) { LocalCredentialFactory.mint(doc, sw) }
                        presentError = null
                        presentedSelfCredential = cred
                    } catch (e: com.elabify.musnad.identity.SecondFactorRequiredException) {
                        presentError = null
                        presentNeedsSecondFactor = true
                    } catch (e: Throwable) {
                        presentError = e.message ?: "Could not create the credential to present."
                    }
                }
            }
            if (isPassport && !showAdvanced) {
                PassportCardDetailScreen(
                    document = doc,
                    photo = photo,
                    anchors = matched?.anchors ?: emptyList(),
                    passiveAuth = doc.passiveAuthResult,
                    canShowQr = remember(doc.id) { LocalCredentialFactory.isPresentable(doc) || matched != null },
                    onShowQr = {
                        presentInitialMode = PresentMode.ATTRIBUTES
                        presentPassportMode = true
                        val m = matched
                        if (m != null) {
                            // Present the issuer-issued, Musnad-anchored credential
                            // so the verifier sees the anchor. Falls back to a
                            // freshly minted self-signed credential below when all
                            // we have is the NFC scan (no matching issued VC).
                            scope.launch {
                                val approved = if (activity != null) {
                                    BiometricGate.authenticate(
                                        activity,
                                        title = "Present passport",
                                        subtitle = "Show your verified passport credential",
                                    )
                                } else { true }
                                if (!approved) {
                                    presentError = "Unlock cancelled. Tap Share QR again to show the QR."
                                    return@launch
                                }
                                val sw = sandwich
                                    ?: withContext(Dispatchers.IO) { IdentitySandwich.load(store) }?.also { sandwich = it }
                                if (sw == null) {
                                    presentError = "Could not unlock your identity. Try again."
                                    return@launch
                                }
                                presentError = null
                                presentedSelfCredential = m.parsed
                            }
                        } else {
                            doPresentQr()
                        }
                    },
                    onShare = {
                        // Build the verifiable presentation (matched anchored
                        // credential when present, else mint), upload to the drop,
                        // and share the composed card + QR + footer image (iOS
                        // parity, ADR-0039).
                        scope.launch {
                            try {
                                val approved = if (activity != null) {
                                    BiometricGate.authenticate(
                                        activity,
                                        title = "Share passport",
                                        subtitle = "Create a verifiable QR to share",
                                    )
                                } else { true }
                                if (!approved) {
                                    presentError = "Unlock cancelled. Tap Share again."
                                    return@launch
                                }
                                val sw = sandwich
                                    ?: withContext(Dispatchers.IO) { IdentitySandwich.load(store) }?.also { sandwich = it }
                                    ?: throw IllegalStateException("Could not unlock your identity. Try again.")
                                val cred = matched?.parsed
                                    ?: withContext(Dispatchers.IO) { LocalCredentialFactory.mint(doc, sw) }
                                val env = withContext(Dispatchers.IO) {
                                    val pres = PresentationBuilder.build(
                                        credential = cred,
                                        selectedClaims = cred.claims.keys.toSet(),
                                        challenge = "0x" + PresentationBuilder.selfNonceHex(),
                                        verifierDid = PresentationBuilder.OPEN_VERIFIER_DID,
                                        pendingRequest = null,
                                        sandwich = sw,
                                    )
                                    PresentationDrop(MiniAppHosts.DROP_HOST).upload(pres)
                                }
                                val img = withContext(Dispatchers.Default) {
                                    val qr = qrBitmap(env.toJsonString(), 600)
                                    PassportShare.composeShareBitmap(context, doc, photo?.asAndroidBitmap(), qr, env.expiresAt)
                                }
                                PassportShare.shareImage(context, img)
                            } catch (e: com.elabify.musnad.identity.SecondFactorRequiredException) {
                                presentError = null
                                presentNeedsSecondFactor = true
                            } catch (e: Throwable) {
                                presentError = e.message ?: "Could not build the share image."
                            }
                        }
                    },
                    onAdvanced = { showAdvanced = true },
                    onBack = { route = IdentityRoute.Hub },
                )
                return
            }
            IDDocumentDetailScreen(
                document = doc,
                photo = photo,
                passiveAuth = doc.passiveAuthResult,
                passiveAuthRunning = passiveAuthRunning,
                sanctionsResult = doc.sanctionsResult,
                // Presentable whenever the passport has the MRZ-derived fields a
                // self-signed credential needs. NOT gated on the lock state: the
                // unlock happens on tap (biometric load of the sandwich), and
                // presenting needs only the loaded sandwich, no second factor.
                canPresent = remember(doc.id) { LocalCredentialFactory.isPresentable(doc) },
                presentError = presentError,
                canIssue = issuerBaseUrl != null && doc.sod != null,
                sodMissing = doc.sod == null,
                submittingHost = issuerHostLabel(issuerBaseUrl),
                savedString = stringResource(R.string.identity_saved_on_this_phone),
                // Mint + present the self-signed passport credential. If the
                // identity auto-locked, re-unlock via biometric (loads the
                // sandwich); then sign the credential header with the master key
                // and route to the present QR sheet. No hardware second factor:
                // the present flow only needs the loaded sandwich.
                onPresentQr = { presentInitialMode = PresentMode.BADGE; presentPassportMode = false; doPresentQr() },
                runPassiveAuth = { force ->
                    if (!passiveAuthRunning) {
                        passiveAuthRunning = true
                        try {
                            // All of this (loading ~hundreds of CSCA certs, optional
                            // bundle refresh, and the chip signature verification) is
                            // heavy; run it entirely off the main thread so opening
                            // Advanced / tapping Re-check never freezes the UI.
                            val result = withContext(Dispatchers.IO) {
                                val csca = if (issuerBaseUrl != null) {
                                    CSCATrustStore(context, issuerBaseUrl)
                                } else {
                                    CSCATrustStore(context)
                                }
                                if (issuerBaseUrl != null) runCatching { csca.refresh(force) }
                                val trusted = csca.trustedCertificates()
                                android.util.Log.d("CSCA", "passiveAuth: issuer=$issuerBaseUrl trustedCerts=${trusted.size} sod=${doc.sod?.size}")
                                val dgs = buildMap<String, ByteArray> {
                                    doc.dg1?.let { put("dg1", it) }
                                    doc.dg2?.let { put("dg2", it) }
                                    doc.dg11?.let { put("dg11", it) }
                                    doc.dg12?.let { put("dg12", it) }
                                    doc.dg15?.let { put("dg15", it) }
                                }
                                PassportPassiveAuthVerifier.verify(
                                    sod = doc.sod,
                                    dataGroups = dgs,
                                    issuingAlpha3 = doc.issuingAuthority,
                                    trustedCscas = trusted,
                                    bundleVersion = csca.version,
                                )
                            }
                            android.util.Log.d("CSCA", "passiveAuth: result status=${result.status} reason=${result.reason} csca=${result.cscaCountry}")
                            idDocumentStore.setPassiveAuthResult(result, doc.id)
                        } catch (e: Throwable) {
                            android.util.Log.w("CSCA", "passiveAuth: verify threw", e)
                        } finally {
                            passiveAuthRunning = false
                        }
                    }
                },
                submitIssuance = {
                    val sandwich = withContext(Dispatchers.IO) { IdentitySandwich.load(store) }
                        ?: throw IllegalStateException("Identity is locked. Unlock and retry.")
                    val ack = IDDocumentIssuanceClient()
                        .submit(sandwich, doc.toPassportIssuanceInput(), issuerBaseUrl)
                    // Approved + auto-minted packets come back with a pickupUrl +
                    // credentialId; queue a pending pickup so the Identity tab
                    // polls + imports the credential in the background (iOS parity).
                    val pickupUrl = ack.pickupUrl?.takeIf { it.isNotEmpty() }
                    val credentialId = ack.credentialId?.takeIf { it.isNotEmpty() }
                    if (ack.status == "approved" && pickupUrl != null && credentialId != null) {
                        pendingPickups.add(
                            PendingPickup(
                                credentialId = credentialId,
                                pickupUrl = pickupUrl,
                                humanLabel = PendingPickupsStore.PASSPORT_LABEL,
                                schemaUri = PASSPORT_SCHEMA,
                                startedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    ack.toIssuanceOutcome()
                },
                runSanctions = {
                    val base = issuerBaseUrl
                        ?: throw IllegalStateException("Add a trusted issuer in Settings > Identity first.")
                    val given = (doc.latinGivenNames ?: doc.givenNames).replace("<", " ").trim()
                    val family = (doc.latinSurname ?: doc.surname).replace("<", " ").trim()
                    val result = SanctionsScreeningClient()
                        .check(given, family, yymmddToIso(doc.dateOfBirth), null, base)
                    idDocumentStore.setSanctionsResult(result, doc.id)
                },
                onSaveNickname = { name -> scope.launch { idDocumentStore.setNickname(name, doc.id) } },
                onDelete = { scope.launch { idDocumentStore.delete(doc.id); route = IdentityRoute.Hub } },
                // For a passport, "Advanced options" is reached from the navy card,
                // so Back returns there; otherwise it pops to the Hub.
                onBack = { if (isPassport) showAdvanced = false else route = IdentityRoute.Hub },
                folderName = run {
                    val cur = folderStore.folderId(CredentialFolderStore.docCardKey(doc.id.toString()))
                    remember(folderVersion) { folderStore.folders() }.firstOrNull { it.id == cur }?.name ?: "None"
                },
                availableFolders = remember(folderVersion) { folderStore.folders().map { it.id to it.name } },
                currentFolderId = remember(folderVersion, doc.id) {
                    folderStore.folderId(CredentialFolderStore.docCardKey(doc.id.toString()))
                },
                onAssignFolder = { fid ->
                    folderStore.assign(CredentialFolderStore.docCardKey(doc.id.toString()), fid)
                    folderVersion++
                },
            )
            return
        }
        is IdentityRoute.Hub -> Unit
    }

    when {
        busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        holderDid == null -> OnboardingScreen(onComplete = { reloadKey++ })

        else -> IdentityHub(
            error = error,
            cards = remember(credentials, documents, holderDid) {
                buildHubCards(credentials, documents, holderDid)
            },
            pendingPickups = pending,
            onCancelPending = { pendingPickups.cancel(it) },
            onOpenSettings = { route = IdentityRoute.Settings },
            onReceive = { route = IdentityRoute.Receive },
            onScanVerifier = { route = IdentityRoute.ScanVerifier },
            onVerifyOther = { route = IdentityRoute.VerifyOther },
            onTapIdDocument = { route = IdentityRoute.TapIdDocument },
            onOpenCredential = { route = IdentityRoute.Credential(it) },
            onOpenDocument = { route = IdentityRoute.Document(it) },
            onDeleteCredential = { cid ->
                scope.launch {
                    withContext(Dispatchers.IO) { MaknoonStore.open(context).credentials().delete(cid) }
                    reloadKey++
                }
            },
            onDeleteDocument = { id -> scope.launch { idDocumentStore.delete(id) } },
            folderStore = folderStore,
            folderVersion = folderVersion,
            activeFolderId = activeFolderId,
            onSelectFolder = { activeFolderId = it },
            onFoldersChanged = { folderVersion++ },
        )
    }
}

/**
 * Bridge the callback-style NFC reader mode to a suspend function: enable
 * foreground reader mode and resume with the first ICAO IsoDep tag the platform
 * delivers. Cancelling the scan (coroutine cancellation) releases the radio.
 * The caller owns stop() on the success path so the radio stays live across the
 * full data-group read, then is released once.
 */
private suspend fun awaitIsoDepTag(activity: FragmentActivity): IsoDep =
    suspendCancellableCoroutine { cont ->
        val started = IDDocumentNfcReaderMode.start(activity) { isoDep ->
            if (cont.isActive) cont.resume(isoDep)
        }
        if (!started && cont.isActive) {
            cont.resumeWithException(IDDocumentReaderError.NfcUnavailable)
        }
        cont.invokeOnCancellation { IDDocumentNfcReaderMode.stop(activity) }
    }

/** host[:port] label for the "Submitting to …" line, or "issuer" when unknown. */
private fun issuerHostLabel(baseUrl: String?): String {
    if (baseUrl.isNullOrBlank()) return "issuer"
    return runCatching {
        val u = java.net.URI(baseUrl)
        val host = u.host ?: return "issuer"
        if (u.port > 0) "$host:${u.port}" else host
    }.getOrDefault("issuer")
}

/** Chip-native YYMMDD -> ISO 8601 (YYYY-MM-DD) for sanctions screening. DOB is
 *  always in the past, so a two-digit year above 30 maps to the 1900s. */
private fun yymmddToIso(yymmdd: String): String {
    if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return yymmdd
    val yy = yymmdd.substring(0, 2).toInt()
    val year = if (yy > 30) 1900 + yy else 2000 + yy
    return "%04d-%s-%s".format(year, yymmdd.substring(2, 4), yymmdd.substring(4, 6))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityHub(
    error: String?,
    cards: List<HubCard>,
    pendingPickups: List<PendingPickup>,
    onCancelPending: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onReceive: () -> Unit,
    onScanVerifier: () -> Unit,
    onVerifyOther: () -> Unit,
    onTapIdDocument: () -> Unit,
    onOpenCredential: (String) -> Unit,
    onOpenDocument: (UUID) -> Unit,
    onDeleteCredential: (String) -> Unit,
    onDeleteDocument: (UUID) -> Unit,
    folderStore: CredentialFolderStore,
    folderVersion: Int,
    activeFolderId: String?,
    onSelectFolder: (String?) -> Unit,
    onFoldersChanged: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity_title)) },
                navigationIcon = {
                    IconButton(onClick = onOpenSettings) {
                        // iOS: "gearshape" (outlined gear), tinted .purple.
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        // iOS: "plus.circle" (plus in an outlined circle), tinted .purple.
                        Icon(
                            Icons.Outlined.AddCircle,
                            contentDescription = stringResource(R.string.identity_quick_actions),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_receive_credential)) },
                            leadingIcon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                            onClick = { menuOpen = false; onReceive() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_scan_verifier)) },
                            leadingIcon = { Icon(Icons.Filled.VerifiedUser, contentDescription = null) },
                            onClick = { menuOpen = false; onScanVerifier() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_verify_someone)) },
                            leadingIcon = { Icon(Icons.Filled.PersonSearch, contentDescription = null) },
                            onClick = { menuOpen = false; onVerifyOther() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.identity_tap_id_document)) },
                            leadingIcon = { Icon(Icons.Filled.ContactPage, contentDescription = null) },
                            onClick = { menuOpen = false; onTapIdDocument() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md, bottom = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            error?.let {
                Banner(title = stringResource(R.string.identity_error), variant = BannerVariant.ERROR, body = it)
            }

            // Pending issuance pickups: credentials minted on the issuer side,
            // waiting to be anchored + imported. Shown at the top of the hub
            // with a cancel option (iOS PendingPickupsStore section).
            if (pendingPickups.isNotEmpty()) {
                PendingPickupsSection(pendingPickups, onCancelPending)
            }

            val credentialCards = cards.filterIsInstance<HubCard.Credential>()
            val documentCards = cards.filterIsInstance<HubCard.Document>()

            if (credentialCards.isEmpty() && documentCards.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxl)) {
                    EmptyState(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        title = stringResource(R.string.identity_no_credentials_yet),
                        subtitle = stringResource(R.string.identity_empty_subtitle),
                        iconSize = 56.dp,
                    )
                }
            } else {
                // One folder strip governs BOTH sections: a folder may hold
                // credentials and/or ID documents, so selecting one filters both
                // (ADR-0037). Membership keys are namespaced ("cred:<cid>" /
                // "passport:<id>") so the two card kinds share one folder map.
                val folders = remember(folderVersion) { folderStore.folders() }
                val countFor: (String) -> Int = remember(folderVersion, credentialCards, documentCards) {
                    { fid ->
                        credentialCards.count {
                            folderStore.folderId(CredentialFolderStore.cardKey(it.entity.cid)) == fid
                        } + documentCards.count {
                            folderStore.folderId(CredentialFolderStore.docCardKey(it.document.id.toString())) == fid
                        }
                    }
                }
                CredentialFolderStrip(
                    folders = folders,
                    activeFolderId = activeFolderId,
                    allCount = credentialCards.size + documentCards.size,
                    countFor = countFor,
                    onSelect = onSelectFolder,
                    onCreate = { name -> folderStore.add(name); onFoldersChanged() },
                    onRename = { id, name -> folderStore.rename(id, name); onFoldersChanged() },
                    onDelete = { id -> folderStore.remove(id); onFoldersChanged() },
                    modifier = Modifier.fillMaxWidth(),
                )

                val folderIds = activeFolderId?.let { folderStore.cardIds(it) }
                // One unified, sorted list (ADR-0039): credentials and passports
                // interleave by the shared buildHubCards sort, with no
                // "Credentials" / "ID documents" section banners. Filtered by the
                // active folder across both card kinds.
                val visibleCards = if (folderIds == null) {
                    cards
                } else {
                    cards.filter { hc ->
                        when (hc) {
                            is HubCard.Credential -> folderIds.contains(CredentialFolderStore.cardKey(hc.entity.cid))
                            is HubCard.Document -> folderIds.contains(CredentialFolderStore.docCardKey(hc.document.id.toString()))
                        }
                    }
                }

                if (activeFolderId != null && visibleCards.isEmpty()) {
                    FolderEmptyState(onShowAll = { onSelectFolder(null) })
                }
                // Long-press a card for a context menu (iOS parity): Move to
                // folder or Delete. moveCardKey drives the folder picker;
                // menuCard the menu; deleteCard the delete confirm.
                var moveCardKey by remember { mutableStateOf<String?>(null) }
                var menuCard by remember { mutableStateOf<HubCard?>(null) }
                var deleteCard by remember { mutableStateOf<HubCard?>(null) }
                fun keyFor(hc: HubCard): String = when (hc) {
                    is HubCard.Credential -> CredentialFolderStore.cardKey(hc.entity.cid)
                    is HubCard.Document -> CredentialFolderStore.docCardKey(hc.document.id.toString())
                }
                if (visibleCards.isNotEmpty()) {
                    // Peek-stacked like the iOS Identity tab (cards overlap, each
                    // peeking its top region), one stack for both kinds.
                    val overlap = CredentialCardDefaults.height - CredentialCardDefaults.peekHeight
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(-overlap),
                    ) {
                        visibleCards.forEach { hc ->
                            when (hc) {
                                is HubCard.Credential -> CredentialCard(
                                    data = hc.data,
                                    onClick = { onOpenCredential(hc.entity.cid) },
                                    onLongClick = { menuCard = hc },
                                )
                                is HubCard.Document -> IdDocumentCard(
                                    data = hc.data,
                                    onClick = { onOpenDocument(hc.document.id) },
                                    onLongClick = { menuCard = hc },
                                )
                            }
                        }
                    }
                }
                // Context menu (Move to folder / Delete).
                menuCard?.let { hc ->
                    AlertDialog(
                        onDismissRequest = { menuCard = null },
                        title = { Text(hc.sortType) },
                        text = {
                            Column {
                                TextButton(
                                    onClick = { moveCardKey = keyFor(hc); menuCard = null },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.identity_card_move_to_folder), modifier = Modifier.fillMaxWidth()) }
                                TextButton(
                                    onClick = { deleteCard = hc; menuCard = null },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.identity_card_delete), color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { menuCard = null }) { Text(stringResource(R.string.common_cancel)) } },
                    )
                }
                // Delete confirmation.
                deleteCard?.let { hc ->
                    AlertDialog(
                        onDismissRequest = { deleteCard = null },
                        title = { Text(stringResource(R.string.identity_card_delete_title)) },
                        text = { Text(stringResource(R.string.identity_card_delete_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                when (hc) {
                                    is HubCard.Credential -> onDeleteCredential(hc.entity.cid)
                                    is HubCard.Document -> onDeleteDocument(hc.document.id)
                                }
                                deleteCard = null
                            }) { Text(stringResource(R.string.identity_card_delete), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { deleteCard = null }) { Text(stringResource(R.string.common_cancel)) } },
                    )
                }
                moveCardKey?.let { key ->
                    MoveToFolderDialog(
                        folders = folders,
                        currentFolderId = folderStore.folderId(key),
                        onSelect = { fid -> folderStore.assign(key, fid); onFoldersChanged(); moveCardKey = null },
                        onDismiss = { moveCardKey = null },
                    )
                }
            }
        }
    }
}

// Shown when a folder is selected but holds no (live) cards. Mirrors the iOS
// folderEmptyState: a hint plus a "Show all" escape hatch. A folder can hold
// both credentials and ID documents.
@Composable
private fun FolderEmptyState(onShowAll: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            stringResource(R.string.identity_nothing_in_folder),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.identity_folder_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onShowAll) { Text(stringResource(R.string.identity_show_all_credentials)) }
    }
}

// The pending-pickups section: a labelled list of rows for credentials the
// issuer has minted but we have not imported yet. Mirrors the iOS
// pendingPickupsSection / pendingPickupRow (icon, label, "Anchoring…" spinner,
// short credential id, destructive cancel).
@Composable
private fun PendingPickupsSection(
    pending: List<PendingPickup>,
    onCancel: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SectionHeader(title = stringResource(R.string.identity_pending_pickups))
        pending.forEach { entry ->
            PendingPickupRow(entry, onCancel)
        }
    }
}

@Composable
private fun PendingPickupRow(
    entry: PendingPickup,
    onCancel: (String) -> Unit,
) {
    val palette = CardPalette.forSchema(entry.schemaUri ?: "")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.card),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.brush),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    tint = palette.foreground,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    entry.humanLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.identity_anchoring),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    shortCid(entry.credentialId),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            IconButton(onClick = { onCancel(entry.credentialId) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.identity_cancel_pending_pickup),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// Apple-Wallet style overlap: each card peeks the top peekHeight of the next,
// the last card is fully revealed. Negative arrangement spacing pulls each card
// up over its predecessor; later children draw on top, so their soft shadows
// (clip=false) layer correctly over the card above.
@Composable
private fun CredentialStack(
    cards: List<HubCard.Credential>,
    onOpenCredential: (String) -> Unit,
    onLongPressCard: ((String) -> Unit)? = null,
) {
    val overlap = CredentialCardDefaults.height - CredentialCardDefaults.peekHeight
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(-overlap),
    ) {
        cards.forEach { card ->
            CredentialCard(
                data = card.data,
                onClick = { onOpenCredential(card.entity.cid) },
                onLongClick = onLongPressCard?.let { { it(card.entity.cid) } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionPlaceholder(title: String, body: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Banner(title = title, variant = BannerVariant.INFO, body = body)
        }
    }
}

// ---------------------------------------------------------------------------
// Hub-card assembly. Mirrors iOS IdentityView.walletCards: parse each stored
// credential into its display fields and pair each saved document with a card,
// then sort the unified list by type, nickname, issuer.
// ---------------------------------------------------------------------------

private fun buildHubCards(
    credentials: List<CredentialEntity>,
    documents: List<IDDocument>,
    holderDid: String?,
): List<HubCard> {
    // Fold any passport credential that represents a scanned document into that
    // document's single navy card (ADR-0039): hide a self-issued passport VC AND
    // an issuer-issued passport credential whose normalized {passportNumber,
    // dateOfBirth, expiryDate} tuple matches a scanned IDDocument. The matched
    // credential's anchors still drive the document card's pinned-network strip.
    val docKeys = PassportPairing.documentKeys(documents)
    val credentialCards = credentials
        .filterNot { it.schema == PASSPORT_SCHEMA && it.issuerDid == holderDid }
        .filterNot { entity ->
            entity.schema == PASSPORT_SCHEMA &&
                runCatching { ParsedCredential.parse(entity.credentialJson) }.getOrNull()
                    ?.let { PassportPairing.key(it) }
                    ?.let { docKeys.contains(it) } == true
        }
        .mapNotNull { entity -> credentialHubCard(entity) }

    val documentCards = documents.map { documentHubCard(it) }

    return (credentialCards + documentCards).sortedWith(
        compareBy(
            { it.sortType.lowercase() },
            { it.sortNick.lowercase() },
            { it.sortIssuer.lowercase() },
        ),
    )
}

private fun credentialHubCard(entity: CredentialEntity): HubCard.Credential? {
    val parsed = runCatching { ParsedCredential.parse(entity.credentialJson) }.getOrNull()
    val title = schemaLabel(entity.schema)
    val issuerLabel = shortIssuerName(entity.issuerDid)
    val expiryMillis = parsed?.header?.exp?.let { it * 1000L }
    val statusLevel = expiryStatus(expiryMillis, System.currentTimeMillis())
    val identifier = parsed?.cid ?: entity.cid

    val data = CredentialCardData(
        id = entity.cid,
        title = title,
        issuer = issuerLabel,
        identifier = shortCid(identifier),
        nickname = entity.nickname,
        issuedText = "Issued " + formatEpochSeconds(parsed?.header?.iat ?: (entity.createdAt / 1000L)),
        // Expiry shows only inside the credential (its detail/present screen),
        // not on the Identity list card. The status dot still reflects expiry.
        expiryText = null,
        statusLevel = statusLevel,
        palette = CardPalette.forSchema(entity.schema),
    )
    return HubCard.Credential(
        entity = entity,
        data = data,
        sortType = title,
        sortNick = entity.nickname.orEmpty(),
        sortIssuer = issuerLabel,
    )
}

private fun documentHubCard(document: IDDocument): HubCard.Document {
    val portrait: ImageBitmap? = document.portraitJpeg?.let { bytes ->
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    val passiveAuth = when (document.passiveAuthResult?.status) {
        PassiveAuthResult.Status.VERIFIED -> StatusLevel.OK
        PassiveAuthResult.Status.FAILED -> StatusLevel.EXPIRED
        // Integrity-only, unavailable, or not yet run: needs attention.
        else -> StatusLevel.WARN
    }
    // Subtitle mirrors iOS: "<2-letter country> · NFC Scan" (e.g. "US · NFC Scan").
    val countryCode = com.elabify.app.maknoon.iddocument.ISO3166.alpha2(document.issuingAuthority)
        ?: document.issuingAuthority.uppercase()
    val data = IdDocumentCardData(
        id = document.id.toString(),
        name = document.displayName,
        country = "$countryCode · NFC Scan",
        documentType = document.kindLabel,
        // Expiry shows only inside the passport (its detail card), not on the
        // Identity list card (iOS parity).
        expiryText = null,
        portrait = portrait,
        passiveAuth = passiveAuth,
    )
    return HubCard.Document(
        document = document,
        data = data,
        // ID documents sort under their kind label, then nickname, then country.
        sortType = document.kindLabel,
        sortNick = document.nickname.orEmpty(),
        sortIssuer = document.summary,
    )
}

// Compact credential id for the card's monospace identifier slot.
private fun shortCid(cid: String): String {
    if (cid.length <= 14) return cid
    return cid.take(8) + "..." + cid.takeLast(4)
}

private fun formatEpochSeconds(seconds: Long): String = formatEpochMillis(seconds * 1000L)

private fun formatEpochMillis(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}
