package com.elabify.app.maknoon.ui.wallet.ethereum

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the EIP-681 URI parser: a scanned token-payment QR
 * `ethereum:0x<TOKEN>@<chain>/transfer?address=0x<RECIPIENT>...` must resolve the
 * recipient from the `address=` param, NOT the URI target (the token contract).
 * Returning the target would build transfer(<contract>, amount) and send the
 * tokens to the contract instead of the recipient.
 *
 * The KAT JSON (src/test/resources/eip681-parse-kat.json) is the cross-platform
 * contract; it is byte-identical to the inline copy in the iOS
 * EthereumURIParserTests.
 */
class EthereumUriParserTest {

    private fun loadKat(): JSONArray {
        val stream = javaClass.getResourceAsStream("/eip681-parse-kat.json")
            ?: error("eip681-parse-kat.json not found on the test classpath")
        return JSONArray(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun parserKat() {
        val cases = loadKat()
        assertTrue("KAT is empty", cases.length() > 0)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.optString("name")
            val parsed = EthereumUriParser.parse(c.getString("uri"))
            assertEquals("recipient: $name", c.getString("recipient"), parsed.recipient)
            assertEquals("tokenContract: $name", if (c.isNull("tokenContract")) null else c.getString("tokenContract"), parsed.tokenContract)
            assertEquals("amount: $name", if (c.isNull("amountBaseUnits")) null else c.getString("amountBaseUnits"), parsed.amountBaseUnits)
        }
    }

    /** A token-transfer URI: the recipient is the address= param, never the target. */
    @Test
    fun tokenTransferUriResolvesAddressParamNotContract() {
        val uri = "ethereum:0x449b3317a6d1efb1bc3ba0700c9eaa4ffff4ae65@8453/transfer?address=0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f&uint256=14461320000"
        val parsed = EthereumUriParser.parse(uri)
        assertEquals("0x1bd4e1b715213bd0c43d2623af4d77c46a6e5c2f", parsed.recipient.lowercase())
        assertNotEquals("0x449b3317a6d1efb1bc3ba0700c9eaa4ffff4ae65", parsed.recipient.lowercase())
        assertEquals("0x449b3317a6d1efb1bc3ba0700c9eaa4ffff4ae65", parsed.tokenContract?.lowercase())
    }
}
