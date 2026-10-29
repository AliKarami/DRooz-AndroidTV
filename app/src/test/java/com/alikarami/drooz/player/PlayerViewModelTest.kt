package com.alikarami.drooz.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.alikarami.drooz.data.PlayerPrefs
import com.alikarami.drooz.data.PlayerSettings
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.player.engine.EngineEvent
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.*
import com.alikarami.drooz.ui.common.UiStrings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    /** runTest drains pending tasks after the body: clear the VMs so the tick loop stops. */
    private fun playerTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            stores.forEach { it.clear() }
        }
    }

    private val K = listOf(0.0, 6.5, 8.208, 18.167, 26.208, 27.375, 37.375, 43.0, 50.75, 60.75)

    private val seriesRef = TitleRef(TitleKind.SERIES, 7, "some-show")
    private val filmRef = TitleRef(TitleKind.FILM, 9, "a-film")
    private fun ep(s: Int, n: Int, id: String) = Episode(s, n, id, emptyList(), emptyList(), emptyList(), false)
    private fun card(r: TitleRef, title: String) = TitleCard(r, title, 2020, "ph", 50, null, null, emptyList(),
        emptyList(), null, emptyList(), null, null, null)
    private val series = TitleDetail(
        card(seriesRef, "Some Show"), null, emptyList(),
        StreamCatalog(
            main = listOf(Season(1, listOf(ep(1, 1, "s1e1"), ep(1, 2, "s1e2"))), Season(2, listOf(ep(2, 1, "s2e1")))),
            dub = listOf(Season(1, listOf(ep(1, 1, "d1e1"), ep(1, 2, "d1e2")))),
        ),
        emptyList(), null,
    )
    private val film = TitleDetail(
        card(filmRef, "A Film"), null, emptyList(),
        StreamCatalog(listOf(Season(1, listOf(ep(1, 1, "f1")))), emptyList()),
        emptyList(), null,
    )

    private class FakeApi(val detail: TitleDetail, val iframes: List<List<Double>>) : SiteApi {
        val opens = mutableListOf<Triple<String, String, Int>>()
        var gate: CompletableDeferred<Unit>? = null
        val failures = ArrayDeque<Exception>()
        val reports = mutableListOf<Triple<String, String, String>>()
        override suspend fun home() = error("unused")
        override suspend fun list(path: String, page: Int) = error("unused")
        override suspend fun search(query: String) = error("unused")
        override suspend fun title(ref: TitleRef) = detail
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
        override suspend fun subtitle(url: String) = ""
        override suspend fun reportStreamError(streamId: String, event: String, msg: String) {
            reports += Triple(streamId, event, msg)
        }
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession {
            opens += Triple(episode.streamId, lang, height)
            gate?.await()
            failures.removeFirstOrNull()?.let { throw it }
            return StreamSession("T", "https://cdn/master-${opens.size}.m3u8", "/bd/${episode.streamId}.jpg",
                emptyList(), emptyMap(), emptyList(), null, null, iframes, episode.streamId)
        }
    }

    private class MemWatch : WatchStore {
        val map = mutableMapOf<String, WatchEntry>()
        override suspend fun get(streamId: String) = map[streamId]
        override suspend fun put(entry: WatchEntry) { map[entry.streamId] = entry }
        override suspend fun all() = map.values.toList()
    }

    private class NoTitlePrefs : TitlePrefs {
        override suspend fun dubSelected(postId: Long): Boolean? = null
        override suspend fun setDubSelected(postId: Long, selected: Boolean) {}
    }

    private class MemPrefs : PlayerPrefs {
        override val settings = MutableStateFlow(PlayerSettings())
        override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) { settings.value = transform(settings.value) }
    }

    private var now = 1_000_000L
    private val engine = FakeEngine()
    private val watch = MemWatch()

    private inner class Env(detail: TitleDetail = series) {
        val api = FakeApi(detail, listOf(K))
        val titleInfo = TitleInfoRepository(api, now = { now }, fetchScope = CoroutineScope(dispatcher + SupervisorJob()))
        val exits = mutableListOf<Unit>()

        fun vm(streamId: String = "s1e1", dub: Boolean = false, scope: TestScope): PlayerViewModel {
            val args = PlayerArgs(api.detail.card.ref, streamId, dub)
            val v = PlayerViewModel(args, engine, api, titleInfo, NoTitlePrefs(), watch, MemPrefs(), clock = { now })
            val store = ViewModelStore().also { stores += it }
            @Suppress("UNCHECKED_CAST")
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T = v as T
            })[PlayerViewModel::class.java]
            scope.backgroundScope.launch { v.exits.collect { exits += it } }
            return v
        }
    }

    @Test fun opensStreamAndLoadsEngineFromStart() = playerTest {
        val env = Env()
        val v = env.vm(scope = this)
        runCurrent()
        assertEquals(listOf(Triple("s1e1", "original", 1080)), env.api.opens)
        assertEquals(listOf("https://cdn/master-1.m3u8" to 0L), engine.loads)
        assertEquals(1, engine.played)
        assertEquals(PlayerPhase.Loading, v.state.value.phase)
        engine.emit(EngineEvent.Ready)
        runCurrent()
        val s = v.state.value
        assertEquals(PlayerPhase.Playing, s.phase)
        assertEquals("Some Show", s.title)
        assertEquals("فصل ۱ · قسمت ۱", s.episodeLabel)
        assertEquals(SiteConfig.BASE_URL + "/bd/s1e1.jpg", s.backdropUrl)
        assertEquals("s1e1", s.currentStreamId)
    }

    @Test fun filmHasNoEpisodeLabel() = playerTest {
        val env = Env(film)
        val v = env.vm("f1", scope = this)
        runCurrent()
        assertEquals("A Film", v.state.value.title)
        assertNull(v.state.value.episodeLabel)
        assertEquals(1, engine.loads.size)
    }

    @Test fun dubArgsOpenDubbedCatalogAndLang() = playerTest {
        val env = Env()
        env.vm("d1e2", dub = true, scope = this)
        runCurrent()
        assertEquals(listOf(Triple("d1e2", "dubbed-fa", 1080)), env.api.opens)
    }

    @Test fun missingEpisodeFails() = playerTest {
        val env = Env()
        val v = env.vm("s1e1", dub = true, scope = this)
        runCurrent()
        assertTrue(env.api.opens.isEmpty())
        assertEquals(PlayerPhase.Failed(UiStrings.PLAY_FAILED), v.state.value.phase)
    }

    @Test fun resumesFromSavedPosition() = playerTest {
        watch.map["s1e1"] = WatchEntry("s1e1", seriesRef.path, 1, 1, 600_000, 3_600_000, 0, false)
        Env().vm(scope = this)
        runCurrent()
        assertEquals(600_000L, engine.loads.single().second)
    }

    @Test fun finishedEntryStartsFromZero() = playerTest {
        watch.map["s1e1"] = WatchEntry("s1e1", seriesRef.path, 1, 1, 600_000, 3_600_000, 0, true)
        Env().vm(scope = this)
        runCurrent()
        assertEquals(0L, engine.loads.single().second)
    }

    @Test fun openingInvalidatesTitleAndRemembersBackdrop() = playerTest {
        val env = Env()
        env.vm(scope = this)
        runCurrent()
        assertNotNull(env.titleInfo.lastKnown(seriesRef))
        assertNull(env.titleInfo.peek(seriesRef))
        assertEquals("/bd/s1e1.jpg", env.titleInfo.playedBackdrops.value[seriesRef])
    }

    @Test fun backdropBeforeSessionComesFromTitlePage() = playerTest {
        val env = Env()
        env.titleInfo.remember(seriesRef, "/played.jpg")
        env.api.gate = CompletableDeferred()
        val v = env.vm(scope = this)
        assertEquals(SiteConfig.BASE_URL + "/played.jpg", v.state.value.backdropUrl)
        runCurrent()
        assertEquals(PlayerPhase.Loading, v.state.value.phase)
        assertEquals("Some Show", v.state.value.title)
    }

    @Test fun savesProgressEveryTenSecondsWhilePlaying() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        engine.durationMs = 3_600_000
        engine.positionMs = 20_000
        runCurrent()
        assertTrue(engine.isPlaying.value)
        advanceTimeBy(9_000)
        assertNull(watch.map["s1e1"])
        advanceTimeBy(1_000)
        runCurrent()
        val e = watch.map["s1e1"]!!
        assertEquals(20_000L, e.positionMs)
        assertEquals(3_600_000L, e.durationMs)
        assertEquals(seriesRef.path, e.refPath)
        assertEquals(1, e.season); assertEquals(1, e.episode)
        assertFalse(e.finished)
        assertEquals(PlayerPhase.Playing, v.state.value.phase)
    }

    @Test fun onStopSavesAndPauses() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.durationMs = 3_600_000
        engine.positionMs = 3_500_000
        v.onStop()
        runCurrent()
        assertEquals(1, engine.paused)
        assertEquals(3_500_000L, watch.map["s1e1"]!!.positionMs)
        assertTrue(watch.map["s1e1"]!!.finished)
    }

    @Test fun nothingSavedBeforePlaybackStarted() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        v.onStop()
        runCurrent()
        assertNull(watch.map["s1e1"])
    }

    @Test fun httpErrorDuringPlaybackReopensStreamOnce() = playerTest {
        val env = Env()
        val v = env.vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.positionMs = 1_234_000
        engine.emit(EngineEvent.Error(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        runCurrent()
        assertEquals(2, env.api.opens.size)
        assertEquals("https://cdn/master-2.m3u8" to 1_234_000L, engine.loads.last())
        assertEquals(PlayerPhase.Playing, v.state.value.phase)
        now += 30_000
        engine.emit(EngineEvent.Error(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        runCurrent()
        assertEquals(2, env.api.opens.size)
        assertEquals(PlayerPhase.Failed(UiStrings.PLAYBACK_FAILED), v.state.value.phase)
    }

    @Test fun nonRecoverableErrorFails() = playerTest {
        val env = Env()
        val v = env.vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.emit(EngineEvent.Error(4001, "ERROR_CODE_DECODER_INIT_FAILED", null))
        runCurrent()
        assertEquals(1, env.api.opens.size)
        assertEquals(PlayerPhase.Failed(UiStrings.PLAYBACK_FAILED), v.state.value.phase)
    }

    @Test fun setupErrorReportedOncePerMinute() = playerTest {
        val env = Env()
        env.vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Error(2001, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", null))
        engine.emit(EngineEvent.Error(2001, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", null))
        runCurrent()
        assertEquals(listOf(Triple("s1e1", "setupError", "2001")), env.api.reports)
        now += 60_000
        engine.emit(EngineEvent.Error(2002, "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT", null))
        runCurrent()
        assertEquals(Triple("s1e1", "setupError", "2002"), env.api.reports.last())
        assertEquals(2, env.api.reports.size)
    }

    @Test fun errorAfterReadyReportedAsError() = playerTest {
        val env = Env()
        env.vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        engine.emit(EngineEvent.Error(4001, "ERROR_CODE_DECODER_INIT_FAILED", null))
        runCurrent()
        assertEquals(listOf(Triple("s1e1", "error", "4001")), env.api.reports)
    }

    @Test fun openFailureShowsNetworkMessageAndRetryWorks() = playerTest {
        val env = Env()
        env.api.failures += SiteException.NetworkError(IOException("down"))
        val v = env.vm(scope = this)
        runCurrent()
        assertEquals(PlayerPhase.Failed(UiStrings.PLAY_FAILED_NETWORK), v.state.value.phase)
        assertTrue(engine.loads.isEmpty())
        v.retry()
        assertEquals(PlayerPhase.Loading, v.state.value.phase)
        runCurrent()
        assertEquals(2, env.api.opens.size)
        assertEquals(1, engine.loads.size)
    }

    @Test fun serverErrorIsNetworkOtherErrorsGeneric() = playerTest {
        val env = Env()
        env.api.failures += SiteException.HttpError(502)
        env.api.failures += SiteException.ParseError("stream")
        val v = env.vm(scope = this)
        runCurrent()
        assertEquals(PlayerPhase.Failed(UiStrings.PLAY_FAILED_NETWORK), v.state.value.phase)
        v.retry()
        runCurrent()
        assertEquals(PlayerPhase.Failed(UiStrings.PLAY_FAILED), v.state.value.phase)
    }

    @Test fun notLoggedInExits() = playerTest {
        val env = Env()
        env.api.failures += SiteException.NotLoggedIn()
        val v = env.vm(scope = this)
        runCurrent()
        assertEquals(1, env.exits.size)
        assertFalse(v.state.value.phase is PlayerPhase.Failed)
    }

    @Test fun closeWhileOpeningNeverLoadsEngine() = playerTest {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.api.gate = gate
        val v = env.vm(scope = this)
        runCurrent()
        assertEquals(1, env.api.opens.size)
        v.close()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(engine.loads.isEmpty())
        assertEquals(1, env.exits.size)
        assertNull(watch.map["s1e1"])
        v.close()
        runCurrent()
        assertEquals(1, env.exits.size)
    }

    @Test fun closeAfterPlayingSavesThenExits() = playerTest {
        val env = Env()
        val v = env.vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.durationMs = 3_600_000
        engine.positionMs = 61_000
        v.close()
        runCurrent()
        assertEquals(61_000L, watch.map["s1e1"]!!.positionMs)
        assertEquals(1, env.exits.size)
    }

    @Test fun seekByIsSnappedToKeyframes() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.durationMs = 8_400_000
        engine.positionMs = 20_000
        v.seekBy(10_000)
        assertEquals(listOf(27_375L), engine.seeks)
    }

    @Test fun seekWithUnknownDurationIsNotClamped() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.durationMs = 0
        engine.positionMs = 5_000
        v.seekBy(-10_000)
        v.seekTo(40_000)
        assertEquals(listOf(0L, 40_000L), engine.seeks)
    }

    @Test fun togglePlayPausesAndSavesThenResumes() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.durationMs = 3_600_000
        engine.positionMs = 50_000
        v.togglePlay()
        runCurrent()
        assertEquals(1, engine.paused)
        assertEquals(50_000L, watch.map["s1e1"]!!.positionMs)
        v.togglePlay()
        assertEquals(2, engine.played)
    }

    @Test fun clearingReleasesEngine() = playerTest {
        Env().vm(scope = this)
        runCurrent()
        stores.forEach { it.clear() }
        assertTrue(engine.released)
    }
}
