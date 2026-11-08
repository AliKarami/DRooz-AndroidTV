package com.alikarami.drooz.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UiStringsTest {
    // B10: the site writes plurals with a space («سریال های»); the rail and Persian typography use a ZWNJ.
    @Test fun pluralSuffixJoinsWithZwnj() {
        assertEquals("سریال‌های بروز شده", UiStrings.joinPlurals("سریال های بروز شده"))
        assertEquals("فیلم‌ها", UiStrings.joinPlurals("فیلم ها"))
        assertEquals("سریال‌های برتر IMDb", UiStrings.joinPlurals("سریال های برتر IMDb"))
        // Already joined, or no plural: unchanged.
        assertEquals("سریال‌ها", UiStrings.joinPlurals("سریال‌ها"))
        assertEquals("ادامه تماشا", UiStrings.joinPlurals("ادامه تماشا"))
        assertEquals("دوبله فارسی", UiStrings.joinPlurals("دوبله فارسی"))
    }
}
