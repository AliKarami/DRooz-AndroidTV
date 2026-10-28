package com.alikarami.drooz.ui.common

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.alikarami.drooz.BuildConfig
import com.alikarami.drooz.data.Backdrop
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

const val BACKDROP_FADE_MS = 300

/** A wide image that hasn't arrived by now gets the blurred poster in the meantime. */
private const val POSTER_GRACE_MS = 500L
/** Give up on an image after this long, so a slow server can't stall the backdrop. */
private const val LOAD_TIMEOUT_MS = 8_000L

private fun backdropRequest(context: Context, b: Backdrop): ImageRequest? = when (b) {
    is Backdrop.Wide -> ImageRequest.Builder(context).data(b.url).size(Size(ImageSizes.HERO_W, ImageSizes.HERO_H)).build()
    is Backdrop.BlurredPoster ->
        ImageRequest.Builder(context).data(b.url).size(Size(ImageSizes.POSTER_W, ImageSizes.POSTER_H)).build()
    Backdrop.None -> null
}

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
        Log.d("DroozBackdrop", "$b -> $outcome in ${SystemClock.uptimeMillis() - start} ms")
    }
    return result is SuccessResult
}

/**
 * Full-bleed backdrop image for the title identified by [key] (no gradients; callers add their own).
 * An image is fetched first and only then cross-faded in, so a change never flashes an empty frame.
 * A wide image that is slow shows the blurred [poster] meanwhile; one that fails (or times out) leaves
 * the poster in place.
 */
@Composable
fun BackdropImage(key: Any?, target: Backdrop?, poster: Backdrop.BlurredPoster?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Pair<Any?, Backdrop>?>(null) }
    LaunchedEffect(key, target) {
        if (target == null) return@LaunchedEffect
        if (target == Backdrop.None) {
            shown = key to Backdrop.None
            return@LaunchedEffect
        }
        coroutineScope {
            val main = async { preload(context, target) }
            if (withTimeoutOrNull(POSTER_GRACE_MS) { main.await() } == true) {
                shown = key to target
                return@coroutineScope
            }
            val fallback = poster?.takeIf { it != target }
            val showingThisTitle = shown?.first == key
            if (!showingThisTitle && fallback != null && preload(context, fallback)) shown = key to fallback
            if (main.await()) shown = key to target
            else if (shown?.first != key) shown = key to Backdrop.None
        }
    }
    Crossfade(shown?.second, modifier, animationSpec = tween(BACKDROP_FADE_MS), label = "backdrop") { b ->
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
}
