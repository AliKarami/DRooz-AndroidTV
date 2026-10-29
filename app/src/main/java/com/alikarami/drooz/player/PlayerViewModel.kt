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
import com.alikarami.drooz.player.engine.EngineEvent
import com.alikarami.drooz.player.engine.PlaybackEngine
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.episodeLabel
import com.alikarami.drooz.ui.common.safeLaunch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

/** HTTP statuses that mean the stream key expired / the CDN URL went stale: reopen the stream page. */
private val RECOVERABLE_HTTP = setOf(403, 404, 410)

sealed interface PlayerPhase {
    data object Loading : PlayerPhase
    /** Includes paused and buffering. */
    data object Playing : PlayerPhase
    data class Failed(val message: String) : PlayerPhase
}

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

    /** A Ready was seen for the current stream (any load): progress is worth saving, errors may recover. */
    private var everReady = false
    /** A Ready was seen since the last [loadEngine]: errors before it are "setupError". */
    private var readySinceLoad = false
    private var lastReopenAt: Long? = null
    private var lastReportAt: Long? = null
    private var playedSinceSaveMs = 0L
    /** Set by the up-next countdown (Task 9): the episode counts as finished. */
    private var upNextReached = false

    init {
        viewModelScope.launch { engine.events.collect(::onEvent) }
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
            session = null
        }
        if (!silent) _state.update { it.copy(phase = PlayerPhase.Loading, currentStreamId = id) }
        openJob = viewModelScope.launch {
            try {
                val detail = titleInfo.get(ref)
                val seasons = if (dub) detail.catalog.dub else detail.catalog.main
                val ep = seasons.flatMap { it.episodes }.firstOrNull { it.streamId == id }
                _state.update {
                    it.copy(
                        title = detail.card.title,
                        episodeLabel = ep?.takeIf { ref.kind == TitleKind.SERIES }?.let { e -> episodeLabel(e.season, e.number) },
                        backdropUrl = if (session == null) pageBackdrop(detail) ?: it.backdropUrl else it.backdropUrl,
                    )
                }
                if (ep == null) {
                    fail(UiStrings.PLAY_FAILED)
                    return@launch
                }
                episode = ep
                val s = api.openStream(ep, ref, if (dub) "dubbed-fa" else "original", STREAM_HEIGHT)
                // Opening the stream marks it played on the site: the title page is stale.
                titleInfo.invalidate(ref)
                s.backdropPath?.let { titleInfo.remember(ref, it) }
                session = s
                s.backdropPath?.let { p -> _state.update { it.copy(backdropUrl = SiteConfig.BASE_URL + p) } }
                val start = startAt ?: watch.get(id)?.let { resumePositionMs(it.positionMs, it.durationMs, it.finished) } ?: 0L
                if (closed) return@launch
                loadEngine(s.masterUrl, start)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SiteException.NotLoggedIn) {
                // SessionGuardedSiteApi already routes the app to Login.
                _exits.trySend(Unit)
            } catch (e: Throwable) {
                if (BuildConfig.DEBUG) Log.w(TAG, "open failed: $e")
                fail(openFailureMessage(e))
            }
        }
    }

    /** Every engine load (start, retry, recovery, episode switch) goes through here. */
    private fun loadEngine(masterUrl: String, startMs: Long) {
        readySinceLoad = false
        engine.load(masterUrl, startMs)
        engine.play()
    }

    private fun openFailureMessage(e: Throwable): String = when (e) {
        is SiteException.NetworkError, is SiteException.Blocked, is IOException -> UiStrings.PLAY_FAILED_NETWORK
        is SiteException.HttpError -> if (e.code >= 500) UiStrings.PLAY_FAILED_NETWORK else UiStrings.PLAY_FAILED
        else -> UiStrings.PLAY_FAILED
    }

    private fun fail(message: String) = _state.update { it.copy(phase = PlayerPhase.Failed(message)) }

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
                _state.update { it.copy(phase = PlayerPhase.Playing) }
            }
            EngineEvent.Ended -> save()
            is EngineEvent.Error -> onError(e)
        }
    }

    private fun onError(e: EngineEvent.Error) {
        report(e)
        if (BuildConfig.DEBUG) Log.w(TAG, "engine error ${e.codeName} http=${e.httpStatus}")
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
        playedSinceSaveMs += TICK_MS
        if (playedSinceSaveMs >= SAVE_EVERY_MS) save()
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
        val dur = engine.durationMs
        return WatchEntry(
            streamId, ref.path, ep.season, ep.number, pos, dur, 0,
            finished = isFinished(pos, dur) || upNextReached,
            subtitleOffsetMs = _state.value.subtitleOffsetMs,
        )
    }

    private suspend fun persist(entry: WatchEntry) {
        try {
            withContext(NonCancellable) { watch.put(entry) }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "save failed: $e")
        }
    }

    // ---- user actions ----

    fun togglePlay() {
        if (engine.isPlaying.value) {
            engine.pause()
            save()
        } else {
            engine.play()
        }
    }

    /** Snapped to the session's keyframes. */
    fun seekBy(deltaMs: Long) {
        val current = engine.positionMs
        engine.seekTo(snapped(current + deltaMs, current))
    }

    /** Scrub commit; snapped like [seekBy]. */
    fun seekTo(ms: Long) {
        engine.seekTo(snapped(ms, engine.positionMs))
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
        engine.release()
    }

    private companion object {
        const val TAG = "DroozPlayer"
    }
}
