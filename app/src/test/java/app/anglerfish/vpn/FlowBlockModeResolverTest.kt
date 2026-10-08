package app.anglerfish.vpn

import app.anglerfish.data.BlockMode
import app.anglerfish.nat.FlowEndpoints
import app.anglerfish.nat.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

private val SOURCE: InetAddress = InetAddress.getByName("10.0.0.2")
private val DEST: InetAddress = InetAddress.getByName("93.184.216.34")

class FlowBlockModeResolverTest {

    @Test
    fun `a resolved uid owning a selected package returns that package's mode`() {
        val resolver = FlowBlockModeResolver(
            uidResolver = fakeUidResolver(1000),
            packagesForUid = { setOf("com.example.app") },
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )

        val mode = resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 0L)

        assertEquals(BlockMode.AdFilterOnly, mode)
    }

    // Review Focus: uid resolution itself finds nothing.
    @Test
    fun `no resolvable uid fails closed to FullBlock`() {
        val resolver = FlowBlockModeResolver(
            uidResolver = fakeUidResolver(uid = null),
            packagesForUid = { setOf("com.example.app") },
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )

        val mode = resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 0L)

        assertEquals(BlockMode.FullBlock, mode)
    }

    // Review Focus: the uid is real but owns no package Anglerfish ever selected.
    @Test
    fun `an owning package that is not selected fails closed to FullBlock`() {
        val resolver = FlowBlockModeResolver(
            uidResolver = fakeUidResolver(1000),
            packagesForUid = { setOf("com.other.app") },
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )

        val mode = resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 0L)

        assertEquals(BlockMode.FullBlock, mode)
    }

    @Test
    fun `a second lookup for the same flow is served from cache, not a second uid resolution`() {
        var calls = 0
        val resolver = FlowBlockModeResolver(
            uidResolver = { _, _, _, _, _ -> calls++; 1000 },
            packagesForUid = { setOf("com.example.app") },
            selectedPackages = mapOf("com.example.app" to BlockMode.FullBlock),
        )

        resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 0L)
        resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 1_000L)

        assertEquals(1, calls)
    }

    // Review Focus / Final review (I4): an unresolvable uid is usually the kernel's conntrack race
    // on a flow's first packet, not a stable fact -- caching it as FullBlock for the full 10-minute
    // window would wrongly block a retransmitted SYN moments later even after the race resolves
    // itself. A failed lookup must not be cached, so the next packet gets a fresh attempt.
    @Test
    fun `a failed uid resolution is retried on the next packet, not cached`() {
        var calls = 0
        val resolver = FlowBlockModeResolver(
            uidResolver = { _, _, _, _, _ -> calls++; if (calls == 1) null else 1000 },
            packagesForUid = { setOf("com.example.app") },
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )

        val first = resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 0L)
        val second = resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 1L)

        assertEquals(BlockMode.FullBlock, first)
        assertEquals(BlockMode.AdFilterOnly, second)
        assertEquals(2, calls)
    }

    // Final review (I5): matches the exception boundary DnsInterceptor already has -- a real
    // dependency failure (ConnectivityManager.getConnectionOwnerUid can throw SecurityException if
    // the app is no longer the active VpnService, /proc/net reads can throw IOException) must not
    // be an uncaught exception on the one drain thread, which would crash the whole process.
    @Test
    fun `an exception from uid resolution fails closed instead of crashing`() {
        val resolver = FlowBlockModeResolver(
            uidResolver = { _, _, _, _, _ -> throw SecurityException("no longer the active VpnService") },
            packagesForUid = { setOf("com.example.app") },
            selectedPackages = mapOf("com.example.app" to BlockMode.AdFilterOnly),
        )

        val mode = resolver.resolve(TransportProtocol.TCP, FlowEndpoints(SOURCE, 1234, DEST, 443), now = 0L)

        assertEquals(BlockMode.FullBlock, mode)
    }

    private fun fakeUidResolver(uid: Int?): UidResolver = UidResolver { _, _, _, _, _ -> uid }
}
