package com.alikarami.drooz.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor

/** A small indeterminate spinner (tv-material has none), in the surrounding content color by default. */
@Composable
fun BusySpinner(modifier: Modifier = Modifier, size: Dp = 18.dp, stroke: Dp = 2.5.dp, color: Color = LocalContentColor.current) {
    val angle by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spinnerAngle",
    )
    Canvas(modifier.size(size)) {
        val w = stroke.toPx()
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(w))
        drawArc(color, angle, 100f, false, style = Stroke(w, cap = StrokeCap.Round))
    }
}
