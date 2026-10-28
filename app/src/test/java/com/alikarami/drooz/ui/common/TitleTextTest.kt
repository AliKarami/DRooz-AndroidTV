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

    @Test fun persianDigits() {
        assertEquals("S۲ E۷ ۱۴۱", "S2 E7 141".toPersianDigits())
    }

    @Test fun episodeLabelFormat() {
        assertEquals("فصل ۲ · قسمت ۷", episodeLabel(2, 7))
    }
}
