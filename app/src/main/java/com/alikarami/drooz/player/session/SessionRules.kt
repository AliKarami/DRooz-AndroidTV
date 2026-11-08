package com.alikarami.drooz.player.session

import android.view.KeyEvent
import androidx.media3.common.Player
import com.alikarami.drooz.player.PlayerPhase
import com.alikarami.drooz.player.PlayerUiState

/** A media-session press, in the view model's terms. */
enum class MediaKey { TOGGLE, PLAY, PAUSE, BACK, FORWARD, NEXT, STOP }

sealed interface MediaButton {
    data class Press(val key: MediaKey) : MediaButton
    /** Ours, but no action: key ups, repeats, PREVIOUS. Handled, so the framework default never runs. */
    data object Ignore : MediaButton
    /** Not a media key: left to the framework. */
    data object NotOurs : MediaButton
}

/**
 * A media-button event that reached the session (the activity did not consume it). Only a key's first
 * down acts: the framework default would seek on every FF repeat and turn a double PLAY_PAUSE into "next".
 */
fun mediaButton(keyCode: Int, isDown: Boolean, repeatCount: Int): MediaButton {
    val key = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> MediaKey.TOGGLE
        KeyEvent.KEYCODE_MEDIA_PLAY -> MediaKey.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> MediaKey.PAUSE
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> MediaKey.FORWARD
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> MediaKey.BACK
        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKey.NEXT
        KeyEvent.KEYCODE_MEDIA_STOP -> MediaKey.STOP
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return MediaButton.Ignore
        else -> return MediaButton.NotOurs
    }
    return if (isDown && repeatCount == 0) MediaButton.Press(key) else MediaButton.Ignore
}

/** What the session shows and allows; [controllable] = a stream is on screen (phase Playing). */
data class SessionInfo(
    val title: String? = null,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val controllable: Boolean = false,
    val hasNext: Boolean = false,
)

fun sessionInfo(state: PlayerUiState): SessionInfo = SessionInfo(
    title = state.title.ifEmpty { null },
    subtitle = state.episodeLabel,
    artworkUrl = state.backdropUrl,
    controllable = state.phase == PlayerPhase.Playing,
    hasNext = state.hasNext,
)

/**
 * The player commands the session advertises. Stop (save and exit) and the reads always; transport only
 * while a stream is on screen; next only when there is a next episode. Never prepare / set items /
 * previous / speed / volume: those are not the view model's to give away.
 */
fun sessionCommands(controllable: Boolean, hasNext: Boolean): Set<Int> = buildSet {
    add(Player.COMMAND_STOP)
    add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    add(Player.COMMAND_GET_TIMELINE)
    add(Player.COMMAND_GET_METADATA)
    if (controllable) {
        add(Player.COMMAND_PLAY_PAUSE)
        add(Player.COMMAND_SEEK_BACK)
        add(Player.COMMAND_SEEK_FORWARD)
        add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (hasNext) {
            add(Player.COMMAND_SEEK_TO_NEXT)
            add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
    }
}
