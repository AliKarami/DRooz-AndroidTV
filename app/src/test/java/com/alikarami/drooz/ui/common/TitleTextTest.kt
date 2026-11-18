package com.alikarami.drooz.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleTextTest {
    @Test fun prettifiesSlug() {
        assertEquals(
            "Shaun The Sheep The Beast Of Mossy Bottom",
            prettifySlug("shaun-the-sheep-the-beast-of-mossy-bottom"),
        )
    }

    @Test fun prettifiesPercentEncodedSlug() {
        // A slug as it appears in the link: «Uzak %C5%9Eehir» on a card read wrong.
        assertEquals("Uzak Şehir", prettifySlug("uzak-%c5%9eehir"))
        // A literal + is kept; a malformed escape leaves the slug as it is.
        assertEquals("C++ Story", prettifySlug("c++-story"))
        assertEquals("50% Off", prettifySlug("50%-off"))
    }

    @Test fun persianDigits() {
        assertEquals("S۲ E۷ ۱۴۱", "S2 E7 141".toPersianDigits())
    }

    @Test fun episodeLabelFormat() {
        assertEquals("فصل ۲ · قسمت ۷", episodeLabel(2, 7))
    }
}
