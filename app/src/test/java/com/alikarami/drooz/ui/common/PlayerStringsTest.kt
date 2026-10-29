package com.alikarami.drooz.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerStringsTest {
    @Test fun speedLabels() {
        assertEquals("۰٫۵×", UiStrings.speed(0.5f))
        assertEquals("۰٫۷۵×", UiStrings.speed(0.75f))
        assertEquals("۱×", UiStrings.speed(1f))
        assertEquals("۱٫۲۵×", UiStrings.speed(1.25f))
        assertEquals("۲×", UiStrings.speed(2f))
        assertEquals("سرعت ۱×", UiStrings.speedButton(1f))
    }

    @Test fun syncOffset() {
        assertEquals("+۰٫۵ ثانیه", UiStrings.syncOffset(500))
        assertEquals("−۱٫۵ ثانیه", UiStrings.syncOffset(-1500))
        assertEquals("۰ ثانیه", UiStrings.syncOffset(0))
        assertEquals("+۲ ثانیه", UiStrings.syncOffset(2000))
    }

    @Test fun qualityLabels() {
        assertEquals("خودکار (1080p)", UiStrings.qualityAuto(1080))
        assertEquals("خودکار", UiStrings.qualityAuto(null))
        assertEquals("کیفیت: خودکار (1080p)", UiStrings.qualityButton("خودکار (1080p)"))
    }

    @Test fun percentAndCountdown() {
        assertEquals("۱۲۰٪", UiStrings.percent(120))
        assertEquals("پخش خودکار تا ۸ ثانیه دیگر", UiStrings.autoPlayIn(8))
    }

    @Test fun audioLabelsByText() {
        assertEquals("زبان اصلی ۵.۱", UiStrings.audioLabel("original 6CH"))
        assertEquals("دوبله فارسی", UiStrings.audioLabel("dubbed-fa"))
        assertEquals("زبان اصلی", UiStrings.audioLabel("original"))
        assertEquals("Commentary", UiStrings.audioLabel("Commentary"))
    }

    @Test fun clock() {
        assertEquals("00:00", formatClock(0))
        assertEquals("23:07", formatClock(23 * 60_000L + 7_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("00:00", formatClock(-5))
    }
}
