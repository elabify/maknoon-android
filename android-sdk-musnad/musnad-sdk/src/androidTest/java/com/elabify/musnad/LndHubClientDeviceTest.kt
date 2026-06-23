// LNDHub client tests (Lightning) via MockWebServer: auth token exchange,
// authed balance, and connection-string parsing.

package com.elabify.musnad

import com.elabify.musnad.wallet.LndHubClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LndHubClientDeviceTest {

    @Test
    fun authAndBalance() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"tok","refresh_token":"r"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"BTC":{"AvailableBalance":12345}}"""))
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val client = LndHubClient(base)
            val tokens = client.auth("alice", "secret")
            assertEquals("tok", tokens.accessToken)
            val authReq = server.takeRequest()
            assertEquals("/auth?type=auth", authReq.path)
            assertTrue(authReq.body.readUtf8().contains("\"login\""))

            assertEquals(12345L, client.balanceSats("tok"))
            assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun parsesConnectionString() {
        val (login, password, base) = LndHubClient.parseConnection("lndhub://abc:def@lndhub.example.com")!!
        assertEquals("abc", login)
        assertEquals("def", password)
        assertEquals("https://lndhub.example.com", base)
    }
}
