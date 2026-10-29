package com.alikarami.drooz.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTimelineTest {
    private val a = SubCue(1000, 2000, "a")
    private val b = SubCue(3000, 4000, "b")

    @Test fun findsActiveCue() {
        val t = SubtitleTimeline(listOf(a, b))
        assertEquals(listOf(a), t.at(1500))
        assertEquals(emptyList<SubCue>(), t.at(2000))
        assertEquals(listOf(b), t.at(3999))
    }

    @Test fun overlappingCuesBothReturned() {
        val long = SubCue(1000, 5000, "a")
        val short = SubCue(2000, 3000, "b")
        assertEquals(listOf(long, short), SubtitleTimeline(listOf(long, short)).at(2500))
    }

    @Test fun beforeFirstAndAfterLastEmpty() {
        val t = SubtitleTimeline(listOf(a, b))
        assertEquals(emptyList<SubCue>(), t.at(0))
        assertEquals(emptyList<SubCue>(), t.at(10_000))
    }

    @Test fun unsortedInputIsSorted() {
        val t = SubtitleTimeline(listOf(b, a))
        assertEquals(listOf(a), t.at(1500))
        assertEquals(listOf(b), t.at(3500))
    }

    @Test fun emptyTimeline() {
        val t = SubtitleTimeline(emptyList())
        assertEquals(true, t.isEmpty)
        assertEquals(emptyList<SubCue>(), t.at(100))
    }
}
