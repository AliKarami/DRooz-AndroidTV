package com.alikarami.drooz.ui.home

import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroSettleTest {
    private fun hero(id: Long, title: String = "T$id") =
        HeroUi(TitleRef(TitleKind.FILM, id, "s$id"), title, emptyList(), emptyList(), null, Backdrop.None, null, emptyList(), null)

    @Test fun firstHeroShowsAtOnce() = assertEquals(0L, heroSettleDelayMs(shown = null, next = hero(1)))

    // A detail upgrade of the title already on screen is not a focus move.
    @Test fun sameTitleUpgradeShowsAtOnce() = assertEquals(0L, heroSettleDelayMs(hero(1), hero(1, "Real Title")))

    // A new title waits until the card's focus animation is done, so the two never share a frame,
    // and passing over cards quickly swaps the hero only once, for the card focus settles on.
    @Test fun newTitleWaitsForFocusToSettle() {
        val d = heroSettleDelayMs(hero(1), hero(2))
        assertEquals(HERO_SETTLE_MS, d)
        assertTrue("settle $d ms must outlast the 150 ms focus animation yet keep the hero well under 0.5 s", d in 150L..250L)
    }
}
