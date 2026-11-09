package com.alikarami.drooz.player.session

import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_HEADSETHOOK
import android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import android.view.KeyEvent.KEYCODE_MEDIA_REWIND
import android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD
import android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
import android.view.KeyEvent.KEYCODE_MEDIA_STOP
import androidx.media3.common.Player
import com.alikarami.drooz.player.PlayerPhase
import com.alikarami.drooz.player.PlayerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRulesTest {
    @Test fun firstDownOfEachMediaKeyIsOnePress() {
        assertEquals(MediaButton.Press(MediaKey.TOGGLE), mediaButton(KEYCODE_MEDIA_PLAY_PAUSE, true, 0))
        assertEquals(MediaButton.Press(MediaKey.TOGGLE), mediaButton(KEYCODE_HEADSETHOOK, true, 0))
        assertEquals(MediaButton.Press(MediaKey.PLAY), mediaButton(KEYCODE_MEDIA_PLAY, true, 0))
        assertEquals(MediaButton.Press(MediaKey.PAUSE), mediaButton(KEYCODE_MEDIA_PAUSE, true, 0))
        assertEquals(MediaButton.Press(MediaKey.FORWARD), mediaButton(KEYCODE_MEDIA_FAST_FORWARD, true, 0))
        assertEquals(MediaButton.Press(MediaKey.FORWARD), mediaButton(KEYCODE_MEDIA_SKIP_FORWARD, true, 0))
        assertEquals(MediaButton.Press(MediaKey.BACK), mediaButton(KEYCODE_MEDIA_REWIND, true, 0))
        assertEquals(MediaButton.Press(MediaKey.BACK), mediaButton(KEYCODE_MEDIA_SKIP_BACKWARD, true, 0))
        assertEquals(MediaButton.Press(MediaKey.NEXT), mediaButton(KEYCODE_MEDIA_NEXT, true, 0))
        assertEquals(MediaButton.Press(MediaKey.STOP), mediaButton(KEYCODE_MEDIA_STOP, true, 0))
    }

    @Test fun repeatsAndKeyUpsAreSwallowed() {
        // The framework default would seek on every FF repeat and turn a double PLAY_PAUSE into "next".
        assertEquals(MediaButton.Ignore, mediaButton(KEYCODE_MEDIA_FAST_FORWARD, true, 3))
        assertEquals(MediaButton.Ignore, mediaButton(KEYCODE_MEDIA_PLAY_PAUSE, true, 1))
        assertEquals(MediaButton.Ignore, mediaButton(KEYCODE_MEDIA_PLAY_PAUSE, false, 0))
        assertEquals(MediaButton.Ignore, mediaButton(KEYCODE_MEDIA_NEXT, false, 0))
    }

    @Test fun previousIsSwallowedOtherKeysAreNotOurs() {
        assertEquals(MediaButton.Ignore, mediaButton(KEYCODE_MEDIA_PREVIOUS, true, 0))
        assertEquals(MediaButton.NotOurs, mediaButton(KEYCODE_DPAD_CENTER, true, 0))
    }

    @Test fun onlyStopAndReadsWhileNotPlaying() {
        val c = sessionCommands(controllable = false, hasNext = true)
        assertTrue(Player.COMMAND_STOP in c)
        assertTrue(Player.COMMAND_GET_METADATA in c)
        assertFalse(Player.COMMAND_PLAY_PAUSE in c)
        assertFalse(Player.COMMAND_SEEK_TO_NEXT in c)
    }

    @Test fun playingAdvertisesTransportAndNextOnlyWhenThereIsOne() {
        val c = sessionCommands(controllable = true, hasNext = false)
        for (cmd in listOf(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
        )) assertTrue("$cmd", cmd in c)
        assertFalse(Player.COMMAND_SEEK_TO_NEXT in c)
        assertFalse(Player.COMMAND_SEEK_TO_PREVIOUS in c)
        assertFalse(Player.COMMAND_PREPARE in c)
        assertFalse(Player.COMMAND_SET_MEDIA_ITEM in c)
        val n = sessionCommands(controllable = true, hasNext = true)
        assertTrue(Player.COMMAND_SEEK_TO_NEXT in n)
        assertTrue(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM in n)
    }

    @Test fun infoFollowsTheScreenState() {
        val st = PlayerUiState(
            phase = PlayerPhase.Playing, title = "Some Show", episodeLabel = "S1 E2",
            backdropUrl = "https://x/bd.jpg", hasNext = true,
        )
        assertEquals(
            SessionInfo("Some Show", "S1 E2", "https://x/bd.jpg", controllable = true, hasNext = true),
            sessionInfo(st),
        )
        assertFalse(sessionInfo(st.copy(phase = PlayerPhase.Loading)).controllable)
        assertFalse(sessionInfo(st.copy(phase = PlayerPhase.Failed("x"))).controllable)
        assertNull(sessionInfo(st.copy(title = "")).title)
    }
}
