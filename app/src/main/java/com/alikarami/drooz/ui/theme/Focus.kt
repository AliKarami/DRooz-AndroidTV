package com.alikarami.drooz.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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

private const val FOCUS_MS = 150

/** Focus treatment: scale up, 3dp white outline and soft shadow, animated over 150 ms. */
fun Modifier.droozFocus(scale: Float = 1.08f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, tween(FOCUS_MS), label = "focusScale")
    val a by animateFloatAsState(if (focused) 1f else 0f, tween(FOCUS_MS), label = "focusAlpha")
    val shape = RoundedCornerShape(10.dp)
    this
        .onFocusChanged { focused = it.hasFocus }
        .scale(s)
        .shadow((16 * a).dp, shape, clip = false)
        .border(3.dp, Color.White.copy(alpha = a), shape)
}
