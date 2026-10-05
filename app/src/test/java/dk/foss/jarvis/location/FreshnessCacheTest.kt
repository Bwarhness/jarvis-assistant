package dk.foss.jarvis.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreshnessCacheTest {

    private var now = 1_000_000L
    private val clock: () -> Long = { now }

    @Test
    fun `empty cache returns null`() {
        val cache = FreshnessCache<String>(ttlMs = 60_000, nowMs = clock)
        assertNull(cache.get())
    }

    @Test
    fun `fresh value is returned`() {
        val cache = FreshnessCache<String>(ttlMs = 60_000, nowMs = clock)
        cache.put("Dresden")
        now += 5_000
        assertEquals("Dresden", cache.get())
    }

    @Test
    fun `expired value returns null`() {
        val cache = FreshnessCache<String>(ttlMs = 60_000, nowMs = clock)
        cache.put("Dresden")
        now += 60_001
        assertNull(cache.get())
    }

    @Test
    fun `value at exact ttl boundary is expired`() {
        val cache = FreshnessCache<String>(ttlMs = 60_000, nowMs = clock)
        cache.put("Dresden")
        now += 60_000
        assertNull(cache.get())
    }

    @Test
    fun `put refreshes the timestamp`() {
        val cache = FreshnessCache<String>(ttlMs = 60_000, nowMs = clock)
        cache.put("Dresden")
        now += 59_000
        cache.put("Prague")
        now += 59_000
        assertEquals("Prague", cache.get())
        now += 2_000
        assertNull(cache.get())
    }

    @Test
    fun `clear empties the cache`() {
        val cache = FreshnessCache<String>(ttlMs = 60_000, nowMs = clock)
        cache.put("Dresden")
        cache.clear()
        assertNull(cache.get())
    }
}
