package com.alikarami.drooz.site.parse

import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Test

class ListParserTest {
    private val list = ListParser.parse(fixture("list_new_films.html"))

    @Test fun newFilmsPage1() {
        assertEquals(8, list.items.size)
        assertEquals(5744063L, list.items[0].ref.postId)
        assertEquals(1, list.page)
        assertEquals(478, list.lastPage)
    }

    @Test fun cardFields() {
        val verity = list.items.first { it.ref.postId == 5738721L }
        assertEquals("Verity", verity.title)
        assertEquals(listOf("جنایی", "درام", "معمایی", "هیجان انگیز"), verity.genres)
        assertEquals(listOf("Anne Hathaway", "Dakota Johnson", "Josh Hartnett"), verity.stars)
        assertEquals(listOf("آمریکا"), verity.countries)
        assertEquals("0ad6ee864a9a4e46a99be4f13316e245.jpg.webp", verity.posterHash)
    }

    @Test fun pageWithoutPaginationIsSinglePage() {
        val p = ListParser.parse("<html><body></body></html>")
        assertEquals(1, p.page)
        assertEquals(1, p.lastPage)
        assertEquals(0, p.items.size)
    }
}
