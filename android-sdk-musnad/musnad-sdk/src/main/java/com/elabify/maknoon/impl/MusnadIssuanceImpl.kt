package com.elabify.maknoon.impl

import android.content.Context
import android.net.Uri
import com.elabify.maknoon.CredentialID
import com.elabify.maknoon.IssuancePreview
import com.elabify.maknoon.MaknoonConfig
import com.elabify.maknoon.MaknoonError
import com.elabify.maknoon.MusnadIssuance
import com.elabify.maknoon.PassportFields
import com.elabify.maknoon.PassportReadSession
import com.elabify.musnad.data.CredentialEntity
import com.elabify.musnad.data.MaknoonStore
import com.elabify.musnad.net.IssuerClient
import com.elabify.musnad.net.PickupOutcome
import com.elabify.musnad.present.ParsedCredential
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [MusnadIssuance] over the issuer pickup client + credential store. The pickup half
 * (resolve a signed credential from a pickup link and save it) is wired here. The
 * e-passport half (`readPassport` / `submitPassportIssuance`) depends on the NFC chip
 * reader + CSCA passive-auth code that currently lives in the reference app; it is wired
 * when that reader is extracted into the SDK during the app-migration step of Phase 1A.
 */
internal class MusnadIssuanceImpl(
    private val appContext: Context,
    private val config: MaknoonConfig,
) : MusnadIssuance {

    // Raw picked-up credential JSON, keyed by cid, so acceptIssuance can persist exactly
    // what resolvePickup validated.
    private val pending = ConcurrentHashMap<String, String>()

    override suspend fun resolvePickup(uri: Uri): IssuancePreview = withContext(Dispatchers.IO) {
        val host = uri.host
        if (host == null || config.allowedIssuerHosts.none { it.equals(host, ignoreCase = true) }) {
            throw MaknoonError.InvalidRequest("Issuer host not in the configured allowlist")
        }
        val outcome = IssuerClient(baseUrl = "").pickup(uri.toString())
        val json = when (outcome) {
            is PickupOutcome.Ready -> outcome.credentialJson
            // Distinct from Network: a poll loop has to tell "still minting"
            // apart from "the request failed".
            is PickupOutcome.Pending -> throw MaknoonError.IssuanceNotReady(outcome.estimatedAnchorAt)
        }
        val parsed = ParsedCredential.parse(json)
        pending[parsed.header.cid] = json
        IssuancePreview(
            issuerDid = parsed.header.iss,
            schema = parsed.header.schema,
            claimKeys = parsed.claims.keys.toSet(),
            cid = CredentialID(parsed.header.cid),
        )
    }

    override suspend fun acceptIssuance(preview: IssuancePreview): CredentialID =
        withContext(Dispatchers.IO) {
            val json = pending[preview.cid.value]
                ?: throw MaknoonError.InvalidRequest("Resolve the pickup before accepting")
            MaknoonStore.open(appContext).credentials()
                .upsert(credentialEntityOf(json, System.currentTimeMillis()))
            pending.remove(preview.cid.value)
            preview.cid
        }

    override suspend fun readPassport(session: PassportReadSession): PassportFields {
        // The passport NFC chip read + ICAO 9303 parsing + CSCA passive authentication
        // currently live in the reference app (not the SDK). This is wired when that reader
        // is extracted into the SDK during the Phase 1A app-migration step.
        throw MaknoonError.Configuration("readPassport is wired when the NFC passport reader is extracted into the SDK")
    }

    override suspend fun submitPassportIssuance(
        issuerDid: String,
        passport: PassportFields,
    ): CredentialID {
        throw MaknoonError.Configuration("submitPassportIssuance is wired with the passport reader extraction")
    }
}

/**
 * The row to persist for a picked-up credential.
 *
 * Extracted from [MusnadIssuanceImpl.acceptIssuance] so the unit of [nowMs] can
 * be tested without a database or a Context. That is not hypothetical tidiness:
 * this line shipped `System.currentTimeMillis() / 1000L` while every other
 * writer of the column uses milliseconds and the UI divides by 1000 to display
 * it. The DAO orders by `createdAt DESC`, so a credential imported through the
 * facade sorted as if issued in 1970 and sank to the bottom of the holder's
 * list permanently. Nothing failed, and no type was wrong.
 *
 * @param nowMs wall clock in MILLISECONDS, matching the column's unit.
 */
internal fun credentialEntityOf(credentialJson: String, nowMs: Long): CredentialEntity {
    val parsed = ParsedCredential.parse(credentialJson)
    return CredentialEntity(
        cid = parsed.header.cid,
        issuerDid = parsed.header.iss,
        subjectDid = parsed.header.sub,
        schema = parsed.header.schema,
        credentialJson = credentialJson,
        nickname = null,
        createdAt = nowMs,
    )
}
