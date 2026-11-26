package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchlistParserTest {
    @Test fun readsTheLivePage() {
        val p = WatchlistParser.parse(fixture("watchlist.html"))
        assertEquals(listOf(5710154L, 5708324L, 20487L), p.items.map { it.ref.postId })
        assertEquals(1, p.page)
        assertEquals(1, p.lastPage)
        val whale = p.items[1]
        assertEquals(TitleKind.FILM, whale.ref.kind)
        assertEquals("The Whale", whale.title)
        assertEquals(2022, whale.year)
        assertEquals("cb7af4225609d9ee11cb914b9d49e385.jpg.webp", whale.posterHash)
    }

    /** Films then series; titles only on «مورد علاقه» or «دیده شده» are not on «لیست من». */
    @Test fun keepsWatchlistCardsFromBothSections() {
        val html = """
            <div id="filmList">
              ${card("film", 1, "watchList favoriteList", "A Film (2020)")}
              ${card("film", 2, "favoriteList", "Liked Only (2019)")}
            </div>
            <div id="seriesList">
              ${card("series", 3, "watchedList", "Seen Only (2018)")}
              ${card("series", 4, "watchList", "A Series")}
            </div>
        """
        val p = WatchlistParser.parse(html)
        assertEquals(listOf(1L, 4L), p.items.map { it.ref.postId })
        assertEquals(TitleKind.SERIES, p.items[1].ref.kind)
        assertEquals("A Series", p.items[1].title)
        assertNull(p.items[1].year)
    }

    @Test fun emptyList() = assertEquals(0, WatchlistParser.parse("<html><body></body></html>").items.size)

    private fun card(kind: String, id: Long, lists: String, title: String) = """
        <div class="col-4 col-md-2 p-1 ty-compact-list $lists">
          <a href="/post/$kind/$id/slug-$id/"><img src="data:image/svg+xml" data-src="/img/120-173/hash$id.jpg.webp" title="$title"></a>
        </div>
    """
}
