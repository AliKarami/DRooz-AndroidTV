package com.alikarami.drooz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.ui.theme.DroozColors
import java.util.Locale

/** Secondary text on top of a backdrop (meta lines, chips). */
val MetaTextColor = Color(0xFFCFD2DA)

/** "IMDb 7.9": a Latin label, so Latin digits (the rest of a meta line uses Persian digits). */
fun imdbLabel(score: Double) = "IMDb " + String.format(Locale.US, "%.1f", score)

/** Yellow IMDb badge with black, Latin-digit text. */
@Composable
fun ImdbBadge(score: Double, style: TextStyle = MaterialTheme.typography.labelLarge) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            imdbLabel(score),
            Modifier.background(DroozColors.ImdbBadge, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
            color = Color.Black, fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false,
            style = style.copy(fontFamily = FontFamily.SansSerif),
        )
    }
}

/** One plain meta item; never wraps. */
@Composable
fun MetaText(text: String, style: TextStyle = MaterialTheme.typography.labelLarge, color: Color = MetaTextColor) {
    Text(text, color = color, style = style, maxLines = 1, softWrap = false)
}

/** Outlined chip («دوبله», «4K»); never wraps. */
@Composable
fun MetaChip(text: String, style: TextStyle = MaterialTheme.typography.labelMedium) {
    Text(
        text,
        Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
        color = MetaTextColor, style = style, maxLines = 1, softWrap = false,
    )
}

/**
 * A single line of items laid out from the start edge with [gap] between them. Items that don't fit
 * are dropped from the end (never wrapped or clipped mid-item), so put the least important ones last.
 */
@Composable
fun FitRow(modifier: Modifier = Modifier, gap: Dp = 12.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(loose) }
        val kept = buildList {
            var used = 0
            for (p in placeables) {
                val next = used + (if (isEmpty()) 0 else gapPx) + p.width
                if (next > constraints.maxWidth) break
                add(p)
                used = next
            }
        }
        val width = (kept.sumOf { it.width } + gapPx * (kept.size - 1).coerceAtLeast(0))
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (kept.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            kept.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gapPx
            }
        }
    }
}
