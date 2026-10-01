package app.anglerfish.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistParsingTest {

    @Test
    fun `parseHostsFile extracts domains from standard 0_0_0_0-sink hosts-file lines`() {
        val text = "0.0.0.0 ads.example.com\n0.0.0.0 tracker.example.com"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com", "tracker.example.com"), result)
    }

    @Test
    fun `parseHostsFile rejects lines whose sink address is not 0_0_0_0`() {
        // StevenBlack's own list header uses 127.0.0.1/::1 for localhost/broadcast bootstrapping
        // entries (localhost, local, broadcasthost, ip6-*) -- those aren't ad domains, and
        // "127.0.0.1 local" would otherwise block every *.local mDNS name (printers, NAS boxes)
        // once suffix matching is applied.
        val text = "127.0.0.1 local\n::1 localhost\n0.0.0.0 ads.example.com"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com"), result)
    }

    @Test
    fun `parseHostsFile rejects a self-referential sink-address-as-domain line`() {
        // The real file's own "0.0.0.0 0.0.0.0" header line would otherwise add the sink
        // address itself as a blocked "domain".
        val text = "0.0.0.0 0.0.0.0\n0.0.0.0 ads.example.com"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com"), result)
    }

    @Test
    fun `parseHostsFile lowercases extracted domains`() {
        val text = "0.0.0.0 Ads.Example.COM"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com"), result)
    }

    @Test
    fun `parseHostsFile skips comment lines`() {
        val text = "# this is a comment\n0.0.0.0 ads.example.com"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com"), result)
    }

    @Test
    fun `parseHostsFile skips blank lines`() {
        val text = "0.0.0.0 ads.example.com\n\n   \n0.0.0.0 tracker.example.com"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com", "tracker.example.com"), result)
    }

    @Test
    fun `parseHostsFile skips malformed lines with no domain token`() {
        val text = "0.0.0.0\n0.0.0.0 ads.example.com"

        val result = parseHostsFile(text)

        assertEquals(setOf("ads.example.com"), result)
    }

    @Test
    fun `parseHostsFile on garbled input with no second token per line returns an empty set rather than throwing`() {
        val result = parseHostsFile("notahostsfile\nalsonotone")

        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `isDomainBlocked returns true for an exact match`() {
        val blocklist = setOf("doubleclick.net")

        assertTrue(isDomainBlocked("doubleclick.net", blocklist))
    }

    @Test
    fun `isDomainBlocked returns true for a subdomain of a blocked entry`() {
        val blocklist = setOf("doubleclick.net")

        assertTrue(isDomainBlocked("ads.doubleclick.net", blocklist))
    }

    @Test
    fun `isDomainBlocked returns true for a multi-level subdomain`() {
        val blocklist = setOf("doubleclick.net")

        assertTrue(isDomainBlocked("pagead2.ads.doubleclick.net", blocklist))
    }

    @Test
    fun `isDomainBlocked returns false for an unrelated domain`() {
        val blocklist = setOf("doubleclick.net")

        assertFalse(isDomainBlocked("example.com", blocklist))
    }

    @Test
    fun `isDomainBlocked does not false-positive on a domain that merely ends with a blocked string`() {
        val blocklist = setOf("doubleclick.net")

        assertFalse(isDomainBlocked("evildoubleclick.net", blocklist))
    }

    @Test
    fun `isDomainBlocked matches regardless of the queried domain's letter case`() {
        val blocklist = setOf("doubleclick.net")

        assertTrue(isDomainBlocked("DoubleClick.NET", blocklist))
    }

    @Test
    fun `isDomainBlocked matches a fully-qualified domain with a trailing dot`() {
        val blocklist = setOf("doubleclick.net")

        assertTrue(isDomainBlocked("doubleclick.net.", blocklist))
    }
}
