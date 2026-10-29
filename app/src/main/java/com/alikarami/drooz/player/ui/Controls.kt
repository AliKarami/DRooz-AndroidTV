package com.alikarami.drooz.player.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.player.PlayerUiState
import com.alikarami.drooz.player.engine.PlaybackEngine
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.formatClock
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.theme.DroozColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Which control takes focus when the controls (re)appear. */
enum class ControlsTarget { PLAY, SEEK_BAR, SUBTITLE_AUDIO, QUALITY, SPEED, EPISODES }

/** The seek bar reads the engine this often while it is on screen (and right after each seek). */
private const val SEEK_POLL_MS = 250L

private val PillShape = RoundedCornerShape(50)
private val PillHeight = 44.dp

/**
 * Top: title + episode. Bottom: the LTR seek bar, then the buttons (LTR −10 / ⏯ / +10 cluster first,
 * then the panel openers). The bottom block's height above the screen edge goes to
 * [OverlayController.controlsCoverPx] so the subtitles can lift above it.
 */
@Composable
internal fun ControlsOverlay(
    state: PlayerUiState,
    engine: PlaybackEngine,
    controller: OverlayController,
    playing: Boolean,
    containerH: Int,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onOpen: (Overlay) -> Unit,
    onNext: () -> Unit,
) {
    val requesters = remember { ControlsTarget.entries.associateWith { FocusRequester() } }
    val hasEpisodes = state.seasonEpisodes.isNotEmpty()
    LaunchedEffect(controller.focusToken) {
        // The token also bumps when a panel opens while this fades out: only take focus when current.
        if (controller.overlay != Overlay.CONTROLS) return@LaunchedEffect
        val target = controller.controlsTarget.takeIf { it != ControlsTarget.EPISODES || hasEpisodes } ?: ControlsTarget.PLAY
        requesters.getValue(target).requestFocusSoon()
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 64.dp),
        ) {
            if (state.title.isNotEmpty()) {
                TitleText(state.title, MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            state.episodeLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFCCCCCC))
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                .padding(top = 96.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { controller.controlsCoverPx = containerH - it.positionInRoot().y.roundToInt() }
                    .padding(start = 48.dp, end = 48.dp, bottom = 28.dp),
            ) {
                SeekRow(
                    engine, controller, requesters.getValue(ControlsTarget.SEEK_BAR),
                    down = requesters.getValue(ControlsTarget.PLAY),
                )
                ButtonsRow(state, playing, requesters, onTogglePlay, onSeekBy, onOpen, onNext)
            }
        }
    }
}

@Composable
private fun ButtonsRow(
    state: PlayerUiState,
    playing: Boolean,
    requesters: Map<ControlsTarget, FocusRequester>,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onOpen: (Overlay) -> Unit,
    onNext: () -> Unit,
) {
    // Centred when it fits; scrolls (the focused pill brings itself into view) if a long row would not,
    // so no button is ever clipped out of reach.
    Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Physical right = forward, as on every TV app (designer's call against the RTL layout).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlayerPill(stringResource(R.string.player_seek_back), { onSeekBy(-SEEK_STEP_MS) })
                PlayerPill(onTogglePlay, Modifier.focusRequester(requesters.getValue(ControlsTarget.PLAY))) {
                    PlayPauseIcon(playing, Modifier.padding(horizontal = 20.dp).size(18.dp))
                }
                PlayerPill(stringResource(R.string.player_seek_forward), { onSeekBy(SEEK_STEP_MS) })
            }
        }
        PlayerPill(
            stringResource(R.string.player_subtitle_audio), { onOpen(Overlay.SUBTITLE_AUDIO) },
            Modifier.focusRequester(requesters.getValue(ControlsTarget.SUBTITLE_AUDIO)),
        )
        PlayerPill(
            UiStrings.qualityButton(state.selectedQuality), { onOpen(Overlay.QUALITY) },
            Modifier.focusRequester(requesters.getValue(ControlsTarget.QUALITY)),
        )
        PlayerPill(
            UiStrings.speedButton(state.speed), { onOpen(Overlay.SPEED) },
            Modifier.focusRequester(requesters.getValue(ControlsTarget.SPEED)),
        )
        if (state.seasonEpisodes.isNotEmpty()) {
            PlayerPill(
                stringResource(R.string.player_episodes), { onOpen(Overlay.EPISODES) },
                Modifier.focusRequester(requesters.getValue(ControlsTarget.EPISODES)),
            )
        }
        if (state.hasNext) {
            PlayerPill(stringResource(R.string.player_next_episode), onNext)
        }
    }
    }
}

/**
 * Elapsed · track · remaining, always LTR. The engine is polled here (not higher up) so a tick redraws
 * this row only: the track reads position in the draw phase, the clocks change once a second.
 * While scrubbing the knob sits at the scrub head with its time above it.
 */
@Composable
private fun SeekRow(engine: PlaybackEngine, controller: OverlayController, requester: FocusRequester, down: FocusRequester) {
    var pos by remember { mutableLongStateOf(engine.positionMs) }
    var buffered by remember { mutableLongStateOf(engine.bufferedMs) }
    var duration by remember { mutableLongStateOf(engine.durationMs) }
    LaunchedEffect(controller.refresh) {
        while (true) {
            pos = engine.positionMs
            buffered = engine.bufferedMs
            duration = engine.durationMs
            delay(SEEK_POLL_MS)
        }
    }
    var focused by remember { mutableStateOf(false) }
    val emphasis = animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "seekFocus")
    val elapsed by remember { derivedStateOf { formatClock(controller.scrubMs ?: pos) } }
    val remaining by remember {
        derivedStateOf { if (duration > 0) "-" + formatClock(duration - (controller.scrubMs ?: pos)) else "" }
    }
    val scrubLabel by remember { derivedStateOf { controller.scrubMs?.let(::formatClock) } }
    fun fraction(ms: Long): Float = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .focusProperties { this.down = down }
                .onFocusChanged {
                    focused = it.isFocused
                    controller.seekBarFocused = it.isFocused
                }
                .focusable(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val clockStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default)
            Text(elapsed, style = clockStyle, color = Color.White, modifier = Modifier.widthIn(min = 64.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .drawBehind {
                        val e = emphasis.value
                        val h = (4 + 2 * e).dp.toPx()
                        val top = size.height / 2 - h / 2
                        val w = size.width
                        val r = CornerRadius(h / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(w, h), r)
                        drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(0f, top), Size(w * fraction(buffered), h), r)
                        drawRoundRect(DroozColors.Accent, Offset(0f, top), Size(w * fraction(pos), h), r)
                        val x = w * fraction(controller.scrubMs ?: pos)
                        val knob = (6 + 3 * e).dp.toPx()
                        val c = Offset(x, size.height / 2)
                        drawCircle(Color.White.copy(alpha = 0.9f), knob + 2.5.dp.toPx(), c)
                        drawCircle(DroozColors.Accent, knob, c)
                    },
            ) {
                scrubLabel?.let { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold),
                        color = Color.Black,
                        modifier = Modifier
                            .layout { measurable, constraints ->
                                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                                layout(constraints.maxWidth, 0) {
                                    val x = (fraction(controller.scrubMs ?: pos) * constraints.maxWidth - p.width / 2f)
                                        .roundToInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0))
                                    p.place(x, -p.height - 6.dp.roundToPx())
                                }
                            }
                            .background(Color.White, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Text(remaining, style = clockStyle, color = Color.White, modifier = Modifier.widthIn(min = 64.dp))
        }
    }
}

/** Mockup `.b`: translucent pill, white with black content when focused (as the title page's buttons). */
@Composable
internal fun PlayerPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(PillHeight),
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.85f),
            pressedContentColor = Color.Black,
        ),
    ) {
        Box(
            Modifier.fillMaxHeight().then(if (fillWidth) Modifier.fillMaxWidth() else Modifier),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
internal fun PlayerPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    padding: Dp = 14.dp,
    fillWidth: Boolean = false,
) {
    PlayerPill(onClick, modifier, fillWidth) {
        Text(
            text, Modifier.padding(horizontal = padding),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            maxLines = 1, softWrap = false,
        )
    }
}

/** Drawn, not a glyph: ❚❚ / ▶ are missing from Vazirmatn and vary across TV fonts. */
@Composable
private fun PlayPauseIcon(playing: Boolean, modifier: Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val bar = w * 0.32f
            val r = CornerRadius(bar * 0.25f)
            drawRoundRect(color, Offset(w * 0.08f, 0f), Size(bar, h), r)
            drawRoundRect(color, Offset(w * 0.92f - bar, 0f), Size(bar, h), r)
        } else {
            val path = Path().apply {
                moveTo(w * 0.15f, 0f)
                lineTo(w, h / 2)
                lineTo(w * 0.15f, h)
                close()
            }
            drawPath(path, color)
        }
    }
}
