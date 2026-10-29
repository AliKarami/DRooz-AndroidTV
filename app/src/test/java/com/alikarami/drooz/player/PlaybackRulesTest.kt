package com.alikarami.drooz.player

import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRulesTest {
    private val K = listOf(0.0, 6.5, 8.208, 18.167, 26.208, 27.375, 37.375, 43.0, 50.75, 60.75)

    @Test fun snapTenForwardLandsOnNearestKeyframe() = assertEquals(27.375, snapToKeyframe(30.0, 20.0, K, 8400.0), 0.0)
    @Test fun snapSmallForwardKeepsFartherKeyframe() = assertEquals(43.0, snapToKeyframe(45.0, 40.0, K, 8400.0), 0.0)
    @Test fun snapForwardNeverStaysPut() = assertEquals(50.75, snapToKeyframe(45.0, 42.0, K, 8400.0), 0.0)
    @Test fun snapTenBackward() = assertEquals(18.167, snapToKeyframe(20.0, 30.0, K, 8400.0), 0.0)
    @Test fun snapSmallBackwardMovesBack() = assertEquals(18.167, snapToKeyframe(25.0, 28.0, K, 8400.0), 0.0)
    @Test fun snapExactKeyframeUnchanged() = assertEquals(37.375, snapToKeyframe(37.375, 10.0, K, 8400.0), 0.0)
    @Test fun snapClampsAtStartAndEnd() {
        assertEquals(0.0, snapToKeyframe(-6.0, 4.0, K, 8400.0), 0.0)
        assertEquals(60.0, snapToKeyframe(70.0, 59.0, emptyList(), 60.0), 0.0)
    }
    @Test fun snapWithoutKeyframesReturnsTarget() = assertEquals(123.4, snapToKeyframe(123.4, 113.4, emptyList(), 8400.0), 0.0)

    @Test fun resumeOnlyAfterThirtySecondsAndBeforeFinish() {
        assertEquals(0L, resumePositionMs(29_000, 3_600_000, false))
        assertEquals(31_000L, resumePositionMs(31_000, 3_600_000, false))
        assertEquals(0L, resumePositionMs(3_300_000, 3_600_000, false))
        assertEquals(0L, resumePositionMs(600_000, 3_600_000, true))
        assertEquals(600_000L, resumePositionMs(600_000, 0, false))
    }

    @Test fun finishedAtNinetyPercent() {
        assertTrue(isFinished(3_240_000, 3_600_000))
        assertFalse(isFinished(3_239_000, 3_600_000))
        assertFalse(isFinished(5_000, 0))
    }

    @Test fun upNextWindow() {
        assertFalse(upNextDue(3_000_000, 3_600_000)); assertTrue(upNextDue(3_560_000, 3_600_000))
        assertFalse(upNextDue(70_000, 100_000))
    }

    private fun ep(s: Int, e: Int) = Episode(s, e, "s${s}e$e", emptyList(), emptyList(), emptyList(), false)

    @Test fun nextEpisodeWithinSeason() {
        val seasons = listOf(Season(1, listOf(ep(1, 1), ep(1, 2))))
        assertEquals("s1e2", nextEpisode(seasons, "s1e1")?.streamId)
    }

    @Test fun nextEpisodeCrossesSeasons() {
        val seasons = listOf(Season(2, listOf(ep(2, 1))), Season(1, listOf(ep(1, 2), ep(1, 1))))
        assertEquals("s2e1", nextEpisode(seasons, "s1e2")?.streamId)
    }

    @Test fun nextEpisodeAfterLastIsNull() {
        val seasons = listOf(Season(1, listOf(ep(1, 1), ep(1, 2))), Season(2, listOf(ep(2, 1))))
        assertNull(nextEpisode(seasons, "s2e1"))
        assertNull(nextEpisode(seasons, "nope"))
    }

    @Test fun scrubAccelerates() = assertEquals(listOf(10_000L, 10_000L, 30_000L, 60_000L), listOf(0, 4, 5, 10).map(::scrubStepMs))

    @Test fun qualityOptionsUseSiteLabelsByHeight() = assertEquals(
        listOf(QualityOption(1080, "1080 [ 2.48 GB ]"), QualityOption(720, "720 [ 1.14 GB ]"), QualityOption(480, "480 [ 553 MB ]"), QualityOption(360, "360p")),
        qualityOptions(
            listOf(480, 1080, 720, 1080, 360).map { VariantInfo(it, null) },
            mapOf(2475 to "1080 [ 2.48 GB ]", 1100 to "720 [ 1.14 GB ]", 488 to "480 [ 553 MB ]"),
        ),
    )

    // Two 1080 renditions: the label of the smaller one comes first in the map (height matching would pick it).
    private val labels1080 = linkedMapOf(1200 to "1080 [ 1.2 GB ]", 2475 to "1080 [ 2.48 GB ]")

    @Test fun qualityOptions_matchesByBitrateKey() = assertEquals(
        listOf(QualityOption(1080, "1080 [ 2.48 GB ]")),
        qualityOptions(listOf(VariantInfo(1080, 2475), VariantInfo(1080, 1200)), labels1080),
    )

    @Test fun qualityOptions_nearestWithin15Percent() {
        assertEquals(
            listOf(QualityOption(1080, "1080 [ 2.48 GB ]")),
            qualityOptions(listOf(VariantInfo(1080, 2400)), labels1080),
        )
        // Beyond 15 % of every key: back to the height match.
        assertEquals(
            listOf(QualityOption(1080, "1080 [ 1.2 GB ]")),
            qualityOptions(listOf(VariantInfo(1080, 5000)), labels1080),
        )
    }

    @Test fun qualityOptions_unknownBitrate_fallsBackToHeight() = assertEquals(
        listOf(QualityOption(720, "720 [ 900 MB ]"), QualityOption(480, "480p")),
        qualityOptions(listOf(VariantInfo(480, null), VariantInfo(720, null)), mapOf(1500 to "720 [ 900 MB ]")),
    )
}
