package com.alikarami.drooz.player

import com.alikarami.drooz.player.engine.EngineEvent
import com.alikarami.drooz.player.engine.EngineTracks
import com.alikarami.drooz.player.engine.PlaybackEngine
import com.alikarami.drooz.player.engine.VideoSize
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/** Records every command; tests drive it with [emit] and the settable state. */
class FakeEngine : PlaybackEngine {
    val loads = mutableListOf<Pair<String, Long>>()
    val seeks = mutableListOf<Long>()
    var played = 0
    var paused = 0
    var released = false
    val gains = mutableListOf<Float>()
    val qualitySelections = mutableListOf<Int?>()
    val audioSelections = mutableListOf<Int>()
    val speeds = mutableListOf<Float>()

    override val tracks = MutableStateFlow(EngineTracks())
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<EngineEvent> = _events
    override val isPlaying = MutableStateFlow(false)
    override val buffering = MutableStateFlow(false)
    override val videoSize = MutableStateFlow<VideoSize?>(null)
    override var positionMs: Long = 0
    override var durationMs: Long = 0
    override var bufferedMs: Long = 0
    override var multichannelOutput: Boolean = false

    fun emit(e: EngineEvent) {
        check(_events.tryEmit(e))
    }

    override fun load(masterUrl: String, startMs: Long) {
        loads += masterUrl to startMs
        positionMs = startMs
        tracks.value = EngineTracks() // like the real engine: tracks reset, overrides cleared
    }
    override fun play() { played++; isPlaying.value = true }
    override fun pause() { paused++; isPlaying.value = false }
    override fun seekTo(ms: Long) { seeks += ms; positionMs = ms }
    override fun setSpeed(speed: Float) { speeds += speed }
    override fun setGain(gain: Float) { gains += gain }
    override fun selectQuality(height: Int?) { qualitySelections += height }
    override fun selectAudio(index: Int) { audioSelections += index }
    override fun release() { released = true }
}
