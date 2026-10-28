package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleRef
import org.jsoup.nodes.Element

/**
 * Parses one title "card" (`.postMeta` on lists/home, the header block on title pages).
 * Metadata lines are recognised by their Font Awesome icon, not by position.
 */
object CardParser {
    private val TITLE_YEAR = Regex("""^(.*?)\s*\((\d{4})\)\s*$""")

    fun parse(el: Element): TitleCard? {
        val link = el.select("a[href*=/post/]").firstOrNull { TitleRef.fromPath(it.attr("href")) != null } ?: return null
        val ref = TitleRef.fromPath(link.attr("href")) ?: return null
        val headingEl = el.selectFirst("h1, h2, h3") ?: el.selectFirst("b")
        val heading = headingEl?.let { h -> (h.selectFirst("a")?.ownText() ?: h.ownText()).trim().ifEmpty { h.text().trim() } }.orEmpty()
        val m = TITLE_YEAR.find(heading)
        val title = m?.groupValues?.get(1)?.ifBlank { null } ?: heading.ifBlank { null } ?: ref.slug
        val year = m?.groupValues?.get(2)?.toIntOrNull()
        val poster = el.select("img").asSequence()
            .map { it.attr("data-src").ifEmpty { it.attr("src") } }
            .firstNotNullOfOrNull { src -> src.takeIf { "/img/" in it && "avatar" !in it }?.let(ImageUrls::hashOf) }

        fun line(icon: String): Element? = el.select("i.$icon").firstOrNull()?.parent()
        fun value(icon: String): String? = line(icon)?.text()?.substringAfter(":")?.trim()?.ifBlank { null }
        fun list(icon: String): List<String> = line(icon)?.let { l ->
            val links = l.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            links.ifEmpty { l.html().substringAfter(":").split("<spl></spl>").map { org.jsoup.Jsoup.parse(it).text().trim() } }
        }.orEmpty().filter { it.isNotEmpty() }

        return TitleCard(
            ref = ref,
            title = title,
            year = year,
            posterHash = poster,
            durationMin = line("fa-hourglass-half")?.text()?.let(PersianText::minutes),
            imdb = line("fa-star")?.text()?.let(PersianText::rating),
            criticsScore = line("fa-flag")?.text()?.let(PersianText::criticsScore),
            genres = list("fa-typo3"),
            countries = list("fa-globe"),
            director = list("fa-user").firstOrNull(),
            stars = list("fa-users"),
            synopsis = el.selectFirst(".text-justify")?.text()?.trim()?.ifBlank { null },
            episodeLabel = value("fa-chevron-circle-left") ?: line("fa-chevron-circle-left")?.text()?.trim(),
            status = line("fa-circle-notch")?.text()?.trim()?.ifBlank { null },
        )
    }
}
