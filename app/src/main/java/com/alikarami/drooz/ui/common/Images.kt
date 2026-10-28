package com.alikarami.drooz.ui.common

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size
import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.ui.theme.DroozColors
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** Exact pixel sizes requested from the site's resizer. */
object ImageSizes {
    const val POSTER_W = 240
    const val POSTER_H = 360
    const val CARD_W = 440
    const val CARD_H = 248
    const val HERO_W = 1920
    const val HERO_H = 1080
}

fun posterUrl(hash: String) = ImageUrls.poster(hash, ImageSizes.POSTER_W, ImageSizes.POSTER_H)
fun cardUrl(hash: String) = ImageUrls.backdrop(hash, ImageSizes.CARD_W, ImageSizes.CARD_H)
fun heroUrl(hash: String) = ImageUrls.backdrop(hash, ImageSizes.HERO_W, ImageSizes.HERO_H)

/** Built once (in AppGraph); fetches through the site's cookie/UA-carrying client. */
fun buildImageLoader(context: Context, client: OkHttpClient): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("images").toOkioPath())
                .maxSizeBytes(200L * 1024 * 1024)
                .build()
        }
        .build()

/** Network image at a fixed pixel size (no resampling beyond what the URL already encodes). */
@Composable
fun DroozImage(url: String?, widthPx: Int, heightPx: Int, modifier: Modifier = Modifier) {
    val request = ImageRequest.Builder(LocalContext.current)
        .data(url)
        .size(Size(widthPx, heightPx))
        .crossfade(150)
        .build()
    Box(modifier.background(DroozColors.Surface)) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}
