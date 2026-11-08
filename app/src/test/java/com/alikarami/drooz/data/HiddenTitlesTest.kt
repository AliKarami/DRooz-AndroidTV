package com.alikarami.drooz.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HiddenTitlesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hideThenAll() = runTest {
        val store = FileHiddenTitles(tmp.newFile("h.json"))
        store.hide(1, at = 10)
        store.hide(2, at = 20)
        assertEquals(mapOf(1L to 10L, 2L to 20L), store.all())
    }

    // Hiding again (it came back after a play) moves the hide time forward.
    @Test fun hideAgainReplacesTime() = runTest {
        val store = FileHiddenTitles(tmp.newFile("h.json"))
        store.hide(1, at = 10)
        store.hide(1, at = 30)
        assertEquals(mapOf(1L to 30L), store.all())
    }

    @Test fun survivesReopen() = runTest {
        val file = tmp.newFile("h.json")
        FileHiddenTitles(file).hide(7, at = 42)
        assertEquals(mapOf(7L to 42L), FileHiddenTitles(file).all())
        assertFalse(File(file.parentFile, "h.json.tmp").exists())
    }

    @Test fun missingOrCorruptFileStartsEmptyAndRecovers() = runTest {
        val missing = File(tmp.newFolder("m"), "h.json")
        assertEquals(emptyMap<Long, Long>(), FileHiddenTitles(missing).all())
        val bad = tmp.newFile("h.json").apply { writeText("{not json") }
        val store = FileHiddenTitles(bad)
        assertEquals(emptyMap<Long, Long>(), store.all())
        store.hide(3, at = 1)
        assertEquals(mapOf(3L to 1L), FileHiddenTitles(bad).all())
    }

    @Test fun capsKeepingNewestHides() = runTest {
        val file = tmp.newFile("h.json")
        val store = FileHiddenTitles(file)
        for (i in 1..HIDDEN_TITLES_CAP + 5) store.hide(i.toLong(), at = i.toLong())
        val all = FileHiddenTitles(file).all()
        assertEquals(HIDDEN_TITLES_CAP, all.size)
        assertFalse(1L in all)
        assertEquals((HIDDEN_TITLES_CAP + 5).toLong(), all[(HIDDEN_TITLES_CAP + 5).toLong()])
    }

    @Test fun clearForgetsOnDiskToo() = runTest {
        val file = tmp.newFile("h.json")
        val store = FileHiddenTitles(file)
        store.hide(1, at = 1)
        store.clear()
        assertEquals(emptyMap<Long, Long>(), store.all())
        assertEquals(emptyMap<Long, Long>(), FileHiddenTitles(file).all())
    }
}
