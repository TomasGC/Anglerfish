package app.anglerfish.nat

import java.net.InetAddress

enum class TransportProtocol { TCP, UDP }

data class FlowKey(
    val protocol: TransportProtocol,
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destAddress: InetAddress,
    val destPort: Int,
)

data class FlowEndpoints(
    val sourceAddress: InetAddress,
    val sourcePort: Int,
    val destAddress: InetAddress,
    val destPort: Int,
)

fun FlowKey.toEndpoints(): FlowEndpoints = FlowEndpoints(sourceAddress, sourcePort, destAddress, destPort)
