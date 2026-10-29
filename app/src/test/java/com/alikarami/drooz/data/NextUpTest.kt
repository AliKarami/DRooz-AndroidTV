package com.alikarami.drooz.data

import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpTest {
    private fun ep(s: Int, n: Int, played: Boolean = false) = Episode(
        season = s, number = n, streamId = "s${s}e$n",
        videos = emptyList(), audios = emptyList(), subtitles = emptyList(), played = played,
    )

    private fun entry(id: String, pos: Long, dur: Long, updatedAt: Long, finished: Boolean = false) = WatchEntry(
        streamId = id, refPath = "/post/series/1/x/", season = 1, episode = 1,
        positionMs = pos, durationMs = dur, updatedAt = updatedAt, finished = finished,
    )

    private fun season1(vararg eps: Episode) = listOf(Season(1, eps.toList()))

    @Test
    fun nothingWatched_startsAtFirst() {
        val e1 = ep(1, 1)
        val r = nextUp(season1(e1, ep(1, 2), ep(1, 3)), emptyMap())
        assertEquals(NextUp.Start(e1), r)
    }

    @Test
    fun serverPlayed_nextAfterLastWatched_crossesSeasons() {
        val s2e1 = ep(2, 1)
        val seasons = listOf(Season(1, listOf(ep(1, 1, true), ep(1, 2, true))), Season(2, listOf(s2e1)))
        assertEquals(NextUp.Next(s2e1), nextUp(seasons, emptyMap()))
    }

    @Test
    fun gapsDoNotMatter_lastWatchedWins() {
        val e4 = ep(1, 4)
        val r = nextUp(season1(ep(1, 1, true), ep(1, 2), ep(1, 3, true), e4), emptyMap())
        assertEquals(NextUp.Next(e4), r)
    }

    @Test
    fun localFinishedCountsAsWatched() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val en = entry("s1e1", 0, 2_400_000, 1, finished = true)
        assertEquals(NextUp.Next(e2), nextUp(season1(e1, e2), mapOf("s1e1" to en)))
        assertTrue(episodeState(e1, en).watched)
    }

    @Test
    fun resumeBeatsNext_mostRecentResumable() {
        val e3 = ep(1, 3)
        val entries = mapOf(
            "s1e2" to entry("s1e2", 600_000, 2_400_000, 5),
            "s1e3" to entry("s1e3", 100_000, 2_400_000, 9),
        )
        val r = nextUp(season1(ep(1, 1, true), ep(1, 2), e3), entries) as NextUp.Resume
        assertEquals(e3, r.episode)
        assertEquals(0.0417f, r.state.progress!!, 0.001f)
        assertEquals(39, r.state.remainingMin)
    }

    @Test
    fun underThirtySeconds_isNotResumable() {
        val e1 = ep(1, 1)
        val en = entry("s1e1", 20_000, 2_400_000, 1)
        assertEquals(NextUp.Start(e1), nextUp(season1(e1, ep(1, 2)), mapOf("s1e1" to en)))
        assertNull(episodeState(e1, en).progress)
    }

    @Test
    fun ninetyPercent_isWatchedNotResumable() {
        val e1 = ep(1, 1)
        val e2 = ep(1, 2)
        val en = entry("s1e1", 2_200_000, 2_400_000, 1)
        assertTrue(episodeState(e1, en).watched)
        assertEquals(NextUp.Next(e2), nextUp(season1(e1, e2), mapOf("s1e1" to en)))
    }

    // The server's played threshold is unknown: local in-progress state wins until it catches up.
    @Test
    fun serverPlayed_butLocallyInProgress_resumes() {
        val e1 = ep(1, 1, true)
        val e2 = ep(1, 2, true)
        val en = entry("s1e2", 600_000, 2_400_000, 5)
        val state = episodeState(e2, en)
        assertFalse(state.watched)
        assertEquals(0.25f, state.progress!!, 0.001f)
        assertEquals(600_000L, state.resumeMs)
        assertEquals(30, state.remainingMin)
        val r = nextUp(season1(e1, e2, ep(1, 3)), mapOf("s1e2" to en)) as NextUp.Resume
        assertEquals(e2, r.episode)
    }

    @Test
    fun serverPlayed_withoutLocalEntry_isWatched() {
        assertTrue(episodeState(ep(1, 1, true), null).watched)
    }

    @Test
    fun serverPlayed_withFinishedOrTooShortLocalEntry_isWatched() {
        val e1 = ep(1, 1, true)
        assertTrue(episodeState(e1, entry("s1e1", 600_000, 2_400_000, 1, finished = true)).watched)
        assertTrue(episodeState(e1, entry("s1e1", 2_300_000, 2_400_000, 1)).watched)
        assertTrue(episodeState(e1, entry("s1e1", 10_000, 2_400_000, 1)).watched)
    }

    @Test
    fun allWatched_again() {
        val e1 = ep(1, 1, true)
        assertEquals(NextUp.Again(e1), nextUp(season1(e1, ep(1, 2, true)), emptyMap()))
    }

    @Test
    fun emptySeasons_null() {
        assertNull(nextUp(emptyList(), emptyMap()))
    }

    @Test
    fun film_resume() {
        val e1 = ep(1, 1)
        val en = entry("s1e1", 1_800_000, 7_440_000, 1)
        val r = nextUp(season1(e1), mapOf("s1e1" to en)) as NextUp.Resume
        assertEquals(94, r.state.remainingMin)
    }
}
