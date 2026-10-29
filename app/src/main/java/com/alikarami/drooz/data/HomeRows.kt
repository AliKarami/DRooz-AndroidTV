package com.alikarami.drooz.data

sealed interface RowSource {
    data object Recent : RowSource
    data class HomeSection(val title: String) : RowSource
    data class ListPath(val path: String) : RowSource
}

data class HomeRowConfig(val key: String, val title: String, val source: RowSource)

object HomeRows {
    val rows: List<HomeRowConfig> = listOf(
        HomeRowConfig("recent", "ادامه تماشا", RowSource.Recent),
        HomeRowConfig("featured", "فیلم های برگزیده", RowSource.HomeSection("فیلم های برگزیده")),
        HomeRowConfig("updated-series", "سریال های بروز شده", RowSource.HomeSection("سریال های بروز شده")),
        HomeRowConfig("new-films", "فیلم های جدید", RowSource.HomeSection("فیلم های جدید")),
        HomeRowConfig("new-series", "سریال های جدید", RowSource.ListPath("/archive/category/new-tv-show/")),
        HomeRowConfig("popular", "سریال های محبوب", RowSource.ListPath("/archive/playlist/show/most-popular-tv-shows/")),
        HomeRowConfig("imdb-top", "سریال های برتر IMDb", RowSource.ListPath("/archive/playlist/show/imdb-top-250-tv-series/")),
        HomeRowConfig("dubbed", "دوبله فارسی", RowSource.ListPath("/archive/category/dubbed-films/")),
        HomeRowConfig("kids", "مناسب کودکان", RowSource.ListPath("/archive/category/safe-for-children/")),
    )
}
