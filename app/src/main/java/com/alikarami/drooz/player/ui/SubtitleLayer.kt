package com.alikarami.drooz.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.data.SubtitleChoice
import com.alikarami.drooz.data.SubtitleStyle
import com.alikarami.drooz.player.SubtitleCues
import com.alikarami.drooz.player.engine.VideoSize
import com.alikarami.drooz.ui.theme.Vazirmatn
import kotlin.math.max
import kotlin.math.min

/** At 100 % the subtitle font is 4.5 % of the screen height. */
const val SUBTITLE_BASE_FRACTION = 0.045f

/** Above the picture's bottom edge when the bars are too thin (or there are none). */
private const val NO_BAR_FRACTION = 0.06f

/** Line box height relative to the font size (Vazirmatn's ascent + descent ≈ 1.4 em). */
const val SUBTITLE_LINE_HEIGHT_EM = 1.4f

/** Vertical padding of the background box, each side. */
val SUBTITLE_BOX_PADDING_V: Dp = 2.dp
private val SUBTITLE_BOX_PADDING_H: Dp = 10.dp
private val SUBTITLE_BOX_SHAPE = RoundedCornerShape(6.dp)

/** Distances (px) from the screen's bottom edge to each text block's bottom. Secondary = English in DUAL. */
data class SubtitlePlacement(val primaryBottomPx: Int, val secondaryBottomPx: Int?)

/** Subtitle font size in px for a container height and a [SubtitleStyle.sizePercent]. */
fun subtitleFontPx(containerH: Int, sizePercent: Int): Float = containerH * SUBTITLE_BASE_FRACTION * sizePercent / 100f

/**
 * Letterbox-aware placement. A bottom bar at least 1.5 lines tall holds the single subtitle (centred
 * in it) or, in DUAL, the English line, with Farsi just inside the picture. Otherwise text sits 6 % up
 * (DUAL: English there, Farsi two lines plus a margin above it). Visible controls lift everything so the
 * lowest block clears them by a margin, spacing kept.
 */
fun subtitlePlacement(
    containerW: Int, containerH: Int, video: VideoSize?, lineHeightPx: Int,
    dual: Boolean, controlsHeightPx: Int?,
): SubtitlePlacement {
    val videoH = if (video == null || video.width <= 0 || video.height <= 0) {
        containerH
    } else {
        min(containerH.toLong(), containerW.toLong() * video.height / video.width).toInt()
    }
    val bar = (containerH - videoH) / 2
    val margin = lineHeightPx / 2

    var primary: Int
    var secondary: Int?
    if (bar * 2 >= lineHeightPx * 3) {
        val inBar = (bar - lineHeightPx) / 2
        if (dual) {
            secondary = inBar
            primary = bar + margin
        } else {
            secondary = null
            primary = inBar
        }
    } else {
        val base = (containerH * NO_BAR_FRACTION).toInt()
        if (dual) {
            secondary = base
            primary = base + 2 * lineHeightPx + margin
        } else {
            secondary = null
            primary = base
        }
    }

    if (controlsHeightPx != null) {
        val lowest = secondary?.let { min(it, primary) } ?: primary
        val lift = max(0, controlsHeightPx + margin - lowest)
        primary += lift
        secondary = secondary?.plus(lift)
    }
    return SubtitlePlacement(primary, secondary)
}

/**
 * The app's own subtitle renderer (ExoPlayer's text track is off). Fills its parent, which must be the
 * full screen: sizes and [placement] are relative to it. [preview] shows a sample line when no cue is
 * on screen (the style panel's live preview), following [choice]: EN → the English sample, DUAL → Persian
 * plus English, FA / FA_DUB → Persian, OFF → nothing.
 */
@Composable
fun SubtitleLayer(
    cues: SubtitleCues,
    style: SubtitleStyle,
    placement: SubtitlePlacement,
    preview: Boolean,
    choice: SubtitleChoice = SubtitleChoice.FA,
) {
    val sampleFa = stringResource(R.string.player_subtitle_sample)
    val sampleEn = stringResource(R.string.player_subtitle_sample_en)
    val showSample = preview && cues.primary == null && cues.secondary == null
    val primary = cues.primary ?: if (showSample) {
        when (choice) {
            SubtitleChoice.EN -> sampleEn
            SubtitleChoice.OFF -> null
            else -> sampleFa
        }
    } else {
        null
    }
    val secondary = cues.secondary ?: if (showSample && choice == SubtitleChoice.DUAL) sampleEn else null

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerH = constraints.maxHeight
        val textStyle = with(density) {
            val fontPx = subtitleFontPx(containerH, style.sizePercent)
            TextStyle(
                fontFamily = Vazirmatn,
                fontWeight = FontWeight.Medium,
                fontSize = fontPx.toSp(),
                lineHeight = (fontPx * SUBTITLE_LINE_HEIGHT_EM).toSp(),
                color = Color(style.color.argb),
                shadow = Shadow(Color.Black, blurRadius = 6f),
                textAlign = TextAlign.Center,
                textDirection = TextDirection.Content,
                // The rendered line box must match the lineHeightPx subtitlePlacement was given.
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            )
        }
        val box = Color.Black.copy(alpha = style.backgroundOpacity / 100f)
        val maxW = maxWidth * 0.8f

        @Composable
        fun Line(text: String, bottomPx: Int) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { bottomPx.toDp() })
                    .widthIn(max = maxW)
                    .background(box, SUBTITLE_BOX_SHAPE)
                    .padding(horizontal = SUBTITLE_BOX_PADDING_H, vertical = SUBTITLE_BOX_PADDING_V),
            ) {
                Text(text, style = textStyle)
            }
        }

        primary?.let { Line(it, placement.primaryBottomPx) }
        val secondaryBottom = placement.secondaryBottomPx
        if (secondary != null) Line(secondary, secondaryBottom ?: placement.primaryBottomPx)
    }
}
