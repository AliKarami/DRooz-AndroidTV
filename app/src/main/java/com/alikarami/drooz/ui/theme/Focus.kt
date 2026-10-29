package com.alikarami.drooz.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale

private const val FOCUS_MS = 150

/** Focus scales; the single source of truth (Surface's own scale is disabled, see [droozSurfaceScale]). */
object FocusScale {
    const val CARD = 1.08f
    const val TILE = 1.1f
    /** Wide controls (the search field): 1.08 on a 500dp field would swallow its neighbours. */
    const val FIELD = 1.03f
}

/** One corner shape for cards, tiles and their focus ring. */
val DroozCardShape = RoundedCornerShape(10.dp)

/** tv-material's Surface scales 1.1x on focus by default; droozFocus owns scaling, so turn it off. */
val droozSurfaceScale: ClickableSurfaceScale = ClickableSurfaceScale.None

/** Focus must not recolour the surface; the ring is drawn by droozFocus. (Surface border/glow default to none.) */
@Composable
fun droozSurfaceColors(): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
    containerColor = DroozColors.Surface,
    focusedContainerColor = DroozColors.Surface,
    pressedContainerColor = DroozColors.Surface,
)

/**
 * Focus treatment: scale up, 3dp white outline and soft shadow, animated over 150 ms.
 * The animated values are read only in the layer and draw phases, so a focus move re-draws the two
 * cards involved instead of recomposing them on every animation frame (measured: ~50 ms frames on TV).
 */
fun Modifier.droozFocus(scale: Float = FocusScale.CARD): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s = animateFloatAsState(if (focused) scale else 1f, tween(FOCUS_MS), label = "focusScale")
    val a = animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS), label = "focusAlpha")
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = s.value
            scaleY = s.value
            shadowElevation = 16.dp.toPx() * a.value
            shape = DroozCardShape
            clip = false
        }
        .drawWithContent {
            drawContent()
            val alpha = a.value
            if (alpha > 0f) {
                val stroke = 3.dp.toPx()
                val outline = DroozCardShape.createOutline(
                    Size(size.width - stroke, size.height - stroke), layoutDirection, this,
                )
                translate(stroke / 2, stroke / 2) {
                    drawOutline(outline, Color.White.copy(alpha = alpha), style = Stroke(stroke))
                }
            }
        }
}
