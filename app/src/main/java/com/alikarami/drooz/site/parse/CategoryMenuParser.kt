package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.model.CategoryKind
import com.alikarami.drooz.site.model.SiteCategory
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The site's navigation menu (on every page) as browsable categories. Only listings with the ordinary
 * `/archive/...` card markup ListParser reads are kept: playlists (no fixture proves their markup),
 * coming-soon (not a normal listing), static `/page/` lists and anything with an unknown query are dropped.
 */
object CategoryMenuParser {
    private val LISTING = Regex("""^/archive/(?:category/([a-z0-9-]+)|series|(?:film|series)/(?:country|genre)/[a-z0-9-]+)/$""")
    private val TYPE_QUERY = Regex("""^type=(film|series)$""")
    private val EXCLUDED_SLUGS = setOf("coming-soon")

    fun parse(html: String): List<SiteCategory> = parse(Jsoup.parse(html))

    fun parse(doc: Document): List<SiteCategory> {
        val menu = doc.selectFirst("#navbar") ?: doc.selectFirst("nav") ?: return emptyList()
        val seen = HashSet<String>()
        return menu.select("a[href]").mapNotNull { a ->
            val label = PersianText.normalize(a.text()).ifEmpty { return@mapNotNull null }
            val href = a.attr("href").trim().removePrefix(SiteConfig.BASE_URL)
            val rawPath = href.substringBefore('?').substringBefore('#')
            val path = if (rawPath.endsWith("/")) rawPath else "$rawPath/"
            val query = href.substringAfter('?', "").substringBefore('#')
            val m = LISTING.find(path) ?: return@mapNotNull null
            val slug = m.groupValues[1]
            if (slug in EXCLUDED_SLUGS) return@mapNotNull null
            val type = if (query.isEmpty()) null else TYPE_QUERY.find(query)?.groupValues?.get(1) ?: return@mapNotNull null
            val full = if (query.isEmpty()) path else "$path?$query"
            if (!seen.add(full)) return@mapNotNull null
            SiteCategory(label, full, kind(path, slug, type, a))
        }
    }

    /** By URL first (`?type=`, `/film/` or `/series/`, a `-films` / `-series` slug), else by the menu it sits in. */
    private fun kind(path: String, slug: String, type: String?, link: Element): CategoryKind = when {
        type == "film" -> CategoryKind.FILM
        type == "series" -> CategoryKind.SERIES
        path.startsWith("/archive/film/") -> CategoryKind.FILM
        path.startsWith("/archive/series/") -> CategoryKind.SERIES
        slug.endsWith("-films") || slug.endsWith("-film") -> CategoryKind.FILM
        "series" in slug || "tv-show" in slug -> CategoryKind.SERIES
        else -> {
            val menu = link.parents().firstOrNull { it.hasClass("dropdown") }
                ?.selectFirst(".dropdown-toggle")?.text()?.let(PersianText::normalize).orEmpty()
            when {
                "فیلم" in menu -> CategoryKind.FILM
                "سریال" in menu -> CategoryKind.SERIES
                else -> CategoryKind.BOTH
            }
        }
    }
}
