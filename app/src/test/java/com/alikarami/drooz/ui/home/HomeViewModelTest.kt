package com.alikarami.drooz.ui.home

import com.alikarami.drooz.data.Backdrop
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
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun ref(id: Long, slug: String = "slug-$id") = TitleRef(TitleKind.FILM, id, slug)
    private fun card(r: TitleRef, title: String = "Title ${r.postId}", synopsis: String? = "syn") =
        TitleCard(r, title, 2020, "ph${r.postId}", 100, Rating(7.5, 1000), null, listOf("Drama"), emptyList(), null,
            emptyList(), synopsis, null, null)
    private fun detail(r: TitleRef, title: String = "Real Title", thumb: String? = "thumb", dub: Boolean = false) = TitleDetail(
        card(r, title), null, emptyList(),
        StreamCatalog(
            listOf(Season(1, listOf(Episode(1, 1, "s", listOf(VideoVariant("v", 3840, 2160, "1")), emptyList(),
                listOf(SubtitleRef("x", "fa")), false)))),
            if (dub) listOf(Season(1, emptyList())) else emptyList(),
        ),
        emptyList(), thumb,
    )

    private class FakeApi(
        var homePage: HomePage,
        var onList: suspend (String) -> ListPage = { ListPage(emptyList(), 1, 1) },
        var onTitle: suspend (TitleRef) -> TitleDetail = { error("no detail") },
    ) : SiteApi {
        val listCalls = mutableListOf<String>()
        val titleCalls = mutableListOf<TitleRef>()
        var onHome: suspend () -> HomePage = { homePage }
        override suspend fun home() = onHome()
        override suspend fun list(path: String, page: Int): ListPage { listCalls += path; return onList(path) }
        override suspend fun title(ref: TitleRef): TitleDetail { titleCalls += ref; return onTitle(ref) }
        override suspend fun search(query: String) = error("unused")
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = error("unused")
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
    }

    private fun home() = HomePage(
        featured = listOf(FeaturedItem(ref(10), "bd10")),
        recentlyStreamed = listOf(PosterRef(ref(1, "shaun-the-sheep"), "rp1"), PosterRef(ref(2, "other-one"), "rp2")),
        sections = listOf(
            HomeSection("فیلم های برگزیده", null, (10L..14L).map { card(ref(it)) }),
            HomeSection("فیلم های جدید", null, listOf(card(ref(20)))),
        ),
    )

    private fun TestScope.vm(api: FakeApi): HomeViewModel {
        val repo = TitleInfoRepository(api, fetchScope = backgroundScope)
        return HomeViewModel(api, repo)
    }

    @Test fun exposesFeaturedBackdropForTitlePage() = runTest(dispatcher) {
        val vm = vm(FakeApi(home())); advanceUntilIdle()
        assertEquals("bd10", vm.featuredBackdrop(ref(10)))
        assertEquals(null, vm.featuredBackdrop(ref(11)))
    }

    @Test fun firstRowIsRecentlyStreamedLandscape() = runTest(dispatcher) {
        val vm = vm(FakeApi(home())); advanceUntilIdle()
        val row = vm.uiState.value.rows[0]
        assertEquals("recent", row.key)
        assertEquals(listOf("Shaun The Sheep", "Other One"), row.items.map { it.title })
        assertTrue(row.items.all { it.landscape })
        assertFalse(row.loading)
    }

    @Test fun homeSectionsMappedInConfigOrder() = runTest(dispatcher) {
        val vm = vm(FakeApi(home())); advanceUntilIdle()
        val rows = vm.uiState.value.rows
        assertEquals(listOf("recent", "featured", "updated-series", "new-films"), rows.take(4).map { it.key })
        assertEquals(5, rows[1].items.size)
        assertEquals(1, rows[3].items.size)
        assertTrue(rows[2].items.isEmpty())
    }

    @Test fun listRowsLoadLazily() = runTest(dispatcher) {
        val api = FakeApi(home(), onList = { ListPage(listOf(card(ref(30))), 1, 1) })
        val vm = vm(api); advanceUntilIdle()
        assertTrue(api.listCalls.isEmpty())
        vm.loadRowIfNeeded("popular"); advanceUntilIdle()
        vm.loadRowIfNeeded("popular"); advanceUntilIdle()
        assertEquals(1, api.listCalls.size)
        assertEquals(1, vm.uiState.value.rows.first { it.key == "popular" }.items.size)
    }

    @Test fun heroShowsCardDataImmediately() = runTest(dispatcher) {
        val api = FakeApi(home(), onList = { ListPage(listOf(card(ref(30), "Hello", "the synopsis")), 1, 1) })
        val vm = vm(api); advanceUntilIdle()
        vm.loadRowIfNeeded("popular"); advanceUntilIdle()
        vm.onFocus("popular", 0); runCurrent()
        val hero = vm.uiState.value.hero!!
        assertEquals("Hello", hero.title)
        assertEquals("the synopsis", hero.synopsis)
        assertTrue(hero.backdrop is Backdrop.BlurredPoster)
        assertTrue(api.titleCalls.isEmpty())
    }

    @Test fun heroImdbIsStructuredNotMetaText() = runTest(dispatcher) {
        val vm = vm(FakeApi(home())); advanceUntilIdle()
        vm.onFocus("featured", 0); runCurrent()
        val hero = vm.uiState.value.hero!!
        assertEquals(7.5, hero.imdb!!, 0.0)
        assertTrue(hero.meta.none { "IMDb" in it })
    }

    @Test fun heroCarriesGenres() = runTest(dispatcher) {
        val vm = vm(FakeApi(home())); advanceUntilIdle()
        vm.onFocus("featured", 0); runCurrent()
        assertEquals(listOf("Drama"), vm.uiState.value.hero!!.genres)
    }

    @Test fun heroFetchesDetailAfterDwell() = runTest(dispatcher) {
        val api = FakeApi(home(), onTitle = { detail(it) })
        val vm = vm(api); advanceUntilIdle()
        vm.onFocus("featured", 1)
        advanceTimeBy(299); runCurrent()
        assertTrue(api.titleCalls.isEmpty())
        vm.onFocus("featured", 2)
        advanceTimeBy(299); runCurrent()
        assertTrue(api.titleCalls.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(ref(12)), api.titleCalls)
    }

    @Test fun heroUpgradesTitleWhenDetailLoads() = runTest(dispatcher) {
        val api = FakeApi(home(), onTitle = { detail(it, "Real Title", dub = true) })
        val vm = vm(api); advanceUntilIdle()
        vm.onFocus("recent", 0); runCurrent()
        assertEquals("Shaun The Sheep", vm.uiState.value.hero!!.title)
        advanceTimeBy(301); advanceUntilIdle()
        val hero = vm.uiState.value.hero!!
        assertEquals("Real Title", hero.title)
        assertTrue(hero.backdrop is Backdrop.Wide)
        assertTrue(hero.chips.containsAll(listOf("دوبله", "زیرنویس", "4K")))
    }

    @Test fun wideHeroKeepsPosterFallback() = runTest(dispatcher) {
        val api = FakeApi(home(), onTitle = { detail(it) })
        val vm = vm(api); advanceUntilIdle()
        vm.onFocus("recent", 0); advanceTimeBy(301); advanceUntilIdle()
        val hero = vm.uiState.value.hero!!
        assertTrue(hero.backdrop is Backdrop.Wide)
        assertTrue(hero.posterBackdrop is Backdrop.BlurredPoster)
    }

    @Test fun heroFailureKeepsPosterHero() = runTest(dispatcher) {
        val api = FakeApi(home(), onTitle = { error("boom") })
        val vm = vm(api); advanceUntilIdle()
        vm.onFocus("recent", 0)
        advanceTimeBy(301); advanceUntilIdle()
        val hero = vm.uiState.value.hero!!
        assertEquals("Shaun The Sheep", hero.title)
        assertTrue(hero.backdrop is Backdrop.BlurredPoster)
    }

    @Test fun remembersFocusedCardPerRow() = runTest(dispatcher) {
        val vm = vm(FakeApi(home())); advanceUntilIdle()
        vm.onFocus("featured", 4)
        assertEquals(4, vm.focusedIndex("featured"))
        assertEquals(0, vm.focusedIndex("recent"))
    }

    @Test fun rowErrorIsInlineAndRetryable() = runTest(dispatcher) {
        var fail = true
        val api = FakeApi(home(), onList = {
            if (fail) error("net") else ListPage(listOf(card(ref(30))), 1, 1)
        })
        val vm = vm(api); advanceUntilIdle()
        vm.loadRowIfNeeded("popular"); advanceUntilIdle()
        val bad = vm.uiState.value.rows.first { it.key == "popular" }
        assertTrue(bad.error); assertFalse(bad.loading)
        assertNull(vm.uiState.value.error)
        fail = false
        vm.retryRow("popular"); advanceUntilIdle()
        val ok = vm.uiState.value.rows.first { it.key == "popular" }
        assertFalse(ok.error); assertEquals(1, ok.items.size)
    }

    @Test fun homeFailureSetsErrorAndRetryRecovers() = runTest(dispatcher) {
        val api = FakeApi(home())
        api.onHome = { error("offline") }
        val vm = vm(api); advanceUntilIdle()
        assertEquals("offline", vm.uiState.value.error)
        assertFalse(vm.uiState.value.rows[0].loading)
        api.onHome = { home() }
        vm.retryRow("recent"); advanceUntilIdle()
        assertNull(vm.uiState.value.error)
        assertEquals(2, vm.uiState.value.rows[0].items.size)
    }

    @Test fun refreshKeepsLoadedListRows() = runTest(dispatcher) {
        val api = FakeApi(home(), onList = { ListPage(listOf(card(ref(30))), 1, 1) })
        val vm = vm(api); advanceUntilIdle()
        assertFalse(vm.uiState.value.rows.first { it.key == "popular" }.requested)
        vm.loadRowIfNeeded("popular"); advanceUntilIdle()
        vm.retryRow("recent"); advanceUntilIdle()
        val popular = vm.uiState.value.rows.first { it.key == "popular" }
        assertEquals(1, popular.items.size)
        assertTrue(popular.requested)
        assertEquals(1, api.listCalls.size)
    }

    @Test fun staleRefreshDoesNotOverwriteNewer() = runTest(dispatcher) {
        val stale = CompletableDeferred<HomePage>()
        val api = FakeApi(home())
        var n = 0
        api.onHome = { if (n++ == 0) stale.await() else home() }
        val vm = vm(api); runCurrent()
        vm.refresh(); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rows[0].items.size)
        stale.complete(HomePage(emptyList(), emptyList(), emptyList())); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rows[0].items.size)
    }

    @Test fun retryRowDoesNotDuplicateInFlightLoad() = runTest(dispatcher) {
        val gate = CompletableDeferred<ListPage>()
        val api = FakeApi(home(), onList = { gate.await() })
        val vm = vm(api); advanceUntilIdle()
        vm.loadRowIfNeeded("popular"); runCurrent()
        vm.retryRow("popular"); runCurrent()
        assertEquals(1, api.listCalls.size)
        gate.complete(ListPage(listOf(card(ref(30))), 1, 1)); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.rows.first { it.key == "popular" }.items.size)
    }
}
