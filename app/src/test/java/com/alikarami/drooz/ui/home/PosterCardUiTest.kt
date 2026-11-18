package com.alikarami.drooz.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class PosterCardUiTest {
    @Test fun shortEpisodeBadgeFitsTheCaption() {
        // The app's own form; no-break spaces keep each number with its word when the caption wraps.
        assertEquals("فصل\u00A0۳ · قسمت\u00A0۷", shortEpisodeBadge("قسمت ۰۷ فصل سوم"))
        assertEquals("فصل\u00A0۲۵ · قسمت\u00A0۱۴", shortEpisodeBadge("قسمت ۱۴ فصل بیست و پنجم"))
        // Anything else keeps the site's wording.
        assertEquals("قسمت ۰۷", shortEpisodeBadge("قسمت ۰۷"))
        assertEquals("قسمت ۰۷ فصل سوم / ۷۰", shortEpisodeBadge("قسمت ۰۷ فصل سوم / ۷۰"))
    }
}
