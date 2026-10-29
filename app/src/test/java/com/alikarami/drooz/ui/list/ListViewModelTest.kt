package com.alikarami.drooz.ui.list

import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
class ListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val path = "/archive/category/new-films/"

    private fun ref(id: Long) = TitleRef(TitleKind.FILM, id, "slug-$id")
    private fun card(id: Long) = TitleCard(ref(id), "Title $id", 2020, "ph$id", 100, Rating(7.5, 1000), null,
        listOf("Drama"), emptyList(), null, emptyList(), "syn $id", null, null)

    /** 20 cards per page; page n holds ids n*100 .. n*100+19. */
    private fun page(n: Int, last: Int) = ListPage((0 until 20).map { card(n * 100L + it) }, n, last)

    private class FakeApi(var onList: suspend (String, Int) -> ListPage) : SiteApi {
    override suspend fun subtitle(url: String): String = error("unused")
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}
        val listCalls = mutableListOf<Pair<String, Int>>()
        override suspend fun list(path: String, page: Int): ListPage { listCalls += path to page; return onList(path, page) }
        override suspend fun home() = error("unused")
        override suspend fun title(ref: TitleRef): TitleDetail = error("no detail")
        override suspend fun search(query: String) = error("unused")
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = error("unused")
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
    }

    private fun TestScope.vm(api: FakeApi) =
        ListViewModel(api, TitleInfoRepository(api, fetchScope = backgroundScope), path, "فیلم های جدید")

    @Test fun loadsFirstPage() = runTest(dispatcher) {
        val api = FakeApi { _, p -> page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals(listOf(path to 1), api.listCalls)
        assertEquals(20, s.items.size)
        assertEquals("Title 100", s.items[0].title)
        assertFalse(s.loading)
        assertNull(s.error)
        assertEquals("فیلم های جدید", s.title)
    }

    @Test fun loadsNextPageWhenNearEnd() = runTest(dispatcher) {
        val api = FakeApi { _, p -> page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(vm.uiState.value.items.size - 6); advanceUntilIdle()
        assertEquals(listOf(path to 1, path to 2), api.listCalls)
        assertEquals(40, vm.uiState.value.items.size)
        assertEquals("Title 200", vm.uiState.value.items[20].title)
    }

    @Test fun doesNotLoadNextPageFarFromEnd() = runTest(dispatcher) {
        val api = FakeApi { _, p -> ListPage((0 until 40).map { card(p * 100L + it) }, p, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(0); advanceUntilIdle()
        assertEquals(listOf(path to 1), api.listCalls)
    }

    @Test fun keepsLoadingWhenFocusIsStillNearEndAfterPageLands() = runTest(dispatcher) {
        // Focus reached the end while page 2 was in flight; nothing else will re-trigger a load.
        val gate = CompletableDeferred<ListPage>()
        val api = FakeApi { _, p -> if (p == 2) gate.await() else page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(19); runCurrent()
        gate.complete(ListPage((0 until 3).map { card(200L + it) }, 2, 5)); advanceUntilIdle()
        assertEquals(listOf(path to 1, path to 2, path to 3), api.listCalls)
        assertEquals(43, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.loadingMore)
    }

    @Test fun stopsAtLastPage() = runTest(dispatcher) {
        val api = FakeApi { _, p -> page(p, 2) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(vm.uiState.value.items.size - 6); advanceUntilIdle()
        vm.onFocusIndex(vm.uiState.value.items.size - 1); advanceUntilIdle()
        assertEquals(listOf(path to 1, path to 2), api.listCalls)
        assertTrue(vm.uiState.value.endReached)
    }

    @Test fun doesNotDoubleLoad() = runTest(dispatcher) {
        val gate = CompletableDeferred<ListPage>()
        val api = FakeApi { _, p -> if (p == 2) gate.await() else page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(vm.uiState.value.items.size - 6); runCurrent()
        assertTrue(vm.uiState.value.loadingMore)
        vm.onFocusIndex(vm.uiState.value.items.size - 5); runCurrent()
        assertEquals(listOf(path to 1, path to 2), api.listCalls)
        gate.complete(page(2, 5)); advanceUntilIdle()
        assertEquals(40, vm.uiState.value.items.size)
        assertFalse(vm.uiState.value.loadingMore)
    }

    @Test fun pageErrorKeepsItemsAndShowsRetry() = runTest(dispatcher) {
        var fail = true
        val api = FakeApi { _, p -> if (p == 2 && fail) error("net") else page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(vm.uiState.value.items.size - 6); advanceUntilIdle()
        val bad = vm.uiState.value
        assertEquals(20, bad.items.size)
        assertTrue(bad.pageError)
        assertFalse(bad.loadingMore)
        assertNull(bad.error)
        // Focus moving near the end again does not hammer the failing page; Retry does.
        vm.onFocusIndex(19); advanceUntilIdle()
        assertEquals(2, api.listCalls.size)
        fail = false
        vm.retry(); advanceUntilIdle()
        assertEquals(listOf(path to 1, path to 2, path to 2), api.listCalls)
        assertFalse(vm.uiState.value.pageError)
        assertEquals(40, vm.uiState.value.items.size)
    }

    @Test fun firstPageErrorIsScreenErrorAndRetryRecovers() = runTest(dispatcher) {
        var fail = true
        val api = FakeApi { _, p -> if (fail) error("offline") else page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        assertEquals("offline", vm.uiState.value.error)
        assertFalse(vm.uiState.value.loading)
        fail = false
        vm.retry(); advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals(20, vm.uiState.value.items.size)
    }

    @Test fun duplicateCardsAcrossPagesAreDropped() = runTest(dispatcher) {
        // The site's lists shift when new titles are added, so page 2 can repeat page 1's tail.
        val api = FakeApi { _, p -> if (p == 1) page(1, 5) else ListPage(listOf(card(119), card(300)), 2, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(19); advanceUntilIdle()
        assertEquals(21, vm.uiState.value.items.size)
        assertEquals(ref(300), vm.uiState.value.items.last().ref)
    }

    @Test fun sameTitleWithDifferentSlugIsDeduped() = runTest(dispatcher) {
        // The grid keys cards by kind + postId; a renamed/re-encoded slug must not slip through as a duplicate.
        val renamed = card(119).copy(ref = TitleRef(TitleKind.FILM, 119, "renamed-slug"))
        val api = FakeApi { _, p -> if (p == 1) page(1, 5) else ListPage(listOf(renamed, card(300)), 2, 5) }
        val vm = vm(api); advanceUntilIdle()
        vm.onFocusIndex(19); advanceUntilIdle()
        val keys = vm.uiState.value.items.map { it.ref.kind to it.ref.postId }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(21, keys.size)
    }

    @Test fun heroFollowsFocusAndIndexIsRemembered() = runTest(dispatcher) {
        val api = FakeApi { _, p -> page(p, 5) }
        val vm = vm(api); advanceUntilIdle()
        assertEquals(0, vm.focusedIndex)
        vm.onFocusIndex(3); runCurrent()
        val hero = vm.uiState.value.hero!!
        assertEquals("Title 103", hero.title)
        assertEquals("syn 103", hero.synopsis)
        assertTrue(hero.backdrop is Backdrop.BlurredPoster)
        assertEquals(3, vm.focusedIndex)
    }
}
