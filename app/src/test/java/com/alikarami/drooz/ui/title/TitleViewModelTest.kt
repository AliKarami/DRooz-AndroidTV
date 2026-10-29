package com.alikarami.drooz.ui.title

import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.player.PlayerArgs
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.ui.common.UiStrings
import java.io.IOException
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

    private class FakeApi(var d: TitleDetail) : SiteApi {
    override suspend fun subtitle(url: String): String = error("unused")
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}
        var titleCalls = 0
        var titleGate: CompletableDeferred<Unit>? = null
        var titleFailure: Exception? = null
        val watchlist = mutableListOf<Pair<Long, String>>()
        var failWatchlist = false
        var gate: CompletableDeferred<Unit>? = null
        val streams = mutableListOf<Triple<String, String, Int>>()
        var streamGate: CompletableDeferred<Unit>? = null
        var streamFailure: Exception? = null
        override suspend fun home() = error("unused")
        override suspend fun list(path: String, page: Int) = error("unused")
        override suspend fun title(ref: TitleRef): TitleDetail {
            titleCalls++
            titleGate?.await()
            titleFailure?.let { throw it }
            return d
        }
        override suspend fun search(query: String) = error("unused")
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession {
            streams += Triple(episode.streamId, lang, height)
            streamGate?.await()
            streamFailure?.let { throw it }
            return StreamSession("T", "https://x/master.m3u8", "/bd.jpg", emptyList(), emptyMap(), emptyList(), null, null, emptyList(), episode.streamId)
        }
        override suspend fun setWatchlist(postId: Long, state: String) {
            gate?.await()
            if (failWatchlist) error("boom")
            watchlist += postId to state
        }
    }

    private class FakeWatchStore(val entries: MutableList<WatchEntry> = mutableListOf(), var failure: Exception? = null) : WatchStore {
        override suspend fun get(streamId: String) = entries.firstOrNull { it.streamId == streamId }
        override suspend fun put(entry: WatchEntry) { entries.removeAll { it.streamId == entry.streamId }; entries += entry }
        override suspend fun all(): List<WatchEntry> { failure?.let { throw it }; return entries.toList() }
    }
    private var store = FakeWatchStore()
    @Before fun resetStore() { store = FakeWatchStore() }

    private fun entry(id: String, pos: Long, dur: Long, finished: Boolean = false, path: String = ref(TitleKind.SERIES).path,
        at: Long = 1) = WatchEntry(id, path, 1, 1, pos, dur, at, finished)

    private class FakePrefs(val map: MutableMap<Long, Boolean> = mutableMapOf(), var defaultDub: Boolean = false) : TitlePrefs {
        override suspend fun defaultDub() = defaultDub
        override suspend fun setDefaultDub(on: Boolean) { defaultDub = on }
        override suspend fun dubSelected(postId: Long) = map[postId]
        override suspend fun setDubSelected(postId: Long, selected: Boolean) { map[postId] = selected }
    }
    private var prefs = FakePrefs()
    @Before fun resetPrefs() { prefs = FakePrefs() }

    private fun repo(api: FakeApi) = TitleInfoRepository(api, fetchScope = CoroutineScope(dispatcher + SupervisorJob()))

    private fun TestScope.vm(api: FakeApi, repo: TitleInfoRepository = repo(api)): TitleViewModel =
        TitleViewModel(api.d.card.ref, api, repo, prefs, store)

    private suspend fun TestScope.load(d: TitleDetail): Pair<TitleViewModel, FakeApi> {
        val api = FakeApi(d); val v = vm(api); advanceUntilIdle(); return v to api
    }

    private suspend fun TestScope.loadWithRepo(d: TitleDetail): Triple<TitleViewModel, FakeApi, TitleInfoRepository> {
        val api = FakeApi(d); val r = repo(api); val v = vm(api, r); advanceUntilIdle(); return Triple(v, api, r)
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

    @Test fun playEmitsArgsForPrimaryEpisode() = runTest(dispatcher) {
        val (v, api) = load(series(0).let { d -> d.copy(catalog = d.catalog.copy(main = listOf(
            Season(1, listOf(ep(1, 1, played = true), ep(1, 2))), Season(2, listOf(ep(2, 1)))))) })
        val got = collect(v)
        v.play(); settle()
        assertEquals(listOf<PlayEvent>(PlayEvent.Start(PlayerArgs(api.d.card.ref, "s1e2", dub = false))), got)
        assertTrue(api.streams.isEmpty())
    }

    @Test fun playTileEmitsThatEpisode() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        val got = collect(v)
        v.selectSeason(2)
        v.play(v.uiState.value.episodes[0].episode); settle()
        assertEquals(listOf<PlayEvent>(PlayEvent.Start(PlayerArgs(api.d.card.ref, "s2e1", dub = false))), got)
        assertTrue(api.streams.isEmpty())
    }

    @Test fun dubSelectionPlaysDubStream() = runTest(dispatcher) {
        val dub = listOf(Season(1, listOf(ep(1, 1, id = "dub1"))))
        val (v, api) = load(series(0, dub = dub))
        assertTrue(v.uiState.value.dubAvailable)
        val got = collect(v)
        v.toggleDub()
        v.play(); settle()
        assertEquals(PlayEvent.Start(PlayerArgs(api.d.card.ref, "dub1", dub = true)), got.single())
    }

    @Test fun doublePressEmitsOneStartUntilResumed() = runTest(dispatcher) {
        val (v, _) = load(series(0))
        val got = collect(v)
        v.play(); v.play(); settle()
        assertEquals(1, got.size)
        v.onResume(); settle()
        v.play(); settle()
        assertEquals(2, got.size)
    }

    // The player was closed before the stream opened, so it never invalidated: the cached page stays valid.
    @Test fun resumeWithoutInvalidationKeepsCachedPage() = runTest(dispatcher) {
        val (v, _) = load(series(0))
        collect(v)
        val before = v.uiState.value.detail
        v.play(); settle()
        v.onResume(); settle()
        assertNull(v.uiState.value.error)
        assertEquals(before, v.uiState.value.detail)
    }

    /** advanceUntilIdle does not run backgroundScope collectors; runCurrent does. */
    private fun TestScope.settle() { advanceUntilIdle(); runCurrent() }

    private fun TestScope.collect(v: TitleViewModel): MutableList<PlayEvent> {
        val into = mutableListOf<PlayEvent>()
        backgroundScope.launch(dispatcher) { v.playEvents.collect { into += it } }
        runCurrent()
        return into
    }

    @Test fun watchlistToggleCallsSiteOnce() = runTest(dispatcher) {
        val (v, api) = load(series(0).copy(inWatchlist = false))
        assertEquals(false, v.uiState.value.inWatchlist)
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
        val (v, api) = load(series(0).copy(inWatchlist = false))
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
        val (v, api) = load(series(0).copy(inWatchlist = false))
        api.failWatchlist = true
        v.toggleWatchlist(); advanceUntilIdle()
        assertEquals(false, v.uiState.value.inWatchlist)
        assertNotNull(v.uiState.value.error)
    }

    // #2: a title already on the watchlist opens as such; pressing removes it.
    @Test fun watchlistStateComesFromPage() = runTest(dispatcher) {
        val (v, api) = load(series(0).copy(inWatchlist = true))
        assertEquals(true, v.uiState.value.inWatchlist)
        v.toggleWatchlist(); advanceUntilIdle()
        assertEquals(false, v.uiState.value.inWatchlist)
        assertEquals(listOf(7L to "watch"), api.watchlist)
    }

    // Unknown state stays unknown: the control never claims a state it cannot know.
    @Test fun unknownWatchlistStaysNeutral() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        assertNull(v.uiState.value.inWatchlist)
        v.toggleWatchlist(); advanceUntilIdle()
        assertNull(v.uiState.value.inWatchlist)
        assertEquals(1, api.watchlist.size)
    }

    // The cached page must not bring the old state back on the next visit.
    @Test fun watchlistToggleUpdatesCachedPage() = runTest(dispatcher) {
        val api = FakeApi(series(0).copy(inWatchlist = false))
        val repo = repo(api)
        val v = vm(api, repo); advanceUntilIdle()
        v.toggleWatchlist(); advanceUntilIdle()
        val again = vm(api, repo); advanceUntilIdle()
        assertEquals(true, again.uiState.value.inWatchlist)
        assertEquals(1, api.titleCalls)
    }

    // #4: the failure toast is shown once, not on every return to the page.
    @Test fun watchlistErrorIsConsumed() = runTest(dispatcher) {
        val (v, api) = load(series(0).copy(inWatchlist = false))
        api.failWatchlist = true
        v.toggleWatchlist(); advanceUntilIdle()
        assertNotNull(v.uiState.value.error)
        v.onErrorShown()
        assertNull(v.uiState.value.error)
        assertNotNull(v.uiState.value.detail)
    }

    // #5: the not-started label names the episode that will play.
    @Test fun primaryLabelNotStartedNamesFirstAvailableEpisode() = runTest(dispatcher) {
        val r = ref(TitleKind.SERIES)
        val (v, _) = load(detail(r, listOf(Season(1, listOf(ep(1, 3), ep(1, 4))))))
        assertEquals("پخش قسمت ۳", v.uiState.value.primaryLabel)
        assertEquals(3, v.uiState.value.primaryEpisode?.number)
        val (v2, _) = load(detail(r, listOf(Season(2, listOf(ep(2, 1), ep(2, 2))))))
        assertEquals("پخش فصل ۲ قسمت ۱", v2.uiState.value.primaryLabel)
    }

    // #1: after a play, coming back revalidates the page in place (no skeleton) and shows the new progress.
    @Test fun resumeAfterPlayRevalidatesInPlace() = runTest(dispatcher) {
        val (v, api, repo) = loadWithRepo(series(0))
        collect(v)
        v.play(); settle()
        repo.invalidate(api.d.card.ref)       // the player does this when the stream opens
        api.d = series(1)
        api.titleGate = CompletableDeferred()
        v.onResume(); runCurrent()
        assertNotNull(v.uiState.value.detail)                      // no skeleton while revalidating
        assertEquals("پخش قسمت ۱", v.uiState.value.primaryLabel)
        api.titleGate!!.complete(Unit); advanceUntilIdle()
        assertEquals("ادامه فصل ۱ قسمت ۲", v.uiState.value.primaryLabel)
        assertTrue(v.uiState.value.episodes[0].watched)
        assertEquals(2, api.titleCalls)
    }

    @Test fun resumeWithoutPlayDoesNotRefetch() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        v.onResume(); advanceUntilIdle()
        assertEquals(1, api.titleCalls)
    }

    // The focused tile stays put: revalidation never switches the season under the user.
    @Test fun revalidationKeepsSelectedSeason() = runTest(dispatcher) {
        val (v, api, repo) = loadWithRepo(series(4))
        collect(v)
        assertEquals(1, v.uiState.value.selectedSeason)
        v.play(); settle()
        repo.invalidate(api.d.card.ref)
        api.d = series(5)
        v.onResume(); advanceUntilIdle()
        assertEquals("ادامه فصل ۲ قسمت ۱", v.uiState.value.primaryLabel)
        assertEquals(1, v.uiState.value.selectedSeason)
        assertTrue(v.uiState.value.episodes.all { it.watched })
    }

    @Test fun failedRevalidationKeepsPageQuietly() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        collect(v)
        v.play(); settle()
        api.titleFailure = com.alikarami.drooz.site.SiteException.NetworkError(RuntimeException("x"))
        v.onResume(); advanceUntilIdle()
        assertNotNull(v.uiState.value.detail)
        assertNull(v.uiState.value.error)
    }

    // Reopening a played title shows the cached page at once, then the fresh one.
    @Test fun reopenAfterPlayShowsCachedThenFresh() = runTest(dispatcher) {
        val api = FakeApi(series(0))
        val repo = repo(api)
        val v = vm(api, repo); advanceUntilIdle()
        collect(v)
        v.play(); settle()
        repo.invalidate(api.d.card.ref)
        api.d = series(1)
        api.titleGate = CompletableDeferred()
        val again = vm(api, repo); runCurrent()
        assertEquals("پخش قسمت ۱", again.uiState.value.primaryLabel)
        api.titleGate!!.complete(Unit); advanceUntilIdle()
        assertEquals("ادامه فصل ۱ قسمت ۲", again.uiState.value.primaryLabel)
    }

    @Test fun localFinished_marksTileWatched_andPrimaryMovesOn() = runTest(dispatcher) {
        store.entries += entry("s1e1", 2_900_000, 3_000_000, finished = true)
        val (v, _) = load(series(0))
        assertTrue(v.uiState.value.episodes[0].watched)
        assertEquals(UiStrings.continueWatching(1, 2), v.uiState.value.primaryLabel)
    }

    @Test fun localPartial_showsProgress_andContinuesThatEpisode() = runTest(dispatcher) {
        store.entries += entry("s1e2", 600_000, 2_400_000)
        val (v, _) = load(series(0))
        val s = v.uiState.value
        assertEquals(0.25f, s.episodes[1].progress!!, 0.001f)
        assertNull(s.episodes[0].progress)
        assertEquals(2, s.primaryEpisode?.number)
        assertEquals(2, s.initialFocusEpisode)
    }

    @Test fun film_resume_labelShowsRemainingMinutes() = runTest(dispatcher) {
        val r = ref(TitleKind.FILM)
        store.entries += entry("s1e1", 1_800_000, 7_440_000, path = r.path)
        val (v, _) = load(detail(r, listOf(Season(1, listOf(ep(1, 1))))))
        assertEquals("ادامه (۹۴ دقیقه باقی‌مانده)", v.uiState.value.primaryLabel)
    }

    @Test fun onResume_rereadsLocalState_withoutServerChange() = runTest(dispatcher) {
        val (v, api) = load(series(0))
        assertFalse(v.uiState.value.episodes[0].watched)
        store.entries += entry("s1e1", 2_900_000, 3_000_000, finished = true)
        v.onResume(); advanceUntilIdle()
        assertTrue(v.uiState.value.episodes[0].watched)
        assertEquals(1, api.titleCalls)
    }

    @Test fun watchStoreFailure_pageStillShows() = runTest(dispatcher) {
        store.failure = IOException("disk")
        val (v, _) = load(series(0))
        assertNotNull(v.uiState.value.detail)
        assertTrue(v.uiState.value.episodes.none { it.watched })
    }

    @Test fun otherTitlesEntriesAreIgnored() = runTest(dispatcher) {
        store.entries += entry("s1e1", 2_900_000, 3_000_000, finished = true, path = "/post/series/99/other/")
        val (v, _) = load(series(0))
        assertFalse(v.uiState.value.episodes[0].watched)
    }

    @Test fun defaultDub_appliesOnlyWithoutSavedChoice() = runTest(dispatcher) {
        val dub = listOf(Season(1, listOf(ep(1, 1, id = "dub1"))))
        prefs = FakePrefs(defaultDub = true)
        val (v, _) = load(series(0, dub = dub))
        assertTrue(v.uiState.value.dubSelected)
        prefs = FakePrefs(mutableMapOf(7L to false), defaultDub = true)
        val (v2, _) = load(series(0, dub = dub))
        assertFalse(v2.uiState.value.dubSelected)
        prefs = FakePrefs(defaultDub = true)
        val (v3, _) = load(series(0))
        assertFalse(v3.uiState.value.dubSelected)
    }
}
