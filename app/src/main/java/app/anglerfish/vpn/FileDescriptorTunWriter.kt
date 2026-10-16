package app.anglerfish.vpn

import app.anglerfish.nat.TunWriter
import java.io.FileOutputStream

// Concurrent relay sessions (UdpRelay/TcpRelay, each on its own coroutine) and DnsInterceptor's
// response writes all share this one file descriptor -- @Synchronized stops two packets from
// interleaving mid-write, which would corrupt both.
class FileDescriptorTunWriter(private val output: FileOutputStream) : TunWriter {
    @Synchronized
    override fun write(packet: ByteArray) {
        output.write(packet)
    }
}
