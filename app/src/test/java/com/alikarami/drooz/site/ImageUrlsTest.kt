package com.alikarami.drooz.site

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlsTest {
    @Test fun posterUrl() = assertEquals(
        "https://robin.robert-redford.net/img/300-450/abc.jpg.webp", ImageUrls.poster("abc.jpg.webp", 300, 450))

    @Test fun backdropUrl() = assertEquals(
        "https://robin.robert-redford.net/img/1920-1080/abc.jpg.webp", ImageUrls.backdrop("abc.jpg.webp", 1920, 1080))

    @Test fun trailerThumbUrl() = assertEquals(
        "https://robin.robert-redford.net/img/1280-720/trailer/abc.jpg.webp", ImageUrls.trailerThumb("abc.jpg.webp", 1280, 720))
}
