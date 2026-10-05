package app.anglerfish.dns

import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section
import java.io.IOException

// Thin wrapper over dnsjava's wire-format parsing/building -- hand-rolling DNS message parsing
// (domain-name decompression especially) is a well-known source of real bugs, including
// compression-pointer loop vulnerabilities; dnsjava is pure Java (no native code) and handles it
// correctly. Explicit Java getter/setter calls throughout (never Kotlin property-access sugar)
// since some dnsjava accessor names (e.g. getID()) don't decapitalize cleanly into a Kotlin
// property.
object DnsMessages {

    fun parseQuery(payload: ByteArray): Message? =
        try {
            val message = Message(payload)
            if (message.getQuestion() != null) message else null
        } catch (_: IOException) {
            null
        }

    fun domainOf(message: Message): String = message.getQuestion().getName().toString()

    fun buildNxDomainResponse(query: Message): ByteArray {
        val response = Message(query.getHeader().getID())
        response.getHeader().setFlag(Flags.QR.toInt())
        response.getHeader().setRcode(Rcode.NXDOMAIN)
        response.addRecord(query.getQuestion(), Section.QUESTION)
        return response.toWire()
    }
}
