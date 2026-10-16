package app.anglerfish.vpn

import app.anglerfish.data.BlockMode
import app.anglerfish.nat.FlowEndpoints
import app.anglerfish.nat.FlowKey
import app.anglerfish.nat.SessionTable
import app.anglerfish.nat.TransportProtocol

// BlockMode is fixed for the lifetime of one tunnel run (switching a selected app's mode is only
// possible while the tunnel is stopped), so caching per flow for this instance's lifetime is always
// correct, never stale -- there is no "mode changed under me" case to invalidate against.
class FlowBlockModeResolver(
    private val uidResolver: UidResolver,
    private val packagesForUid: (Int) -> Set<String>,
    private val selectedPackages: Map<String, BlockMode>,
) {
    private val cache = SessionTable<BlockMode>()

    // Takes the existing FlowEndpoints bundle (nat/FlowKey.kt) rather than its four fields
    // separately -- with `now` that would be six parameters, over detekt.yml's LongParameterList
    // budget.
    fun resolve(protocol: TransportProtocol, endpoints: FlowEndpoints, now: Long): BlockMode {
        val key = FlowKey(
            protocol, endpoints.sourceAddress, endpoints.sourcePort, endpoints.destAddress, endpoints.destPort,
        )
        cache.get(key)?.let {
            cache.touch(key, now)
            return it
        }
        val mode = lookupBlockMode(protocol, endpoints)
        if (mode != null) cache.put(key, mode, now)
        return mode ?: BlockMode.FullBlock
    }

    fun evictIdle(now: Long) {
        cache.evictIdle(now, FLOW_MODE_CACHE_IDLE_TIMEOUT_MS)
    }

    // Returns null when the mode couldn't be determined at all (uid resolution found nothing, or
    // threw) -- resolve() must not cache that result. It's usually transient: the kernel's
    // conntrack race on a flow's first packet (ConnectivityManagerUidResolver already retries a
    // few times, but on-device testing during #42 still found a residual ~2%), or a real dependency
    // failure (ConnectivityManager.getConnectionOwnerUid can throw SecurityException once this app
    // is no longer the active VpnService; the /proc/net fallback can throw IOException). Caching it
    // for the full 10-minute window would wrongly block a retransmitted SYN moments later even
    // after the race or failure resolves itself. A resolved uid with no selected owning package IS
    // a stable fact and is cached normally, via resolveBlockMode's own null -> FullBlock fallback.
    private fun lookupBlockMode(protocol: TransportProtocol, endpoints: FlowEndpoints): BlockMode? =
        try {
            val uid = uidResolver.resolve(
                protocol, endpoints.sourceAddress, endpoints.sourcePort, endpoints.destAddress, endpoints.destPort,
            ) ?: return null
            resolveBlockMode(packagesForUid(uid), selectedPackages) ?: BlockMode.FullBlock
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            null
        }

    private companion object {
        // Longer than NatRelay's own session timeouts (60s UDP / 5min TCP, nat/NatRelay.kt) on
        // purpose: re-resolving a mode costs a real uid lookup, re-creating a NAT session does not,
        // so this cache can safely outlive the sessions it was originally resolved for.
        const val FLOW_MODE_CACHE_IDLE_TIMEOUT_MS = 10 * 60_000L
    }
}
