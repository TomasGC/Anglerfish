package app.anglerfish.nat

private const val SEQ_MOD_MASK = 0xFFFFFFFFL
private const val DEFAULT_PEER_WINDOW = 65535
private const val HASH_PRIME = 31

enum class TcpConnectionState { SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, LAST_ACK, FIN_WAIT, CLOSED }

// appAckNumber/appWindow track what the app has told us about ITS receive side (the highest
// sequence number of ours it has acknowledged, and its most recently advertised window) -- used by
// TcpRelay to pace how fast it pulls data off the real destination socket, so a slow-reading app
// can't be sent data it will only drop. sequenceNumber/ackNumber are plain Long but always kept
// masked to 32 bits (see SEQ_MOD_MASK): the wire format is a real 32-bit field, and comparing an
// unmasked internal counter against a value parsed off the wire would silently desync a connection
// the moment either side crossed 2^32.
data class TcpConnection(
    val state: TcpConnectionState,
    val sequenceNumber: Long,
    val ackNumber: Long,
    val appAckNumber: Long = 0L,
    val appWindow: Int = DEFAULT_PEER_WINDOW,
)

// Overrides equals()/hashCode() because the generated versions compare `payload` by reference,
// not content -- the same ByteArray-in-a-data-class trap TcpStateMachineTest hit once already.
data class TcpSegmentToSend(
    val sequenceNumber: Long,
    val ackNumber: Long,
    val syn: Boolean = false,
    val ack: Boolean = false,
    val fin: Boolean = false,
    val rst: Boolean = false,
    val payload: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TcpSegmentToSend) return false
        return sequenceNumber == other.sequenceNumber &&
            ackNumber == other.ackNumber &&
            syn == other.syn &&
            ack == other.ack &&
            fin == other.fin &&
            rst == other.rst &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = HASH_PRIME * result + ackNumber.hashCode()
        result = HASH_PRIME * result + syn.hashCode()
        result = HASH_PRIME * result + ack.hashCode()
        result = HASH_PRIME * result + fin.hashCode()
        result = HASH_PRIME * result + rst.hashCode()
        result = HASH_PRIME * result + payload.contentHashCode()
        return result
    }
}

sealed interface TcpAction {
    data class SendSegment(val segment: TcpSegmentToSend) : TcpAction

    // Same ByteArray-content-equality override as TcpSegmentToSend/Ipv4TcpSegment above.
    data class DeliverToDestination(val payload: ByteArray) : TcpAction {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeliverToDestination) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }
    object CloseDestinationSocket : TcpAction
    object ShutdownDestinationOutput : TcpAction
}

data class TcpTransitionResult(
    val connection: TcpConnection,
    val actions: List<TcpAction>,
)

// Public entry points only -- one per real-world event TcpRelay needs to react to. The actual
// per-state logic lives in TcpTransitions below, kept as a separate (file-private) object so
// neither object grows past a size worth reviewing in one piece.
object TcpStateMachine {

    fun onSyn(initialSequenceNumber: Long, appSequenceNumber: Long, appWindow: Int): TcpTransitionResult {
        val connection = TcpConnection(
            state = TcpConnectionState.SYN_RECEIVED,
            sequenceNumber = (initialSequenceNumber + 1) and SEQ_MOD_MASK,
            ackNumber = (appSequenceNumber + 1) and SEQ_MOD_MASK,
            appAckNumber = initialSequenceNumber,
            appWindow = appWindow,
        )
        val synAck = TcpTransitions.ackSegment(initialSequenceNumber, connection.ackNumber).copy(syn = true)
        return TcpTransitionResult(connection, listOf(TcpAction.SendSegment(synAck)))
    }

    // The real destination socket never connected -- there is no TcpConnection yet to base a reply
    // on, so this builds the standard "nothing is listening" response directly: RST+ACK, seq=0,
    // ack=the app's ISN+1.
    fun onConnectFailed(appSequenceNumber: Long): TcpTransitionResult {
        val closed = TcpConnection(
            state = TcpConnectionState.CLOSED,
            sequenceNumber = 0L,
            ackNumber = (appSequenceNumber + 1) and SEQ_MOD_MASK,
        )
        val rstAck = TcpSegmentToSend(sequenceNumber = 0L, ackNumber = closed.ackNumber, rst = true, ack = true)
        return TcpTransitionResult(closed, listOf(TcpAction.SendSegment(rstAck)))
    }

    fun onSegment(connection: TcpConnection, segment: Ipv4TcpSegment): TcpTransitionResult {
        val tracked = TcpTransitions.trackPeerState(connection, segment)
        if (segment.rst) {
            val closed = tracked.copy(state = TcpConnectionState.CLOSED)
            return TcpTransitionResult(closed, listOf(TcpAction.CloseDestinationSocket))
        }
        return when (tracked.state) {
            TcpConnectionState.SYN_RECEIVED -> TcpTransitions.onHandshakeAck(tracked, segment)
            TcpConnectionState.ESTABLISHED -> TcpTransitions.onEstablishedSegment(tracked, segment)
            TcpConnectionState.CLOSE_WAIT -> TcpTransitionResult(tracked, emptyList())
            TcpConnectionState.LAST_ACK,
            TcpConnectionState.FIN_WAIT,
            -> TcpTransitions.onWaitingForFinAck(tracked, segment)
            TcpConnectionState.CLOSED -> TcpTransitionResult(tracked, emptyList())
        }
    }

    fun onDataFromDestination(connection: TcpConnection, data: ByteArray): TcpTransitionResult {
        val next = connection.copy(sequenceNumber = (connection.sequenceNumber + data.size) and SEQ_MOD_MASK)
        val segment = TcpTransitions.ackSegment(connection.sequenceNumber, connection.ackNumber).copy(payload = data)
        return TcpTransitionResult(next, listOf(TcpAction.SendSegment(segment)))
    }

    // Called whether the destination closed before the app did (-> FIN_WAIT) or after the app
    // already sent its own FIN (-> LAST_ACK, from CLOSE_WAIT) -- the "send our FIN, consume one
    // sequence number" step is identical either way, only the resulting state differs.
    fun onDestinationClosed(connection: TcpConnection): TcpTransitionResult {
        val finSequenceNumber = connection.sequenceNumber
        val nextState = if (connection.state == TcpConnectionState.CLOSE_WAIT) {
            TcpConnectionState.LAST_ACK
        } else {
            TcpConnectionState.FIN_WAIT
        }
        val next = connection.copy(state = nextState, sequenceNumber = (connection.sequenceNumber + 1) and SEQ_MOD_MASK)
        val fin = TcpSegmentToSend(
            sequenceNumber = finSequenceNumber,
            ackNumber = next.ackNumber,
            ack = true,
            fin = true,
        )
        return TcpTransitionResult(next, listOf(TcpAction.SendSegment(fin)))
    }

    fun onDestinationUnreachable(connection: TcpConnection): TcpTransitionResult {
        val closed = connection.copy(state = TcpConnectionState.CLOSED)
        val rst = TcpSegmentToSend(
            sequenceNumber = connection.sequenceNumber,
            ackNumber = connection.ackNumber,
            rst = true,
        )
        return TcpTransitionResult(closed, listOf(TcpAction.SendSegment(rst)))
    }
}

// Implementation detail of TcpStateMachine, split into its own (file-private) object purely to
// keep each object's function count under this project's detekt threshold -- there is no
// meaningful API boundary here, just a size split.
private object TcpTransitions {

    // Refreshes what we know about the app's receive side from any segment carrying an ACK --
    // every segment past the handshake does. A dedicated step so every state-specific handler
    // automatically sees up-to-date flow-control data without remembering to update it itself.
    fun trackPeerState(connection: TcpConnection, segment: Ipv4TcpSegment): TcpConnection = if (segment.ack) {
        connection.copy(appAckNumber = segment.ackNumber, appWindow = segment.windowSize)
    } else {
        connection
    }

    fun onHandshakeAck(connection: TcpConnection, segment: Ipv4TcpSegment): TcpTransitionResult {
        val established = connection.copy(state = TcpConnectionState.ESTABLISHED)
        return if (!segment.ack) {
            TcpTransitionResult(connection, emptyList())
        } else if (segment.payload.isEmpty()) {
            TcpTransitionResult(established, emptyList())
        } else {
            onEstablishedSegment(established, segment)
        }
    }

    fun onEstablishedSegment(connection: TcpConnection, segment: Ipv4TcpSegment): TcpTransitionResult {
        val isInOrder = segment.sequenceNumber == connection.ackNumber
        if (!isInOrder && (segment.payload.isNotEmpty() || segment.fin)) {
            return duplicateAck(connection)
        }
        if (segment.fin) return closeWaitTransition(connection, segment)
        if (segment.payload.isNotEmpty()) return newDataTransition(connection, segment)
        return TcpTransitionResult(connection, emptyList())
    }

    // Our own FIN (sent from onDestinationClosed) already consumed one sequence number, so
    // connection.sequenceNumber IS the value the app must ACK for teardown to complete. FIN_WAIT
    // and LAST_ACK share this exact check; only how they got here differs.
    fun onWaitingForFinAck(connection: TcpConnection, segment: Ipv4TcpSegment): TcpTransitionResult {
        val finAcked = segment.ack && segment.ackNumber == connection.sequenceNumber
        return if (finAcked) {
            val closed = connection.copy(state = TcpConnectionState.CLOSED)
            TcpTransitionResult(closed, listOf(TcpAction.CloseDestinationSocket))
        } else {
            TcpTransitionResult(connection, emptyList())
        }
    }

    fun ackSegment(sequenceNumber: Long, ackNumber: Long): TcpSegmentToSend =
        TcpSegmentToSend(sequenceNumber = sequenceNumber, ackNumber = ackNumber, ack = true)

    // The app has no more data for us -- ACK its FIN, tell the real socket there's nothing more
    // coming from this side (half-close), and wait for the destination's own EOF before sending our
    // FIN. Not jumping straight to our own FIN here: the destination may still have queued data that
    // needs to reach the app first.
    private fun closeWaitTransition(connection: TcpConnection, segment: Ipv4TcpSegment): TcpTransitionResult {
        val next = connection.copy(
            state = TcpConnectionState.CLOSE_WAIT,
            ackNumber = (segment.sequenceNumber + segment.payload.size + 1) and SEQ_MOD_MASK,
        )
        val finAck = ackSegment(next.sequenceNumber, next.ackNumber).copy(fin = true)
        val actions = buildList {
            if (segment.payload.isNotEmpty()) add(TcpAction.DeliverToDestination(segment.payload))
            add(TcpAction.SendSegment(finAck))
            add(TcpAction.ShutdownDestinationOutput)
        }
        return TcpTransitionResult(next, actions)
    }

    private fun newDataTransition(connection: TcpConnection, segment: Ipv4TcpSegment): TcpTransitionResult {
        val next = connection.copy(ackNumber = (connection.ackNumber + segment.payload.size) and SEQ_MOD_MASK)
        val ack = ackSegment(next.sequenceNumber, next.ackNumber)
        val actions = listOf(TcpAction.DeliverToDestination(segment.payload), TcpAction.SendSegment(ack))
        return TcpTransitionResult(next, actions)
    }

    private fun duplicateAck(connection: TcpConnection): TcpTransitionResult {
        val ack = ackSegment(connection.sequenceNumber, connection.ackNumber)
        return TcpTransitionResult(connection, listOf(TcpAction.SendSegment(ack)))
    }
}
