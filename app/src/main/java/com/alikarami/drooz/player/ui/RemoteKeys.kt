package com.alikarami.drooz.player.ui

import android.view.KeyEvent
import com.alikarami.drooz.player.scrubStepMs

/** Controls fade after this long without a key press (while playing, no panel, no up-next card). */
const val CONTROLS_HIDE_MS = 4_000L

/** A tap of LEFT/RIGHT (or a media FF/REW press) seeks this far. */
const val SEEK_STEP_MS = 10_000L

/** What sits on top of the video. Panels are opened from the controls' buttons. */
enum class Overlay { NONE, CONTROLS, SUBTITLE_AUDIO, QUALITY, SPEED, EPISODES }

sealed interface KeyAction {
    data object ShowControls : KeyAction
    data object TogglePlayAndShow : KeyAction
    /** Tap or media FF/REW: immediate snapped seek. */
    data class Seek(val deltaMs: Long) : KeyAction
    /** Held key repeat: move the scrub head only. */
    data class Scrub(val deltaMs: Long) : KeyAction
    /** Key up after a hold. */
    data object CommitScrub : KeyAction
    /** Close the top overlay, or exit when NONE. */
    data object Back : KeyAction
    /** Let focus handle it. */
    data object None : KeyAction
}

/**
 * Pure mapping, so the remote's behaviour is testable. [seekBarFocused] = the seek bar has focus.
 * Physical RIGHT is always forward and LEFT backward, whatever the layout direction.
 */
fun mapKey(
    keyCode: Int,
    isDown: Boolean,
    repeatCount: Int,
    overlay: Overlay,
    seekBarFocused: Boolean,
    scrubbing: Boolean,
): KeyAction {
    when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
            return if (isDown && repeatCount == 0) KeyAction.TogglePlayAndShow else KeyAction.None
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
            return if (isDown) KeyAction.Seek(SEEK_STEP_MS) else KeyAction.None
        KeyEvent.KEYCODE_MEDIA_REWIND ->
            return if (isDown) KeyAction.Seek(-SEEK_STEP_MS) else KeyAction.None
        KeyEvent.KEYCODE_BACK ->
            return if (isDown) KeyAction.None else KeyAction.Back
    }

    val direction = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
        KeyEvent.KEYCODE_DPAD_LEFT -> -1
        else -> 0
    }
    // A key up that ends a hold commits wherever it lands (controls open during the hold).
    if (direction != 0 && !isDown && scrubbing) return KeyAction.CommitScrub

    val seekKeys = overlay == Overlay.NONE || (overlay == Overlay.CONTROLS && seekBarFocused)
    if (direction != 0 && seekKeys) {
        if (!isDown) return KeyAction.None
        return if (repeatCount == 0) {
            KeyAction.Seek(direction * SEEK_STEP_MS)
        } else {
            KeyAction.Scrub(direction * scrubStepMs(repeatCount))
        }
    }

    if (!isDown || !seekKeys) return KeyAction.None
    return when (keyCode) {
        // OK on the hidden screen, or on the focused seek bar (it has no click of its own).
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            if (repeatCount == 0) KeyAction.TogglePlayAndShow else KeyAction.None
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
            if (overlay == Overlay.NONE) KeyAction.ShowControls else KeyAction.None
        else -> KeyAction.None
    }
}
