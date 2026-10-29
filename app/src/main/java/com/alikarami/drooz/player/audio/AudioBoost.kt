@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** How often the player re-evaluates the Audio Boost target (the site ticks on a similar cadence). */
const val GAIN_TICK_MS = 250L

private fun round2(x: Double): Double = Math.round(x * 100) / 100.0

/**
 * Port of the site's per-segment gain map. [gains] are (segment duration in seconds, gain) pairs.
 */
class GainSchedule(
    private val gains: List<Pair<Double, Double>>,
    private val minGain: Double?,
    private val maxGain: Double?,
) {
    private fun clamp(v: Double): Double {
        var r = v
        if (minGain != null && r < minGain) r = minGain
        if (maxGain != null && r > maxGain) r = maxGain
        return r
    }

    fun targetAt(positionSec: Double): Double {
        if (gains.isEmpty()) return 1.0
        var end = 0.0
        for ((duration, gain) in gains) {
            end += duration
            if (end - 4 > positionSec) return clamp(gain * 0.9)
        }
        return clamp(2.0)
    }
}

/** The site's gain smoothing: ignore changes of 0.01 or less, otherwise move 1/18 of the way. */
class GainSmoother(initial: Double = 1.0) {
    var current: Double = initial
        private set
    private var first = true

    fun step(target: Double): Double {
        if (first || Math.round(Math.abs(target - current) * 100) > 1) {
            current = round2((target + current * 17) / 18)
        }
        first = false
        return current
    }
}

/** PCM gain with a linear ramp across each buffer so changes do not click. */
class GainAudioProcessor : BaseAudioProcessor() {
    @Volatile
    var gain: Float = 1f

    private var applied: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val channels = inputAudioFormat.channelCount
        val frames = size / (bytesPerSample * channels)
        val target = gain
        val start = applied
        for (f in 0 until frames) {
            val g = start + (target - start) * (f + 1) / frames
            for (c in 0 until channels) {
                if (bytesPerSample == 2) {
                    val v = Math.round(inputBuffer.getShort() * g)
                    out.putShort(v.coerceIn(-32768, 32767).toShort())
                } else {
                    out.putFloat((inputBuffer.getFloat() * g).coerceIn(-1f, 1f))
                }
            }
        }
        // Any trailing partial frame is passed through untouched.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        applied = target
        out.flip()
    }

    /** A new stream (or output) starts at unity, not ramping down from the last stream's gain. */
    override fun onReset() {
        applied = 1f
    }
}

data class AudioOption(val label: String, val channels: Int)

/** Chooses the initial audio track index, or null when there are no options. */
fun pickAudioTrack(options: List<AudioOption>, dub: Boolean, multichannel: Boolean): Int? {
    if (options.isEmpty()) return null
    val labels = options.map { it.label }
    val idx = if (dub) {
        labels.indexOfFirst { it.contains("dubbed") }
    } else if (multichannel) {
        labels.indexOfFirst { it.contains("6CH") && it.contains("original") }
    } else {
        labels.indexOfFirst { it.contains("original") && !it.contains("6CH") }
    }
    return if (idx >= 0) idx else 0
}
