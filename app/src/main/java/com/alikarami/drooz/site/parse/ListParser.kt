package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.model.ListPage
import org.jsoup.Jsoup

object ListParser {
    fun parse(html: String): ListPage {
        val doc = Jsoup.parse(html)
        val items = doc.select(".postMeta").mapNotNull(CardParser::parse).distinctBy { it.ref }
        val page = doc.selectFirst(".pagination .page-item.active")?.text()
            ?.let { PersianText.digits(it).trim().toIntOrNull() } ?: 1
        val last = doc.select(".pagination .page-link")
            .mapNotNull { PersianText.digits(it.text()).trim().toIntOrNull() }
            .maxOrNull() ?: page
        return ListPage(items, page, maxOf(page, last))
    }
}
