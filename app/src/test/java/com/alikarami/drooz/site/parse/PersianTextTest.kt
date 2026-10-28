package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersianTextTest {
    @Test fun digits() = assertEquals("2026 / 141", PersianText.digits("۲۰۲۶ / ١٤١"))
    @Test fun rating() = assertEquals(Rating(7.9, 181000), PersianText.rating("۷.۹ از ۱۰ با ۱۸۱ هزار رای"))
    @Test fun ratingPlain() = assertEquals(Rating(6.4, 999), PersianText.rating("۶.۴ از ۱۰ با ۹۹۹ رای"))
    @Test fun ratingMillions() = assertEquals(Rating(9.3, 3_100_000), PersianText.rating("۹.۳ از ۱۰ با ۳.۱ میلیون رای"))
    @Test fun ratingInteger() = assertEquals(Rating(6.0, 23000), PersianText.rating("۶ از ۱۰ با ۲۳ هزار رای"))
    @Test fun ratingMissing() = assertNull(PersianText.rating("بدون امتیاز"))
    @Test fun minutes() = assertEquals(141, PersianText.minutes("مدت : ۱۴۱ دقیقه"))
    @Test fun critics() = assertEquals(38, PersianText.criticsScore("امتیاز منتقدین : 38 / 100"))
}
