package com.alikarami.drooz.data

import coil3.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Clears what the app caches for browsing (never the session, prefs or watch state). */
interface CacheCleaner {
    suspend fun clear()

    /** On logout only: forgets «جستجوهای اخیر» (not part of [clear]). */
    suspend fun clearRecentSearches() {}
}

/** Image disk + memory cache, home page cache (memory and its cold-start copy on disk), title cache. */
class AppCacheCleaner(
    private val imageLoader: ImageLoader,
    private val homeCache: HomePageCache,
    private val titleInfo: TitleInfoRepository,
    private val recentSearches: RecentSearches? = null,
) : CacheCleaner {
    override suspend fun clearRecentSearches() {
        recentSearches?.clear()
    }

    override suspend fun clear() {
        homeCache.clear()
        titleInfo.clear()
        imageLoader.memoryCache?.clear()
        withContext(Dispatchers.IO) { imageLoader.diskCache?.clear() }
    }
}
