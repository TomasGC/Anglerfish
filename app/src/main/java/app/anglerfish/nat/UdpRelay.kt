package app.anglerfish.nat

import android.net.VpnService
import app.anglerfish.data.BlocklistRepository
import app.anglerfish.dns.Ipv4UdpPacket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

// One real, protected UDP socket per flow (protect() before send, same reasoning as #40's
// UdpDnsForwarder: an unprotected socket's own outbound packets would re-enter the tunnel they're
// escaping). The relay coroutine runs for the entry's whole lifetime independently of outgoing app
// traffic, since real data can arrive from the destination at any time.
class UdpRelay(
    vpnService: VpnService,
    private val endpoints: FlowEndpoints,
    private val tunWriter: TunWriter,
    scope: CoroutineScope,
    private val blocklistRepository: BlocklistRepository,
    private val onClosed: () -> Unit,
) : UdpSession {
    private val closed = AtomicBoolean(false)
    private var firstDatagramSeen = false
    private var blocked = false

    private val socket = DatagramSocket().apply {
        vpnService.protect(this)
        connect(endpoints.destAddress, endpoints.destPort)
    }

    private val relayJob: Job = scope.launch(Dispatchers.IO) { relayLoop() }

    // #63: only the flow's first datagram is inspected -- a QUIC Initial packet, if this is one
    // at all. Non-QUIC UDP/443 traffic (QuicInitialPacket.extractClientHello returns null) and
    // every later datagram on an already-decided flow pass straight through, same as before.
    // SniGate is constructed fresh and used once here rather than held as a field, since a QUIC
    // Initial packet's ClientHello realistically always arrives whole in one UDP datagram --
    // unlike TCP, there's no multi-call buffering need on this path; a Pending result from a
    // single offer() call is simply treated the same as "not blocked", consistent with the
    // spec's documented QUIC fragmentation fail-open limit.
    override fun sendToDestination(payload: ByteArray) {
        if (blocked) return
        if (!firstDatagramSeen) {
            firstDatagramSeen = true
            val clientHello = QuicInitialPacket.extractClientHello(payload)
            if (clientHello != null) {
                val gate = SniGate(blocklistRepository::isBlockedNow)
                if (gate.offer(clientHello) == SniGateResult.Blocked) {
                    blocked = true
                    return
                }
            }
        }
        try {
            socket.send(DatagramPacket(payload, payload.size))
        } catch (_: IOException) {
            close()
        }
    }

    // Idempotent and calls onClosed exactly once: without this, a failed send() (on the caller's
    // thread) and the relay coroutine's own failure path could both call close(), each believing it
    // owns the single call to onClosed, and remove whatever entry NatRelay has installed under this
    // flow's key at that moment -- possibly a replacement relay created after this one died.
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        relayJob.cancel()
        socket.close()
        onClosed()
    }

    private fun relayLoop() {
        try {
            relayLoopBody()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            close()
        }
    }

    // relayLoop (the caller) already runs on Dispatchers.IO, so the blocking receive() below needs
    // no further dispatcher switch.
    private fun relayLoopBody() {
        val buffer = ByteArray(MAX_UDP_PACKET_SIZE)
        while (!closed.get()) {
            val length = try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                packet.length
            } catch (_: IOException) {
                close()
                return
            }
            tunWriter.write(
                Ipv4UdpPacket.build(
                    sourceAddress = endpoints.destAddress,
                    sourcePort = endpoints.destPort,
                    destAddress = endpoints.sourceAddress,
                    destPort = endpoints.sourcePort,
                    payload = buffer.copyOfRange(0, length),
                ),
            )
        }
    }

    private companion object {
        const val MAX_UDP_PACKET_SIZE = 65507
    }
}
