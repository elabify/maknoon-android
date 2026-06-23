// Networking tests on the Pixel 9. Deterministic request/response shapes via
// MockWebServer, plus a live smoke against the real musnad-dev verifier
// (skipped, not failed, if the device is offline) to prove TLS + JSON parsing
// end-to-end on GrapheneOS without GMS.

package com.elabify.musnad

import com.elabify.musnad.net.IssuerClient
import com.elabify.musnad.net.PickupOutcome
import com.elabify.musnad.net.VerifierClient
import java.io.IOException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class NetworkDeviceTest {

    @Test
    fun reissueChallengeRequestAndParse() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"nonce":"deadbeef","expiresAt":123}"""))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val nonce = IssuerClient(base).reissueChallenge("did:elabify:sepolia:holder:0xabc")
            assertEquals("deadbeef", nonce)
            val rec = server.takeRequest()
            assertEquals("/v1/issuance/reissue/challenge", rec.path)
            assertTrue(rec.body.readUtf8().contains("\"holderDid\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun verifierChallengeParse() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"requestId":"r1","challenge":"0xdead","issuedAt":10,"expiresAt":310}"""),
        )
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val resp = VerifierClient(base).challenge(listOf("age_over_18"))
            assertEquals("r1", resp.requestId)
            assertEquals("0xdead", resp.challenge)
            assertEquals(310L, resp.expiresAt)
            assertEquals("/v1/challenge", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun pickupPendingVsReady() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"pending_anchor","estimatedAnchorAt":99}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"state":"ready","credential":{"x":1}}"""))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val client = IssuerClient(base)
            val pending = client.pickup("$base/v1/issuance/pickup/tok")
            assertTrue(pending is PickupOutcome.Pending && pending.estimatedAnchorAt == 99L)
            val ready = client.pickup("$base/v1/issuance/pickup/tok")
            assertTrue(ready is PickupOutcome.Ready)
        } finally {
            server.shutdown()
        }
    }

    /** Live smoke against the real verifier. Skipped (not failed) if offline. */
    @Test
    fun liveVerifierChallengeSmoke() {
        val verifier = VerifierClient("https://musnad-verifier.elabify.com")
        val resp = try {
            verifier.challenge(listOf("age_over_18"))
        } catch (e: IOException) {
            Assume.assumeNoException("musnad-verifier unreachable; skipping live smoke", e)
            return
        }
        assertTrue("live requestId", resp.requestId.isNotEmpty())
        assertTrue("live challenge hex", resp.challenge.isNotEmpty())
        assertTrue("live expiry in the future-ish", resp.expiresAt >= resp.issuedAt)
    }
}
