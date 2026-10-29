package com.alikarami.drooz.ui.title

/** First-strong isolate … pop isolate: the text keeps its own direction without merging with neighbours. */
private fun isolate(text: String) = "⁨$text⁩"

/**
 * «کارگردان: X · A, B, C». Director and stars are isolated separately: otherwise the neutral « · »
 * between two Latin runs joins them into one LTR run, and in RTL the label ends up beside the last star.
 * [label] formats the director part (e.g. the «خالق: %s» string resource).
 */
fun creditsLine(director: String?, stars: List<String>, label: (String) -> String): String = listOfNotNull(
    director?.takeIf { it.isNotBlank() }?.let { label(isolate(it)) },
    stars.take(3).joinToString(", ").takeIf { it.isNotEmpty() }?.let(::isolate),
).joinToString(" · ")

/** Films show suggestions as their only bottom row, series when that tab is selected; never an empty row. */
fun showSuggestions(isFilm: Boolean, suggestionsTab: Boolean, hasSuggestions: Boolean): Boolean =
    hasSuggestions && (isFilm || suggestionsTab)
