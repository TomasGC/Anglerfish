package app.anglerfish.vpn

// Pure retry policy, independent of what's being looked up -- ConnectivityManagerUidResolver is
// the real caller, using it to work around ConnectivityManager.getConnectionOwnerUid()'s documented
// race (the kernel's conntrack entry for a brand-new socket isn't always visible yet to the first
// query after it sends its first packet). `delay` is injected so this stays sleep-free and
// deterministic under test.
object RetryingLookup {
    fun <T> untilFound(attempts: Int, delay: (attempt: Int) -> Unit, lookup: () -> T?): T? {
        for (attempt in 0 until attempts) {
            val found = lookup()
            if (found != null) return found
            if (attempt < attempts - 1) delay(attempt)
        }
        return null
    }
}
