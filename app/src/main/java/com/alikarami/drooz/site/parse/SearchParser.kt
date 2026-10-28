package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.TitleCard
import org.jsoup.Jsoup

/** Only the «فیلم و سریال» result block; the page's sidebar lists are ignored. */
object SearchParser {
    private const val RESULTS_HEADING = "فیلم و سریال"

    fun parse(html: String): List<TitleCard> {
        val doc = Jsoup.parse(html)
        val h2 = doc.select("h2").firstOrNull { it.text().contains(RESULTS_HEADING) } ?: return emptyList()
        val block = h2.parent()?.parent() ?: return emptyList()
        return block.select("div.rtl").mapNotNull(CardParser::parse).distinctBy { it.ref }
    }
}
