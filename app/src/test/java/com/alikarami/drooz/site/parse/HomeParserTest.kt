package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.Rating
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeParserTest {
    private val home = HomeParser.parse(fixture("home.html"))

    @Test fun featured() {
        val f = home.featured
        assertEquals(6, f.size)
        assertEquals(5740731L, f[0].ref.postId)
        assertEquals("ac89a599f201a486ad141d4d2defd72f.jpg.webp", f[2].backdropHash)
    }

    @Test fun recentlyStreamed() = assertEquals(
        listOf(5744530L, 5743454L, 5732899L, 46629L, 1302463L, 5735053L),
        home.recentlyStreamed.map { it.ref.postId })

    @Test fun recentlyStreamedHasPosters() =
        assertEquals("f2e1a13c82b663225f6fad126480684c.jpg.webp", home.recentlyStreamed[0].posterHash)

    @Test fun sections() {
        assertEquals(listOf("فیلم های برگزیده", "سریال های بروز شده"), home.sections.map { it.title }.take(2))
        val series = home.sections.first { it.title == "سریال های بروز شده" }
        assertEquals("/archive/series/", series.morePath)
        assertEquals(TitleKind.SERIES, series.items.first().ref.kind)
        assertEquals("قسمت ۱۱ فصل یازدهم", series.items.first().episodeLabel)
        assertEquals("در حال پخش", series.items.first().status)
    }

    @Test fun featuredFilmsSection() {
        val s = home.sections.first { it.title == "فیلم های برگزیده" }
        assertEquals("/archive/category/featured-films/", s.morePath)
        assertEquals(5740731L, s.items.first().ref.postId)
        assertEquals("8bde473599128f72889fe6670cbebcb2.jpg.webp", s.items.first().posterHash)
    }

    @Test fun filmCardMetadata() {
        val c = home.sections.flatMap { it.items }.first { it.ref.postId == 5744063L }
        assertEquals("Ali G: Who Iz I?", c.title)
        assertEquals(2026, c.year)
        assertEquals(89, c.durationMin)
        assertEquals(Rating(5.8, 1000), c.imdb)
        assertEquals(38, c.criticsScore)
        assertEquals(listOf("کمدی"), c.genres)
        assertEquals("Sacha Baron Cohen", c.director)
        assertTrue(c.synopsis!!.startsWith("روزنامه‌نگار هیپ‌هاپ"))
    }
}
