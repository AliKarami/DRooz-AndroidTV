package com.alikarami.drooz.data

import com.alikarami.drooz.site.model.HomePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePageCacheTest {
    private var now = 1_000L
    private val cache = HomePageCache(ttlMs = 600_000) { now }
    private val page = HomePage(emptyList(), emptyList(), emptyList())

    @Test fun emptyUntilPut() = assertNull(cache.fresh())

    @Test fun freshWithinTtl() {
        cache.put(page)
        now += 599_999
        assertEquals(page, cache.fresh())
    }

    @Test fun expiresAfterTtl() {
        cache.put(page)
        now += 600_000
        assertNull(cache.fresh())
    }
}
