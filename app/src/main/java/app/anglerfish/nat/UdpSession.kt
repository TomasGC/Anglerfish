package app.anglerfish.nat

interface UdpSession {
    fun sendToDestination(payload: ByteArray)
    fun close()
}
