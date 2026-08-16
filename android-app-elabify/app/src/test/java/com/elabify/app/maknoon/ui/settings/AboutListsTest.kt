package com.elabify.app.maknoon.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The About screen's two lists are alphabetical, and iOS shows the same entries.
 *
 * Both were hand-ordered before: services roughly by topic, components in
 * whatever order they were added, with a comment claiming "the exact iOS order"
 * that nothing enforced. Neither is a list a reader can scan, and the two
 * platforms could drift apart with nothing to catch it. Sorting them is only
 * half the fix; this is the half that survives the next entry someone appends
 * to the bottom.
 *
 * Case-insensitive because that is how a reader scans a list: "mempool.space"
 * belongs between Matter Labs and Optimism, not after Trust Wallet.
 */
class AboutListsTest {
    @Test
    fun `default services are alphabetical`() {
        val names = SERVICES.map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    @Test
    fun `open-source components are alphabetical`() {
        val names = COMPONENTS.map { it.name }
        assertEquals(names.sortedBy { it.lowercase() }, names)
    }

    @Test
    fun `no duplicate entries`() {
        // A duplicate is invisible in a long sorted list, and reads as two
        // separate credits for the same project.
        assertEquals(SERVICES.map { it.name }.distinct().size, SERVICES.size)
        assertEquals(COMPONENTS.map { it.name }.distinct().size, COMPONENTS.size)
    }

    @Test
    fun `every entry has a name, a description and an https url`() {
        for (s in SERVICES) {
            assertTrue("service has no name", s.name.isNotBlank())
            assertTrue("${s.name} has no description", s.description.isNotBlank())
            assertTrue("${s.name} url is not https: ${s.url}", s.url.startsWith("https://"))
        }
        for (c in COMPONENTS) {
            assertTrue("component has no name", c.name.isNotBlank())
            assertTrue("${c.name} has no license", c.license.isNotBlank())
            assertTrue("${c.name} url is not https: ${c.url}", c.url.startsWith("https://"))
        }
    }

    @Test
    fun `the chains we ship are credited`() {
        // Every network in the picker reaches somebody's RPC by default, and the
        // About screen is where we say whose. Pharos and HashKey were added to
        // the network catalog without a credit, which is the gap this pins.
        val names = SERVICES.map { it.name }
        for (expected in listOf("Pharos", "HashKey Chain", "Base", "PublicNode")) {
            assertTrue("$expected is not credited", names.contains(expected))
        }
    }
}
