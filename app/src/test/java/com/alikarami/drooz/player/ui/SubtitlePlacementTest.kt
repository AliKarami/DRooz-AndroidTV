package com.alikarami.drooz.player.ui

import com.alikarami.drooz.player.engine.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitlePlacementTest {
    private val w = 1920
    private val h = 1080
    private val line = 60
    private val scope = VideoSize(1920, 803) // 2.39:1 → bars of 138 px
    private val fullFrame = VideoSize(1920, 1080)

    @Test fun scopeFilmSingleSitsCentredInBottomBar() {
        val p = subtitlePlacement(w, h, scope, line, dual = false, controlsHeightPx = null)
        assertEquals(39, p.primaryBottomPx)
        assertNull(p.secondaryBottomPx)
    }

    @Test fun scopeFilmDualPutsEnglishInBarFarsiInPicture() {
        val p = subtitlePlacement(w, h, scope, line, dual = true, controlsHeightPx = null)
        assertEquals(39, p.secondaryBottomPx)
        assertEquals(168, p.primaryBottomPx)
    }

    @Test fun fullFrameUsesSixPercent() {
        val p = subtitlePlacement(w, h, fullFrame, line, dual = false, controlsHeightPx = null)
        assertEquals(64, p.primaryBottomPx)
        assertNull(p.secondaryBottomPx)
    }

    @Test fun fullFrameDualStacksFarsiAboveEnglish() {
        val p = subtitlePlacement(w, h, fullFrame, line, dual = true, controlsHeightPx = null)
        assertEquals(64, p.secondaryBottomPx)
        assertEquals(214, p.primaryBottomPx)
    }

    @Test fun controlsLiftEverything() {
        val p = subtitlePlacement(w, h, scope, line, dual = false, controlsHeightPx = 300)
        assertEquals(330, p.primaryBottomPx)
        assertNull(p.secondaryBottomPx)
    }

    @Test fun controlsLiftKeepsDualSpacing() {
        val p = subtitlePlacement(w, h, scope, line, dual = true, controlsHeightPx = 300)
        assertEquals(330, p.secondaryBottomPx)
        assertEquals(330 + (168 - 39), p.primaryBottomPx)
    }

    @Test fun controlsBelowSubtitlesChangeNothing() {
        val p = subtitlePlacement(w, h, fullFrame, line, dual = false, controlsHeightPx = 10)
        assertEquals(64, p.primaryBottomPx)
    }

    @Test fun unknownVideoSizeTreatedAsFullFrame() {
        assertEquals(
            subtitlePlacement(w, h, fullFrame, line, dual = false, controlsHeightPx = null),
            subtitlePlacement(w, h, null, line, dual = false, controlsHeightPx = null),
        )
        assertEquals(
            subtitlePlacement(w, h, fullFrame, line, dual = true, controlsHeightPx = null),
            subtitlePlacement(w, h, null, line, dual = true, controlsHeightPx = null),
        )
    }

    @Test fun narrowBarFallsBackToSixPercent() {
        // 16:9.6 → bar 26 px < 1.5 lines
        val p = subtitlePlacement(w, h, VideoSize(1920, 1028), line, dual = false, controlsHeightPx = null)
        assertEquals(64, p.primaryBottomPx)
    }
}
