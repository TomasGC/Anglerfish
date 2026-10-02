package app.anglerfish.nat

import android.net.VpnService
import app.anglerfish.dns.Ipv4UdpDatagram
import app.anglerfish.dns.Ipv4UdpPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

private const val UDP_IDLE_TIMEOUT_MS = 60_000L
private const val TCP_IDLE_TIMEOUT_MS = 5 * 60_000L

// Dispatches a raw packet from the tun device to the right protocol's relay, creating a
// session-table entry on first contact (a fresh UDP packet, or a TCP SYN) and routing subsequent
// packets to the existing entry. Mirrors DnsInterceptor's orchestrator role from #40, but for
// ongoing bidirectional flows instead of one request/response.
//
// Single-caller contract: handleOutgoingPacket assumes it is only ever called from one thread at a
// time (the eventual #42 tun-read loop) -- the get-then-create-then-put sequence in handleUdp/
// handleTcp is not itself synchronized, and would race if called concurrently.
class NatRelay(
    private val vpnService: VpnService,
    private val tunWriter: TunWriter,
    private val scope: CoroutineScope,
) {
    private val udpSessions = SessionTable<UdpRelay>()
    private val tcpSessions = SessionTable<TcpRelay>()

    fun handleOutgoingPacket(raw: ByteArray, now: Long) {
        try {
            dispatchOutgoingPacket(raw, now)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // malformed input, or a real-socket failure while creating a new session -- one bad
            // packet must never take down the shared packet-dispatch loop
        }
    }

    fun evictIdle(now: Long) {
        udpSessions.evictIdle(now, UDP_IDLE_TIMEOUT_MS).forEach { it.close() }
        tcpSessions.evictIdle(now, TCP_IDLE_TIMEOUT_MS).forEach { it.close() }
    }

    private fun dispatchOutgoingPacket(raw: ByteArray, now: Long) {
        val udpDatagram = Ipv4UdpPacket.parse(raw)
        if (udpDatagram != null) {
            handleUdp(udpDatagram, now)
            return
        }
        Ipv4TcpPacket.parse(raw)?.let { handleTcp(it, now) }
    }

    private fun handleUdp(datagram: Ipv4UdpDatagram, now: Long) {
        val key = FlowKey(
            TransportProtocol.UDP,
            datagram.sourceAddress,
            datagram.sourcePort,
            datagram.destAddress,
            datagram.destPort,
        )
        val relay = udpSessions.get(key) ?: createUdpRelay(key, now)
        udpSessions.touch(key, now)
        relay.sendToDestination(datagram.payload)
    }

    private fun createUdpRelay(key: FlowKey, now: Long): UdpRelay {
        lateinit var relay: UdpRelay
        relay = UdpRelay(vpnService, key.toEndpoints(), tunWriter, scope, onClosed = { udpSessions.remove(key, relay) })
        udpSessions.put(key, relay, now)
        return relay
    }

    private fun handleTcp(segment: Ipv4TcpSegment, now: Long) {
        val key = FlowKey(
            TransportProtocol.TCP,
            segment.sourceAddress,
            segment.sourcePort,
            segment.destAddress,
            segment.destPort,
        )
        val existing = tcpSessions.get(key)
        if (existing != null && existing.isActive()) {
            // A bare SYN (no ACK) for an entry that's still live is a retransmit of the original SYN
            // during normal connect latency, not a new connection -- a dying-but-not-yet-evicted
            // entry is handled by the isActive() check above routing it to the "start fresh" path
            // instead, since isActive() is false once that entry has reached CLOSED.
            if (!(segment.syn && !segment.ack)) {
                tcpSessions.touch(key, now)
                existing.handle(segment)
            }
            return
        }
        if (!segment.syn) return
        existing?.let { tcpSessions.remove(key, it) }
        startTcpRelay(key, segment, now)
    }

    private fun startTcpRelay(key: FlowKey, segment: Ipv4TcpSegment, now: Long) {
        lateinit var relay: TcpRelay
        relay = TcpRelay(vpnService, key.toEndpoints(), tunWriter, scope, onClosed = { tcpSessions.remove(key, relay) })
        tcpSessions.put(key, relay, now)
        relay.start(segment.sequenceNumber, segment.windowSize)
    }
}
