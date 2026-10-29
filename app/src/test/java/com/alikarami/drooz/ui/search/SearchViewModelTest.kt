package com.alikarami.drooz.ui.search

import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun card(id: Long, title: String = "Title $id") = TitleCard(TitleRef(TitleKind.SERIES, id, "slug-$id"), title,
        2025, "ph$id", null, Rating(8.0, 100), null, listOf("Drama"), emptyList(), null, emptyList(), "syn $id", null, null)

    private class FakeApi(var onSearch: suspend (String) -> List<TitleCard>) : SiteApi {
    override suspend fun subtitle(url: String): String = error("unused")
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}
        val calls = mutableListOf<String>()
        override suspend fun search(query: String): List<TitleCard> { calls += query; return onSearch(query) }
        override suspend fun list(path: String, page: Int): ListPage = error("unused")
        override suspend fun home() = error("unused")
        override suspend fun title(ref: TitleRef): TitleDetail = error("no detail")
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = error("unused")
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
    }

    private fun TestScope.vm(api: FakeApi) = SearchViewModel(api, TitleInfoRepository(api, fetchScope = backgroundScope))

    @Test fun debouncesQueries() = runTest(dispatcher) {
        val api = FakeApi { q -> listOf(card(1, q)) }
        val vm = vm(api)
        vm.onQueryChange("p"); advanceTimeBy(100)
        vm.onQueryChange("pl"); advanceTimeBy(100)
        vm.onQueryChange("plu"); advanceTimeBy(100)
        assertTrue(api.calls.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("plu"), api.calls)
        assertEquals("plu", vm.uiState.value.items.single().title)
        assertEquals("plu", vm.uiState.value.query)
    }

    @Test fun ignoresShortQueries() = runTest(dispatcher) {
        val api = FakeApi { listOf(card(1)) }
        val vm = vm(api)
        vm.onQueryChange("p"); advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertTrue(vm.uiState.value.items.isEmpty())
        assertFalse(vm.uiState.value.loading)
        // Backspacing to one character clears earlier results.
        vm.onQueryChange("pluribus"); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)
        vm.onQueryChange("p"); advanceUntilIdle()
        assertEquals(listOf("pluribus"), api.calls)
        assertTrue(vm.uiState.value.items.isEmpty())
        assertNull(vm.uiState.value.hero)
        assertFalse(vm.uiState.value.isEmptyResult)
    }

    @Test fun latestQueryWins() = runTest(dispatcher) {
        val slow = CompletableDeferred<List<TitleCard>>()
        val api = FakeApi { q -> if (q == "pl") slow.await() else listOf(card(2, "plu result")) }
        val vm = vm(api)
        vm.onQueryChange("pl"); advanceTimeBy(500); runCurrent()
        assertEquals(listOf("pl"), api.calls)
        assertTrue(vm.uiState.value.loading)
        vm.onQueryChange("plu"); advanceUntilIdle()
        slow.complete(listOf(card(1, "pl result"))); advanceUntilIdle()
        assertEquals(listOf("pl", "plu"), api.calls)
        assertEquals("plu result", vm.uiState.value.items.single().title)
        assertFalse(vm.uiState.value.loading)
    }

    @Test fun voiceResultRunsSearchImmediately() = runTest(dispatcher) {
        val api = FakeApi { listOf(card(1)) }
        val vm = vm(api)
        vm.onVoiceResult("pluribus"); runCurrent()
        assertEquals(listOf("pluribus"), api.calls)
        assertEquals("pluribus", vm.uiState.value.query)
        assertEquals(1, vm.uiState.value.items.size)
    }

    @Test fun submitSkipsTheDebounce() = runTest(dispatcher) {
        val api = FakeApi { listOf(card(1)) }
        val vm = vm(api)
        vm.onQueryChange("pluribus"); runCurrent()
        vm.submit(); runCurrent()
        assertEquals(listOf("pluribus"), api.calls)
        // The pending debounced search was replaced, not run a second time.
        advanceUntilIdle()
        assertEquals(listOf("pluribus"), api.calls)
    }

    @Test fun emptyResultIsDistinctFromIdle() = runTest(dispatcher) {
        val api = FakeApi { emptyList() }
        val vm = vm(api)
        assertFalse(vm.uiState.value.isEmptyResult)
        vm.onQueryChange("پلوریبوس"); advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmptyResult)
        assertEquals("پلوریبوس", vm.uiState.value.searchedFor)
    }

    @Test fun errorThenRetryRecovers() = runTest(dispatcher) {
        var fail = true
        val api = FakeApi { if (fail) error("offline") else listOf(card(1)) }
        val vm = vm(api)
        vm.onQueryChange("pluribus"); advanceUntilIdle()
        assertEquals("offline", vm.uiState.value.error)
        assertFalse(vm.uiState.value.isEmptyResult)
        fail = false
        vm.retry(); advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.items.size)
    }

    @Test fun focusDrivesHeroAndNewResultsResetFocus() = runTest(dispatcher) {
        val api = FakeApi { q -> (1L..5L).map { card(if (q == "pluribus") it else it + 10) } }
        val vm = vm(api)
        vm.onQueryChange("pluribus"); advanceUntilIdle()
        vm.onFocusIndex(3); runCurrent()
        assertEquals("Title 4", vm.uiState.value.hero!!.title)
        assertEquals(3, vm.focusedIndex)
        assertTrue(vm.focusInResults)
        vm.onFieldFocused()
        assertFalse(vm.focusInResults)
        vm.onQueryChange("severance"); advanceUntilIdle()
        assertEquals(0, vm.focusedIndex)
        assertNull(vm.uiState.value.hero)
        assertEquals("Title 11", vm.uiState.value.items.first().title)
    }

    @Test fun failedSearchClearsPreviousResults() = runTest(dispatcher) {
        val api = FakeApi { q -> if (q == "severance") error("offline") else listOf(card(1)) }
        val vm = vm(api)
        vm.onQueryChange("pluribus"); advanceUntilIdle()
        vm.onFocusIndex(0); runCurrent()
        assertNotNull(vm.uiState.value.hero)
        vm.onQueryChange("severance"); advanceUntilIdle()
        val s = vm.uiState.value
        // The old answer must not pose as results for the new query: the screen shows the error + retry.
        assertTrue(s.items.isEmpty())
        assertNull(s.searchedFor)
        assertNull(s.hero)
        assertEquals("offline", s.error)
        assertFalse(s.loading)
        assertEquals(0, vm.focusedIndex)
    }

    @Test fun resultsJumpWaitsThenFocusesOnlyForTheCurrentAnswer() = runTest(dispatcher) {
        val gate = CompletableDeferred<List<TitleCard>>()
        val api = FakeApi { gate.await() }
        val vm = vm(api)
        vm.onQueryChange("pluribus"); runCurrent()
        vm.submit()
        // Submitted but not landed yet: wait (not drop) even before the coroutine has run.
        assertEquals(ResultsJump.Wait, vm.uiState.value.resultsJump())
        runCurrent()
        assertEquals(ResultsJump.Wait, vm.uiState.value.resultsJump())
        gate.complete(listOf(card(1))); advanceUntilIdle()
        assertEquals(ResultsJump.Focus, vm.uiState.value.resultsJump())
    }

    @Test fun resultsJumpDropsForShortEmptyOrFailedAnswers() = runTest(dispatcher) {
        var mode = "empty"
        val api = FakeApi { if (mode == "fail") error("offline") else emptyList() }
        val vm = vm(api)
        vm.onVoiceResult("p"); runCurrent()
        assertEquals(ResultsJump.Drop, vm.uiState.value.resultsJump())
        vm.onVoiceResult("qxjvwkz"); advanceUntilIdle()
        assertEquals(ResultsJump.Drop, vm.uiState.value.resultsJump())
        mode = "fail"
        vm.onVoiceResult("severance"); advanceUntilIdle()
        assertEquals(ResultsJump.Drop, vm.uiState.value.resultsJump())
    }

    @Test fun submitAfterAnErrorWaitsForTheNewAnswer() = runTest(dispatcher) {
        var fail = true
        val api = FakeApi { if (fail) error("offline") else listOf(card(1)) }
        val vm = vm(api)
        vm.onQueryChange("pluribus"); advanceUntilIdle()
        assertEquals("offline", vm.uiState.value.error)
        fail = false
        vm.submit()
        // The stale error must not make the screen drop the jump before the new search runs.
        assertEquals(ResultsJump.Wait, vm.uiState.value.resultsJump())
        advanceUntilIdle()
        assertEquals(ResultsJump.Focus, vm.uiState.value.resultsJump())
    }
}
