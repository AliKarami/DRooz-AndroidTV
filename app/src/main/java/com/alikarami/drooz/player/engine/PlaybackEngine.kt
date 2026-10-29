@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.engine

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.alikarami.drooz.player.VariantInfo
import com.alikarami.drooz.player.audio.AudioOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

data class EngineTracks(
    /** Distinct supported video heights, highest first. */
    val heights: List<Int> = emptyList(),
    val playingHeight: Int? = null,
    val audio: List<AudioOption> = emptyList(),
    val selectedAudio: Int? = null,
    /** Every supported video variant with its bitrate (kbps), for the site's bitrate-keyed labels. */
    val variants: List<VariantInfo> = heights.map { VariantInfo(it, null) },
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

/** [Format.bitrate], else [Format.peakBitrate], in kbps; null when neither is known. */
internal fun bitrateKbps(format: Format): Int? {
    val bps = format.bitrate.takeIf { it > 0 } ?: format.peakBitrate.takeIf { it > 0 } ?: return null
    return (bps / 1000.0).roundToInt()
}

/** HLS rendition name (EXT-X-MEDIA NAME → label), else id, else language. */
internal fun audioLabel(format: Format): String =
    format.label ?: format.id ?: format.language ?: "und"
