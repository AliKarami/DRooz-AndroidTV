package com.alikarami.drooz.data

import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Clears what the app caches for browsing (never the session, prefs or watch state). */
interface CacheCleaner {
    suspend fun clear()
}

/** Image disk + memory cache, home page cache, title cache. */
class AppCacheCleaner(
    private val imageLoader: ImageLoader,
    private val homeCache: HomePageCache,
    private val titleInfo: TitleInfoRepository,
) : CacheCleaner {
    override suspend fun clear() {
        homeCache.clear()
        titleInfo.clear()
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
    }
}
