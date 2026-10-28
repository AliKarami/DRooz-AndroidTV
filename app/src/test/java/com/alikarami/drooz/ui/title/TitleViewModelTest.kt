package com.alikarami.drooz.ui.title

import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import kotlinx.coroutines.CompletableDeferred
import com.alikarami.drooz.site.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun ref(kind: TitleKind) = TitleRef(kind, 7, "slug")
    private fun ep(s: Int, n: Int, played: Boolean = false, id: String = "s${s}e$n") =
        Episode(s, n, id, emptyList(), emptyList(), emptyList(), played)
    private fun card(r: TitleRef) = TitleCard(r, "T", 2020, null, 50, null, null, emptyList(), emptyList(), null,
        emptyList(), null, null, null)
    private fun detail(r: TitleRef, main: List<Season>, dub: List<Season> = emptyList()) =
        TitleDetail(card(r), null, emptyList(), StreamCatalog(main, dub), emptyList(), null)
    private fun series(s1Played: Int, s1Count: Int = 5, s2Played: Int = 0, dub: List<Season> = emptyList()) = detail(
        ref(TitleKind.SERIES),
        listOf(
            Season(1, (1..s1Count).map { ep(1, it, it <= s1Played) }),
            Season(2, (1..3).map { ep(2, it, it <= s2Played) }),
        ),
        dub,
    )

    private class FakeApi(val d: TitleDetail) : SiteApi {
        val watchlist = mutableListOf<Pair<Long, String>>()
        var failWatchlist = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun home() = error("unused")
        override suspend fun list(path: String, page: Int) = error("unused")
        override suspend fun title(ref: TitleRef) = d
        override suspend fun search(query: String) = error("unused")
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = error("unused")
        override suspend fun setWatchlist(postId: Long, state: String) {
            gate?.await()
            if (failWatchlist) error("boom")
            watchlist += postId to state
        }
    }

    private class FakePrefs(val map: MutableMap<Long, Boolean> = mutableMapOf()) : TitlePrefs {
        override suspend fun dubSelected(postId: Long) = map[postId]
        override suspend fun setDubSelected(postId: Long, selected: Boolean) { map[postId] = selected }
    }
    private var prefs = FakePrefs()
    @Before fun resetPrefs() { prefs = FakePrefs() }

    private fun TestScope.vm(api: FakeApi): TitleViewModel {
        val repo = TitleInfoRepository(api, fetchScope = CoroutineScope(dispatcher + SupervisorJob()))
        return TitleViewModel(api.d.card.ref, api, repo, prefs)
    }

    private suspend fun TestScope.load(d: TitleDetail): Pair<TitleViewModel, FakeApi> {
        val api = FakeApi(d); val v = vm(api); advanceUntilIdle(); return v to api
    }

    @Test fun primaryLabelNotStarted() = runTest(dispatcher) {
        val (v, _) = load(series(0))
        assertEquals("پخش قسمت ۱", v.uiState.value.primaryLabel)
    }

    @Test fun primaryLabelContinue() = runTest(dispatcher) {
        val (v, _) = load(series(3))
        assertEquals("ادامه فصل ۱ قسمت ۴", v.uiState.value.primaryLabel)
        assertEquals(4, v.uiState.value.primaryEpisode?.number)
    }

    @Test fun primaryLabelAllWatched() = runTest(dispatcher) {
        val (v, _) = load(series(5, s2Played = 3))
        assertEquals("تماشای دوباره", v.uiState.value.primaryLabel)
        assertEquals(1, v.uiState.value.primaryEpisode?.number)
        assertEquals(1, v.uiState.value.selectedSeason)
    }

    @Test fun filmLabel() = runTest(dispatcher) {
        val r = ref(TitleKind.FILM)
        val (v, _) = load(detail(r, listOf(Season(1, listOf(ep(1, 1))))))
        assertEquals("پخش", v.uiState.value.primaryLabel)
        val (v2, _) = load(detail(r, listOf(Season(1, listOf(ep(1, 1, played = true))))))
        assertEquals("تماشای دوباره", v2.uiState.value.primaryLabel)
    }

    @Test fun crossesSeasonBoundary() = runTest(dispatcher) {
        val (v, _) = load(series(5))
        val s = v.uiState.value
        assertEquals("ادامه فصل ۲ قسمت ۱", s.primaryLabel)
        assertEquals(2, s.selectedSeason)
        assertEquals(listOf(1, 2), s.seasons)
    }

    @Test fun initialFocusIsNextUnwatched() = runTest(dispatcher) {
        val (v, _) = load(series(2))
        val s = v.uiState.value
        assertEquals(3, s.initialFocusEpisode)
        assertEquals(listOf(true, true, false, false, false), s.episodes.map { it.watched })
        assertEquals(50, s.episodes[0].minutes)
        assertEquals("فصل ۱ · قسمت ۱", s.episodes[0].label)
    }

    @Test fun selectSeasonChangesEpisodes() = runTest(dispatcher) {
        val (v, _) = load(series(0))
        v.selectSeason(2)
        assertEquals(3, v.uiState.value.episodes.size)
        assertEquals(1, v.uiState.value.initialFocusEpisode)
    }

    @Test fun dubHiddenWithoutDub() = runTest(dispatcher) {
        val (v, _) = load(series(0))
        assertFalse(v.uiState.value.dubAvailable)
        v.toggleDub()
        assertFalse(v.uiState.value.dubSelected)
    }

    @Test fun dubSelectionPlaysDubStream() = runTest(dispatcher) {
        val dub = listOf(Season(1, listOf(ep(1, 1, id = "dub1"))))
        val (v, _) = load(series(0, dub = dub))
        assertTrue(v.uiState.value.dubAvailable)
        val got = mutableListOf<PlayRequest>()
        val job = launch(got, v)
        v.play(); runCurrent()
        assertEquals("original", got.last().lang)
        v.toggleDub()
        assertTrue(v.uiState.value.dubSelected)
        v.play(); runCurrent()
        assertEquals("dubbed-fa", got.last().lang)
        assertEquals("dub1", got.last().episode.streamId)
        job.cancel()
    }

    private fun TestScope.launch(into: MutableList<PlayRequest>, v: TitleViewModel) =
        backgroundScope.launch(dispatcher) { v.playRequests.collect { into += it } }.also { runCurrent() }

    @Test fun watchlistToggleCallsSiteOnce() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        assertNull(v.uiState.value.inWatchlist)
        v.toggleWatchlist(); advanceUntilIdle()
        assertEquals(listOf(7L to "watch"), api.watchlist)
        assertEquals(true, v.uiState.value.inWatchlist)
        v.toggleWatchlist(); advanceUntilIdle()
        assertEquals(false, v.uiState.value.inWatchlist)
        assertEquals(2, api.watchlist.size)
    }

    @Test fun dubChoiceRestoredOnLoad() = runTest(dispatcher) {
        prefs = FakePrefs(mutableMapOf(7L to true))
        val dub = listOf(Season(1, listOf(ep(1, 1, id = "dub1"))))
        val (v, _) = load(series(0, dub = dub))
        assertTrue(v.uiState.value.dubSelected)
        assertEquals("dub1", v.uiState.value.primaryEpisode?.streamId)
        // Stored choice is ignored when the title has no dub.
        prefs = FakePrefs(mutableMapOf(7L to true))
        val (v2, _) = load(series(0))
        assertFalse(v2.uiState.value.dubSelected)
    }

    @Test fun toggleDubPersists() = runTest(dispatcher) {
        prefs = FakePrefs()
        val (v, _) = load(series(0, dub = listOf(Season(1, listOf(ep(1, 1))))))
        v.toggleDub(); advanceUntilIdle()
        assertEquals(true, prefs.map[7L])
        v.toggleDub(); advanceUntilIdle()
        assertEquals(false, prefs.map[7L])
    }

    @Test fun secondPressWhileInFlightIsIgnored() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        api.gate = CompletableDeferred()
        v.toggleWatchlist(); runCurrent()
        v.toggleWatchlist(); runCurrent()
        api.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, api.watchlist.size)
        assertEquals(true, v.uiState.value.inWatchlist)
        v.toggleWatchlist(); advanceUntilIdle()
        assertEquals(2, api.watchlist.size)
    }

    @Test fun watchlistFailureReverts() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        api.failWatchlist = true
        v.toggleWatchlist(); advanceUntilIdle()
        assertNull(v.uiState.value.inWatchlist)
        assertNotNull(v.uiState.value.error)
    }
}
