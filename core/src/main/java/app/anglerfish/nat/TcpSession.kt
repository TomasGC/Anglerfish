package app.anglerfish.nat

interface TcpSession {
    fun isActive(): Boolean
    fun start(appSequenceNumber: Long, appWindow: Int)
    fun handle(segment: Ipv4TcpSegment)
    fun close()
}
