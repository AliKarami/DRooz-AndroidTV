@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alikarami.drooz.player.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioBoostTest {
    private val fixtureGains = listOf(10.0078 to 7.81, 10.0078 to 9.71, 9.98458 to 4.02, 10.0078 to 1.93)

    @Test fun targetClampedToMax() = assertEquals(1.93, GainSchedule(fixtureGains, 0.64, 1.93).targetAt(0.0), 1e-9)

    @Test fun targetUsesSegmentEndingMoreThanFourSecondsAhead() {
        val s = GainSchedule(fixtureGains, 0.64, 1.93)
        assertEquals(1.93, s.targetAt(7.0), 1e-9)
        assertEquals(1.737, s.targetAt(30.0), 1e-9)
    }

    @Test fun targetPastEndAndEmpty() {
        assertEquals(1.93, GainSchedule(fixtureGains, 0.64, 1.93).targetAt(1000.0), 1e-9)
        assertEquals(1.0, GainSchedule(emptyList(), null, null).targetAt(5.0), 1e-9)
    }

    @Test fun smootherApproachesLikeTheSite() {
        val g = GainSmoother()
        assertEquals(1.05, g.step(1.93), 1e-9)
        assertEquals(1.10, g.step(1.93), 1e-9)
    }

    @Test fun smootherIgnoresTinyDifferences() {
        val g = GainSmoother(); g.step(1.0)
        assertEquals(1.0, g.step(1.005), 1e-9)
    }

    private fun shortBuf(vararg v: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(v.size * 2).order(ByteOrder.nativeOrder())
        v.forEach { b.putShort(it.toShort()) }
        b.flip(); return b
    }

    private fun readShorts(b: ByteBuffer): List<Int> {
        val out = mutableListOf<Int>()
        while (b.remaining() >= 2) out += b.short.toInt()
        return out
    }

    private fun fmt(enc: Int, ch: Int = 2) = AudioFormat(48000, ch, enc)

    @Test fun processorScales16BitAndClips() {
        val p = GainAudioProcessor()
        p.configure(fmt(C.ENCODING_PCM_16BIT)); p.flush(); p.gain = 2f
        p.queueInput(shortBuf(0, 0)); p.getOutput() // first buffer ramps from 1 to 2
        p.queueInput(shortBuf(1000, -1000, 30000, -30000))
        assertEquals(listOf(2000, -2000, 32767, -32768), readShorts(p.getOutput()))
    }

    @Test fun gainProcessor_resetRestoresUnityGain() {
        val p = GainAudioProcessor()
        p.configure(fmt(C.ENCODING_PCM_16BIT)); p.flush(); p.gain = 2f
        p.queueInput(shortBuf(0, 0)); p.getOutput() // applied gain is now 2
        p.reset()
        p.configure(fmt(C.ENCODING_PCM_16BIT)); p.flush(); p.gain = 1f
        // From unity to unity: no ramp down from the old 2x.
        p.queueInput(shortBuf(1000, -1000, 1000, -1000))
        assertEquals(listOf(1000, -1000, 1000, -1000), readShorts(p.getOutput()))
    }

    @Test fun processorRampsWithinABuffer() {
        val p = GainAudioProcessor()
        p.configure(fmt(C.ENCODING_PCM_16BIT, 1)); p.flush(); p.gain = 3f
        p.queueInput(shortBuf(1000, 1000, 1000, 1000))
        val o = readShorts(p.getOutput())
        assertTrue(o.zipWithNext().all { it.first < it.second })
        assertTrue(o.first() >= 1000)
        assertEquals(3000.0, o.last().toDouble(), 50.0)
    }

    @Test fun processorScalesFloat() {
        val p = GainAudioProcessor()
        p.configure(fmt(C.ENCODING_PCM_FLOAT)); p.flush(); p.gain = 2f
        val warm = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()); warm.putFloat(0f).putFloat(0f).flip()
        p.queueInput(warm); p.getOutput()
        val b = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()); b.putFloat(0.25f).putFloat(0.8f).flip()
        p.queueInput(b)
        val o = p.getOutput()
        assertEquals(0.5f, o.float, 1e-6f)
        assertEquals(1.0f, o.float, 1e-6f)
    }

    @Test(expected = UnhandledAudioFormatException::class)
    fun processorRejectsOtherEncodings() { GainAudioProcessor().configure(fmt(C.ENCODING_PCM_24BIT)) }

    @Test fun picksSixChannelOriginalOnlyWhenOutputSupportsIt() {
        val opts = listOf(AudioOption("original", 2), AudioOption("original-6CH", 6), AudioOption("dubbed-fa", 2))
        assertEquals(1, pickAudioTrack(opts, dub = false, multichannel = true))
        assertEquals(0, pickAudioTrack(opts, dub = false, multichannel = false))
        assertEquals(2, pickAudioTrack(opts, dub = true, multichannel = true))
    }

    @Test fun pickFallsBackToFirstAndNullWhenEmpty() {
        assertEquals(0, pickAudioTrack(listOf(AudioOption("und", 2)), dub = true, multichannel = false))
        assertNull(pickAudioTrack(emptyList(), dub = false, multichannel = false))
    }
}
