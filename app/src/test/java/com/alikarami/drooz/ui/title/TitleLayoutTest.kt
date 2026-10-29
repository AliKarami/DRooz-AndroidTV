package com.alikarami.drooz.ui.title

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLayoutTest {
    private val fsi = "⁨"
    private val pdi = "⁩"
    private val label: (String) -> String = { "کارگردان: $it" }

    @Test fun creditsIsolateDirectorAndStarsSeparately() {
        val line = creditsLine("Muye Wen", listOf("Teng Shen", "Qiming Jiang", "Peter Daniel Adams", "Extra"), label)
        assertEquals("کارگردان: ${fsi}Muye Wen$pdi · ${fsi}Teng Shen, Qiming Jiang, Peter Daniel Adams$pdi", line)
    }

    @Test fun creditsWithoutDirector() {
        assertEquals("${fsi}A, B$pdi", creditsLine(null, listOf("A", "B"), label))
    }

    @Test fun creditsWithoutStars() {
        assertEquals("کارگردان: ${fsi}X$pdi", creditsLine("X", emptyList(), label))
    }

    @Test fun creditsEmpty() {
        assertEquals("", creditsLine(null, emptyList(), label))
    }

    @Test fun filmWithoutSuggestionsShowsNoSuggestionRow() {
        assertFalse(showSuggestions(isFilm = true, suggestionsTab = false, hasSuggestions = false))
    }

    @Test fun filmWithSuggestionsShowsThem() {
        assertTrue(showSuggestions(isFilm = true, suggestionsTab = false, hasSuggestions = true))
    }

    @Test fun seriesShowsSuggestionsOnlyWhenTabSelected() {
        assertFalse(showSuggestions(isFilm = false, suggestionsTab = false, hasSuggestions = true))
        assertTrue(showSuggestions(isFilm = false, suggestionsTab = true, hasSuggestions = true))
        assertFalse(showSuggestions(isFilm = false, suggestionsTab = true, hasSuggestions = false))
    }
}
