package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.ListPage
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleRef
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * «لیست من» (`/user/watchlist/`). Unlike the archive lists it has no `.postMeta` cards: a films section then
 * a series section of compact poster cards, one per title on any of the user's three lists. The card's
 * classes say which (`watchList`, `favoriteList`, `watchedList`); only `watchList` is «لیست من». The poster's
 * `title` is "Title (Year)". The whole list is one page.
 */
object WatchlistParser {
    private val TITLE_YEAR = Regex("""^(.*?)\s*\((\d{4})\)\s*$""")

    fun parse(html: String): ListPage = parse(Jsoup.parse(html))

    fun parse(doc: Document): ListPage {
        val items = doc.select(".ty-compact-list.watchList").mapNotNull { el ->
            val ref = el.select("a[href*=/post/]").firstNotNullOfOrNull { TitleRef.fromPath(it.attr("href")) }
                ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val label = img?.attr("title")?.trim().orEmpty()
            val m = TITLE_YEAR.find(label)
            val poster = img?.let { it.attr("data-src").ifEmpty { it.attr("src") } }?.let(ImageUrls::hashOf)
            TitleCard(
                ref = ref,
                title = m?.groupValues?.get(1)?.ifBlank { null } ?: label.ifBlank { null } ?: ref.slug,
                year = m?.groupValues?.get(2)?.toIntOrNull(),
                posterHash = poster,
                durationMin = null, imdb = null, criticsScore = null, genres = emptyList(), countries = emptyList(),
                director = null, stars = emptyList(), synopsis = null, episodeLabel = null, status = null,
            )
        }.distinctBy { it.ref.kind to it.ref.postId }
        return ListPage(items, page = 1, lastPage = 1)
    }
}
