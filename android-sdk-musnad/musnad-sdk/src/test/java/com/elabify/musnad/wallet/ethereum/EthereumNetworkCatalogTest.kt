package com.elabify.musnad.wallet.ethereum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural checks over the EVM network catalog.
 *
 * The enum's own header says adding a chain is "a one-case addition here plus
 * per-case", and every per-case member is a separate `when`. Kotlin's exhaustive
 * `when` catches a MISSING arm at compile time, but nothing catches a WRONG one:
 * a duplicated chain id, a copy-pasted RPC or explorer host, or a testnet left
 * out of `isTestnet` so the wallet offers fiat conversion on play money. These
 * assert the properties that hold across every case, so the next chain addition
 * is checked rather than reviewed.
 *
 * Mirrors MaknoonTests/EthereumNetworkCatalogTests.swift; iOS is the reference
 * for the catalog's contents and these two files must agree.
 */
class EthereumNetworkCatalogTest {

    @Test
    fun `chain ids are unique`() {
        val seen = mutableMapOf<Long, EthereumNetwork>()
        for (n in EthereumNetwork.entries) {
            val clash = seen[n.chainId]
            if (clash != null) {
                throw AssertionError("${n.name} shares chain id ${n.chainId} with ${clash.name}")
            }
            seen[n.chainId] = n
        }
        assertEquals(EthereumNetwork.entries.size, seen.size)
    }

    @Test
    fun `raw values are unique and stable across platforms`() {
        // The raw value is a persisted JSON key and must equal the Swift case
        // name, so a rename silently orphans a user's saved wallet selection.
        val raws = EthereumNetwork.entries.map { it.rawValue }
        assertEquals("raw values must be unique", raws.size, raws.toSet().size)
        for (raw in raws) {
            assertTrue("raw value '$raw' must be lowerCamelCase", raw.first().isLowerCase())
            assertFalse("raw value '$raw' must not contain '_'", raw.contains('_'))
        }
    }

    @Test
    fun `every network has a usable rpc and explorer`() {
        for (n in EthereumNetwork.entries) {
            assertTrue("${n.name} rpc", n.defaultRPCURL.startsWith("https://"))
            assertTrue("${n.name} explorer", n.defaultExplorerURL.startsWith("https://"))
        }
    }

    @Test
    fun `no two networks share an rpc or explorer host`() {
        // The copy-paste failure: a new case that keeps its neighbour's endpoint
        // reads correctly and silently talks to the wrong chain.
        fun host(url: String) = url.removePrefix("https://").trimEnd('/').substringBefore('/')
        val rpcs = mutableMapOf<String, EthereumNetwork>()
        val explorers = mutableMapOf<String, EthereumNetwork>()
        for (n in EthereumNetwork.entries) {
            val r = host(n.defaultRPCURL)
            rpcs[r]?.let { throw AssertionError("${n.name} shares RPC host $r with ${it.name}") }
            rpcs[r] = n
            val e = host(n.defaultExplorerURL)
            explorers[e]?.let { throw AssertionError("${n.name} shares explorer $e with ${it.name}") }
            explorers[e] = n
        }
    }

    @Test
    fun `testnets are classified as testnets and carry no fiat price`() {
        for (n in EthereumNetwork.entries.filter { it.isTestnet }) {
            assertEquals("${n.name} classification", EthereumNetwork.Classification.TESTNET, n.classification)
            assertNull("${n.name} must have no CoinGecko id", n.coinGeckoAssetId)
        }
        for (n in EthereumNetwork.entries.filter { it.classification == EthereumNetwork.Classification.TESTNET }) {
            assertTrue("${n.name} is classified TESTNET but isTestnet is false", n.isTestnet)
        }
    }

    @Test
    fun `pharos atlantic and hashkey testnet are wired as expected`() {
        val pharos = EthereumNetwork.PHAROS_ATLANTIC_TESTNET
        assertEquals(688689L, pharos.chainId)
        assertEquals("PHRS", pharos.ticker)
        assertTrue(pharos.isTestnet)
        // Atlantic, not the unreachable 688688 host.
        assertTrue(pharos.defaultRPCURL.contains("atlantic."))
        // No Etherscan-style read API: the wallet falls back to RPC-only history.
        assertNull(pharos.defaultExplorerAPIURL)

        val hashkey = EthereumNetwork.HASHKEY_TESTNET
        assertEquals(133L, hashkey.chainId)
        assertEquals("HSK", hashkey.ticker)
        assertTrue(hashkey.isTestnet)
        assertNotNull(hashkey.defaultExplorerAPIURL)
    }

    @Test
    fun `the retired pharos testnet is absent`() {
        // Pharos runs two testnets and only 688689 is reachable. A case for 688688
        // would offer a user a chain whose RPC rejects every request.
        assertNull(EthereumNetwork.entries.firstOrNull { it.chainId == 688688L })
    }

    @Test
    fun `every network resolves from its chain id and raw value`() {
        for (n in EthereumNetwork.entries) {
            assertEquals(n, EthereumNetwork.entries.first { it.chainId == n.chainId })
            assertEquals(n, EthereumNetwork.entries.first { it.rawValue == n.rawValue })
        }
    }
}
