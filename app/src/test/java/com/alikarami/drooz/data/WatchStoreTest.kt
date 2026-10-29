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
        val dir = tmp.newFolder("ro")
        val target = java.io.File(dir, "w.json")
        dir.setWritable(false)                    // the temp file cannot be created
        try {
            val store = FileWatchStore(target) { now }
            now = 7; store.put(entry("a", 5))
            assertEquals(5L, store.get("a")!!.positionMs)
            assertEquals(false, java.io.File(dir, "w.json.tmp").exists())
            assertEquals(false, target.exists())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test fun corruptFileIsKeptAsideNotOverwritten() = runTest {
        val file = tmp.newFile("w.json").apply { writeText("{not json") }
        val store = FileWatchStore(file) { now }
        now = 1; store.put(entry("a"))
        val aside = java.io.File(file.parentFile, "w.json.corrupt")
        assertEquals("{not json", aside.readText())
        assertEquals(listOf("a"), FileWatchStore(file) { now }.all().map { it.streamId })
    }

    @Test fun emptyOrMissingFileIsNotCorrupt() = runTest {
        val empty = tmp.newFile("w.json")
        FileWatchStore(empty) { now }.put(entry("a"))
        assertEquals(false, java.io.File(empty.parentFile, "w.json.corrupt").exists())
        val missing = java.io.File(tmp.newFolder("m"), "w.json")
        FileWatchStore(missing) { now }.put(entry("b"))
        assertEquals(false, java.io.File(missing.parentFile, "w.json.corrupt").exists())
        assertEquals(listOf("b"), FileWatchStore(missing) { now }.all().map { it.streamId })
    }

    @Test fun unreadableFileIsKeptAsideBeforeStartingEmpty() = runTest {
        val target = tmp.newFolder("w.json")      // reading a directory fails with an IOException every time
        java.io.File(target, "x").writeText("kept")
        val store = FileWatchStore(target) { now }
        assertEquals(emptyList<WatchEntry>(), store.all())
        now = 1; store.put(entry("a"))
        assertEquals("kept", java.io.File(target.parentFile, "w.json.corrupt/x").readText())
        assertEquals(listOf("a"), FileWatchStore(target) { now }.all().map { it.streamId })
    }
}
