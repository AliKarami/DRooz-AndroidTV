package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.Rating
import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchParserTest {
    // poster + title links de-duplicated; sidebar "popular series" must NOT be included
    @Test fun findsPluribusOnce() {
        val r = SearchParser.parse(fixture("search_pluribus.html"))
        assertEquals(listOf(5735053L), r.map { it.ref.postId })
        assertEquals("Pluribus", r[0].title)
        assertEquals(Rating(7.9, 181000), r[0].imdb)
    }

    @Test fun noResults() = assertEquals(emptyList<Any>(), SearchParser.parse("<html><body><h2>فیلم و سریال</h2></body></html>"))
}
