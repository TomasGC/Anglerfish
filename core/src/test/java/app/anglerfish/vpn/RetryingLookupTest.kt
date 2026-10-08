package app.anglerfish.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetryingLookupTest {

    @Test
    fun `returns the first non-null result without retrying`() {
        var calls = 0
        val result = RetryingLookup.untilFound(attempts = 3, delay = { }) {
            calls++
            42
        }
        assertEquals(42, result)
        assertEquals(1, calls)
    }

    // The real-world case this exists for: ConnectivityManager.getConnectionOwnerUid() can
    // transiently return nothing right after a fresh socket's first packet, because the kernel's
    // conntrack entry for it is created asynchronously -- a documented race NetGuard also retries.
    @Test
    fun `retries until a non-null result appears`() {
        var calls = 0
        val result = RetryingLookup.untilFound(attempts = 3, delay = { }) {
            calls++
            if (calls < 3) null else 7
        }
        assertEquals(7, result)
        assertEquals(3, calls)
    }

    @Test
    fun `gives up after the configured number of attempts`() {
        var calls = 0
        var delays = 0
        val result = RetryingLookup.untilFound(attempts = 3, delay = { delays++ }) {
            calls++
            null
        }
        assertNull(result)
        assertEquals(3, calls)
        assertEquals(2, delays)
    }
}
