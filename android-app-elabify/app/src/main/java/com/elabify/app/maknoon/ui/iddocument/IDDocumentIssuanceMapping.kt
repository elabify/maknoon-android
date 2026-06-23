// Pure mapping helpers shared by the Tap-ID-document and document-detail
// issuance wiring: turn a saved IDDocument into the issuer packet input, and
// turn the issuer's ack into the UI outcome the screens render.

package com.elabify.app.maknoon.ui.iddocument

import com.elabify.app.maknoon.iddocument.AttestationSubmitAck
import com.elabify.app.maknoon.iddocument.IDDocument
import com.elabify.app.maknoon.iddocument.PassportIssuanceInput

private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

/** Map a saved document to the issuer attestation packet input (chip blobs hex-encoded). */
internal fun IDDocument.toPassportIssuanceInput(): PassportIssuanceInput = PassportIssuanceInput(
    documentNumber = documentNumber,
    surname = surname,
    givenNames = givenNames,
    latinSurname = latinSurname,
    latinGivenNames = latinGivenNames,
    nativeFullName = nativeFullName,
    nationality = nationality,
    issuingAuthority = issuingAuthority,
    sex = sex,
    dateOfBirth = dateOfBirth,
    dateOfExpiry = dateOfExpiry,
    documentType = documentType,
    personalNumber = personalNumber,
    placeOfBirth = placeOfBirth,
    sodHex = sod?.toHexLower() ?: "",
    dg1Hex = dg1?.toHexLower(),
    dg2Hex = dg2?.toHexLower(),
    dg11Hex = dg11?.toHexLower(),
    dg12Hex = dg12?.toHexLower(),
    dg15Hex = dg15?.toHexLower(),
    activeAuthChallengeHex = activeAuthChallengeHex,
    activeAuthSignatureHex = activeAuthSignatureHex,
    activeAuthVerifiedLocally = activeAuthVerifiedLocally,
)

/**
 * Map the issuer ack to the UI outcome. An auto-minted (approved) packet with a
 * credential id anchors in the background; everything else is awaiting review.
 */
internal fun AttestationSubmitAck.toIssuanceOutcome(): IDDocumentIssuanceOutcome =
    if (status == "approved" && !credentialId.isNullOrEmpty()) {
        IDDocumentIssuanceOutcome.SubmittedForAnchor(credentialId!!)
    } else {
        IDDocumentIssuanceOutcome.PendingReview(
            pendingId = pendingId,
            proofPreVerified = proofPreVerified,
            reason = proofPreVerifiedReason,
        )
    }
