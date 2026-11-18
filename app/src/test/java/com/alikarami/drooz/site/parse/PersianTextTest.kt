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
    // Vote counts can carry thousands separators (Arabic ٬, Arabic comma ، or a Latin comma).
    @Test fun ratingWithThousandsSeparators() {
        assertEquals(Rating(7.5, 12300), PersianText.rating("۷.۵ از ۱۰ با ۱۲٬۳۰۰ رای"))
        assertEquals(Rating(7.5, 12300), PersianText.rating("۷.۵ از ۱۰ با ۱۲،۳۰۰ رای"))
        assertEquals(Rating(7.5, 1234), PersianText.rating("7.5 از 10 با 1,234 رای"))
    }
    @Test fun digitsKeepsCommasBetweenWords() = assertEquals("درام، جنایی، 2", PersianText.digits("درام، جنایی، ۲"))
    @Test fun critics() = assertEquals(38, PersianText.criticsScore("امتیاز منتقدین : 38 / 100"))

    // The site's editors mix Arabic ي/ك with Persian ی/ک, and ZWNJ, NBSP or a space between word parts.
    @Test fun normalize() {
        assertEquals("فیلم های برگزیده", PersianText.normalize("فيلم\u200cهاي\u00a0 برگزيده "))
        assertEquals("کودک", PersianText.normalize("كودك"))
        assertEquals("abc", PersianText.normalize("abc"))
    }

    @Test fun ordinals() {
        assertEquals(1, PersianText.ordinal("اول"))
        assertEquals(3, PersianText.ordinal("سوم"))
        assertEquals(11, PersianText.ordinal("یازدهم"))
        assertEquals(18, PersianText.ordinal("هیجدهم"))
        assertEquals(20, PersianText.ordinal("بیستم"))
        assertEquals(25, PersianText.ordinal("بیست و پنجم"))
        assertEquals(30, PersianText.ordinal("سی‌ام"))
        assertEquals(4, PersianText.ordinal("۴"))
        assertNull(PersianText.ordinal("آخر"))
        assertNull(PersianText.ordinal("بیست و دهم"))
    }

    @Test fun episodeBadge() {
        assertEquals(3 to 7, PersianText.episodeBadge("قسمت ۰۷ فصل سوم"))
        assertEquals(11 to 11, PersianText.episodeBadge(" قسمت ۱۱ فصل یازدهم"))
        assertEquals(25 to 6, PersianText.episodeBadge("قسمت ۰۶ فصل بیست و پنجم"))
        // Other shapes are left to the caller.
        assertNull(PersianText.episodeBadge("قسمت ۰۷"))
        assertNull(PersianText.episodeBadge("قسمت ۰۷ فصل سوم / ۷۰"))
    }
}
