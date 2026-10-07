package app.anglerfish.nat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SessionTableTest {

    private val key = FlowKey(TransportProtocol.UDP, InetAddress.getByName("10.0.0.2"), 1, InetAddress.getByName("8.8.8.8"), 2)

    @Test
    fun `put then get returns the stored value`() {
        val table = SessionTable<String>()

        table.put(key, "entry", now = 0L)

        assertEquals("entry", table.get(key))
    }

    @Test
    fun `get on a missing key returns null`() {
        val table = SessionTable<String>()

        assertNull(table.get(key))
    }

    @Test
    fun `remove deletes the entry and returns its value`() {
        val table = SessionTable<String>()
        table.put(key, "entry", now = 0L)

        val removed = table.remove(key)

        assertEquals("entry", removed)
        assertNull(table.get(key))
    }

    @Test
    fun `evictIdle removes entries older than the threshold and returns their values`() {
        val table = SessionTable<String>()
        table.put(key, "entry", now = 0L)

        val evicted = table.evictIdle(now = 1000L, idleTimeoutMillis = 500L)

        assertEquals(listOf("entry"), evicted)
        assertNull(table.get(key))
    }

    @Test
    fun `remove with an expected value only removes when the stored value still matches`() {
        val table = SessionTable<String>()
        table.put(key, "original", now = 0L)

        val removedWrongValue = table.remove(key, "a different instance")
        val removedCorrectValue = table.remove(key, "original")

        assertTrue(!removedWrongValue)
        assertTrue(removedCorrectValue)
        assertNull(table.get(key))
    }

    @Test
    fun `evictIdle keeps entries within the threshold`() {
        val table = SessionTable<String>()
        table.put(key, "entry", now = 0L)

        val evicted = table.evictIdle(now = 400L, idleTimeoutMillis = 500L)

        assertTrue(evicted.isEmpty())
        assertEquals("entry", table.get(key))
    }

    @Test
    fun `touch resets the idle clock so a recently-touched entry survives eviction`() {
        val table = SessionTable<String>()
        table.put(key, "entry", now = 0L)

        table.touch(key, now = 900L)
        val evicted = table.evictIdle(now = 1000L, idleTimeoutMillis = 500L)

        assertTrue(evicted.isEmpty())
        assertEquals("entry", table.get(key))
    }
}
