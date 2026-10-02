package app.anglerfish.nat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class TcpStateMachineTest {

    private val dummyAddress = InetAddress.getByName("10.0.0.2")

    private fun incoming(
        sequenceNumber: Long,
        ackNumber: Long = 0L,
        syn: Boolean = false,
        ack: Boolean = false,
        fin: Boolean = false,
        rst: Boolean = false,
        windowSize: Int = 65535,
        payload: ByteArray = ByteArray(0),
    ) = Ipv4TcpSegment(
        sourceAddress = dummyAddress,
        sourcePort = 1,
        destAddress = dummyAddress,
        destPort = 2,
        sequenceNumber = sequenceNumber,
        ackNumber = ackNumber,
        syn = syn,
        ack = ack,
        fin = fin,
        rst = rst,
        psh = payload.isNotEmpty(),
        windowSize = windowSize,
        payload = payload,
    )

    private fun synReceived(initialSequenceNumber: Long = 5000L, appSequenceNumber: Long = 100L) =
        TcpStateMachine.onSyn(initialSequenceNumber, appSequenceNumber, appWindow = 65535).connection

    @Test
    fun `onSyn moves to SYN_RECEIVED and emits a matching SYN-ACK`() {
        val result = TcpStateMachine.onSyn(initialSequenceNumber = 5000L, appSequenceNumber = 100L, appWindow = 65535)

        assertEquals(TcpConnectionState.SYN_RECEIVED, result.connection.state)
        assertEquals(5001L, result.connection.sequenceNumber)
        assertEquals(101L, result.connection.ackNumber)
        val sent = (result.actions.single() as TcpAction.SendSegment).segment
        assertTrue(sent.syn)
        assertTrue(sent.ack)
        assertEquals(5000L, sent.sequenceNumber)
        assertEquals(101L, sent.ackNumber)
    }

    @Test
    fun `onSyn near the 32-bit boundary wraps the tracked sequence and ack numbers`() {
        val result = TcpStateMachine.onSyn(initialSequenceNumber = 0xFFFFFFFFL, appSequenceNumber = 0xFFFFFFFFL, appWindow = 65535)

        assertEquals(0L, result.connection.sequenceNumber)
        assertEquals(0L, result.connection.ackNumber)
    }

    @Test
    fun `onConnectFailed replies with RST-ACK using the app's sequence number and no live connection`() {
        val result = TcpStateMachine.onConnectFailed(appSequenceNumber = 100L)

        assertEquals(TcpConnectionState.CLOSED, result.connection.state)
        val sent = (result.actions.single() as TcpAction.SendSegment).segment
        assertTrue(sent.rst)
        assertTrue(sent.ack)
        assertEquals(0L, sent.sequenceNumber)
        assertEquals(101L, sent.ackNumber)
    }

    @Test
    fun `a pure ACK completing the handshake moves to ESTABLISHED with no actions`() {
        val afterSyn = synReceived()

        val result = TcpStateMachine.onSegment(afterSyn, incoming(sequenceNumber = 101L, ack = true))

        assertEquals(TcpConnectionState.ESTABLISHED, result.connection.state)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `an ACK carrying data during the handshake is delivered once ESTABLISHED`() {
        val afterSyn = synReceived()

        val result = TcpStateMachine.onSegment(afterSyn, incoming(sequenceNumber = 101L, ack = true, payload = byteArrayOf(7)))

        assertEquals(TcpConnectionState.ESTABLISHED, result.connection.state)
        val delivered = result.actions.first() as TcpAction.DeliverToDestination
        assertArrayEquals(byteArrayOf(7), delivered.payload)
    }

    @Test
    fun `an ACK segment updates the tracked peer ack number and window`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 101L, ackNumber = 4000L, ack = true, windowSize = 8192))

        assertEquals(4000L, result.connection.appAckNumber)
        assertEquals(8192, result.connection.appWindow)
    }

    @Test
    fun `new in-order data is delivered and advances the ack number by the payload size`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 101L, ack = true, payload = byteArrayOf(1, 2, 3)))

        assertEquals(104L, result.connection.ackNumber)
        val delivered = result.actions[0] as TcpAction.DeliverToDestination
        assertArrayEquals(byteArrayOf(1, 2, 3), delivered.payload)
        val ackSent = (result.actions[1] as TcpAction.SendSegment).segment
        assertEquals(104L, ackSent.ackNumber)
    }

    @Test
    fun `new data whose ack number would wrap past 32 bits masks correctly`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 0xFFFFFFFEL)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 0xFFFFFFFEL, ack = true, payload = byteArrayOf(1, 2, 3)))

        assertEquals(1L, result.connection.ackNumber) // 0xFFFFFFFE + 3, masked to 32 bits
    }

    @Test
    fun `a duplicate or out-of-order segment is re-ACKed without being delivered`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        // sequenceNumber does not match the expected ackNumber (101L) -- already-seen or reordered data.
        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 50L, ack = true, payload = byteArrayOf(9)))

        assertEquals(101L, result.connection.ackNumber)
        val action = result.actions.single() as TcpAction.SendSegment
        assertEquals(101L, action.segment.ackNumber)
        assertTrue(action.segment.payload.isEmpty())
    }

    @Test
    fun `an out-of-order FIN is re-ACKed without advancing state, instead of being delivered`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        // sequenceNumber (50L) does not match the expected ackNumber (101L): a gap exists before this FIN.
        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 50L, ack = true, fin = true, payload = byteArrayOf(9)))

        assertEquals(TcpConnectionState.ESTABLISHED, result.connection.state)
        assertEquals(101L, result.connection.ackNumber)
        val action = result.actions.single() as TcpAction.SendSegment
        assertEquals(101L, action.segment.ackNumber)
        assertFalse(action.segment.fin)
    }

    @Test
    fun `a pure ACK with no payload in ESTABLISHED produces no actions`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 101L, ack = true))

        assertTrue(result.actions.isEmpty())
        assertEquals(established.copy(appAckNumber = 0L, appWindow = 65535), result.connection)
    }

    @Test
    fun `a FIN with no payload moves to CLOSE_WAIT, acks sequenceNumber plus one, and shuts down the destination output`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 101L, ack = true, fin = true))

        assertEquals(TcpConnectionState.CLOSE_WAIT, result.connection.state)
        assertEquals(102L, result.connection.ackNumber)
        val sent = (result.actions.filterIsInstance<TcpAction.SendSegment>().single()).segment
        assertTrue(sent.fin)
        assertEquals(102L, sent.ackNumber)
        assertTrue(result.actions.contains(TcpAction.ShutdownDestinationOutput))
    }

    @Test
    fun `a FIN carrying a final chunk of payload acks past both the payload and the FIN itself`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 101L, ack = true, fin = true, payload = byteArrayOf(1, 2)))

        assertEquals(104L, result.connection.ackNumber) // 101 + 2 payload bytes + 1 for FIN
        val delivered = result.actions[0] as TcpAction.DeliverToDestination
        assertArrayEquals(byteArrayOf(1, 2), delivered.payload)
        val sent = (result.actions[1] as TcpAction.SendSegment).segment
        assertTrue(sent.fin)
        assertEquals(104L, sent.ackNumber)
    }

    @Test
    fun `the app's own FIN acknowledgement completes teardown from LAST_ACK`() {
        val closeWait = TcpConnection(TcpConnectionState.CLOSE_WAIT, sequenceNumber = 5001L, ackNumber = 102L)
        val afterOurFin = TcpStateMachine.onDestinationClosed(closeWait)

        assertEquals(TcpConnectionState.LAST_ACK, afterOurFin.connection.state)
        val ourFin = (afterOurFin.actions.single() as TcpAction.SendSegment).segment
        assertEquals(5001L, ourFin.sequenceNumber)

        val result = TcpStateMachine.onSegment(afterOurFin.connection, incoming(sequenceNumber = 102L, ackNumber = 5002L, ack = true))

        assertEquals(TcpConnectionState.CLOSED, result.connection.state)
        assertEquals(TcpAction.CloseDestinationSocket, result.actions.single())
    }

    @Test
    fun `an ACK of our FIN in FIN_WAIT closes the destination socket`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)
        val afterOurFin = TcpStateMachine.onDestinationClosed(established)

        assertEquals(TcpConnectionState.FIN_WAIT, afterOurFin.connection.state)

        val result = TcpStateMachine.onSegment(afterOurFin.connection, incoming(sequenceNumber = 101L, ackNumber = 5002L, ack = true))

        assertEquals(TcpConnectionState.CLOSED, result.connection.state)
        assertEquals(TcpAction.CloseDestinationSocket, result.actions.single())
    }

    @Test
    fun `an ACK that does not cover our FIN in FIN_WAIT produces no actions`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)
        val afterOurFin = TcpStateMachine.onDestinationClosed(established)

        val result = TcpStateMachine.onSegment(afterOurFin.connection, incoming(sequenceNumber = 101L, ackNumber = 999L, ack = true))

        assertTrue(result.actions.isEmpty())
        assertEquals(TcpConnectionState.FIN_WAIT, result.connection.state)
    }

    @Test
    fun `a non-ACK segment in FIN_WAIT produces no actions`() {
        val finWait = TcpConnection(TcpConnectionState.FIN_WAIT, sequenceNumber = 5002L, ackNumber = 102L)

        val result = TcpStateMachine.onSegment(finWait, incoming(sequenceNumber = 102L))

        assertTrue(result.actions.isEmpty())
        assertEquals(TcpConnectionState.FIN_WAIT, result.connection.state)
    }

    @Test
    fun `a RST from any state closes the destination socket immediately`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onSegment(established, incoming(sequenceNumber = 101L, rst = true))

        assertEquals(TcpConnectionState.CLOSED, result.connection.state)
        assertEquals(TcpAction.CloseDestinationSocket, result.actions.single())
    }

    @Test
    fun `onDataFromDestination advances our own sequence number and emits a data segment`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onDataFromDestination(established, byteArrayOf(1, 2, 3, 4))

        assertEquals(5005L, result.connection.sequenceNumber)
        val sent = (result.actions.single() as TcpAction.SendSegment).segment
        assertEquals(5001L, sent.sequenceNumber)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), sent.payload.toList())
    }

    @Test
    fun `onDataFromDestination masks our own sequence number past the 32-bit boundary`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 0xFFFFFFFEL, ackNumber = 101L)

        val result = TcpStateMachine.onDataFromDestination(established, byteArrayOf(1, 2, 3))

        assertEquals(1L, result.connection.sequenceNumber) // 0xFFFFFFFE + 3, masked to 32 bits
    }

    @Test
    fun `onDestinationClosed from ESTABLISHED moves to FIN_WAIT and emits our own FIN`() {
        val established = TcpConnection(TcpConnectionState.ESTABLISHED, sequenceNumber = 5001L, ackNumber = 101L)

        val result = TcpStateMachine.onDestinationClosed(established)

        assertEquals(TcpConnectionState.FIN_WAIT, result.connection.state)
        assertEquals(5002L, result.connection.sequenceNumber)
        val sent = (result.actions.single() as TcpAction.SendSegment).segment
        assertTrue(sent.fin)
        assertEquals(5001L, sent.sequenceNumber)
    }

    @Test
    fun `onDestinationClosed from CLOSE_WAIT moves to LAST_ACK`() {
        val closeWait = TcpConnection(TcpConnectionState.CLOSE_WAIT, sequenceNumber = 5001L, ackNumber = 102L)

        val result = TcpStateMachine.onDestinationClosed(closeWait)

        assertEquals(TcpConnectionState.LAST_ACK, result.connection.state)
    }

    @Test
    fun `onDestinationUnreachable closes the connection and emits a RST`() {
        val synReceivedConnection = synReceived()

        val result = TcpStateMachine.onDestinationUnreachable(synReceivedConnection)

        assertEquals(TcpConnectionState.CLOSED, result.connection.state)
        val sent = (result.actions.single() as TcpAction.SendSegment).segment
        assertTrue(sent.rst)
    }
}
