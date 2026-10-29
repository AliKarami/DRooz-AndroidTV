package com.alikarami.drooz.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** The title page's button / chip corner shape. */
val PillShape = RoundedCornerShape(8.dp)

/** Mockup `.btn`: translucent pill, white with black text when focused. */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.85f),
            pressedContentColor = Color.Black,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text, Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, softWrap = false,
            )
        }
    }
}

/** Mockup `.seg`: two segments in one focus stop; [secondOn] marks [second] as on, a click toggles. */
@Composable
fun SegmentSwitch(first: String, second: String, secondOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onToggle,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = PillShape)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.28f),
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(3.dp)) {
            Segment(first, !secondOn)
            Segment(second, secondOn)
        }
    }
}

@Composable
private fun Segment(text: String, on: Boolean) {
    Text(
        text,
        Modifier
            .background(if (on) Color.White.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = if (on) Color.Black else Color.White,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
        maxLines = 1, softWrap = false,
    )
}
