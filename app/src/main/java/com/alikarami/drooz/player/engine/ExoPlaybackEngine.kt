@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.engine

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.player.audio.AudioOption
import com.alikarami.drooz.player.audio.GainAudioProcessor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import androidx.media3.common.VideoSize as Media3VideoSize

/**
 * Media3 engine: HLS over OkHttp, PCM gain in the audio sink, keyframe seeks, no ExoPlayer
 * text track (subtitles are rendered by the app). All calls and reads on the main thread.
 * Logging is debug-only: stream URLs carry the user's token and are never logged.
 */
class ExoPlaybackEngine(context: Context, client: OkHttpClient) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val gainProcessor = GainAudioProcessor()

    private val renderersFactory = object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(gainProcessor))
            .build()
    }

    /** Public only so the activity can attach the video surface. */
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client)))
        .build()
        .apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }

    private val _tracks = MutableStateFlow(EngineTracks())
    override val tracks: StateFlow<EngineTracks> = _tracks.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 8)
    override val events: Flow<EngineEvent> = _events.asSharedFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    override val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    override val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    override val positionMs: Long get() = exoPlayer.currentPosition
    override val durationMs: Long
        get() = exoPlayer.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
    override val bufferedMs: Long get() = exoPlayer.bufferedPosition

    // Every getCapabilities overload is @Deprecated in 1.11.1; still the documented source of the
    // routed output's max PCM channel count (HDMI EDID / eARC), and cheap enough for one read per load.
    @Suppress("DEPRECATION")
    override val multichannelOutput: Boolean
        get() = AudioCapabilities.getCapabilities(appContext, exoPlayer.audioAttributes, null)
            .maxChannelCount >= 6

    /** Audio tracks in the order of [EngineTracks.audio]: (group, track index in group). */
    private var audioRefs: List<Pair<Tracks.Group, Int>> = emptyList()
    private var awaitingReady = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = playbackState == Player.STATE_BUFFERING
            when (playbackState) {
                Player.STATE_READY -> if (awaitingReady) {
                    awaitingReady = false
                    _events.tryEmit(EngineEvent.Ready)
                }
                Player.STATE_ENDED -> _events.tryEmit(EngineEvent.Ended)
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onVideoSizeChanged(videoSize: Media3VideoSize) {
            _videoSize.value =
                if (videoSize.width > 0 && videoSize.height > 0) VideoSize(videoSize.width, videoSize.height) else null
            _tracks.value = _tracks.value.copy(playingHeight = currentHeight())
        }

        override fun onTracksChanged(tracks: Tracks) {
            publishTracks(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val status = httpStatusOf(error)
            if (BuildConfig.DEBUG) Log.w(TAG, "error ${error.errorCodeName} http=$status")
            _events.tryEmit(EngineEvent.Error(error.errorCode, error.errorCodeName, status))
        }
    }

    init {
        exoPlayer.addListener(listener)
    }

    private fun currentHeight(): Int? {
        val sizeH = exoPlayer.videoSize.height
        if (sizeH > 0) return sizeH
        return exoPlayer.videoFormat?.height?.takeIf { it > 0 }
    }

    private fun publishTracks(tracks: Tracks) {
        val heights = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g.getTrackFormat(it).height } }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
        val refs = tracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        audioRefs = refs
        val audio = refs.map { (g, i) ->
            val f = g.getTrackFormat(i)
            AudioOption(audioLabel(f), if (f.channelCount == Format.NO_VALUE) 0 else f.channelCount)
        }
        val selected = refs.indexOfFirst { (g, i) -> g.isTrackSelected(i) }.takeIf { it >= 0 }
        _tracks.value = EngineTracks(heights, currentHeight(), audio, selected)
        if (BuildConfig.DEBUG) Log.d(TAG, "tracks heights=$heights audio=${audio.map { it.label }} selected=$selected")
    }

    override fun load(masterUrl: String, startMs: Long) {
        awaitingReady = true
        audioRefs = emptyList()
        _tracks.value = EngineTracks()
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        val item = MediaItem.Builder()
            .setUri(masterUrl)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        exoPlayer.setMediaItem(item, startMs.coerceAtLeast(0))
        exoPlayer.prepare()
        if (BuildConfig.DEBUG) Log.d(TAG, "load start=$startMs")
    }

    override fun play() = exoPlayer.play()

    override fun pause() = exoPlayer.pause()

    override fun seekTo(ms: Long) = exoPlayer.seekTo(ms.coerceAtLeast(0))

    override fun setSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    override fun setGain(gain: Float) {
        gainProcessor.gain = gain
    }

    override fun selectQuality(height: Int?) {
        val builder = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (height != null) {
            val override = exoPlayer.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_VIDEO }
                .firstNotNullOfOrNull { g ->
                    val indices = (0 until g.length)
                        .filter { g.isTrackSupported(it) && g.getTrackFormat(it).height == height }
                    if (indices.isEmpty()) null else TrackSelectionOverride(g.mediaTrackGroup, indices)
                }
            if (override != null) builder.setOverrideForType(override)
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    override fun selectAudio(index: Int) {
        val (group, track) = audioRefs.getOrNull(index) ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
            .build()
    }

    override fun release() {
        exoPlayer.removeListener(listener)
        exoPlayer.release()
    }

    private companion object {
        const val TAG = "DroozEngine"
    }
}
