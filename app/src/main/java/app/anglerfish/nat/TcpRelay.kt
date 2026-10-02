package app.anglerfish.nat

import android.net.VpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

// Owns the real outbound Socket for one TCP flow and drives TcpStateMachine against it. The
// app-facing side of this connection is entirely synthesized -- these TCP segments never touch a
// real OS TCP stack on their way out, so every sequence number, ACK, and checksum has to be
// correct by construction (see TcpStateMachine/Ipv4TcpPacket), not delegated to a socket API.
//
// The SYN-ACK is deliberately NOT sent until the real connect() succeeds -- sending it any earlier
// would let the app complete its three-way handshake and send real data (a TLS ClientHello, an
// HTTP request) before the real socket exists to receive it, silently dropping the app's first
// bytes on every connection.
class TcpRelay(
    private val vpnService: VpnService,
    private val endpoints: FlowEndpoints,
    private val tunWriter: TunWriter,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit,
) {
    private val stateLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var connection = TcpConnection(TcpConnectionState.SYN_RECEIVED, sequenceNumber = 0L, ackNumber = 0L)

    private var socket: Socket? = null
    private var relayJob: Job? = null
    private var pendingAppSequenceNumber = 0L
    private var pendingAppWindow = 0

    fun isActive(): Boolean = connection.state != TcpConnectionState.CLOSED

    fun start(appSequenceNumber: Long, appWindow: Int) {
        pendingAppSequenceNumber = appSequenceNumber
        pendingAppWindow = appWindow
        relayJob = scope.launch(Dispatchers.IO) { runRelay() }
    }

    fun handle(segment: Ipv4TcpSegment) {
        transition { TcpStateMachine.onSegment(it, segment) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        relayJob?.cancel()
        socket?.close()
        onClosed()
    }

    // Computes the next connection state under a lock and performs the resulting actions (tun
    // writes, the blocking socket write/shutdown) outside it. Without the lock, the packet-handling
    // call (handle()) and this relay's own IO coroutine (reacting to real socket reads) raced on the
    // same read-modify-write of `connection` -- @Volatile makes each individual read/write visible
    // across threads, but not the read-compute-write sequence atomic, so whichever thread wrote last
    // silently discarded the other's sequence/ack advance.
    private fun transition(compute: (TcpConnection) -> TcpTransitionResult) {
        val actions = synchronized(stateLock) {
            val result = compute(connection)
            connection = result.connection
            result.actions
        }
        actions.forEach { action -> performAction(action) }
    }

    private fun performAction(action: TcpAction) {
        when (action) {
            is TcpAction.SendSegment -> tunWriter.write(buildOutgoingSegment(action.segment))
            is TcpAction.DeliverToDestination -> {
                try {
                    socket?.getOutputStream()?.write(action.payload)
                } catch (_: IOException) {
                    transition { TcpStateMachine.onDestinationUnreachable(it) }
                    close()
                }
            }
            TcpAction.CloseDestinationSocket -> close()
            TcpAction.ShutdownDestinationOutput -> {
                // the socket may already be on its way down; nothing left to shut down cleanly
                try { socket?.shutdownOutput() } catch (_: IOException) { }
            }
        }
    }

    private fun buildOutgoingSegment(toSend: TcpSegmentToSend): ByteArray {
        val segment = Ipv4TcpSegment(
            sourceAddress = endpoints.destAddress,
            sourcePort = endpoints.destPort,
            destAddress = endpoints.sourceAddress,
            destPort = endpoints.sourcePort,
            sequenceNumber = toSend.sequenceNumber,
            ackNumber = toSend.ackNumber,
            syn = toSend.syn,
            ack = toSend.ack,
            fin = toSend.fin,
            rst = toSend.rst,
            psh = toSend.payload.isNotEmpty(),
            windowSize = DEFAULT_WINDOW_SIZE,
            payload = toSend.payload,
        )
        return Ipv4TcpPacket.build(segment)
    }

    // Guards the whole relay lifetime against anything unexpected (a malformed outgoing segment
    // reaching Ipv4TcpPacket.build, an unanticipated exception from the real socket) -- this runs on
    // its own coroutine, so an uncaught exception here would otherwise propagate to the scope's
    // handler and, depending on how #42 wires the scope, could take down every other flow's relay.
    private suspend fun runRelay() {
        try {
            connectAndRelay()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            close()
        }
    }

    private fun connectAndRelay() {
        val realSocket = try {
            Socket().apply {
                vpnService.protect(this)
                connect(InetSocketAddress(endpoints.destAddress, endpoints.destPort), CONNECT_TIMEOUT_MS)
            }
        } catch (_: IOException) {
            transition { TcpStateMachine.onConnectFailed(pendingAppSequenceNumber) }
            close()
            return
        }
        if (closed.get()) {
            realSocket.close()
            return
        }
        socket = realSocket
        val initialSequenceNumber = Random.nextInt().toLong() and SEQUENCE_MASK
        transition { TcpStateMachine.onSyn(initialSequenceNumber, pendingAppSequenceNumber, pendingAppWindow) }
        relayFromDestination(realSocket)
    }

    // Pacing how fast we pull from the real socket to what the app's own advertised window allows:
    // without this, data arrives from the destination (often much faster than a slow or
    // backgrounded app can read it) faster than the app's window permits, the app's kernel drops
    // what doesn't fit, and -- since this relay has no retransmission of its own -- that data is
    // gone for good and the flow stalls permanently. This only throttles; it does not resend
    // anything the app's window has already forced it to drop.
    private fun relayFromDestination(realSocket: Socket) {
        val buffer = ByteArray(MAX_SEGMENT_SIZE)
        while (!closed.get()) {
            val current = connection
            val unacked = (current.sequenceNumber - current.appAckNumber) and SEQUENCE_MASK
            if (unacked >= current.appWindow) {
                Thread.sleep(WINDOW_POLL_INTERVAL_MS)
                continue
            }
            val length = try {
                realSocket.getInputStream().read(buffer)
            } catch (_: IOException) {
                transition { TcpStateMachine.onDestinationUnreachable(it) }
                close()
                return
            }
            if (length < 0) {
                transition { TcpStateMachine.onDestinationClosed(it) }
                return
            }
            val chunk = buffer.copyOfRange(0, length)
            transition { TcpStateMachine.onDataFromDestination(it, chunk) }
        }
    }

    private companion object {
        const val SEQUENCE_MASK = 0xFFFFFFFFL
        const val DEFAULT_WINDOW_SIZE = 65535
        const val MAX_SEGMENT_SIZE = 1400
        const val CONNECT_TIMEOUT_MS = 10_000
        const val WINDOW_POLL_INTERVAL_MS = 50L
    }
}
