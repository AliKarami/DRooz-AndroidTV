package com.alikarami.drooz.data

sealed interface RowSource {
    data object Recent : RowSource
    data class HomeSection(val title: String) : RowSource
    data class ListPath(val path: String) : RowSource
}

data class HomeRowConfig(val title: String, val source: RowSource)

object HomeRows {
    val rows: List<HomeRowConfig> = listOf(
        HomeRowConfig("ادامه تماشا", RowSource.Recent),
        HomeRowConfig("فیلم های برگزیده", RowSource.HomeSection("فیلم های برگزیده")),
        HomeRowConfig("سریال های بروز شده", RowSource.HomeSection("سریال های بروز شده")),
        HomeRowConfig("فیلم های جدید", RowSource.HomeSection("فیلم های جدید")),
        HomeRowConfig("سریال های جدید", RowSource.ListPath("/archive/category/new-tv-show/")),
        HomeRowConfig("سریال های محبوب", RowSource.ListPath("/archive/playlist/show/most-popular-tv-shows/")),
        HomeRowConfig("سریال های برتر IMDb", RowSource.ListPath("/archive/playlist/show/imdb-top-250-tv-series/")),
        HomeRowConfig("دوبله فارسی", RowSource.ListPath("/archive/category/dubbed-films/")),
        HomeRowConfig("مناسب کودکان", RowSource.ListPath("/archive/category/safe-for-children/")),
    )
}
