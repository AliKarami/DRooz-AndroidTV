package com.alikarami.drooz.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowsTest {
    @Test fun order() {
        assertEquals(
            listOf("ادامه تماشا", "فیلم های برگزیده", "سریال های بروز شده", "فیلم های جدید", "سریال های جدید",
                "سریال های محبوب", "سریال های برتر IMDb", "دوبله فارسی", "مناسب کودکان"),
            HomeRows.rows.map { it.title },
        )
        assertEquals(
            listOf("/archive/category/new-tv-show/", "/archive/playlist/show/most-popular-tv-shows/",
                "/archive/playlist/show/imdb-top-250-tv-series/", "/archive/category/dubbed-films/",
                "/archive/category/safe-for-children/"),
            HomeRows.rows.mapNotNull { (it.source as? RowSource.ListPath)?.path },
        )
        assertEquals(RowSource.Recent, HomeRows.rows[0].source)
    }
}
