@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.engine

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.alikarami.drooz.player.audio.AudioOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class EngineTracks(
    val heights: List<Int> = emptyList(),
    val playingHeight: Int? = null,
    val audio: List<AudioOption> = emptyList(),
    val selectedAudio: Int? = null,
)

sealed interface EngineEvent {
    /** First STATE_READY after each [PlaybackEngine.load]. */
    data object Ready : EngineEvent
    data object Ended : EngineEvent
    data class Error(val code: Int, val codeName: String, val httpStatus: Int?) : EngineEvent
}

data class VideoSize(val width: Int, val height: Int)

/** The player as the view model sees it; [ExoPlaybackEngine] in the app, a fake in tests. */
interface PlaybackEngine {
    val tracks: StateFlow<EngineTracks>
    val events: Flow<EngineEvent>
    val isPlaying: StateFlow<Boolean>
    val buffering: StateFlow<Boolean>
    val videoSize: StateFlow<VideoSize?>
    val positionMs: Long
    /** 0 when unknown. */
    val durationMs: Long
    val bufferedMs: Long
    /** True when the current audio output takes ≥ 6 channels (HDMI → eARC soundbar). */
    val multichannelOutput: Boolean

    fun load(masterUrl: String, startMs: Long)
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun setSpeed(speed: Float)
    fun setGain(gain: Float)
    /** null = Automatic (ABR). */
    fun selectQuality(height: Int?)
    fun selectAudio(index: Int)
    fun release()
}

/** The HTTP status of the first [HttpDataSource.InvalidResponseCodeException] in the cause chain. */
internal fun httpStatusOf(error: Throwable?): Int? {
    var e = error
    var depth = 0
    while (e != null && depth < 16) {
        if (e is HttpDataSource.InvalidResponseCodeException) return e.responseCode
        e = e.cause
        depth++
    }
    return null
}

/** HLS rendition name (EXT-X-MEDIA NAME → label), else id, else language. */
internal fun audioLabel(format: Format): String =
    format.label ?: format.id ?: format.language ?: "und"
