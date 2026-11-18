package com.alikarami.drooz.data

import com.alikarami.drooz.site.parse.PersianText

sealed interface RowSource {
    data object Recent : RowSource
    /** [title] is kept normalized, the form HomeParser gives section titles, so the lookup is an exact match. */
    data class HomeSection private constructor(val title: String) : RowSource {
        companion object {
            operator fun invoke(title: String) = HomeSection(PersianText.normalize(title))
        }
    }
    data class ListPath(val path: String) : RowSource
}

data class HomeRowConfig(val key: String, val title: String, val source: RowSource)

object HomeRows {
    val rows: List<HomeRowConfig> = listOf(
        HomeRowConfig("recent", "ادامه تماشا", RowSource.Recent),
        HomeRowConfig("featured", "فیلم های برگزیده", RowSource.HomeSection("فیلم های برگزیده")),
        // The listing behind the home section's «بیشتر»: the section itself has only wide 373×67 strips (cropped
        // to a sliver as posters) and no titles; the listing has posters, titles and episode badges.
        HomeRowConfig("updated-series", "سریال های بروز شده", RowSource.ListPath("/archive/series/")),
        HomeRowConfig("new-films", "فیلم های جدید", RowSource.HomeSection("فیلم های جدید")),
        HomeRowConfig("new-series", "سریال های جدید", RowSource.ListPath("/archive/category/new-tv-show/")),
        HomeRowConfig("popular", "سریال های محبوب", RowSource.ListPath("/archive/playlist/show/most-popular-tv-shows/")),
        HomeRowConfig("imdb-top", "سریال های برتر IMDb", RowSource.ListPath("/archive/playlist/show/imdb-top-250-tv-series/")),
        HomeRowConfig("dubbed", "دوبله فارسی", RowSource.ListPath("/archive/category/dubbed-films/")),
        HomeRowConfig("kids", "مناسب کودکان", RowSource.ListPath("/archive/category/safe-for-children/")),
    )
}
