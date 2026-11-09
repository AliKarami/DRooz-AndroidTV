package com.alikarami.drooz.ui.home

import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeEntryRowTest {
    private fun card(id: Long) = CardUi(TitleRef(TitleKind.SERIES, id, "x"), "t$id", null, null, landscape = false)
    private fun row(key: String, items: Int = 0, loading: Boolean = false, error: Boolean = false) =
        RowUi(key, key, (1..items).map { card(it.toLong()) }, loading = loading, error = error)

    // Cold start: the stored page fills the later rows while «ادامه تماشا» (first) is still reading local history.
    @Test fun waitsForAnEarlierRowThatIsStillLoading() {
        assertNull(homeEntryRow(listOf(row("cw", loading = true), row("featured", items = 5)), focusedRowKey = null))
    }

    @Test fun entersTheFirstRowOnceItHasCards() {
        assertEquals("cw", homeEntryRow(listOf(row("cw", items = 3), row("featured", items = 5)), null)?.key)
    }

    @Test fun skipsAnEarlierRowThatFailedOrIsEmpty() {
        assertEquals("featured", homeEntryRow(listOf(row("cw", error = true), row("featured", items = 5)), null)?.key)
        assertEquals("featured", homeEntryRow(listOf(row("cw"), row("featured", items = 5)), null)?.key)
    }

    @Test fun theRememberedRowWinsEvenWhileAnEarlierOneLoads() {
        val rows = listOf(row("cw", loading = true), row("featured", items = 5), row("new", items = 4))
        assertEquals("new", homeEntryRow(rows, focusedRowKey = "new")?.key)
    }
}
