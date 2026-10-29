package com.alikarami.drooz.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.Text

/** "shaun-the-sheep" -> "Shaun The Sheep". */
fun prettifySlug(slug: String): String =
    slug.split('-').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

fun String.toPersianDigits(): String = map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

fun episodeLabel(season: Int, episode: Int): String = "فصل $season · قسمت $episode".toPersianDigits()

private fun String.isLatinTitle(): Boolean =
    any { it.isLetter() } && none { it.code in 0x0600..0x06FF }

/**
 * Titles are mostly Latin: those render left-to-right in the system sans; Persian text keeps
 * the theme's Vazirmatn in the surrounding RTL direction.
 */
@Composable
fun TitleText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 2,
) {
    if (text.isLatinTitle()) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
                style = style.copy(fontFamily = FontFamily.SansSerif),
            )
        }
    } else {
        Text(text, modifier, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = style)
    }
}
