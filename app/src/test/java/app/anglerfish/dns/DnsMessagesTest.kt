package app.anglerfish.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Type

class DnsMessagesTest {

    private fun buildQuery(domain: String): Message {
        val name = Name.fromString(if (domain.endsWith(".")) domain else "$domain.")
        val question = Record.newRecord(name, Type.A, DClass.IN)
        return Message.newQuery(question)
    }

    @Test
    fun `parseQuery parses a well-formed DNS query`() {
        val wire = buildQuery("doubleclick.net").toWire()

        val parsed = DnsMessages.parseQuery(wire)

        requireNotNull(parsed)
        assertEquals("doubleclick.net.", DnsMessages.domainOf(parsed))
    }

    @Test
    fun `parseQuery returns null for garbage bytes`() {
        assertNull(DnsMessages.parseQuery(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `parseQuery returns null for an empty payload`() {
        assertNull(DnsMessages.parseQuery(ByteArray(0)))
    }

    @Test
    fun `buildNxDomainResponse matches the query's transaction ID and carries NXDOMAIN`() {
        val query = buildQuery("ads.example.com")

        val responseBytes = DnsMessages.buildNxDomainResponse(query)
        val response = Message(responseBytes)

        assertEquals(query.getHeader().getID(), response.getHeader().getID())
        assertEquals(Rcode.NXDOMAIN, response.getHeader().getRcode())
        assertTrue(response.getHeader().getFlag(Flags.QR.toInt()))
        assertEquals(query.getQuestion().getName(), response.getQuestion().getName())
    }
}
