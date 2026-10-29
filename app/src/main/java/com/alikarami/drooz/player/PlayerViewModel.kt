package com.alikarami.drooz.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.BackdropResolver
import com.alikarami.drooz.data.PlayerPrefs
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleStyle
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.player.audio.AudioOption
import com.alikarami.drooz.player.audio.GAIN_TICK_MS
import com.alikarami.drooz.player.audio.GainSchedule
import com.alikarami.drooz.player.audio.GainSmoother
import com.alikarami.drooz.player.audio.pickAudioTrack
import com.alikarami.drooz.player.engine.EngineTracks
import com.alikarami.drooz.player.subtitle.SrtParser
import com.alikarami.drooz.player.subtitle.SubtitleTimeline
import com.alikarami.drooz.data.PlayerSettings
import com.alikarami.drooz.player.engine.EngineEvent
import com.alikarami.drooz.player.engine.PlaybackEngine
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.site.model.SubtitleKind
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.episodeLabel
import com.alikarami.drooz.ui.common.safeLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToLong

const val TICK_MS = 100L
const val SAVE_EVERY_MS = 10_000L
const val STREAM_HEIGHT = 1080
const val REOPEN_MIN_INTERVAL_MS = 60_000L
const val ERROR_REPORT_MIN_INTERVAL_MS = 60_000L
const val SUBTITLE_STEP_MS = 500L
/** A failed subtitle download is retried once automatically after this delay (the site drops requests). */
const val SUBTITLE_RETRY_MS = 5_000L
val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** HTTP statuses that mean the stream key expired / the CDN URL went stale: reopen the stream page. */
private val RECOVERABLE_HTTP = setOf(403, 404, 410)

sealed interface PlayerPhase {
    data object Loading : PlayerPhase
    /** Includes paused and buffering. */
    data object Playing : PlayerPhase
    data class Failed(val message: String) : PlayerPhase
}

/** The cue text on screen. DUAL: [primary] = Farsi, [secondary] = English. */
data class SubtitleCues(val primary: String? = null, val secondary: String? = null)

data class UpNext(val label: String, val secondsLeft: Int)

data class PlayerEpisode(val streamId: String, val label: String, val watched: Boolean)

data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.Loading,
    val title: String = "",
    val episodeLabel: String? = null,
    val backdropUrl: String? = null,
    // filled in by Tasks 8–9:
    val subtitle: SubtitleChoice = SubtitleChoice.FA,
    val availableSubtitles: List<SubtitleChoice> = emptyList(),
    val style: SubtitleStyle = SubtitleStyle(),
    val subtitleOffsetMs: Long = 0,
    val qualities: List<QualityOption> = emptyList(),
    val selectedQuality: Int? = null,
    val playingHeight: Int? = null,
    val audio: List<AudioOption> = emptyList(),
    val selectedAudio: Int? = null,
    val boost: Boolean = true,
    val speed: Float = 1f,
    val upNext: UpNext? = null,
    val seasonEpisodes: List<PlayerEpisode> = emptyList(),
    val currentStreamId: String = "",
    val hasNext: Boolean = false,
)

/**
 * The player screen's state. One [open] flow (start, retry, silent recovery, later episode switches),
 * one engine-event collector and one tick loop (runs while the engine plays).
 * Logging is debug-only: stream URLs carry the user's token.
 */
class PlayerViewModel(
    args: PlayerArgs,
    private val engine: PlaybackEngine,
    private val api: SiteApi,
    private val titleInfo: TitleInfoRepository,
    private val titlePrefs: TitlePrefs,
    private val watch: WatchStore,
    private val prefs: PlayerPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
    /** SRT parsing runs off the main thread; tests pass their dispatcher. */
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val ref: TitleRef = args.ref
    private val dub: Boolean = args.dub

    private val _state = MutableStateFlow(
        PlayerUiState(
            title = titleInfo.lastKnown(ref)?.card?.title.orEmpty(),
            backdropUrl = pageBackdrop(titleInfo.lastKnown(ref)),
            currentStreamId = args.streamId,
        )
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _exits = Channel<Unit>(Channel.BUFFERED)
    /** The activity finishes on each emission. */
    val exits: Flow<Unit> = _exits.receiveAsFlow()

    private var streamId: String = args.streamId
    private var episode: Episode? = null
    private var session: StreamSession? = null
    private var openJob: Job? = null
    private var closed = false
    /** The user's play/pause intent: engine.isPlaying is false while buffering and after Ended. */
    private var wantsPlay = false
    /** Last positive engine duration for this stream: the engine reports 0 again after each load. */
    private var knownDurationMs = 0L

    /** A Ready was seen for the current stream (any load): progress is worth saving, errors may recover. */
    private var everReady = false
    /** A Ready was seen since the last [loadEngine]: errors before it are "setupError". */
    private var readySinceLoad = false
    private var lastReopenAt: Long? = null
    private var lastReportAt: Long? = null
    private var playedSinceSaveMs = 0L
    /** In the up-next window (or Ended): the episode counts as finished. */
    private var upNextReached = false

    // ---- up next / episodes ----
    /** The chosen catalog (main or dub), from the title page. */
    private var seasons: List<Season> = emptyList()
    /** "Watch credits": no card for this episode. */
    private var upNextDismissed = false
    /** Playing time the card has been on screen. */
    private var upNextShownMs = 0L
    /**
     * An episode switch is in flight (until the new stream's Ready or a failure): further switches and
     * the old stream's late Ended are ignored, so a double press never skips an episode.
     */
    private var switching = false

    // ---- subtitles ----
    private val _cues = MutableStateFlow(SubtitleCues())
    /** Changes only when the text on screen changes. */
    val cues: StateFlow<SubtitleCues> = _cues.asStateFlow()
    private var settingsApplied = false
    /** The stored global choice (never FA_DUB). */
    private var globalChoice = SubtitleChoice.FA
    /** Stream whose initial subtitle choice / saved offset were applied (not redone on a same-stream reopen). */
    private var subtitlesResolvedFor: String? = null
    /** Downloaded files by (stream id, kind); a failed download stays absent (that side is blank). */
    private val timelines = mutableMapOf<Pair<String, SubtitleKind>, SubtitleTimeline>()
    /** Downloads in flight or done; a failure removes its key so the next request retries. */
    private val requestedSubtitles = mutableSetOf<Pair<String, SubtitleKind>>()
    /** Keys already given their one automatic retry. */
    private val autoRetriedSubtitles = mutableSetOf<Pair<String, SubtitleKind>>()

    // ---- tracks (engine.load clears the overrides: reapply after every load) ----
    private var qualityPending = false
    private var audioPending = false
    /** The user's audio pick this playback, by label (survives reloads and episode switches). */
    private var manualAudioLabel: String? = null
    private var audioDubbed = false

    // ---- Audio Boost ----
    private var gainSchedule: GainSchedule? = null
    private var smoother = GainSmoother()

    init {
        viewModelScope.launch { engine.events.collect(::onEvent) }
        viewModelScope.launch { engine.tracks.collect(::onTracks) }
        viewModelScope.launch { ensureSettings() }
        viewModelScope.launch {
            engine.isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                while (true) {
                    delay(GAIN_TICK_MS)
                    boostTick()
                }
            }
        }
        viewModelScope.launch {
            engine.isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                while (true) {
                    delay(TICK_MS)
                    tick()
                }
            }
        }
        open(args.streamId, startAt = null)
    }

    // ---- opening ----

    /**
     * Opens [id]'s stream page and loads the engine at [startAt] (null = resume from the watch store).
     * [silent] keeps the phase (recovery from an expired stream key while playing).
     */
    private fun open(id: String, startAt: Long?, silent: Boolean = false) {
        if (closed) return
        openJob?.cancel()
        if (id != streamId) {
            streamId = id
            everReady = false
            lastReopenAt = null
            upNextReached = false
            upNextDismissed = false
            upNextShownMs = 0L
            session = null
            episode = null
            knownDurationMs = 0L
            gainSchedule = null
            smoother = GainSmoother()
            _state.update {
                it.copy(subtitleOffsetMs = 0, availableSubtitles = emptyList(), upNext = null, hasNext = nextEpisode(seasons, id) != null)
            }
            _cues.value = SubtitleCues()
        }
        if (!silent) _state.update { it.copy(phase = PlayerPhase.Loading, currentStreamId = id) }
        openJob = viewModelScope.launch {
            try {
                // Same-stream reopen (recovery, retry): the episode is known; skip the title page round trip.
                val ep = episode ?: resolveEpisode(id) ?: run {
                    fail(UiStrings.PLAY_FAILED)
                    return@launch
                }
                episode = ep
                val s = api.openStream(ep, ref, if (dub) "dubbed-fa" else "original", STREAM_HEIGHT)
                // Opening the stream marks it played on the site: the title page is stale.
                titleInfo.invalidate(ref)
                s.backdropPath?.let { titleInfo.remember(ref, it) }
                session = s
                gainSchedule = GainSchedule(s.gains, s.minGain, s.maxGain)
                s.backdropPath?.let { p -> _state.update { it.copy(backdropUrl = SiteConfig.BASE_URL + p) } }
                ensureSettings()
                val saved = watch.get(id)
                val available = availableSubtitles(s)
                _state.update { it.copy(availableSubtitles = available) }
                if (subtitlesResolvedFor != id) {
                    subtitlesResolvedFor = id
                    val choice = if (dub || audioDubbed) {
                        if (SubtitleChoice.FA_DUB in available) SubtitleChoice.FA_DUB else SubtitleChoice.OFF
                    } else {
                        originalChoice(available)
                    }
                    _state.update { it.copy(subtitle = choice, subtitleOffsetMs = saved?.subtitleOffsetMs ?: 0L) }
                }
                val start = startAt ?: saved?.let { resumePositionMs(it.positionMs, it.durationMs, it.finished) } ?: 0L
                if (closed) return@launch
                // Downloads run beside the engine load and never block or fail playback.
                ensureSubtitles(_state.value.subtitle)
                loadEngine(s.masterUrl, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException.NotLoggedIn) {
                // SessionGuardedSiteApi already routes the app to Login.
                closed = true
                _exits.trySend(Unit)
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) Log.w(TAG, "open failed: $e")
                fail(openFailureMessage(e))
            }
        }
    }

    /** Fetches the title page, fills the header and finds [id] in the chosen catalog. */
    private suspend fun resolveEpisode(id: String): Episode? {
        val detail = titleInfo.get(ref)
        val catalog = if (dub) detail.catalog.dub else detail.catalog.main
        seasons = catalog
        val ep = catalog.flatMap { it.episodes }.firstOrNull { it.streamId == id }
        val isSeries = ref.kind == TitleKind.SERIES
        val episodes = if (isSeries && ep != null) seasonEpisodes(catalog, ep.season) else emptyList()
        _state.update {
            it.copy(
                title = detail.card.title,
                episodeLabel = ep?.takeIf { isSeries }?.let { e -> episodeLabel(e.season, e.number) },
                backdropUrl = if (session == null) pageBackdrop(detail) ?: it.backdropUrl else it.backdropUrl,
                seasonEpisodes = episodes,
                hasNext = nextEpisode(catalog, id) != null,
            )
        }
        return ep
    }

    /** [season]'s episodes in order; watched = played on the site or finished here. */
    private suspend fun seasonEpisodes(catalog: List<Season>, season: Int): List<PlayerEpisode> =
        catalog.firstOrNull { it.number == season }?.episodes.orEmpty().sortedBy { it.number }.map { e ->
            val local = try {
                watch.get(e.streamId)?.finished == true
            } catch (c: CancellationException) {
                throw c
            } catch (x: Exception) {
                false
            }
            PlayerEpisode(e.streamId, episodeLabel(e.season, e.number), e.played || local)
        }

    /** Every engine load (start, retry, recovery, episode switch) goes through here. */
    private fun loadEngine(masterUrl: String, startMs: Long) {
        readySinceLoad = false
        qualityPending = true
        audioPending = true
        engine.load(masterUrl, startMs)
        wantsPlay = true
        engine.play()
    }

    private fun openFailureMessage(e: Throwable): String = when (e) {
        is SiteException.NetworkError, is SiteException.Blocked, is IOException -> UiStrings.PLAY_FAILED_NETWORK
        is SiteException.HttpError -> if (e.code >= 500) UiStrings.PLAY_FAILED_NETWORK else UiStrings.PLAY_FAILED
        else -> UiStrings.PLAY_FAILED
    }

    private fun fail(message: String) {
        switching = false
        if (!closed) _state.update { it.copy(phase = PlayerPhase.Failed(message)) }
    }

    /** What the title page shows before the session's own image arrives. */
    private fun pageBackdrop(detail: TitleDetail?): String? =
        when (val b = BackdropResolver.resolve(ref, emptyMap(), titleInfo.playedBackdrops.value, detail)) {
            is Backdrop.Wide -> b.url
            is Backdrop.BlurredPoster -> b.url
            Backdrop.None -> null
        }

    // ---- engine events ----

    private fun onEvent(e: EngineEvent) {
        when (e) {
            EngineEvent.Ready -> {
                readySinceLoad = true
                everReady = true
                switching = false
                _state.update { it.copy(phase = PlayerPhase.Playing) }
            }
            EngineEvent.Ended -> {
                // A late Ended of the previous episode while switching: the new open is under way.
                if (switching) return
                wantsPlay = false
                // Played to the end: saved as finished whatever the reported position.
                upNextReached = true
                if (everReady && episode != null && nextEpisode(seasons, streamId) != null) playNext() else close()
            }
            is EngineEvent.Error -> onError(e)
        }
    }

    private fun onError(e: EngineEvent.Error) {
        report(e)
        if (BuildConfig.DEBUG) Log.w(TAG, "engine error ${e.codeName} http=${e.httpStatus}")
        if (closed) return
        durationMs()
        val recoverable = everReady && session != null &&
            (e.httpStatus in RECOVERABLE_HTTP || e.codeName.startsWith("ERROR_CODE_IO_"))
        val now = clock()
        val reopenAllowed = lastReopenAt?.let { now - it >= REOPEN_MIN_INTERVAL_MS } ?: true
        if (recoverable && reopenAllowed) {
            lastReopenAt = now
            open(streamId, startAt = engine.positionMs, silent = true)
        } else {
            fail(UiStrings.PLAYBACK_FAILED)
        }
    }

    /** Fire-and-forget, at most once per [ERROR_REPORT_MIN_INTERVAL_MS]; never affects the phase. */
    private fun report(e: EngineEvent.Error) {
        val now = clock()
        if (lastReportAt?.let { now - it < ERROR_REPORT_MIN_INTERVAL_MS } == true) return
        lastReportAt = now
        val event = if (readySinceLoad) "error" else "setupError"
        val id = streamId
        viewModelScope.safeLaunch(onError = {}) { api.reportStreamError(id, event, e.code.toString()) }
    }

    // ---- tick / progress ----

    private fun tick() {
        updateCues()
        if (upNextTick()) return
        playedSinceSaveMs += TICK_MS
        if (playedSinceSaveMs >= SAVE_EVERY_MS) save()
    }

    /**
     * The up-next card: shown while in [upNextDue]'s window, counting down once per second of playing time
     * (ticks only run while playing); at 0 plays the next episode. Returns true when it switched.
     */
    private fun upNextTick(): Boolean {
        // Only once this stream played: after a switch the engine still reports the previous episode.
        if (!everReady || episode == null) return false
        val next = nextEpisode(seasons, streamId) ?: return false
        if (!upNextDue(engine.positionMs, durationMs())) {
            upNextReached = false
            upNextShownMs = 0L
            if (_state.value.upNext != null) _state.update { it.copy(upNext = null) }
            return false
        }
        upNextReached = true
        if (upNextDismissed) return false
        if (_state.value.upNext == null) upNextShownMs = 0L else upNextShownMs += TICK_MS
        val left = UP_NEXT_COUNTDOWN_S - (upNextShownMs / 1000).toInt()
        if (left <= 0) {
            playNext()
            return true
        }
        val card = UpNext(UiStrings.upNextLabel(next.season, next.number), left)
        if (_state.value.upNext != card) _state.update { it.copy(upNext = card) }
        return false
    }

    /** Saves the current position if anything of this stream played. Survives the VM being cleared. */
    private fun save() {
        val entry = currentEntry() ?: return
        playedSinceSaveMs = 0
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { persist(entry) }
    }

    private fun currentEntry(): WatchEntry? {
        val ep = episode ?: return null
        if (!everReady) return null
        val pos = engine.positionMs
        val dur = durationMs()
        return WatchEntry(
            streamId, ref.path, ep.season, ep.number, pos, dur, 0,
            finished = isFinished(pos, dur) || upNextReached,
            subtitleOffsetMs = _state.value.subtitleOffsetMs,
        )
    }

    /** The engine's duration, or the last positive one seen for this stream while it reports 0. */
    private fun durationMs(): Long {
        val d = engine.durationMs
        if (d > 0) knownDurationMs = d
        return knownDurationMs
    }

    private suspend fun persist(entry: WatchEntry) {
        try {
            withContext(NonCancellable) { watch.put(entry) }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "save failed: $e")
        }
    }

    // ---- settings ----

    /** Applies the stored settings once (style, boost, global subtitle choice); defaults if the store fails. */
    private suspend fun ensureSettings() {
        if (settingsApplied) return
        val s = try {
            prefs.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "settings read failed: $e")
            PlayerSettings()
        }
        if (settingsApplied) return
        settingsApplied = true
        globalChoice = s.subtitle.takeIf { it != SubtitleChoice.FA_DUB } ?: SubtitleChoice.FA
        _state.update { it.copy(style = s.style, boost = s.audioBoost) }
    }

    private fun persistSettings(transform: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.safeLaunch(onError = { if (BuildConfig.DEBUG) Log.w(TAG, "settings write failed: $it") }) {
            prefs.update(transform)
        }
    }

    // ---- subtitles ----

    /** FA, EN, DUAL (both FA and EN), FA_DUB, then OFF always. */
    private fun availableSubtitles(s: StreamSession): List<SubtitleChoice> {
        val kinds = s.subtitles.map { it.kind }.toSet()
        return buildList {
            if (SubtitleKind.FA in kinds) add(SubtitleChoice.FA)
            if (SubtitleKind.EN in kinds) add(SubtitleChoice.EN)
            if (SubtitleKind.FA in kinds && SubtitleKind.EN in kinds) add(SubtitleChoice.DUAL)
            if (SubtitleKind.FA_DUB in kinds) add(SubtitleChoice.FA_DUB)
            add(SubtitleChoice.OFF)
        }
    }

    /** Original-audio choice: the stored global one if offered, else FA, else EN, else OFF. */
    private fun originalChoice(available: List<SubtitleChoice>): SubtitleChoice =
        listOf(globalChoice, SubtitleChoice.FA, SubtitleChoice.EN).firstOrNull { it in available } ?: SubtitleChoice.OFF

    /** The files a choice needs; DUAL uses the separate FA and EN files, never the merged track. */
    private fun kindsFor(choice: SubtitleChoice): List<SubtitleKind> = when (choice) {
        SubtitleChoice.FA -> listOf(SubtitleKind.FA)
        SubtitleChoice.EN -> listOf(SubtitleKind.EN)
        SubtitleChoice.DUAL -> listOf(SubtitleKind.FA, SubtitleKind.EN)
        SubtitleChoice.FA_DUB -> listOf(SubtitleKind.FA_DUB)
        SubtitleChoice.OFF -> emptyList()
    }

    /**
     * Starts the downloads [choice] needs, once per stream id and kind. A failure leaves that side blank,
     * retries once by itself after [SUBTITLE_RETRY_MS] and again on the next request (choice change, reopen).
     */
    private fun ensureSubtitles(choice: SubtitleChoice) {
        val s = session ?: return
        val id = streamId
        for (kind in kindsFor(choice)) {
            val url = s.subtitles.firstOrNull { it.kind == kind }?.url ?: continue
            val key = id to kind
            if (!requestedSubtitles.add(key)) continue
            viewModelScope.launch {
                try {
                    val body = api.subtitle(url)
                    val timeline = withContext(parseDispatcher) { SubtitleTimeline(SrtParser.parse(body)) }
                    timelines[key] = timeline
                    if (id == streamId) updateCues()
                } catch (e: CancellationException) {
                    requestedSubtitles.remove(key)
                    throw e
                } catch (e: Throwable) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "subtitle $kind download failed: $e")
                    requestedSubtitles.remove(key)
                    if (autoRetriedSubtitles.add(key)) {
                        delay(SUBTITLE_RETRY_MS)
                        // Only while the same stream is current and its choice still needs this file.
                        if (id == streamId && kind in kindsFor(_state.value.subtitle)) ensureSubtitles(_state.value.subtitle)
                    }
                }
            }
        }
    }

    /** Cue text at position − offset; the StateFlow only emits when the text changes. */
    private fun updateCues() {
        val st = _state.value
        val pos = engine.positionMs - st.subtitleOffsetMs
        fun text(kind: SubtitleKind): String? =
            timelines[streamId to kind]?.at(pos)?.joinToString("\n") { it.text }?.takeIf { it.isNotEmpty() }
        _cues.value = when (st.subtitle) {
            SubtitleChoice.FA -> SubtitleCues(text(SubtitleKind.FA))
            SubtitleChoice.EN -> SubtitleCues(text(SubtitleKind.EN))
            SubtitleChoice.DUAL -> SubtitleCues(text(SubtitleKind.FA), text(SubtitleKind.EN))
            SubtitleChoice.FA_DUB -> SubtitleCues(text(SubtitleKind.FA_DUB))
            SubtitleChoice.OFF -> SubtitleCues()
        }
    }

    private fun applySubtitle(choice: SubtitleChoice) {
        _state.update { it.copy(subtitle = choice) }
        ensureSubtitles(choice)
        updateCues()
    }

    fun setSubtitle(choice: SubtitleChoice) {
        applySubtitle(choice)
        // FA_DUB is per playback only.
        if (choice != SubtitleChoice.FA_DUB) {
            globalChoice = choice
            persistSettings { it.copy(subtitle = choice) }
        }
    }

    /** ±[SUBTITLE_STEP_MS] per press; saved with this stream's progress entry. */
    fun shiftSubtitles(deltaMs: Long) {
        _state.update { it.copy(subtitleOffsetMs = it.subtitleOffsetMs + deltaMs) }
        updateCues()
    }

    fun setStyle(style: SubtitleStyle) {
        _state.update { it.copy(style = style) }
        persistSettings { it.copy(style = style) }
    }

    // ---- tracks ----

    private fun onTracks(t: EngineTracks) {
        _state.update {
            it.copy(
                qualities = qualityOptions(t.heights, session?.qualityLabels.orEmpty()),
                playingHeight = t.playingHeight,
                audio = t.audio,
                selectedAudio = if (t.audio.isEmpty()) null else t.selectedAudio ?: it.selectedAudio,
            )
        }
        if (qualityPending && t.heights.isNotEmpty()) {
            qualityPending = false
            _state.value.selectedQuality?.let { engine.selectQuality(it) }
        }
        if (audioPending && t.audio.isNotEmpty()) {
            audioPending = false
            val manual = manualAudioLabel?.let { l -> t.audio.indexOfFirst { it.label == l }.takeIf { it >= 0 } }
            val index = manual ?: pickAudioTrack(t.audio, dub, engine.multichannelOutput) ?: return
            engine.selectAudio(index)
            val wasDubbed = audioDubbed
            audioDubbed = isDubbed(t.audio[index].label)
            _state.update { it.copy(selectedAudio = index) }
            // E.g. on dubbed audio and the next episode has no dubbed track: same rule as the initial choice.
            if (audioDubbed != wasDubbed) {
                val available = _state.value.availableSubtitles
                applySubtitle(
                    if (audioDubbed) {
                        if (SubtitleChoice.FA_DUB in available) SubtitleChoice.FA_DUB else SubtitleChoice.OFF
                    } else {
                        originalChoice(available)
                    }
                )
            }
        }
    }

    /** null = Automatic (the default, not persisted). */
    fun setQuality(height: Int?) {
        _state.update { it.copy(selectedQuality = height) }
        engine.selectQuality(height)
    }

    fun setAudio(index: Int) {
        val option = _state.value.audio.getOrNull(index) ?: return
        engine.selectAudio(index)
        audioPending = false
        manualAudioLabel = option.label
        val wasDubbed = audioDubbed
        val nowDubbed = isDubbed(option.label)
        audioDubbed = nowDubbed
        _state.update { it.copy(selectedAudio = index) }
        val available = _state.value.availableSubtitles
        if (nowDubbed && SubtitleChoice.FA_DUB in available) {
            applySubtitle(SubtitleChoice.FA_DUB)
        } else if (wasDubbed && !nowDubbed) {
            applySubtitle(originalChoice(available))
        }
        val postId = ref.postId
        viewModelScope.safeLaunch(onError = { if (BuildConfig.DEBUG) Log.w(TAG, "dub pref write failed: $it") }) {
            titlePrefs.setDubSelected(postId, nowDubbed)
        }
    }

    private fun isDubbed(label: String) = label.contains("dubbed")

    // ---- speed / boost ----

    /** Per playback (not persisted); kept across episode switches. */
    fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
        _state.update { it.copy(speed = speed) }
    }

    fun setBoost(on: Boolean) {
        _state.update { it.copy(boost = on) }
        if (on) smoother = GainSmoother() else engine.setGain(1f)
        persistSettings { it.copy(audioBoost = on) }
    }

    /** Every [GAIN_TICK_MS] while playing: follow the session's loudness map. A seek resets nothing. */
    private fun boostTick() {
        if (!_state.value.boost) return
        val schedule = gainSchedule ?: return
        engine.setGain(smoother.step(schedule.targetAt(engine.positionMs / 1000.0)).toFloat())
    }

    // ---- user actions ----

    /** The episode after the current one (across seasons), if any. */
    fun playNext() {
        val next = nextEpisode(seasons, streamId) ?: return
        playEpisode(next.streamId)
    }

    /**
     * Switches to [streamId] of the same catalog: saves this episode first, then the regular open flow
     * (same dub flag; speed kept by the engine; quality/audio reapplied by [loadEngine]; subtitles re-derived).
     */
    fun playEpisode(streamId: String) {
        if (closed || switching || streamId == this.streamId) return
        save()
        switching = true
        open(streamId, startAt = null)
    }

    /** "Watch credits": hides the card for this episode. */
    fun dismissUpNext() {
        upNextDismissed = true
        _state.update { it.copy(upNext = null) }
    }

    fun togglePlay() {
        if (wantsPlay) {
            wantsPlay = false
            engine.pause()
            save()
        } else {
            wantsPlay = true
            engine.play()
        }
    }

    /** Snapped to the session's keyframes. */
    fun seekBy(deltaMs: Long) {
        val current = engine.positionMs
        engine.seekTo(snapped(current + deltaMs, current))
        updateCues()
    }

    /** Scrub commit; snapped like [seekBy]. */
    fun seekTo(ms: Long) {
        engine.seekTo(snapped(ms, engine.positionMs))
        updateCues()
    }

    private fun snapped(targetMs: Long, currentMs: Long): Long {
        val durationMs = engine.durationMs
        // Unknown duration: snapToKeyframe would clamp everything to 0.
        if (durationMs <= 0) return maxOf(0L, targetMs)
        val frames = session?.iframes?.firstOrNull().orEmpty()
        return (snapToKeyframe(targetMs / 1000.0, currentMs / 1000.0, frames, durationMs / 1000.0) * 1000).roundToLong()
    }

    /** Reopens at the current position once something played, else where the watch store says. */
    fun retry() {
        open(streamId, startAt = if (everReady) engine.positionMs else null)
    }

    fun onStop() {
        save()
        wantsPlay = false
        engine.pause()
    }

    /** User Back: cancels a pending open (its HTTP call dies with it), saves, emits exit. */
    fun close() {
        if (closed) return
        closed = true
        openJob?.cancel()
        val entry = currentEntry()
        viewModelScope.launch {
            entry?.let { persist(it) }
            _exits.send(Unit)
        }
    }

    override fun onCleared() {
        closed = true
        engine.release()
    }

    private companion object {
        const val TAG = "DroozPlayer"
    }
}
