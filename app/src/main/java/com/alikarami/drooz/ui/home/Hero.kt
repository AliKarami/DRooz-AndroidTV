package com.alikarami.drooz.ui.home

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.site.model.TitleRef
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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

/** A wide image that hasn't arrived by now gets the blurred poster in the meantime. */
private const val POSTER_GRACE_MS = 500L
/** Give up on an image after this long, so a slow server can't stall the hero. */
private const val LOAD_TIMEOUT_MS = 8_000L

/** Fetches [b] into Coil's memory cache; true only on success within the timeout. */
private suspend fun preload(context: Context, b: Backdrop): Boolean {
    val request = backdropRequest(context, b) ?: return false
    val start = SystemClock.uptimeMillis()
    val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) { SingletonImageLoader.get(context).execute(request) }
    if (BuildConfig.DEBUG) {
        val outcome = when (result) {
            null -> "timeout"
            is ErrorResult -> "error ${result.throwable}"
            else -> "ok"
        }
        Log.d("DroozHero", "$b -> $outcome in ${SystemClock.uptimeMillis() - start} ms")
    }
    return result is SuccessResult
}

/**
 * Focus-following backdrop. An image is fetched first and only then cross-faded in (300 ms), so
 * moving focus never flashes an empty frame. A wide image that is slow shows the title's blurred
 * poster meanwhile; one that fails (or times out) leaves the poster in place.
 */
@Composable
fun HeroBackdrop(hero: HeroUi?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<TitleRef, Backdrop>?>(null) }
    LaunchedEffect(hero?.ref, hero?.backdrop) {
        val h = hero ?: return@LaunchedEffect
        val target = h.backdrop
        if (target == Backdrop.None) {
            shown = h.ref to Backdrop.None
            return@LaunchedEffect
        }
        coroutineScope {
            val main = async { preload(context, target) }
            if (withTimeoutOrNull(POSTER_GRACE_MS) { main.await() } == true) {
                shown = h.ref to target
                return@coroutineScope
            }
            val poster = h.posterBackdrop?.takeIf { it != target }
            val showingThisTitle = shown?.first == h.ref
            if (!showingThisTitle && poster != null && preload(context, poster)) shown = h.ref to poster
            if (main.await()) shown = h.ref to target
            else if (shown?.first != h.ref) shown = h.ref to Backdrop.None
        }
    }
    val bg = DroozColors.Background
    Box(modifier) {
        Crossfade(shown?.second, Modifier.fillMaxSize(), animationSpec = tween(HERO_FADE_MS), label = "heroBackdrop") { b ->
            val request = remember(b) { b?.let { backdropRequest(context, it) } }
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

/** "IMDb 7.9": a Latin label, so Latin digits (the rest of the meta line uses Persian digits). */
private fun imdbLabel(score: Double) = "IMDb " + String.format(Locale.US, "%.1f", score)

@Composable
private fun MetaLine(h: HeroUi) {
    val genres = h.genres.take(2).joinToString(" · ").takeIf { it.isNotEmpty() }
    val plain = h.meta + listOfNotNull(genres)
    if (h.imdb == null && plain.isEmpty() && h.chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        h.imdb?.let { score ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    imdbLabel(score),
                    Modifier.background(DroozColors.ImdbBadge, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                    color = Color.Black, fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.SansSerif),
                )
            }
        }
        plain.forEach {
            Text(it, color = Color(0xFFCFD2DA), style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
        }
        h.chips.forEach {
            Text(
                it,
                Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp),
                color = Color(0xFFCFD2DA), style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
            )
        }
    }
}
