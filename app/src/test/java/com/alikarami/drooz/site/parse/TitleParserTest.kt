package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.Rating
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleParserTest {
    private val pluribusRef = TitleRef(TitleKind.SERIES, 5735053, "pluribus")
    private val ouatimeRef = TitleRef(TitleKind.FILM, 5744530, "once-upon-a-time-in-the-middle-east")
    private val pluribus by lazy { TitleParser.parse(fixture("title_series_pluribus.html"), pluribusRef) }
    private val ouatime by lazy { TitleParser.parse(fixture("title_film_ouatime.html"), ouatimeRef) }

    @Test fun seriesCatalogAllPlayed() {
        val d = pluribus
        assertEquals(1, d.catalog.main.size)
        val eps = d.catalog.main[0].episodes
        assertEquals((1..9).toList(), eps.map { it.number })
        assertTrue(eps.all { it.played })
        assertEquals("325497", eps[0].streamId)
        assertEquals(listOf(480, 720, 1080), eps[0].videos.map { it.height })
        assertEquals(listOf(2, 6), eps[0].audios.map { it.channels })
        assertEquals(listOf("fa", "en"), eps[0].subtitles.map { it.lang })
        assertTrue(d.catalog.dub.isEmpty())
    }

    @Test fun seriesMetadata() {
        val d = pluribus
        assertEquals("Pluribus", d.card.title)
        assertEquals(2025, d.card.year)
        assertEquals(Rating(7.9, 181000), d.card.imdb)
        assertEquals("Apple TV+", d.channel)
        assertEquals(listOf("درام", "علمی-تخیلی"), d.card.genres)
        assertEquals("قسمت ۰۹ فصل اول", d.card.episodeLabel)
        assertEquals("پایان فصل", d.card.status)
        assertEquals(listOf(20835L, 4444536L, 5688281L, 5733846L), d.suggestions.take(4).map { it.postId })
        assertEquals("933a5416ee8344d0532c9e34ecf5b267.jpg.webp", d.suggestionPosters[d.suggestions.first()])
        assertEquals(d.suggestions.toSet(), d.suggestionPosters.keys)
        assertTrue(d.awards.any { it.contains("برنده بهترین بازیگر نقش اول زن") })
        assertTrue(d.card.synopsis!!.startsWith("داستان بدبخت‌ترین فرد"))
        assertEquals("eac12b446fe94c8ef5485a2291db460a.jpg.webp", d.card.posterHash)
    }

    @Test fun filmMetadata() {
        val c = ouatime.card
        assertEquals("Once Upon a Time in the Middle East", c.title)
        assertEquals(141, c.durationMin)
        assertEquals(Rating(8.3, 2000), c.imdb)
    }

    @Test fun filmCatalogArrayFormWithDub() {
        val c = ouatime.catalog
        assertEquals("371517", c.main.single().episodes.single().streamId)
        assertEquals("372848", c.dub.single().episodes.single().streamId)
        assertEquals("dubbed-fa", c.dub.single().episodes.single().audios.single().lang)
        assertFalse(c.main.single().episodes.single().played)
    }

    @Test fun seriesObjectFormKeysAreSortedNumerically() {
        val ep = """{"videos":[],"id":"1","audios":[],"subtitles":[]}"""
        val c = TitleParser.parseCatalog("""{"main":{"10":{"2":$ep,"1":$ep},"2":{"1":$ep}},"subtitle":true}""")
        assertEquals(listOf(2, 10), c.main.map { it.number })
        assertEquals(listOf(1, 2), c.main[1].episodes.map { it.number })
        assertEquals(10, c.main[1].episodes[0].season)
    }

    @Test fun missingStreamJsonThrowsParseError() {
        val e = assertThrows(SiteException.ParseError::class.java) { TitleParser.parse("<html></html>", pluribusRef) }
        assertEquals("STREAM", e.what)
    }

    // The site renders the watchlist toggle as `btn-gray` (off) or `btn-warning` (on); its JS swaps the two.
    private val watchButton = """class="btn btn-gray py-1 watchlist" value="5735053" value2='watch'"""
    private val favoriteButton = """class="btn btn-gray py-1 watchlist" value="5735053" value2='favorite'"""

    @Test fun watchlistOffAsRendered() {
        assertEquals(false, pluribus.inWatchlist)
        assertEquals(false, ouatime.inWatchlist)
    }

    @Test fun watchlistOnWhenButtonIsActive() {
        val html = fixture("title_series_pluribus.html")
        assertTrue(html.contains(watchButton))
        val on = html.replace(watchButton, watchButton.replace("btn-gray", "btn-warning"))
        assertEquals(true, TitleParser.parse(on, pluribusRef).inWatchlist)
    }

    @Test fun watchlistIgnoresOtherToggles() {
        val html = fixture("title_series_pluribus.html")
        val favOn = html.replace(favoriteButton, favoriteButton.replace("btn-gray", "btn-warning"))
        assertEquals(false, TitleParser.parse(favOn, pluribusRef).inWatchlist)
    }

    @Test fun watchlistUnknownWithoutButtonOrMarker() {
        val html = fixture("title_series_pluribus.html")
        assertEquals(null, TitleParser.parse(html.replace("value2='watch'", "value2='other'"), pluribusRef).inWatchlist)
        val unmarked = html.replace(watchButton, watchButton.replace("btn-gray ", ""))
        assertEquals(null, TitleParser.parse(unmarked, pluribusRef).inWatchlist)
    }
}
