// Client for the Elabify-hosted Presentation drop pastebin, mirroring iOS
// Network.swift `PresentationDrop`. Used by the offline qrBack flow when the
// holder wants to render their Presentation as a small QR for an in-person
// verifier to scan back. Plain HTTPS only (ADR-0028: no X-Wing/HPKE sealing,
// no BLE transport here).
//
// One-shot semantics: POST /v1/drop stores the presentation and returns a
// small {dropId,expiresAt} envelope; GET /v1/drop/{dropId} returns it exactly
// once (a second fetch 404s, surfaced as NetworkException(404, ...)).

package com.elabify.musnad.net

import com.elabify.musnad.present.DropEnvelope
import com.elabify.musnad.present.Presentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PresentationDrop(
    private val baseUrl: String, // e.g. https://musnad-verifier.elabify.com
    private val http: MaknoonHttp = MaknoonHttp(),
) {
    /**
     * POST /v1/drop with { presentation }. Returns the public envelope (small),
     * suitable for QR encoding. The wire body matches iOS:
     * JSONEncoder().encode(["presentation": presentation]).
     */
    suspend fun upload(presentation: Presentation): DropEnvelope = withContext(Dispatchers.IO) {
        val body = JSONObject().put("presentation", presentation.toJson()).toString()
        val resp = http.postJson("$baseUrl/v1/drop", body)
        DropEnvelope.fromJson(JSONObject(resp))
    }

    /**
     * GET /v1/drop/{dropId}. One-shot: a second fetch 404s (NetworkException).
     * Server wraps the payload as { presentation }; we unwrap it like iOS.
     */
    suspend fun fetch(dropId: String): Presentation = withContext(Dispatchers.IO) {
        val resp = http.getJson("$baseUrl/v1/drop/$dropId")
        val o = JSONObject(resp)
        Presentation.fromJson(o.getJSONObject("presentation"))
    }
}
