package com.alikarami.drooz.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.alikarami.drooz.data.PlayerPrefs
import com.alikarami.drooz.data.PlayerSettings
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleColor
import com.alikarami.drooz.data.SubtitleStyle
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.player.audio.AudioOption
import com.alikarami.drooz.player.audio.GAIN_TICK_MS
import com.alikarami.drooz.player.engine.EngineEvent
import com.alikarami.drooz.player.engine.EngineTracks
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
import kotlinx.coroutines.flow.first
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
        var titleCalls = 0
        override suspend fun title(ref: TitleRef): TitleDetail { titleCalls++; return detail }
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
        var subtitles: List<SubtitleTrack> = emptyList()
        var qualityLabels: Map<Int, String> = emptyMap()
        var gains: List<Pair<Double, Double>> = emptyList()
        var minGain: Double? = null
        var maxGain: Double? = null
        val subtitleBodies = mutableMapOf<String, String>()
        var subtitleFailure: Exception? = null
        /** One-shot failures, consumed before [subtitleFailure]. */
        val subtitleFailures = ArrayDeque<Exception>()
        val subtitleCalls = mutableListOf<String>()
        override suspend fun subtitle(url: String): String {
            subtitleCalls += url
            subtitleFailures.removeFirstOrNull()?.let { throw it }
            subtitleFailure?.let { throw it }
            return subtitleBodies[url].orEmpty()
        }
        override suspend fun reportStreamError(streamId: String, event: String, msg: String) {
            reports += Triple(streamId, event, msg)
        }
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession {
            opens += Triple(episode.streamId, lang, height)
            gate?.await()
            failures.removeFirstOrNull()?.let { throw it }
            return StreamSession("T", "https://cdn/master-${opens.size}.m3u8", "/bd/${episode.streamId}.jpg",
                subtitles, qualityLabels, gains, minGain, maxGain, iframes, episode.streamId)
        }
    }

    private class MemWatch : WatchStore {
        val map = mutableMapOf<String, WatchEntry>()
        override suspend fun get(streamId: String) = map[streamId]
        override suspend fun put(entry: WatchEntry) { map[entry.streamId] = entry }
        override suspend fun all() = map.values.toList()
    }

    private class MemTitlePrefs : TitlePrefs {
        val map = mutableMapOf<Long, Boolean>()
        override suspend fun dubSelected(postId: Long): Boolean? = map[postId]
        override suspend fun setDubSelected(postId: Long, selected: Boolean) { map[postId] = selected }
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
        val prefs = MemPrefs()
        val titlePrefs = MemTitlePrefs()

        fun vm(streamId: String = "s1e1", dub: Boolean = false, scope: TestScope): PlayerViewModel {
            val args = PlayerArgs(api.detail.card.ref, streamId, dub)
            val v = PlayerViewModel(args, engine, api, titleInfo, titlePrefs, watch, prefs, clock = { now }, parseDispatcher = dispatcher)
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

    @Test fun togglePlayPausesWhileBuffering() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.durationMs = 3_600_000
        engine.positionMs = 70_000
        engine.isPlaying.value = false
        engine.buffering.value = true
        v.togglePlay()
        runCurrent()
        assertEquals(1, engine.paused)
        assertEquals(1, engine.played)
        assertEquals(70_000L, watch.map["s1e1"]!!.positionMs)
        v.togglePlay()
        assertEquals(2, engine.played)
    }

    @Test fun saveDuringSilentReopenKeepsLastKnownDuration() = playerTest {
        val v = Env().vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.durationMs = 3_600_000
        engine.positionMs = 3_500_000
        engine.emit(EngineEvent.Error(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        runCurrent()
        assertEquals(2, engine.loads.size)
        engine.durationMs = 0
        v.onStop()
        runCurrent()
        val e = watch.map["s1e1"]!!
        assertEquals(3_600_000L, e.durationMs)
        assertTrue(e.finished)
    }

    @Test fun recoveryDoesNotRefetchTitlePage() = playerTest {
        val env = Env()
        env.vm(scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        engine.emit(EngineEvent.Error(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        runCurrent()
        assertEquals(2, env.api.opens.size)
        assertEquals(1, env.api.titleCalls)
    }

    @Test fun retryAfterStreamFailureDoesNotRefetchTitlePage() = playerTest {
        val env = Env()
        env.api.failures += SiteException.NetworkError(IOException("down"))
        val v = env.vm(scope = this)
        runCurrent()
        v.retry()
        runCurrent()
        assertEquals(1, engine.loads.size)
        assertEquals(1, env.api.titleCalls)
    }

    @Test fun notLoggedInThenBackExitsOnce() = playerTest {
        val env = Env()
        env.api.failures += SiteException.NotLoggedIn()
        val v = env.vm(scope = this)
        runCurrent()
        v.close()
        runCurrent()
        assertEquals(1, env.exits.size)
    }

    @Test fun clearingReleasesEngine() = playerTest {
        Env().vm(scope = this)
        runCurrent()
        stores.forEach { it.clear() }
        assertTrue(engine.released)
    }

    // ---- Task 8: subtitles, audio, quality, speed, boost ----

    private val FA_URL = "https://cdn/sub/fa.srt"
    private val EN_URL = "https://cdn/sub/en.srt"
    private val DUAL_URL = "https://cdn/sub/sub.srt?stream=1&second=2"
    private val FA_DUB_URL = "https://cdn/sub/fadub.srt"
    private val FA_SRT = "1\n00:00:01,000 --> 00:00:03,000\nسلام\n"
    private val EN_SRT = "1\n00:00:01,000 --> 00:00:03,000\nHello\n"
    private val DUB_SRT = "1\n00:00:01,000 --> 00:00:03,000\nدوبله\n"
    private val fixtureGains = listOf(10.0078 to 7.81, 10.0078 to 9.71, 9.98458 to 4.02, 10.0078 to 1.93)
    private val threeAudio = listOf(AudioOption("original", 2), AudioOption("original-6CH", 6), AudioOption("dubbed-fa", 2))

    private fun Env.withSubtitles(fa: Boolean = true, en: Boolean = true, faDub: Boolean = false): Env {
        api.subtitles = buildList {
            if (fa) add(SubtitleTrack("Farsi", FA_URL))
            if (en) add(SubtitleTrack("English", EN_URL))
            if (fa && en) add(SubtitleTrack("English + Farsi", DUAL_URL))
            if (faDub) add(SubtitleTrack("Farsi for Dub", FA_DUB_URL))
        }
        api.subtitleBodies += mapOf(FA_URL to FA_SRT, EN_URL to EN_SRT, DUAL_URL to "x", FA_DUB_URL to DUB_SRT)
        return this
    }

    /** Opens, emits Ready and runs the loops once. */
    private fun TestScope.started(env: Env, streamId: String = "s1e1", dub: Boolean = false): PlayerViewModel {
        val v = env.vm(streamId, dub, scope = this)
        runCurrent()
        engine.emit(EngineEvent.Ready)
        runCurrent()
        return v
    }

    @Test fun defaultFarsiSubtitleShownAtPosition() = playerTest {
        val env = Env().withSubtitles()
        val v = started(env)
        assertEquals(SubtitleChoice.FA, v.state.value.subtitle)
        assertEquals(
            listOf(SubtitleChoice.FA, SubtitleChoice.EN, SubtitleChoice.DUAL, SubtitleChoice.OFF),
            v.state.value.availableSubtitles,
        )
        assertEquals(listOf(FA_URL), env.api.subtitleCalls)
        engine.positionMs = 2_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(SubtitleCues("سلام", null), v.cues.value)
        engine.positionMs = 3_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(SubtitleCues(), v.cues.value)
    }

    @Test fun dualShowsFarsiAndEnglishFromSeparateFiles() = playerTest {
        val env = Env().withSubtitles()
        val v = started(env)
        v.setSubtitle(SubtitleChoice.DUAL)
        runCurrent()
        assertEquals(listOf(FA_URL, EN_URL), env.api.subtitleCalls)
        engine.positionMs = 2_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(SubtitleCues("سلام", "Hello"), v.cues.value)
        v.setSubtitle(SubtitleChoice.EN)
        v.setSubtitle(SubtitleChoice.FA)
        runCurrent()
        assertEquals(SubtitleCues("سلام", null), v.cues.value)
        assertEquals(2, env.api.subtitleCalls.size)
    }

    @Test fun offSubtitleDownloadsNothingAndShowsNothing() = playerTest {
        val env = Env().withSubtitles()
        env.prefs.settings.value = PlayerSettings(subtitle = SubtitleChoice.OFF)
        val v = started(env)
        assertEquals(SubtitleChoice.OFF, v.state.value.subtitle)
        engine.positionMs = 2_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertTrue(env.api.subtitleCalls.isEmpty())
        assertEquals(SubtitleCues(), v.cues.value)
    }

    @Test fun storedChoiceFallsBackWhenTrackMissing() = playerTest {
        val env = Env().withSubtitles(fa = false, en = true)
        env.prefs.settings.value = PlayerSettings(subtitle = SubtitleChoice.DUAL)
        val v = started(env)
        assertEquals(SubtitleChoice.EN, v.state.value.subtitle)
        assertEquals(listOf(SubtitleChoice.EN, SubtitleChoice.OFF), v.state.value.availableSubtitles)
    }

    @Test fun syncOffsetShiftsAndPersistsPerEpisode() = playerTest {
        val env = Env().withSubtitles()
        val v = started(env)
        engine.durationMs = 3_600_000
        v.shiftSubtitles(SUBTITLE_STEP_MS)
        v.shiftSubtitles(SUBTITLE_STEP_MS)
        assertEquals(1_000L, v.state.value.subtitleOffsetMs)
        engine.positionMs = 3_500
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals("سلام", v.cues.value.primary)
        v.onStop()
        runCurrent()
        assertEquals(1_000L, watch.map["s1e1"]!!.subtitleOffsetMs)
        stores.forEach { it.clear() }
        val v2 = env.vm(scope = this)
        runCurrent()
        assertEquals(1_000L, v2.state.value.subtitleOffsetMs)
    }

    @Test fun subtitleFailureDoesNotBlockPlayback() = playerTest {
        val env = Env().withSubtitles()
        env.api.subtitleFailure = SiteException.NetworkError(IOException("down"))
        val v = started(env)
        assertEquals(1, engine.loads.size)
        assertEquals(PlayerPhase.Playing, v.state.value.phase)
        engine.positionMs = 2_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(SubtitleCues(), v.cues.value)
        assertFalse(v.state.value.phase is PlayerPhase.Failed)
    }

    @Test fun subtitleDownloadDoesNotDelayEngineLoad() = playerTest {
        val env = Env().withSubtitles()
        val v = env.vm(scope = this)
        runCurrent()
        assertEquals(1, engine.loads.size)
        assertEquals(PlayerPhase.Loading, v.state.value.phase)
    }

    @Test fun choiceRememberedGloballyButNotFaDub() = playerTest {
        val env = Env().withSubtitles(faDub = true)
        val v = started(env)
        v.setSubtitle(SubtitleChoice.EN)
        runCurrent()
        assertEquals(SubtitleChoice.EN, env.prefs.settings.first().subtitle)
        v.setSubtitle(SubtitleChoice.FA_DUB)
        runCurrent()
        assertEquals(SubtitleChoice.FA_DUB, v.state.value.subtitle)
        assertEquals(SubtitleChoice.EN, env.prefs.settings.first().subtitle)
    }

    @Test fun dubPlaybackSelectsFarsiForDubOrOff() = playerTest {
        val env = Env().withSubtitles(faDub = true)
        val v = started(env, "d1e1", dub = true)
        assertEquals(SubtitleChoice.FA_DUB, v.state.value.subtitle)
        assertEquals(listOf(FA_DUB_URL), env.api.subtitleCalls)
        stores.forEach { it.clear() }
        val env2 = Env().withSubtitles(faDub = false)
        val v2 = started(env2, "d1e1", dub = true)
        assertEquals(SubtitleChoice.OFF, v2.state.value.subtitle)
    }

    @Test fun autoPicksSixChannelWhenOutputSupportsIt() = playerTest {
        val env = Env()
        val v = started(env)
        engine.multichannelOutput = true
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720), audio = threeAudio)
        runCurrent()
        assertEquals(listOf(1), engine.audioSelections)
        assertEquals(threeAudio, v.state.value.audio)
        assertEquals(1, v.state.value.selectedAudio)
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720), audio = threeAudio, playingHeight = 720)
        runCurrent()
        assertEquals(listOf(1), engine.audioSelections)
    }

    @Test fun switchingToDubbedAudioPicksDubSubtitleAndPersistsDub() = playerTest {
        val env = Env().withSubtitles(faDub = true)
        val v = started(env)
        engine.tracks.value = EngineTracks(audio = threeAudio)
        runCurrent()
        v.setSubtitle(SubtitleChoice.EN)
        v.setAudio(2)
        runCurrent()
        assertEquals(listOf(0, 2), engine.audioSelections)
        assertEquals(2, v.state.value.selectedAudio)
        assertEquals(SubtitleChoice.FA_DUB, v.state.value.subtitle)
        assertEquals(true, env.titlePrefs.map[seriesRef.postId])
        v.setAudio(0)
        runCurrent()
        assertEquals(SubtitleChoice.EN, v.state.value.subtitle)
        assertEquals(false, env.titlePrefs.map[seriesRef.postId])
        assertEquals(SubtitleChoice.EN, env.prefs.settings.first().subtitle)
    }

    @Test fun qualityMenuUsesSiteLabels() = playerTest {
        val env = Env()
        env.api.qualityLabels = mapOf(2475 to "1080 [ 2.48 GB ]", 1100 to "720 [ 1.14 GB ]", 488 to "480 [ 553 MB ]")
        val v = started(env)
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720, 480), playingHeight = 1080)
        runCurrent()
        assertEquals(listOf("1080 [ 2.48 GB ]", "720 [ 1.14 GB ]", "480 [ 553 MB ]"), v.state.value.qualities.map { it.label })
        assertEquals(1080, v.state.value.playingHeight)
        assertTrue(engine.qualitySelections.isEmpty())
        v.setQuality(720)
        assertEquals(listOf<Int?>(720), engine.qualitySelections)
        assertEquals(720, v.state.value.selectedQuality)
        v.setQuality(null)
        assertEquals(listOf(720, null), engine.qualitySelections)
        assertNull(v.state.value.selectedQuality)
    }

    @Test fun qualityAndAudioReappliedAfterSilentRecovery() = playerTest {
        val env = Env()
        val v = started(env)
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720), audio = threeAudio)
        runCurrent()
        v.setQuality(720)
        v.setAudio(1)
        runCurrent()
        engine.emit(EngineEvent.Error(2004, "ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        runCurrent()
        assertEquals(2, engine.loads.size)
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720), audio = threeAudio)
        runCurrent()
        assertEquals(listOf<Int?>(720, 720), engine.qualitySelections)
        assertEquals(listOf(0, 1, 1), engine.audioSelections)
        assertEquals(720, v.state.value.selectedQuality)
    }

    @Test fun boostFollowsGainMapWhilePlaying() = playerTest {
        val env = Env()
        env.api.gains = fixtureGains
        env.api.minGain = 0.64
        env.api.maxGain = 1.93
        val v = started(env)
        assertTrue(v.state.value.boost)
        engine.positionMs = 0
        advanceTimeBy(GAIN_TICK_MS); runCurrent()
        assertEquals(1.05f, engine.gains.last(), 1e-6f)
        advanceTimeBy(GAIN_TICK_MS); runCurrent()
        assertEquals(1.10f, engine.gains.last(), 1e-6f)
    }

    @Test fun boostOffSetsUnityGainAndPersists() = playerTest {
        val env = Env()
        env.api.gains = fixtureGains
        env.api.minGain = 0.64
        env.api.maxGain = 1.93
        val v = started(env)
        advanceTimeBy(GAIN_TICK_MS); runCurrent()
        v.setBoost(false)
        runCurrent()
        assertEquals(1f, engine.gains.last())
        assertFalse(v.state.value.boost)
        assertEquals(false, env.prefs.settings.first().audioBoost)
        val n = engine.gains.size
        advanceTimeBy(GAIN_TICK_MS * 4); runCurrent()
        assertEquals(n, engine.gains.size)
    }

    @Test fun boostOffFromSettingsNeverBoosts() = playerTest {
        val env = Env()
        env.api.gains = fixtureGains
        env.prefs.settings.value = PlayerSettings(audioBoost = false)
        val v = started(env)
        advanceTimeBy(GAIN_TICK_MS * 4); runCurrent()
        assertFalse(v.state.value.boost)
        assertTrue(engine.gains.all { it == 1f })
    }

    @Test fun styleAppliesAndPersists() = playerTest {
        val env = Env()
        val v = started(env)
        val style = SubtitleStyle(140, SubtitleColor.WHITE, 50)
        v.setStyle(style)
        runCurrent()
        assertEquals(style, v.state.value.style)
        assertEquals(style, env.prefs.settings.first().style)
    }

    @Test fun speedAppliesToEngine() = playerTest {
        val v = started(Env())
        v.setSpeed(1.5f)
        assertEquals(listOf(1.5f), engine.speeds)
        assertEquals(1.5f, v.state.value.speed)
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), SPEEDS)
    }

    @Test fun failedSubtitleRetriesAutomaticallyAfterDelay() = playerTest {
        val env = Env().withSubtitles()
        env.api.subtitleFailures += SiteException.NetworkError(IOException("drop"))
        val v = started(env)
        engine.positionMs = 2_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(SubtitleCues(), v.cues.value)
        assertEquals(listOf(FA_URL), env.api.subtitleCalls)
        advanceTimeBy(SUBTITLE_RETRY_MS); runCurrent()
        assertEquals(listOf(FA_URL, FA_URL), env.api.subtitleCalls)
        assertEquals(SubtitleCues("سلام", null), v.cues.value)
    }

    @Test fun failedSubtitleRetriedOnceThenOnNextChoice() = playerTest {
        val env = Env().withSubtitles()
        env.api.subtitleFailure = SiteException.NetworkError(IOException("down"))
        val v = started(env)
        advanceTimeBy(SUBTITLE_RETRY_MS * 4); runCurrent()
        assertEquals(listOf(FA_URL, FA_URL), env.api.subtitleCalls)
        env.api.subtitleFailure = null
        engine.positionMs = 2_000
        v.setSubtitle(SubtitleChoice.FA)
        runCurrent()
        assertEquals(3, env.api.subtitleCalls.size)
        assertEquals(SubtitleCues("سلام", null), v.cues.value)
    }

    // ---- Task 9: up next, episode switching ----

    private val NEAR_END = 3_565_000L
    private val HOUR = 3_600_000L

    /** Started at [streamId], playing an hour-long episode at [pos]. */
    private fun TestScope.nearEnd(env: Env, streamId: String = "s1e1", pos: Long = NEAR_END): PlayerViewModel {
        val v = started(env, streamId)
        engine.durationMs = HOUR
        engine.positionMs = pos
        advanceTimeBy(TICK_MS); runCurrent()
        return v
    }

    @Test fun upNextAppearsInLastFortySecondsAndCountsDown() = playerTest {
        val v = nearEnd(Env())
        assertTrue(v.state.value.hasNext)
        assertEquals(UpNext("قسمت بعدی · فصل ۱ قسمت ۲", 8), v.state.value.upNext)
        assertEquals("قسمت بعدی · فصل ۱ قسمت ۲", UiStrings.upNextLabel(1, 2))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(5, v.state.value.upNext?.secondsLeft)
    }

    @Test fun countdownPausesWhilePaused() = playerTest {
        val v = nearEnd(Env())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(6, v.state.value.upNext?.secondsLeft)
        v.togglePlay()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(6, v.state.value.upNext?.secondsLeft)
    }

    @Test fun countdownZeroPlaysNext() = playerTest {
        val env = Env()
        val v = nearEnd(env)
        advanceTimeBy(UP_NEXT_COUNTDOWN_S * 1_000L); runCurrent()
        assertEquals(listOf("s1e1", "s1e2"), env.api.opens.map { it.first })
        assertEquals("s1e2", v.state.value.currentStreamId)
        assertTrue(watch.map["s1e1"]!!.finished)
        assertNull(v.state.value.upNext)
        assertEquals("فصل ۱ · قسمت ۲", v.state.value.episodeLabel)
    }

    @Test fun reachingWindowSavesFinishedEvenBeforeNinetyPercent() = playerTest {
        val env = Env()
        // 3-minute episode: 40 s before the end is below 90 %.
        val v = started(env)
        engine.durationMs = 180_000
        engine.positionMs = 141_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertNotNull(v.state.value.upNext)
        v.onStop()
        runCurrent()
        assertTrue(watch.map["s1e1"]!!.finished)
    }

    @Test fun dismissKeepsWatchingCredits() = playerTest {
        val env = Env()
        val v = nearEnd(env)
        assertNotNull(v.state.value.upNext)
        v.dismissUpNext()
        assertNull(v.state.value.upNext)
        advanceTimeBy(UP_NEXT_COUNTDOWN_S * 2_000L); runCurrent()
        assertNull(v.state.value.upNext)
        assertEquals(1, env.api.opens.size)
    }

    @Test fun seekingBackHidesCardWithoutDismissing() = playerTest {
        val v = nearEnd(Env())
        advanceTimeBy(2_000); runCurrent()
        assertEquals(6, v.state.value.upNext?.secondsLeft)
        engine.positionMs = 1_000_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertNull(v.state.value.upNext)
        engine.positionMs = 3_570_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(8, v.state.value.upNext?.secondsLeft)
    }

    @Test fun endedPlaysNextAcrossSeasons() = playerTest {
        val env = Env()
        val v = started(env, "s1e2")
        engine.durationMs = HOUR
        engine.positionMs = HOUR
        engine.emit(EngineEvent.Ended)
        runCurrent()
        assertEquals(listOf("s1e2", "s2e1"), env.api.opens.map { it.first })
        assertEquals("s2e1", v.state.value.currentStreamId)
        assertTrue(watch.map["s1e2"]!!.finished)
        assertTrue(env.exits.isEmpty())
    }

    @Test fun endedOnLastEpisodeExits() = playerTest {
        val env = Env()
        val v = nearEnd(env, "s2e1")
        assertFalse(v.state.value.hasNext)
        assertNull(v.state.value.upNext)
        advanceTimeBy(UP_NEXT_COUNTDOWN_S * 2_000L); runCurrent()
        assertNull(v.state.value.upNext)
        engine.positionMs = HOUR
        engine.emit(EngineEvent.Ended)
        runCurrent()
        assertEquals(1, env.exits.size)
        assertEquals(listOf("s2e1"), env.api.opens.map { it.first })
        assertTrue(watch.map["s2e1"]!!.finished)
    }

    @Test fun filmHasNoUpNext() = playerTest {
        val env = Env(film)
        val v = nearEnd(env, "f1")
        assertFalse(v.state.value.hasNext)
        assertNull(v.state.value.upNext)
        engine.emit(EngineEvent.Ended)
        runCurrent()
        assertEquals(1, env.exits.size)
        assertEquals(1, env.api.opens.size)
    }

    @Test fun playEpisodeSwitchesAndKeepsSpeed() = playerTest {
        val env = Env()
        val v = started(env)
        engine.durationMs = HOUR
        engine.positionMs = 600_000
        v.setSpeed(1.25f)
        v.playEpisode("s2e1")
        runCurrent()
        assertEquals(2, engine.loads.size)
        assertEquals(1.25f, engine.speeds.last())
        assertEquals(1.25f, v.state.value.speed)
        assertEquals(listOf("s1e1", "s2e1"), env.api.opens.map { it.first })
        assertEquals("s2e1", v.state.value.currentStreamId)
        assertEquals(600_000L, watch.map["s1e1"]!!.positionMs)
        assertFalse(watch.map["s1e1"]!!.finished)
        assertEquals("فصل ۲ · قسمت ۱", v.state.value.episodeLabel)
        assertEquals(listOf("s2e1"), v.state.value.seasonEpisodes.map { it.streamId })
        assertFalse(v.state.value.hasNext)
    }

    @Test fun switchKeepsDubFlagAndReappliesQuality() = playerTest {
        val env = Env()
        val v = started(env, "d1e1", dub = true)
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720), audio = threeAudio)
        runCurrent()
        v.setQuality(720)
        v.playNext()
        runCurrent()
        assertEquals(listOf(Triple("d1e1", "dubbed-fa", 1080), Triple("d1e2", "dubbed-fa", 1080)), env.api.opens)
        engine.tracks.value = EngineTracks(heights = listOf(1080, 720), audio = threeAudio)
        runCurrent()
        assertEquals(listOf<Int?>(720, 720), engine.qualitySelections)
    }

    @Test fun switchWhereDubbedAudioIsMissingRestoresOriginalSubtitle() = playerTest {
        val env = Env().withSubtitles(faDub = true)
        val v = started(env)
        engine.tracks.value = EngineTracks(audio = threeAudio)
        runCurrent()
        v.setAudio(2)
        runCurrent()
        assertEquals(SubtitleChoice.FA_DUB, v.state.value.subtitle)
        v.playNext()
        runCurrent()
        engine.emit(EngineEvent.Ready)
        engine.tracks.value = EngineTracks(audio = listOf(AudioOption("original", 2)))
        runCurrent()
        assertEquals(0, v.state.value.selectedAudio)
        assertEquals(SubtitleChoice.FA, v.state.value.subtitle)
        engine.positionMs = 2_000
        advanceTimeBy(TICK_MS); runCurrent()
        assertEquals(SubtitleCues("سلام", null), v.cues.value)
    }

    @Test fun seasonEpisodesMarkLocalAndServerWatched() = playerTest {
        val detail = series.copy(
            catalog = series.catalog.copy(
                main = listOf(
                    Season(1, listOf(ep(1, 1, "s1e1").copy(played = true), ep(1, 2, "s1e2"), ep(1, 3, "s1e3"))),
                    Season(2, listOf(ep(2, 1, "s2e1"))),
                ),
            ),
        )
        watch.map["s1e2"] = WatchEntry("s1e2", seriesRef.path, 1, 2, 3_500_000, 3_600_000, 0, true)
        val v = started(Env(detail))
        assertEquals(
            listOf(
                PlayerEpisode("s1e1", "فصل ۱ · قسمت ۱", true),
                PlayerEpisode("s1e2", "فصل ۱ · قسمت ۲", true),
                PlayerEpisode("s1e3", "فصل ۱ · قسمت ۳", false),
            ),
            v.state.value.seasonEpisodes,
        )
    }
}
