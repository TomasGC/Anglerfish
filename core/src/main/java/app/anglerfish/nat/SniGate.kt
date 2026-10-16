package app.anglerfish.nat

sealed interface SniGateResult {
    data class Write(val chunks: List<ByteArray>) : SniGateResult
    data object Blocked : SniGateResult
}

// Orchestrates one flow's SNI decision: hold bytes while TlsClientHelloBuffer is
// still deciding, then either flush everything held plus pass future chunks
// straight through, or block forever. Pure (no socket/Android dependency) so it
// is fully unit-testable without a real TcpRelay/UdpRelay -- the untested glue
// those classes stay is now only "call offer(), write back what it returns",
// same split as FlowBlockModeResolver (pure decision) vs AnglerfishVpnService
// (untested caller).
class SniGate(private val isBlocked: (String) -> Boolean) {
    private val sniBuffer = TlsClientHelloBuffer()
    private var decision: Boolean? = null
    private val held = mutableListOf<ByteArray>()

    fun offer(payload: ByteArray): SniGateResult {
        decision?.let { blocked -> return if (blocked) SniGateResult.Blocked else SniGateResult.Write(listOf(payload)) }

        return when (val result = sniBuffer.feed(payload)) {
            SniResult.Pending -> {
                held += payload
                SniGateResult.Write(emptyList())
            }
            SniResult.NotTls -> resolve(blocked = false, payload)
            is SniResult.Resolved -> resolve(blocked = isBlocked(result.hostname), payload)
        }
    }

    private fun resolve(blocked: Boolean, payload: ByteArray): SniGateResult {
        decision = blocked
        if (blocked) {
            held.clear()
            return SniGateResult.Blocked
        }
        held += payload
        val flushed = held.toList()
        held.clear()
        return SniGateResult.Write(flushed)
    }
}
