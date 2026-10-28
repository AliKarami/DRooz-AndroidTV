package com.alikarami.drooz.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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

/** Focus treatment: scale up, 3dp white outline and soft shadow, animated over 150 ms. */
fun Modifier.droozFocus(scale: Float = FocusScale.CARD): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, tween(FOCUS_MS), label = "focusScale")
    val a by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS), label = "focusAlpha")
    val shape = DroozCardShape
    this
        .onFocusChanged { focused = it.hasFocus }
        .scale(s)
        .shadow((16 * a).dp, shape, clip = false)
        .border(3.dp, Color.White.copy(alpha = a), shape)
}
