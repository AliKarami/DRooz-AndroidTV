package com.alikarami.drooz.player.ui

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteKeysTest {
    @Test fun hiddenRightTapSeeksForward() =
        assertEquals(KeyAction.Seek(10_000), mapKey(KEYCODE_DPAD_RIGHT, true, 0, Overlay.NONE, false, false))

    @Test fun hiddenLeftTapSeeksBack() =
        assertEquals(KeyAction.Seek(-10_000), mapKey(KEYCODE_DPAD_LEFT, true, 0, Overlay.NONE, false, false))

    @Test fun holdScrubsWithAcceleration() {
        assertEquals(KeyAction.Scrub(30_000), mapKey(KEYCODE_DPAD_RIGHT, true, 5, Overlay.NONE, false, true))
        assertEquals(KeyAction.Scrub(-60_000), mapKey(KEYCODE_DPAD_LEFT, true, 12, Overlay.NONE, false, true))
        assertEquals(KeyAction.Scrub(10_000), mapKey(KEYCODE_DPAD_RIGHT, true, 1, Overlay.NONE, false, false))
    }

    @Test fun releaseAfterHoldCommits() {
        assertEquals(KeyAction.CommitScrub, mapKey(KEYCODE_DPAD_RIGHT, false, 0, Overlay.NONE, false, true))
        assertEquals(KeyAction.None, mapKey(KEYCODE_DPAD_RIGHT, false, 0, Overlay.NONE, false, false))
        // Controls may already be showing when the key comes up (they open on the first repeat).
        assertEquals(KeyAction.CommitScrub, mapKey(KEYCODE_DPAD_LEFT, false, 0, Overlay.CONTROLS, true, true))
    }

    @Test fun centerTogglesWhenHidden() {
        assertEquals(KeyAction.TogglePlayAndShow, mapKey(KEYCODE_DPAD_CENTER, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.TogglePlayAndShow, mapKey(KEYCODE_ENTER, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.None, mapKey(KEYCODE_DPAD_CENTER, true, 0, Overlay.CONTROLS, false, false))
    }

    @Test fun centerOnSeekBarToggles() {
        assertEquals(KeyAction.TogglePlayAndShow, mapKey(KEYCODE_DPAD_CENTER, true, 0, Overlay.CONTROLS, true, false))
        assertEquals(KeyAction.None, mapKey(KEYCODE_DPAD_CENTER, true, 3, Overlay.CONTROLS, true, false))
    }

    @Test fun upDownShowControlsWhenHidden() {
        assertEquals(KeyAction.ShowControls, mapKey(KEYCODE_DPAD_UP, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.ShowControls, mapKey(KEYCODE_DPAD_DOWN, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.None, mapKey(KEYCODE_DPAD_DOWN, true, 0, Overlay.CONTROLS, false, false))
    }

    @Test fun arrowsMoveFocusInControlsUnlessOnSeekBar() {
        assertEquals(KeyAction.None, mapKey(KEYCODE_DPAD_RIGHT, true, 0, Overlay.CONTROLS, false, false))
        assertEquals(KeyAction.Seek(10_000), mapKey(KEYCODE_DPAD_RIGHT, true, 0, Overlay.CONTROLS, true, false))
        assertEquals(KeyAction.Scrub(-10_000), mapKey(KEYCODE_DPAD_LEFT, true, 2, Overlay.CONTROLS, true, true))
        assertEquals(KeyAction.None, mapKey(KEYCODE_DPAD_RIGHT, true, 0, Overlay.QUALITY, false, false))
    }

    @Test fun mediaKeysWorkEverywhere() {
        assertEquals(KeyAction.TogglePlayAndShow, mapKey(KEYCODE_MEDIA_PLAY_PAUSE, true, 0, Overlay.SUBTITLE_AUDIO, false, false))
        assertEquals(KeyAction.Seek(10_000), mapKey(KEYCODE_MEDIA_FAST_FORWARD, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.Seek(-10_000), mapKey(KEYCODE_MEDIA_REWIND, true, 0, Overlay.EPISODES, false, false))
        assertEquals(KeyAction.None, mapKey(KEYCODE_MEDIA_PLAY_PAUSE, false, 0, Overlay.NONE, false, false))
    }

    @Test fun explicitPlayAndPauseNeverToggle() {
        assertEquals(KeyAction.PlayAndShow, mapKey(KEYCODE_MEDIA_PLAY, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.PauseAndShow, mapKey(KEYCODE_MEDIA_PAUSE, true, 0, Overlay.SPEED, false, false))
        assertEquals(KeyAction.None, mapKey(KEYCODE_MEDIA_PAUSE, false, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.TogglePlayAndShow, mapKey(KEYCODE_MEDIA_PLAY_PAUSE, true, 0, Overlay.CONTROLS, false, false))
    }

    @Test fun heldFastForwardAndRewindScrubThenCommit() {
        assertEquals(KeyAction.Scrub(30_000), mapKey(KEYCODE_MEDIA_FAST_FORWARD, true, 5, Overlay.NONE, false, true))
        assertEquals(KeyAction.Scrub(-10_000), mapKey(KEYCODE_MEDIA_REWIND, true, 1, Overlay.CONTROLS, false, false))
        assertEquals(KeyAction.CommitScrub, mapKey(KEYCODE_MEDIA_FAST_FORWARD, false, 0, Overlay.CONTROLS, false, true))
        assertEquals(KeyAction.None, mapKey(KEYCODE_MEDIA_REWIND, false, 0, Overlay.NONE, false, false))
    }

    @Test fun heldFastForwardAndRewindInAPanelAreIgnored() {
        for (panel in listOf(Overlay.SUBTITLE_AUDIO, Overlay.QUALITY, Overlay.SPEED, Overlay.EPISODES)) {
            assertEquals(KeyAction.None, mapKey(KEYCODE_MEDIA_FAST_FORWARD, true, 3, panel, false, false))
            assertEquals(KeyAction.None, mapKey(KEYCODE_MEDIA_REWIND, true, 12, panel, false, false))
            // A tap still seeks.
            assertEquals(KeyAction.Seek(10_000), mapKey(KEYCODE_MEDIA_FAST_FORWARD, true, 0, panel, false, false))
        }
    }

    @Test fun backOnlyOnKeyUp() {
        assertEquals(KeyAction.None, mapKey(KEYCODE_BACK, true, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.Back, mapKey(KEYCODE_BACK, false, 0, Overlay.NONE, false, false))
        assertEquals(KeyAction.Back, mapKey(KEYCODE_BACK, false, 0, Overlay.SPEED, false, false))
    }
}
