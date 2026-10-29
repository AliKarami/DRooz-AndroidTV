package com.alikarami.drooz.ui.home

import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.HomePageCache
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.player.PlayerArgs
import com.alikarami.drooz.site.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
    override suspend fun subtitle(url: String): String = error("unused")
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}
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

    // Warm start: a recreated home shows the cached page at once (no skeleton), then revalidates it.
    @Test fun cachedHomeIsShownThenRefreshed() = runTest(dispatcher) {
        val cache = HomePageCache(ttlMs = 600_000) { 0L }
        cache.put(home())
        val api = FakeApi(home())
        val fresh = CompletableDeferred<HomePage>()
        var homeCalls = 0
        api.onHome = { homeCalls++; fresh.await() }
        val vm = HomeViewModel(api, TitleInfoRepository(api, fetchScope = backgroundScope), homeCache = cache)
        runCurrent()
        val recent = vm.uiState.value.rows[0]
        assertEquals(listOf("Shaun The Sheep", "Other One"), recent.items.map { it.title })
        assertFalse(recent.loading)
        assertEquals("bd10", vm.featuredBackdrop(ref(10)))
        assertEquals(1, homeCalls)

        fresh.complete(home().copy(recentlyStreamed = listOf(PosterRef(ref(3, "brand-new"), "rp3"))))
        advanceUntilIdle()
        assertEquals(listOf("Brand New"), vm.uiState.value.rows[0].items.map { it.title })
        assertEquals(ref(3, "brand-new"), cache.fresh()!!.recentlyStreamed.single().ref)
    }

    // Revalidation must not disturb the cached rows when it fails.
    @Test fun failedRefreshKeepsCachedRows() = runTest(dispatcher) {
        val cache = HomePageCache(ttlMs = 600_000) { 0L }
        cache.put(home())
        val api = FakeApi(home())
        api.onHome = { throw com.alikarami.drooz.site.SiteException.NetworkError(RuntimeException("x")) }
        val vm = HomeViewModel(api, TitleInfoRepository(api, fetchScope = backgroundScope), homeCache = cache)
        advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rows[0].items.size)
        assertNull(vm.uiState.value.error)
    }

    // A refresh that reorders the focused row keeps focus memory and the hero on the same title.
    @Test fun reorderingRefreshKeepsFocusedTitle() = runTest(dispatcher) {
        val api = FakeApi(home())
        val vm = vm(api); advanceUntilIdle()
        vm.onFocus("recent", 1); runCurrent()              // "other-one"
        assertEquals(ref(2, "other-one"), vm.uiState.value.hero!!.ref)

        api.homePage = home().copy(recentlyStreamed = listOf(
            PosterRef(ref(5, "newcomer"), "rp5"), PosterRef(ref(1, "shaun-the-sheep"), "rp1"),
            PosterRef(ref(2, "other-one"), "rp2"),
        ))
        vm.refresh(); advanceUntilIdle()
        assertEquals(2, vm.focusedIndex("recent"))
        assertEquals(ref(2, "other-one"), vm.uiState.value.hero!!.ref)
        // Focus events after the refresh resolve against the new order.
        vm.onFocus("recent", 0); runCurrent()
        assertEquals(ref(5, "newcomer"), vm.uiState.value.hero!!.ref)
    }

    // Cards are keyed by title identity in the UI, so a row never holds the same title twice.
    @Test fun rowItemsAreUniqueByTitle() = runTest(dispatcher) {
        val dup = home().copy(recentlyStreamed = listOf(
            PosterRef(ref(1, "shaun-the-sheep"), "rp1"), PosterRef(ref(1, "shaun-the-sheep"), "rp1"),
        ))
        val vm = vm(FakeApi(dup)); advanceUntilIdle()
        assertEquals(1, vm.uiState.value.rows[0].items.size)
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

    // #1: back on home after a play, «ادامه تماشا» is revalidated in place: no skeleton, focus kept by title.
    @Test fun resumeAfterPlayRevalidatesQuietly() = runTest(dispatcher) {
        val api = FakeApi(home())
        val repo = TitleInfoRepository(api, fetchScope = backgroundScope)
        val vm = HomeViewModel(api, repo); advanceUntilIdle()
        vm.onFocus("recent", 1); runCurrent()
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val played = home().copy(recentlyStreamed = listOf(
            PosterRef(ref(9, "just-played"), "rp9"), PosterRef(ref(1, "shaun-the-sheep"), "rp1"),
            PosterRef(ref(2, "other-one"), "rp2"),
        ))
        api.onHome = { calls++; gate.await(); played }
        vm.onResume(); runCurrent()
        assertEquals(0, calls)                              // nothing played: no refetch

        repo.invalidate(ref(9))
        vm.onResume(); runCurrent()
        assertEquals(1, calls)
        val during = vm.uiState.value.rows[0]
        assertFalse(during.loading)
        assertEquals(2, during.items.size)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("Just Played", "Shaun The Sheep", "Other One"), vm.uiState.value.rows[0].items.map { it.title })
        assertEquals(2, vm.focusedIndex("recent"))
        vm.onResume(); advanceUntilIdle()
        assertEquals(1, calls)                              // seen once
    }

    @Test fun failedResumeRevalidationKeepsRows() = runTest(dispatcher) {
        val api = FakeApi(home())
        val repo = TitleInfoRepository(api, fetchScope = backgroundScope)
        val vm = HomeViewModel(api, repo); advanceUntilIdle()
        api.onHome = { throw com.alikarami.drooz.site.SiteException.NetworkError(RuntimeException("x")) }
        repo.invalidate(ref(1))
        vm.onResume(); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.rows[0].items.size)
        assertNull(vm.uiState.value.error)
    }

    // #6: a refresh that removes the focused card hands the row's focus to the card now in its place.
    @Test fun removedFocusedCardMovesFocusToNearest() = runTest(dispatcher) {
        val api = FakeApi(home())
        val vm = vm(api); advanceUntilIdle()
        vm.onFocus("featured", 3); runCurrent()             // ref 13
        api.homePage = home().copy(sections = listOf(
            HomeSection("فیلم های برگزیده", null, listOf(10L, 11L, 12L, 14L).map { card(ref(it)) }),
        ))
        vm.refresh(); advanceUntilIdle()
        assertEquals(3, vm.focusedIndex("featured"))        // ref 14 slid into its place

        api.homePage = home().copy(sections = listOf(
            HomeSection("فیلم های برگزیده", null, listOf(10L, 11L).map { card(ref(it)) }),
        ))
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, vm.focusedIndex("featured"))        // the row got shorter: its last card
        // Focus memory now follows the new card.
        vm.refresh(); advanceUntilIdle()
        assertEquals(1, vm.focusedIndex("featured"))
    }

    // ── «ادامه تماشا» (Continue Watching) ─────────────────────────────────────

    private class FakeWatchStore(val entries: MutableList<WatchEntry> = mutableListOf(), var failure: Exception? = null) : WatchStore {
        override suspend fun get(streamId: String) = entries.firstOrNull { it.streamId == streamId }
        override suspend fun put(entry: WatchEntry) { entries.removeAll { it.streamId == entry.streamId }; entries += entry }
        override suspend fun all(): List<WatchEntry> { failure?.let { throw it }; return entries.sortedByDescending { it.updatedAt } }
    }

    private class FakeTitlePrefs(val map: MutableMap<Long, Boolean> = mutableMapOf(), var defaultDub: Boolean = false) : TitlePrefs {
        override suspend fun defaultDub() = defaultDub
        override suspend fun setDefaultDub(on: Boolean) { defaultDub = on }
        override suspend fun dubSelected(postId: Long) = map[postId]
        override suspend fun setDubSelected(postId: Long, selected: Boolean) { map[postId] = selected }
    }

    private fun series(id: Long) = TitleRef(TitleKind.SERIES, id, "series-$id")
    private fun ep(n: Int, played: Boolean = false) =
        Episode(1, n, "s1e$n", listOf(VideoVariant("v", 1920, 1080, "1")), emptyList(), emptyList(), played)
    /** A two-episode series; [playedEps] are watched on the server. */
    private fun seriesDetail(r: TitleRef, vararg playedEps: Int) = TitleDetail(
        card(r, "Series ${r.postId}"), null, emptyList(),
        StreamCatalog(listOf(Season(1, (1..2).map { ep(it, it in playedEps) })), emptyList()),
        emptyList(), null,
    )
    private fun recentHome(vararg refs: TitleRef) =
        home().copy(recentlyStreamed = refs.map { PosterRef(it, "rp${it.postId}") })
    private fun local(r: TitleRef, streamId: String, pos: Long, dur: Long = 3_000_000, at: Long = 1) =
        WatchEntry(streamId, r.path, 1, 1, pos, dur, at, finished = false)

    // Not backgroundScope: advanceUntilIdle must also run the repository's fetches.
    private fun cwVm(api: FakeApi, store: FakeWatchStore = FakeWatchStore(), prefs: FakeTitlePrefs = FakeTitlePrefs()) =
        HomeViewModel(api, TitleInfoRepository(api, fetchScope = CoroutineScope(dispatcher + SupervisorJob())),
            watchStore = store, titlePrefs = prefs)

    private fun HomeViewModel.recent() = uiState.value.rows.first { it.key == "recent" }

    @Test fun recentRow_showsPendingThenResolvedLabel() = runTest(dispatcher) {
        val a = series(1)
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(recentHome(a), onTitle = { gate.await(); seriesDetail(it, 1) })
        val vm = cwVm(api); runCurrent()
        assertEquals(listOf(a), vm.recent().items.map { it.ref })
        assertNull(vm.recent().items.single().subtitle)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("فصل ۱ · قسمت ۲", vm.recent().items.single().subtitle)
    }

    @Test fun recentRow_dropsFullyWatchedSeries() = runTest(dispatcher) {
        val a = series(1); val b = series(2)
        val api = FakeApi(recentHome(a, b), onTitle = { if (it == a) seriesDetail(it, 1, 2) else seriesDetail(it) })
        val vm = cwVm(api); advanceUntilIdle()
        assertEquals(listOf(b), vm.recent().items.map { it.ref })
    }

    @Test fun recentRow_appendsLocalOnlyTitle() = runTest(dispatcher) {
        val a = series(1); val c = series(3)
        val store = FakeWatchStore(mutableListOf(local(c, "s1e1", pos = 600_000)))
        val api = FakeApi(recentHome(a), onTitle = { seriesDetail(it) })
        val vm = cwVm(api, store); advanceUntilIdle()
        val items = vm.recent().items
        assertEquals(listOf(a, c), items.map { it.ref })
        assertEquals(0.2f, items[1].progress!!, 0.001f)
    }

    @Test fun recentRow_failedDetail_keepsPendingCard() = runTest(dispatcher) {
        val a = series(1); val b = series(2)
        val api = FakeApi(recentHome(a, b), onTitle = { if (it == a) error("net") else seriesDetail(it) })
        val vm = cwVm(api); advanceUntilIdle()
        val items = vm.recent().items
        assertEquals(listOf(a, b), items.map { it.ref })
        assertNull(items[0].subtitle)
        assertEquals("فصل ۱ · قسمت ۱", items[1].subtitle)
    }

    @Test fun recentRow_failingStore_actsAsNoLocalState() = runTest(dispatcher) {
        val a = series(1)
        val api = FakeApi(recentHome(a), onTitle = { seriesDetail(it, 1) })
        val vm = cwVm(api, FakeWatchStore(failure = java.io.IOException("disk"))); advanceUntilIdle()
        assertEquals("فصل ۱ · قسمت ۲", vm.recent().items.single().subtitle)
    }

    @Test fun activate_resolvedRecentCard_playsNextEpisode() = runTest(dispatcher) {
        val a = series(1)
        val api = FakeApi(recentHome(a), onTitle = { seriesDetail(it, 1) })
        val vm = cwVm(api); advanceUntilIdle()
        assertEquals(HomeAction.Play(PlayerArgs(a, "s1e2", dub = false)), vm.activate("recent", a))
    }

    @Test fun activate_usesSavedDubOnlyWhenTitleHasDub() = runTest(dispatcher) {
        val a = series(1); val b = series(2)
        val dubbed = { r: TitleRef -> seriesDetail(r).let { it.copy(catalog = it.catalog.copy(dub = it.catalog.main)) } }
        val api = FakeApi(recentHome(a, b), onTitle = { if (it == a) dubbed(it) else seriesDetail(it) })
        val vm = cwVm(api, prefs = FakeTitlePrefs(defaultDub = true)); advanceUntilIdle()
        assertEquals(HomeAction.Play(PlayerArgs(a, "s1e1", dub = true)), vm.activate("recent", a))
        vm.onResume()
        assertEquals(HomeAction.Play(PlayerArgs(b, "s1e1", dub = false)), vm.activate("recent", b))
    }

    @Test fun activate_pendingCard_opensTitle() = runTest(dispatcher) {
        val a = series(1)
        val api = FakeApi(recentHome(a), onTitle = { CompletableDeferred<TitleDetail>().await() })
        val vm = cwVm(api); runCurrent()
        assertEquals(HomeAction.Open(a), vm.activate("recent", a))
    }

    @Test fun activate_twiceWithoutResume_secondIsNull() = runTest(dispatcher) {
        val a = series(1)
        val api = FakeApi(recentHome(a), onTitle = { seriesDetail(it) })
        val vm = cwVm(api); advanceUntilIdle()
        assertTrue(vm.activate("recent", a) is HomeAction.Play)
        assertNull(vm.activate("recent", a))
        vm.onResume(); advanceUntilIdle()
        assertTrue(vm.activate("recent", a) is HomeAction.Play)
    }

    @Test fun onResume_refreshesProgressFromStore_withoutRefetch() = runTest(dispatcher) {
        val a = series(1)
        val store = FakeWatchStore()
        val api = FakeApi(recentHome(a), onTitle = { seriesDetail(it) })
        val vm = cwVm(api, store); advanceUntilIdle()
        assertNull(vm.recent().items.single().progress)
        val calls = api.titleCalls.size
        store.entries += local(a, "s1e1", pos = 1_500_000)
        vm.onResume(); advanceUntilIdle()
        assertEquals(0.5f, vm.recent().items.single().progress!!, 0.001f)
        assertEquals(calls, api.titleCalls.size)
    }

    // The row can lose a card (a resolve drops it) before Compose recomposes: OK on that card must
    // not play the title that slid into its place (opening a stream page writes to the account).
    @Test fun activate_cardDroppedBeforeRecompose_doesNotPlayAnotherTitle() = runTest(dispatcher) {
        val a = series(1); val b = series(2)
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi(recentHome(a, b), onTitle = { gate.await(); if (it == a) seriesDetail(it, 1, 2) else seriesDetail(it) })
        val vm = cwVm(api); runCurrent()
        assertEquals(listOf(a, b), vm.recent().items.map { it.ref })   // what the screen rendered
        gate.complete(Unit); advanceUntilIdle()                          // a is fully watched: dropped
        assertNull(vm.activate("recent", a))
        assertEquals(HomeAction.Play(PlayerArgs(b, "s1e1", dub = false)), vm.activate("recent", b))
    }

    @Test fun activate_unknownRef_isNull() = runTest(dispatcher) {
        val api = FakeApi(home(), onTitle = { seriesDetail(it) })
        val vm = cwVm(api); advanceUntilIdle()
        assertNull(vm.activate("featured", ref(999)))
        assertNull(vm.activate("nope", ref(11)))
    }

    @Test fun activate_otherRow_opensTitle() = runTest(dispatcher) {
        val api = FakeApi(home(), onTitle = { seriesDetail(it) })
        val vm = cwVm(api); advanceUntilIdle()
        assertEquals(HomeAction.Open(ref(11)), vm.activate("featured", ref(11)))
    }
}
