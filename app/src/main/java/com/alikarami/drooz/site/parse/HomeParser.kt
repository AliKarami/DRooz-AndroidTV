package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.FeaturedItem
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.HomeSection
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.TitleRef
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object HomeParser {
    private const val RECENTLY_STREAMED = "مشاهدات اخیر"
    private const val NEW_FILMS_TITLE = "فیلم های جدید"
    private const val NEW_FILMS_PATH = "/archive/category/new-films/"

    fun parse(html: String): HomePage {
        val doc = Jsoup.parse(html)
        val featured = doc.select(".carousel-item").mapNotNull { item ->
            val ref = TitleRef.fromPath(item.attr("href")) ?: return@mapNotNull null
            val hash = item.selectFirst("img")?.let { imgPath(it) }?.let(ImageUrls::hashOf) ?: return@mapNotNull null
            FeaturedItem(ref, hash)
        }

        var recent = emptyList<PosterRef>()
        val sections = mutableListOf<HomeSection>()
        for (h2 in doc.select("h2")) {
            val header = h2.parent() ?: continue
            val content = header.nextElementSibling() ?: continue
            val title = h2.text().trim()
            if (title.contains(RECENTLY_STREAMED)) {
                recent = posters(content)
                continue
            }
            val items = content.children().mapNotNull(CardParser::parse).distinctBy { it.ref }
            if (items.isNotEmpty()) {
                sections += HomeSection(title, header.selectFirst("a.btn[href]")?.attr("href"), items)
            }
        }

        val newFilms = doc.select(".postMeta").mapNotNull(CardParser::parse).distinctBy { it.ref }
        if (newFilms.isNotEmpty()) sections += HomeSection(NEW_FILMS_TITLE, NEW_FILMS_PATH, newFilms)
        return HomePage(featured, recent, sections)
    }

    private fun posters(content: Element): List<PosterRef> = content.select("a[href*=/post/]").mapNotNull { a ->
        val ref = TitleRef.fromPath(a.attr("href")) ?: return@mapNotNull null
        val hash = a.selectFirst("img")?.let { imgPath(it) }?.let(ImageUrls::hashOf) ?: return@mapNotNull null
        PosterRef(ref, hash)
    }.distinctBy { it.ref }

    private fun imgPath(img: Element) = img.attr("data-src").ifEmpty { img.attr("src") }
}
