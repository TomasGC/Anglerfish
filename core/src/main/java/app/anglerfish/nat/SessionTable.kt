package app.anglerfish.nat

// Generic over the relay type each protocol stores (UdpRelay, TcpRelay) so this stays pure and
// protocol-agnostic. Time is always an injected parameter, never read internally, so eviction can
// be tested deterministically without real clock delays.
class SessionTable<T> {
    private data class Entry<T>(val value: T, var lastActivityMillis: Long)

    private val entries = mutableMapOf<FlowKey, Entry<T>>()

    @Synchronized
    fun get(key: FlowKey): T? = entries[key]?.value

    @Synchronized
    fun put(key: FlowKey, value: T, now: Long) {
        entries[key] = Entry(value, now)
    }

    @Synchronized
    fun touch(key: FlowKey, now: Long) {
        entries[key]?.lastActivityMillis = now
    }

    @Synchronized
    fun remove(key: FlowKey): T? = entries.remove(key)?.value

    // Removing only if the currently-stored value is still the expected one guards against a
    // caller's close callback (fired on a background thread, possibly well after a replacement
    // entry for the same key was already installed) deleting the wrong entry.
    @Synchronized
    fun remove(key: FlowKey, expected: T): Boolean {
        val current = entries[key]?.value ?: return false
        if (current !== expected) return false
        entries.remove(key)
        return true
    }

    // Returns the evicted VALUES, not just their keys: a caller that only gets keys back has no way
    // to close what it just evicted, since a second remove(key) call here always returns null -- the
    // entry is already gone.
    @Synchronized
    fun evictIdle(now: Long, idleTimeoutMillis: Long): List<T> {
        val expiredKeys = entries.filterValues { now - it.lastActivityMillis >= idleTimeoutMillis }.keys.toList()
        return expiredKeys.mapNotNull { entries.remove(it)?.value }
    }
}
