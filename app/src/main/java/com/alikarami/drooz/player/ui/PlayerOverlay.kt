package com.alikarami.drooz.player.ui

import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleStyle
import com.alikarami.drooz.player.PlayerUiState
import com.alikarami.drooz.player.PlayerViewModel
import com.alikarami.drooz.player.UpNext
import com.alikarami.drooz.player.engine.ExoPlaybackEngine
import com.alikarami.drooz.player.engine.VideoSize
import com.alikarami.drooz.ui.common.BusySpinner
import com.alikarami.drooz.ui.common.requestFocusSoon
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the remote has done to the screen. Plain fields are read only by the key handler; state fields
 * are read by the small composables that need them, so a scrub step or a tick redraws the seek bar,
 * not the screen.
 */
@Stable
internal class OverlayController {
    var overlay by mutableStateOf(Overlay.NONE)
        private set
    /** Where focus lands when the controls (re)appear. */
    var controlsTarget = ControlsTarget.PLAY
        private set
    /** Bumped on every overlay change; each overlay's focus effect is keyed on it. */
    var focusToken by mutableIntStateOf(0)
        private set
    /** The scrub head while a held LEFT/RIGHT moves it; null when not scrubbing. */
    var scrubMs by mutableStateOf<Long?>(null)
    var seekBarFocused = false
    /** Bumped after a seek so the seek bar re-reads the engine at once. */
    var refresh by mutableIntStateOf(0)
    /** Height (px above the screen's bottom edge) of the controls' bottom block / the episodes strip. */
    var controlsCoverPx by mutableIntStateOf(0)
    var stripCoverPx by mutableIntStateOf(0)
    /** Uptime of the last key event (auto-hide). */
    var lastKeyAt = 0L
    /** Keys whose KeyDown the screen consumed: their KeyUp is consumed too (never a stray click). */
    val consumedDowns = HashSet<Int>()

    fun show(o: Overlay, target: ControlsTarget = ControlsTarget.PLAY) {
        if (o == Overlay.CONTROLS) controlsTarget = target
        consumedDowns.clear()
        overlay = o
        lastKeyAt = SystemClock.uptimeMillis()
        focusToken++
    }

    fun hide() {
        consumedDowns.clear()
        overlay = Overlay.NONE
        seekBarFocused = false
        focusToken++
    }
}

private fun openerOf(o: Overlay): ControlsTarget = when (o) {
    Overlay.SUBTITLE_AUDIO -> ControlsTarget.SUBTITLE_AUDIO
    Overlay.QUALITY -> ControlsTarget.QUALITY
    Overlay.SPEED -> ControlsTarget.SPEED
    Overlay.EPISODES -> ControlsTarget.EPISODES
    else -> ControlsTarget.PLAY
}

/**
 * Everything over the video while playing: subtitles, buffering spinner, controls, panels, the
 * episodes strip and the up-next card, plus the remote (every key passes [mapKey] at this root).
 * The root is focusable only while nothing is open, so a hidden screen still receives the keys.
 */
@Composable
internal fun PlayerOverlayHost(
    vm: PlayerViewModel,
    engine: ExoPlaybackEngine,
    state: PlayerUiState,
    videoSize: VideoSize?,
    buffering: Boolean,
    containerW: Int,
    containerH: Int,
) {
    val c = remember { OverlayController() }
    val root = remember { FocusRequester() }
    val playNow = remember { FocusRequester() }
    val isPlaying by engine.isPlaying.collectAsStateWithLifecycle()
    // The user's intent, so a rebuffer does not flip the icon to ▶ (engine.isPlaying is false while buffering).
    val playing = isPlaying || (buffering && engine.exoPlayer.playWhenReady)
    val upNext = state.upNext
    val upNextShown = upNext != null
    val scrubbing by remember { derivedStateOf { c.scrubMs != null } }

    val upNextShownNow by rememberUpdatedState(upNextShown)

    fun handleBack() {
        when {
            c.scrubMs != null -> { c.scrubMs = null; c.refresh++ }
            c.overlay == Overlay.CONTROLS -> c.hide()
            c.overlay != Overlay.NONE -> c.show(Overlay.CONTROLS, openerOf(c.overlay))
            upNextShownNow -> vm.dismissUpNext()
            else -> vm.close()
        }
    }

    fun showIfHidden(target: ControlsTarget) {
        if (c.overlay == Overlay.NONE && !upNextShownNow) c.show(Overlay.CONTROLS, target)
    }

    fun perform(action: KeyAction) {
        when (action) {
            KeyAction.ShowControls -> c.show(Overlay.CONTROLS, ControlsTarget.PLAY)
            KeyAction.TogglePlayAndShow -> {
                vm.togglePlay()
                showIfHidden(ControlsTarget.PLAY)
            }
            is KeyAction.Seek -> {
                c.scrubMs = null
                vm.seekBy(action.deltaMs)
                c.refresh++
                showIfHidden(ControlsTarget.SEEK_BAR)
            }
            is KeyAction.Scrub -> {
                val duration = engine.durationMs
                val next = max(0L, (c.scrubMs ?: engine.positionMs) + action.deltaMs)
                c.scrubMs = if (duration > 0) min(next, duration) else next
                showIfHidden(ControlsTarget.SEEK_BAR)
            }
            KeyAction.CommitScrub -> {
                c.scrubMs?.let(vm::seekTo)
                c.scrubMs = null
                c.refresh++
            }
            KeyAction.Back -> handleBack()
            KeyAction.PlayAndShow -> {
                vm.play()
                showIfHidden(ControlsTarget.PLAY)
            }
            KeyAction.PauseAndShow -> {
                vm.pause()
                showIfHidden(ControlsTarget.PLAY)
            }
            KeyAction.None -> Unit
        }
    }

    fun onKey(n: NativeKeyEvent): Boolean {
        val down = when (n.action) {
            NativeKeyEvent.ACTION_DOWN -> true
            NativeKeyEvent.ACTION_UP -> false
            else -> return false
        }
        c.lastKeyAt = SystemClock.uptimeMillis()
        // A fresh press starts clean: an entry left by a press whose KeyUp never came here is stale.
        if (down && n.repeatCount == 0) c.consumedDowns.remove(n.keyCode)
        // The up-next card owns the arrows and OK like an open overlay (its buttons take them);
        // media keys and Back still go through.
        val overlayForKeys = if (upNextShownNow && c.overlay == Overlay.NONE) Overlay.CONTROLS else c.overlay
        val action = mapKey(
            n.keyCode, down, n.repeatCount, overlayForKeys,
            seekBarFocused = c.seekBarFocused && c.overlay == Overlay.CONTROLS,
            scrubbing = c.scrubMs != null,
        )
        if (action == KeyAction.None) return !down && c.consumedDowns.remove(n.keyCode)
        perform(action)
        // After perform: an overlay change clears the set, but this press's own KeyUp must still be eaten.
        if (down) c.consumedDowns += n.keyCode else c.consumedDowns -= n.keyCode
        return true
    }

    // Android 16+ (predictive back) does not deliver KEYCODE_BACK as a key event; this covers it.
    // Where the key event does arrive, onKey consumes its KeyUp and this never fires.
    BackHandler { handleBack() }

    // The view model holds a due up-next card back while a panel is open.
    LaunchedEffect(c.overlay.isPanel) { vm.setPanelOpen(c.overlay.isPanel) }
    // The card takes over when it appears (the controls, if open) and is dismissed by Back.
    LaunchedEffect(upNextShown) {
        if (upNextShown) {
            c.scrubMs = null
            if (c.overlay != Overlay.NONE) c.hide()
        }
    }
    // Nothing open: focus the card when it is up, else the root (so keys keep arriving).
    LaunchedEffect(c.overlay, upNextShown) {
        if (c.overlay != Overlay.NONE) return@LaunchedEffect
        if (upNextShown) playNow.requestFocusSoon() else root.requestFocusSoon()
    }
    // Auto-hide: only the bare controls, only while playing, never mid-scrub or under the card.
    LaunchedEffect(c.overlay, playing, scrubbing, upNextShown) {
        if (c.overlay != Overlay.CONTROLS || !playing || scrubbing || upNextShown) return@LaunchedEffect
        c.lastKeyAt = SystemClock.uptimeMillis()
        while (true) {
            val wait = c.lastKeyAt + CONTROLS_HIDE_MS - SystemClock.uptimeMillis()
            if (wait <= 0) {
                c.hide()
                break
            }
            delay(wait)
        }
    }

    val overlay = c.overlay
    val panel = panelFraction(overlay)

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { onKey(it.nativeKeyEvent) }
            .focusRequester(root)
            .focusProperties { canFocus = c.overlay == Overlay.NONE }
            .focusable(),
    ) {
        // Dim the picture behind a side panel (mockup moment 2).
        val scrim = animateFloatAsState(if (panel != null) 0.4f else 0f, tween(200), label = "scrim")
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = scrim.value)) })

        val cover = when (overlay) {
            Overlay.CONTROLS -> c.controlsCoverPx
            Overlay.EPISODES -> c.stripCoverPx
            else -> 0
        }
        CueLayer(
            vm, state.style, state.subtitle, videoSize, containerW, containerH,
            coverPx = cover,
            preview = overlay == Overlay.SUBTITLE_AUDIO,
            leftInsetFraction = panel ?: 0f,
        )

        if (buffering) {
            BusySpinner(Modifier.align(Alignment.Center), size = 48.dp, stroke = 4.dp, color = Color.White)
        }

        AnimatedVisibility(
            visible = overlay == Overlay.CONTROLS,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(250)),
        ) {
            ControlsOverlay(
                state, engine, c, playing, containerH,
                onTogglePlay = vm::togglePlay,
                onSeekBy = { d ->
                    vm.seekBy(d)
                    c.refresh++
                },
                onOpen = { o -> c.show(o) },
                onNext = vm::playNext,
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.SUBTITLE_AUDIO,
            modifier = Modifier.align(AbsoluteAlignment.TopLeft),
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { -it / 8 },
            exit = fadeOut(tween(150)),
        ) {
            SubtitleAudioPanel(state, vm, c.focusToken.takeIf { overlay == Overlay.SUBTITLE_AUDIO })
        }
        AnimatedVisibility(
            visible = overlay == Overlay.QUALITY,
            modifier = Modifier.align(AbsoluteAlignment.TopLeft),
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { -it / 8 },
            exit = fadeOut(tween(150)),
        ) {
            QualityPanel(state, c.focusToken.takeIf { overlay == Overlay.QUALITY }, onPick = { h ->
                vm.setQuality(h)
                c.show(Overlay.CONTROLS, ControlsTarget.QUALITY)
            })
        }
        AnimatedVisibility(
            visible = overlay == Overlay.SPEED,
            modifier = Modifier.align(AbsoluteAlignment.TopLeft),
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { -it / 8 },
            exit = fadeOut(tween(150)),
        ) {
            SpeedPanel(state.speed, c.focusToken.takeIf { overlay == Overlay.SPEED }, onPick = { s ->
                vm.setSpeed(s)
                c.show(Overlay.CONTROLS, ControlsTarget.SPEED)
            })
        }
        AnimatedVisibility(
            visible = overlay == Overlay.EPISODES,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 6 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 6 },
        ) {
            EpisodesStrip(
                state, c.focusToken.takeIf { overlay == Overlay.EPISODES }, containerH,
                onCoverPx = { c.stripCoverPx = it },
                onPick = { id ->
                    if (id == state.currentStreamId) c.show(Overlay.CONTROLS, ControlsTarget.EPISODES) else vm.playEpisode(id)
                },
                onSeason = vm::showSeason,
            )
        }

        AnimatedVisibility(
            visible = upNext != null,
            modifier = Modifier.align(AbsoluteAlignment.BottomLeft),
            enter = fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -it / 4 },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 4 },
        ) {
            // Keep the last card while it fades out (a plain holder: no state write during composition).
            val last = remember { arrayOfNulls<UpNext>(1) }
            if (upNext != null) last[0] = upNext
            last[0]?.let { card ->
                UpNextCard(
                    card, state.backdropUrl, playNow,
                    onPlay = vm::playNext,
                    onCredits = vm::dismissUpNext,
                    modifier = Modifier.absolutePadding(left = 48.dp, bottom = 48.dp),
                )
            }
        }
    }
}

/**
 * Reads [PlayerViewModel.cues] itself, so a cue change recomposes only this. [coverPx] (the visible
 * bottom block) lifts the subtitles, animated; [leftInsetFraction] centres them beside a side panel.
 */
@Composable
private fun CueLayer(
    vm: PlayerViewModel,
    style: SubtitleStyle,
    choice: SubtitleChoice,
    videoSize: VideoSize?,
    containerW: Int,
    containerH: Int,
    coverPx: Int,
    preview: Boolean,
    leftInsetFraction: Float,
) {
    val cues by vm.cues.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val cover by animateIntAsState(coverPx, tween(200), label = "subtitleLift")
    val lineHeightPx = with(density) {
        (subtitleFontPx(containerH, style.sizePercent) * SUBTITLE_LINE_HEIGHT_EM + 2 * SUBTITLE_BOX_PADDING_V.toPx()).roundToInt()
    }
    val placement = subtitlePlacement(
        containerW, containerH, videoSize, lineHeightPx,
        dual = choice == SubtitleChoice.DUAL,
        controlsHeightPx = cover.takeIf { it > 0 },
    )
    val inset = with(density) { (containerW * leftInsetFraction).toDp() }
    Box(Modifier.fillMaxSize().absolutePadding(left = inset)) {
        SubtitleLayer(cues, style, placement, preview = preview, choice = choice)
    }
}
