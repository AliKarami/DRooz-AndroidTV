package com.alikarami.drooz.data

import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropResolverTest {
    private val ref = TitleRef(TitleKind.FILM, 1, "a")
    private fun detail(poster: String?, trailer: String?) = TitleDetail(
        TitleCard(ref, "t", null, poster, null, null, null, emptyList(), emptyList(), null, emptyList(), null, null, null),
        null, emptyList(), StreamCatalog(emptyList(), emptyList()), emptyList(), trailer,
    )

    @Test fun featuredWins() {
        val r = BackdropResolver.resolve(ref, mapOf(ref to "fh"), mapOf(ref to "/img/1920-1080/p.jpg.webp"), detail("po", "tr"))
        assertEquals(Backdrop.Wide(ImageUrls.backdrop("fh", 1920, 1080)), r)
    }

    @Test fun playedBackdropNext() {
        val r = BackdropResolver.resolve(ref, emptyMap(), mapOf(ref to "/img/1920-1080/p.jpg.webp"), detail("po", "tr"))
        assertEquals(Backdrop.Wide(SiteConfig.BASE_URL + "/img/1920-1080/p.jpg.webp"), r)
    }

    @Test fun trailerThumbNext() {
        val r = BackdropResolver.resolve(ref, emptyMap(), emptyMap(), detail("po", "tr"))
        assertEquals(Backdrop.Wide(ImageUrls.trailerThumb("tr", 1920, 1080)), r)
    }

    @Test fun posterFallback() {
        assertEquals(Backdrop.BlurredPoster(ImageUrls.poster("po", 240, 360)),
            BackdropResolver.resolve(ref, emptyMap(), emptyMap(), detail("po", null)))
        assertEquals(Backdrop.BlurredPoster(ImageUrls.poster("pc", 240, 360)),
            BackdropResolver.resolve(ref, emptyMap(), emptyMap(), null, "pc"))
    }

    @Test fun none() {
        assertEquals(Backdrop.None, BackdropResolver.resolve(ref, emptyMap(), emptyMap(), null))
        assertEquals(Backdrop.None, BackdropResolver.resolve(ref, emptyMap(), emptyMap(), detail(null, null)))
    }
}
