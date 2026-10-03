package app.anglerfish.nat

interface SessionFactory {
    fun createUdp(endpoints: FlowEndpoints, onClosed: () -> Unit): UdpSession
    fun createTcp(endpoints: FlowEndpoints, onClosed: () -> Unit): TcpSession
}
