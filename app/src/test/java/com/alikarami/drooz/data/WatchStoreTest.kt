package com.alikarami.drooz.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L

    private fun entry(id: String, pos: Long = 0) = WatchEntry(id, "/ref/$id", 1, 2, pos, 1000, 0, false)

    @Test fun putThenGetAndNewestFirst() = runTest {
        val store = FileWatchStore(tmp.newFile("w.json")) { now }
        now = 1; store.put(entry("a"))
        now = 2; store.put(entry("b"))
        val all = store.all()
        assertEquals(listOf("b", "a"), all.map { it.streamId })
        assertEquals(listOf(2L, 1L), all.map { it.updatedAt })
        assertEquals(1L, store.get("a")!!.updatedAt)
        assertNull(store.get("zzz"))
    }

    @Test fun putReplacesSameStream() = runTest {
        val store = FileWatchStore(tmp.newFile("w.json")) { now }
        now = 1; store.put(entry("a", 10))
        now = 2; store.put(entry("a", 20))
        assertEquals(1, store.all().size)
        assertEquals(20L, store.get("a")!!.positionMs)
    }

    @Test fun survivesReopen() = runTest {
        val file = tmp.newFile("w.json")
        now = 5
        FileWatchStore(file) { now }.put(entry("a", 42).copy(subtitleOffsetMs = -250))
        val back = FileWatchStore(file) { now }.get("a")
        assertEquals(entry("a", 42).copy(updatedAt = 5, subtitleOffsetMs = -250), back)
    }

    @Test fun corruptFileStartsEmpty() = runTest {
        val file = tmp.newFile("w.json").apply { writeText("{not json") }
        val store = FileWatchStore(file) { now }
        assertEquals(emptyList<WatchEntry>(), store.all())
        now = 1; store.put(entry("a"))
        assertEquals(listOf("a"), FileWatchStore(file) { now }.all().map { it.streamId })
    }

    @Test fun capsAtFiveHundred() = runTest {
        val store = FileWatchStore(tmp.newFile("w.json")) { now }
        for (i in 1..501) { now = i.toLong(); store.put(entry("s$i")) }
        assertEquals(WATCH_STORE_CAP, store.all().size)
        assertNull(store.get("s1"))
        assertEquals("s501", store.all().first().streamId)
    }

    @Test fun ioFailureOnPersistDoesNotThrowAndKeepsEntryInMemory() = runTest {
        val target = tmp.newFolder("w.json")      // a non-empty directory where the file should be
        java.io.File(target, "x").writeText("x")
        val store = FileWatchStore(target) { now }
        now = 7; store.put(entry("a", 5))
        assertEquals(5L, store.get("a")!!.positionMs)
        assertEquals(false, java.io.File(target.parentFile, "w.json.tmp").exists())
    }
}
