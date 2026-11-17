package com.alikarami.drooz.site

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageUrlsTest {
    init { SiteConfig.base = SiteConfig.normalize("site.example") }

    @Test fun posterUrl() = assertEquals(
        "https://site.example/img/300-450/abc.jpg.webp", ImageUrls.poster("abc.jpg.webp", 300, 450))

    @Test fun backdropUrl() = assertEquals(
        "https://site.example/img/1920-1080/abc.jpg.webp", ImageUrls.backdrop("abc.jpg.webp", 1920, 1080))

    @Test fun trailerThumbUrl() = assertEquals(
        "https://site.example/img/1280-720/trailer/abc.jpg.webp", ImageUrls.trailerThumb("abc.jpg.webp", 1280, 720))
}
