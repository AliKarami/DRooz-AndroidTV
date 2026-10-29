package com.alikarami.drooz.player.ui

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.data.BACKGROUND_OPACITIES
import com.alikarami.drooz.data.SUBTITLE_SIZES
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleColor
import com.alikarami.drooz.player.PlayerUiState
import com.alikarami.drooz.player.PlayerViewModel
import com.alikarami.drooz.player.SPEEDS
import com.alikarami.drooz.player.SUBTITLE_STEP_MS
import com.alikarami.drooz.player.UpNext
import com.alikarami.drooz.ui.common.DroozImage
import com.alikarami.drooz.ui.common.ImageSizes
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.common.toPersianDigits
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.ui.theme.FocusScale
import com.alikarami.drooz.ui.theme.droozFocus
import com.alikarami.drooz.ui.theme.droozSurfaceColors
import com.alikarami.drooz.ui.theme.droozSurfaceScale
import kotlin.math.roundToInt

/** Side panels hug the left edge (mockup `.panel`); the subtitle preview centres in what is left. */
const val SUBTITLE_PANEL_FRACTION = 0.3f
const val SMALL_PANEL_FRACTION = 0.24f
private val PanelBackground = Color(0xF5121218) // rgba(18,18,24,.96)
private val RowShape = RoundedCornerShape(8.dp)

fun panelFraction(overlay: Overlay): Float? = when (overlay) {
    Overlay.SUBTITLE_AUDIO -> SUBTITLE_PANEL_FRACTION
    Overlay.QUALITY, Overlay.SPEED -> SMALL_PANEL_FRACTION
    else -> null
}

@Composable
private fun SidePanel(title: String, fraction: Float, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .fillMaxWidth(fraction)
            .background(PanelBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
    ) {
        Text(
            title, Modifier.padding(start = 12.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleLarge, color = Color.White,
        )
        content()
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text, Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = DroozColors.TextSecondary,
    )
}

/** Mockup `.it`: a row, white with black text when focused; ✓ marks the current choice. */
@Composable
private fun PanelOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = rowColors(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (selected) "✓" else "", Modifier.width(22.dp),
                color = DroozColors.Watched, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                label, Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1,
            )
            trailing?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
        }
    }
}

@Composable
private fun rowColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = Color.White,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
    pressedContainerColor = Color.White.copy(alpha = 0.85f),
    pressedContentColor = Color.Black,
)

private fun isOk(code: Int) =
    code == NativeKeyEvent.KEYCODE_DPAD_CENTER || code == NativeKeyEvent.KEYCODE_ENTER || code == NativeKeyEvent.KEYCODE_NUMPAD_ENTER

/**
 * A −/+ button that owns OK itself: every KeyDown (first press and each auto-repeat while held) applies
 * one step at once, the KeyUp is swallowed, so a hold never ends with an extra click.
 */
@Composable
private fun StepButton(symbol: String, enabled: Boolean, onStep: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = { if (enabled) onStep() },
        modifier = modifier
            .size(36.dp)
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent
                if (!isOk(n.keyCode)) return@onPreviewKeyEvent false
                if (n.action == NativeKeyEvent.ACTION_DOWN && enabled) onStep()
                true
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.15f),
            contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
            focusedContainerColor = Color.White,
            focusedContentColor = if (enabled) Color.Black else Color.Black.copy(alpha = 0.35f),
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(symbol, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** Label at the start; LTR «− value +» at the end (or centred when there is no label). */
@Composable
internal fun StepperRow(
    label: String?,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (Int) -> Unit,
    decreaseModifier: Modifier = Modifier,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (label == null) Arrangement.Center else Arrangement.Start,
    ) {
        if (label != null) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StepButton("−", canDecrease, { onStep(-1) }, decreaseModifier)
                Text(
                    value, Modifier.widthIn(min = 76.dp),
                    // Persian copy inside the LTR stepper: lay it out RTL (the number itself is isolated).
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, textDirection = TextDirection.Rtl),
                    color = Color.White, textAlign = TextAlign.Center,
                )
                StepButton("+", canIncrease, { onStep(1) })
            }
        }
    }
}

@Composable
internal fun subtitleChoiceLabel(c: SubtitleChoice): String = stringResource(
    when (c) {
        SubtitleChoice.FA -> R.string.player_sub_fa
        SubtitleChoice.EN -> R.string.player_sub_en
        SubtitleChoice.DUAL -> R.string.player_sub_dual
        SubtitleChoice.FA_DUB -> R.string.player_sub_fa_dub
        SubtitleChoice.OFF -> R.string.player_off
    }
)

// Each overlay takes a focus token that is null unless it is the current overlay: the token bumps on
// every change, and an overlay still fading out must not grab focus back from the one coming in.

/**
 * «زیرنویس و صدا»: everything applies at once, and the subtitle layer behind shows a live preview.
 * Opens on the current subtitle choice.
 */
@Composable
internal fun SubtitleAudioPanel(state: PlayerUiState, vm: PlayerViewModel, focusToken: Int?, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(focusToken) { if (focusToken != null) first.requestFocusSoon() }
    val available = state.availableSubtitles
    val focusChoice = state.subtitle.takeIf { it in available } ?: available.firstOrNull()

    SidePanel(stringResource(R.string.player_subtitle_audio), SUBTITLE_PANEL_FRACTION, modifier) {
        if (available.isNotEmpty()) {
            GroupHeader(stringResource(R.string.player_group_subtitle))
            available.forEach { c ->
                PanelOption(
                    subtitleChoiceLabel(c), c == state.subtitle, { vm.setSubtitle(c) },
                    if (c == focusChoice) Modifier.focusRequester(first) else Modifier,
                )
            }
        }

        GroupHeader(stringResource(R.string.player_group_appearance))
        val style = state.style
        val sizes = remember { SUBTITLE_SIZES.toList() }
        val sizeIndex = sizes.indexOf(style.sizePercent).takeIf { it >= 0 }
            ?: sizes.indexOfFirst { it >= style.sizePercent }.coerceAtLeast(0)
        StepperRow(
            stringResource(R.string.player_size), UiStrings.percent(style.sizePercent),
            canDecrease = sizeIndex > 0, canIncrease = sizeIndex < sizes.lastIndex,
            onStep = { d ->
                // Read the latest state: a held key steps several times before a recomposition.
                val current = vm.state.value.style
                val i = sizes.indexOf(current.sizePercent).takeIf { it >= 0 } ?: sizeIndex
                sizes.getOrNull(i + d)?.let { vm.setStyle(current.copy(sizePercent = it)) }
            },
            decreaseModifier = if (focusChoice == null) Modifier.focusRequester(first) else Modifier,
        )
        ColorRow(style.color) { vm.setStyle(vm.state.value.style.copy(color = it)) }
        PanelOption(
            stringResource(R.string.player_background), false,
            {
                val current = vm.state.value.style
                val i = BACKGROUND_OPACITIES.indexOf(current.backgroundOpacity)
                vm.setStyle(current.copy(backgroundOpacity = BACKGROUND_OPACITIES[(i + 1).mod(BACKGROUND_OPACITIES.size)]))
            },
            trailing = UiStrings.percent(style.backgroundOpacity),
        )

        GroupHeader(stringResource(R.string.player_group_sync))
        StepperRow(
            null, UiStrings.syncOffset(state.subtitleOffsetMs),
            canDecrease = true, canIncrease = true,
            onStep = { d -> vm.shiftSubtitles(d * SUBTITLE_STEP_MS) },
        )

        GroupHeader(stringResource(R.string.player_group_audio))
        state.audio.forEachIndexed { i, a ->
            PanelOption(UiStrings.audioLabel(a.label), i == state.selectedAudio, { vm.setAudio(i) })
        }
        PanelOption(
            stringResource(R.string.player_boost), false, { vm.setBoost(!vm.state.value.boost) },
            trailing = stringResource(if (state.boost) R.string.player_on else R.string.player_off),
        )
    }
}

@Composable
internal fun ColorRow(selected: SubtitleColor, onPick: (SubtitleColor) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.player_color), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SubtitleColor.entries.forEach { c ->
                val isSelected = c == selected
                Surface(
                    onClick = { onPick(c) },
                    modifier = Modifier.size(30.dp),
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.25f),
                    border = ClickableSurfaceDefaults.border(
                        border = Border(BorderStroke(if (isSelected) 3.dp else 1.5.dp, if (isSelected) Color.White else Color(0xFF555555)), shape = CircleShape),
                        focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
                    ),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color(c.argb),
                        focusedContainerColor = Color(c.argb),
                        pressedContainerColor = Color(c.argb),
                    ),
                ) {
                    if (isSelected) {
                        Text(
                            "✓", Modifier.align(Alignment.Center), color = Color.Black,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                }
            }
        }
    }
}

/** «کیفیت»: Automatic (with the height playing now) + the stream's qualities. A pick closes the panel. */
@Composable
internal fun QualityPanel(state: PlayerUiState, focusToken: Int?, onPick: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val current = remember { FocusRequester() }
    LaunchedEffect(focusToken) { if (focusToken != null) current.requestFocusSoon() }
    val selectedPresent = state.selectedQuality != null && state.qualities.any { it.height == state.selectedQuality }
    SidePanel(stringResource(R.string.player_quality), SMALL_PANEL_FRACTION, modifier) {
        PanelOption(
            UiStrings.qualityAuto(state.playingHeight), state.selectedQuality == null, { onPick(null) },
            if (!selectedPresent) Modifier.focusRequester(current) else Modifier,
        )
        state.qualities.forEach { q ->
            val sel = q.height == state.selectedQuality
            PanelOption(q.label, sel, { onPick(q.height) }, if (sel) Modifier.focusRequester(current) else Modifier)
        }
    }
}

/** «سرعت»: [SPEEDS]. A pick closes the panel. */
@Composable
internal fun SpeedPanel(speed: Float, focusToken: Int?, onPick: (Float) -> Unit, modifier: Modifier = Modifier) {
    val current = remember { FocusRequester() }
    LaunchedEffect(focusToken) { if (focusToken != null) current.requestFocusSoon() }
    val focusSpeed = SPEEDS.firstOrNull { it == speed } ?: 1f
    SidePanel(stringResource(R.string.player_speed), SMALL_PANEL_FRACTION, modifier) {
        SPEEDS.forEach { s ->
            PanelOption(
                UiStrings.speed(s), s == speed, { onPick(s) },
                if (s == focusSpeed) Modifier.focusRequester(current) else Modifier,
            )
        }
    }
}

/**
 * «قسمت‌ها»: this season's episodes in a row over the bottom; opens on the one playing. Reports its
 * height above the screen edge (subtitles lift above it).
 */
@Composable
internal fun EpisodesStrip(
    state: PlayerUiState,
    focusToken: Int?,
    containerH: Int,
    onCoverPx: (Int) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val episodes = state.seasonEpisodes
    val currentIndex = episodes.indexOfFirst { it.streamId == state.currentStreamId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 1).coerceAtLeast(0))
    val current = remember { FocusRequester() }
    LaunchedEffect(focusToken) { if (focusToken != null) current.requestFocusSoon() }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(top = 72.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onCoverPx(containerH - it.positionInRoot().y.roundToInt()) }
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.player_episodes), Modifier.padding(start = 48.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(episodes, key = { _, e -> e.streamId }) { i, e ->
                    val isCurrent = e.streamId == state.currentStreamId
                    Surface(
                        onClick = { onPick(e.streamId) },
                        modifier = Modifier
                            .then(if (i == currentIndex) Modifier.focusRequester(current) else Modifier)
                            .droozFocus(FocusScale.CARD)
                            .width(210.dp)
                            .height(84.dp),
                        shape = ClickableSurfaceDefaults.shape(DroozCardShape),
                        scale = droozSurfaceScale,
                        colors = droozSurfaceColors(),
                    ) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(e.label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (isCurrent) {
                                    Text(
                                        stringResource(R.string.player_now_playing),
                                        style = MaterialTheme.typography.bodySmall, color = DroozColors.Accent,
                                    )
                                } else if (e.watched) {
                                    Text(
                                        "✓", color = DroozColors.Watched,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    )
                                }
                            }
                        }
                        if (isCurrent) {
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                                    .background(DroozColors.Accent),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mockup moment 3: bottom-left card with the countdown, «▶ پخش الان» (focused) and «تماشای تیتراژ».
 * [playNow] is focused by the host whenever the card is up and nothing else is open.
 */
@Composable
internal fun UpNextCard(
    upNext: UpNext,
    backdropUrl: String?,
    playNow: FocusRequester,
    onPlay: () -> Unit,
    onCredits: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            // 24 % of the screen per the mockup, but never so narrow that the two buttons squeeze.
            .widthIn(min = 300.dp)
            .fillMaxWidth(0.24f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF214141A)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (backdropUrl != null) {
                DroozImage(backdropUrl, ImageSizes.CARD_W, ImageSizes.CARD_H, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            Text(
                upNext.secondsLeft.toString().toPersianDigits(), Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displayLarge, color = Color.White,
            )
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(upNext.label, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
            Text(UiStrings.autoPlayIn(upNext.secondsLeft), style = MaterialTheme.typography.bodySmall, color = DroozColors.TextSecondary)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerPill(stringResource(R.string.player_play_now), onPlay, Modifier.weight(1f).focusRequester(playNow), padding = 8.dp, fillWidth = true)
            PlayerPill(stringResource(R.string.player_watch_credits), onCredits, Modifier.weight(1f), padding = 8.dp, fillWidth = true)
        }
    }
}
