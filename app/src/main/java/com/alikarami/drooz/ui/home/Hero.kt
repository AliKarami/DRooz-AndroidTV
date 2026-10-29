package com.alikarami.drooz.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.ui.common.BACKDROP_FADE_MS
import com.alikarami.drooz.ui.common.BackdropImage
import com.alikarami.drooz.ui.common.FitRow
import com.alikarami.drooz.ui.common.ImdbBadge
import com.alikarami.drooz.ui.common.MetaChip
import com.alikarami.drooz.ui.common.MetaText
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.theme.DroozColors

/** A new title replaces the hero this long after focus reached its card (see [heroSettleDelayMs]). */
const val HERO_SETTLE_MS = 200L

/**
 * Delay before [next] replaces [shown]. A different title waits [HERO_SETTLE_MS]: the card's 150 ms focus
 * animation then runs alone instead of sharing a frame with the hero's text layout (one ~90 ms frame per
 * key press on the TV), and passing quickly over cards swaps the hero once, for the card focus settles on.
 */
fun heroSettleDelayMs(shown: HeroUi?, next: HeroUi?): Long =
    if (shown == null || next == null || shown.ref == next.ref) 0L else HERO_SETTLE_MS

/** [hero], held back per [heroSettleDelayMs]. */
@Composable
fun rememberSettledHero(hero: HeroUi?): HeroUi? {
    var shown by remember { mutableStateOf(hero) }
    LaunchedEffect(hero) {
        delay(heroSettleDelayMs(shown, hero))
        shown = hero
    }
    return shown
}

/**
 * Focus-following backdrop with the hero gradients. An image is fetched first and only then
 * cross-faded in (300 ms), so moving focus never flashes an empty frame (see [BackdropImage]).
 */
@Composable
fun HeroBackdrop(hero: HeroUi?, modifier: Modifier = Modifier) {
    val bg = DroozColors.Background
    Box(modifier) {
        BackdropImage(hero?.ref, hero?.backdrop, hero?.posterBackdrop, Modifier.fillMaxSize())
        // Mockup `.shadeR`: dark behind the text on the right, clear on the left (absolute direction).
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.35f to Color.Transparent,
                    0.65f to bg.copy(alpha = 0.6f),
                    1f to bg.copy(alpha = 0.95f),
                ),
            ),
        )
        // Mockup `.shadeB`: melts into the page background where the rows start.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to Color.Transparent,
                    0.72f to bg.copy(alpha = 0.85f),
                    1f to bg,
                ),
            ),
        )
    }
}

/**
 * Title, IMDb badge + meta + chips, and a 3-line synopsis for the focused card; cross-fades per title.
 * [compact] (list grids' shorter hero strip) keeps the title to one line and the synopsis to two.
 */
@Composable
fun HeroInfo(hero: HeroUi?, rowTitle: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    AnimatedContent(
        targetState = hero,
        modifier = modifier,
        transitionSpec = {
            // No size animation: nothing is laid out against the hero text, and animating its size
            // re-measured the screen on every frame of the cross-fade.
            (fadeIn(tween(BACKDROP_FADE_MS)) togetherWith fadeOut(tween(BACKDROP_FADE_MS))).using(null)
        },
        contentKey = { it?.ref },
        contentAlignment = Alignment.TopStart,
        label = "heroInfo",
    ) { h ->
        if (h == null) {
            Box(Modifier.fillMaxWidth())
            return@AnimatedContent
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rowTitle != null) {
                Text(
                    rowTitle, color = DroozColors.Accent, fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1,
                )
            }
            TitleText(
                h.title,
                MaterialTheme.typography.displayMedium.copy(
                    fontSize = if (compact) 30.sp else 34.sp, lineHeight = if (compact) 34.sp else 38.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Right,
                ),
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                maxLines = if (compact) 1 else 2,
            )
            MetaLine(h)
            h.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 25.sp),
                    color = Color(0xFFE3E5EA),
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetaLine(h: HeroUi) {
    val genres = h.genres.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }
    val plain = h.meta + listOfNotNull(genres)
    if (h.imdb == null && plain.isEmpty() && h.chips.isEmpty()) return
    FitRow(Modifier.fillMaxWidth()) {
        h.imdb?.let { ImdbBadge(it) }
        plain.forEach { MetaText(it) }
        h.chips.forEach { MetaChip(it) }
    }
}
