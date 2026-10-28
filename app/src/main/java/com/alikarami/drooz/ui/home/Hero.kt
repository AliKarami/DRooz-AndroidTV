package com.alikarami.drooz.ui.home

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.ui.common.ImageSizes
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.theme.DroozColors

private const val HERO_FADE_MS = 300

private fun backdropRequest(context: Context, b: Backdrop): ImageRequest? = when (b) {
    is Backdrop.Wide -> ImageRequest.Builder(context).data(b.url).size(Size(ImageSizes.HERO_W, ImageSizes.HERO_H)).build()
    is Backdrop.BlurredPoster ->
        ImageRequest.Builder(context).data(b.url).size(Size(ImageSizes.POSTER_W, ImageSizes.POSTER_H)).build()
    Backdrop.None -> null
}

/**
 * Focus-following backdrop. The next image is fetched first and only then cross-faded in (300 ms),
 * so moving focus never flashes an empty frame.
 */
@Composable
fun HeroBackdrop(backdrop: Backdrop?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(backdrop) }
    LaunchedEffect(backdrop) {
        if (backdrop == null) return@LaunchedEffect
        backdropRequest(context, backdrop)?.let { SingletonImageLoader.get(context).execute(it) }
        shown = backdrop
    }
    val bg = DroozColors.Background
    Box(modifier) {
        Crossfade(shown, Modifier.fillMaxSize(), animationSpec = tween(HERO_FADE_MS), label = "heroBackdrop") { b ->
            val request = b?.let { backdropRequest(context, it) }
            when (b) {
                is Backdrop.Wide -> AsyncImage(
                    request, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alignment = BiasAlignment(0f, -0.4f),
                )
                is Backdrop.BlurredPoster -> Box(Modifier.fillMaxSize().clipToBounds()) {
                    AsyncImage(
                        request, null,
                        Modifier.fillMaxSize()
                            .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                            .blur(36.dp, BlurredEdgeTreatment.Rectangle),
                        contentScale = ContentScale.Crop,
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                }
                else -> Box(Modifier.fillMaxSize())
            }
        }
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

/** Title, IMDb badge + meta + chips, and a 3-line synopsis for the focused card; cross-fades per title. */
@Composable
fun HeroInfo(hero: HeroUi?, rowTitle: String?, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = hero,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(HERO_FADE_MS)) togetherWith fadeOut(tween(HERO_FADE_MS))).using(SizeTransform(clip = false))
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
                    fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Right,
                ),
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                maxLines = 2,
            )
            MetaLine(h)
            h.synopsis?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 25.sp),
                    color = Color(0xFFE3E5EA),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MetaLine(h: HeroUi) {
    val imdb = h.meta.firstOrNull { it.startsWith("IMDb") }
    val rest = h.meta.filter { it != imdb }
    if (imdb == null && rest.isEmpty() && h.chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (imdb != null) {
            Text(
                imdb,
                Modifier.background(DroozColors.ImdbBadge, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                color = Color.Black, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge,
            )
        }
        rest.forEach { Text(it, color = Color(0xFFCFD2DA), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        h.chips.forEach {
            Text(
                it,
                Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                color = Color(0xFFCFD2DA), style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
