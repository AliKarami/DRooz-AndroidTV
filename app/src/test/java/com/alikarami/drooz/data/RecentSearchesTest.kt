package com.alikarami.drooz.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentSearchesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun newestFirstTrimmedAndCollapsed() {
        var list = emptyList<String>()
        list = withRecentQuery(list, "  breaking   bad ")
        list = withRecentQuery(list, "Pluribus")
        assertEquals(listOf("Pluribus", "breaking bad"), list)
    }

    @Test fun repeatsMoveToTheFrontCaseInsensitivelyWithTheNewSpelling() {
        val list = withRecentQuery(listOf("Pluribus", "Breaking Bad", "Dune"), "breaking BAD")
        assertEquals(listOf("breaking BAD", "Pluribus", "Dune"), list)
        // Persian variants (Arabic yeh, ZWNJ) count as the same query too.
        assertEquals(listOf("بازی‌ها"), withRecentQuery(listOf("بازي ها"), "بازی‌ها"))
    }

    @Test fun keepsTheLastEight() {
        var list = emptyList<String>()
        (1..10).forEach { list = withRecentQuery(list, "q$it") }
        assertEquals((10 downTo 3).map { "q$it" }, list)
    }

    // Too-short queries never get here: only a query that was searched (SearchUiState.searchedFor) is saved.
    @Test fun ignoresBlankQueries() {
        assertEquals(listOf("Dune"), withRecentQuery(listOf("Dune"), "   "))
        assertEquals(listOf("Dune"), withRecentQuery(listOf("Dune"), ""))
    }

    private fun TestScope.store(file: File) =
        DataStoreRecentSearches(PreferenceDataStoreFactory.create(prefsCorruptionHandler(), scope = backgroundScope) { file })

    @Test fun persistsAndClears() = runTest {
        val file = tmp.root.resolve("recent.preferences_pb")
        val s = store(file)
        assertEquals(emptyList<String>(), s.queries.first())
        s.add("Dune")
        s.add("Pluribus")
        s.add("dune")
        assertEquals(listOf("dune", "Pluribus"), s.queries.first())
        s.clear()
        assertEquals(emptyList<String>(), s.queries.first())
    }

    @Test fun aCorruptFileReadsAsNoHistory() = runTest {
        val file = tmp.root.resolve("bad.preferences_pb").apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x55, 0x00)) }
        val s = store(file)
        assertEquals(emptyList<String>(), s.queries.first())
        s.add("Dune")
        assertEquals(listOf("Dune"), s.queries.first())
    }
}
