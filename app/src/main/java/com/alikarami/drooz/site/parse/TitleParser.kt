package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.AudioTrack
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import com.alikarami.drooz.site.model.StreamCatalog
import com.alikarami.drooz.site.model.SubtitleRef
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.site.model.VideoVariant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

object TitleParser {
    private val STREAM = Regex("""var STREAM=(\{.*?\});\s*\n""", RegexOption.DOT_MATCHES_ALL)
    private val TRAILER = Regex("""/img/590-331/trailer/[^'"\s]+""")
    private const val SUGGESTIONS = "آثار پیشنهادی"
    private const val WATCH_BUTTON = "button.watchlist[value2=watch]"

    fun parse(html: String, ref: TitleRef): TitleDetail {
        val streamJson = STREAM.find(html)?.groupValues?.get(1) ?: throw SiteException.ParseError("STREAM")
        val catalog = parseCatalog(streamJson)
        val doc = Jsoup.parse(html)
        val meta = doc.selectFirst(".postMeta")?.parent() ?: throw SiteException.ParseError("postMeta")
        val card = CardParser.parse(meta)?.copy(ref = ref) ?: throw SiteException.ParseError("card")

        val channel = meta.selectFirst("i.fa-tv")?.parent()?.text()?.substringAfter(":")?.trim()?.ifBlank { null }
        val awards = meta.select("i.fa-flag").flatMap { icon ->
            val heading = icon.parent() ?: return@flatMap emptyList()
            val box = heading.parent() ?: return@flatMap emptyList()
            val title = heading.text().trim().trimEnd(':', ' ')
            box.select("div.col-12.px-0").map { "$title: ${it.text().trim()}" }
        }
        val suggestionLinks = doc.select("h2").firstOrNull { it.text().contains(SUGGESTIONS) }
            ?.parent()?.parent()
            ?.select("a[href*=/post/]")
            .orEmpty()
            .mapNotNull { a ->
                val ref = TitleRef.fromPath(a.attr("href")) ?: return@mapNotNull null
                val img = a.selectFirst("img")
                val poster = (img?.attr("data-src")?.ifBlank { null } ?: img?.attr("src"))?.let(ImageUrls::hashOf)
                ref to poster
            }
        val suggestions = suggestionLinks.map { it.first }.distinct()
        val posters = suggestionLinks.mapNotNull { (ref, hash) -> hash?.let { ref to it } }.toMap()
        val trailer = TRAILER.find(html)?.value?.let(ImageUrls::hashOf)
        return TitleDetail(card, channel, awards, catalog, suggestions, trailer, posters, watchlistState(doc.selectFirst(WATCH_BUTTON)))
    }

    /**
     * The «لیست تماشا» toggle is rendered `btn-warning` when the title is on the watchlist and `btn-gray`
     * when not (the site's JS swaps the two on a press); anything else is unknown.
     */
    private fun watchlistState(button: org.jsoup.nodes.Element?): Boolean? = when {
        button == null -> null
        button.hasClass("btn-warning") -> true
        button.hasClass("btn-gray") -> false
        else -> null
    }

    /** `STREAM` is `{"main": seasons, "dub"?: seasons, "subtitle": bool}`; films use arrays, series objects keyed by number. */
    fun parseCatalog(streamJson: String): StreamCatalog {
        val root = runCatching { Json.parseToJsonElement(streamJson).jsonObject }
            .getOrElse { throw SiteException.ParseError("STREAM") }
        return StreamCatalog(
            main = root["main"]?.let(::seasons).orEmpty(),
            dub = root["dub"]?.let(::seasons).orEmpty(),
        )
    }

    private fun numbered(el: JsonElement): List<Pair<Int, JsonElement>> = when (el) {
        is JsonArray -> el.mapIndexed { i, v -> (i + 1) to v }
        is JsonObject -> el.entries.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.sortedBy { it.first }
        else -> emptyList()
    }

    private fun seasons(el: JsonElement): List<Season> = numbered(el).map { (season, eps) ->
        Season(season, numbered(eps).mapNotNull { (n, ep) -> episode(season, n, ep) })
    }

    private fun episode(season: Int, number: Int, el: JsonElement): Episode? {
        val o = el as? JsonObject ?: return null
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content.orEmpty()
        fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return Episode(
            season = season,
            number = number,
            streamId = o.str("id"),
            videos = list("videos").map {
                VideoVariant(it.str("id"), it.str("width").toIntOrNull() ?: 0, it.str("height").toIntOrNull() ?: 0, it.str("size"))
            },
            audios = list("audios").map { AudioTrack(it.str("id"), it.str("lang"), it.str("channels").toIntOrNull() ?: 2) },
            subtitles = list("subtitles").map { SubtitleRef(it.str("id"), it.str("lang")) },
            played = (o["played"] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.content == "1") } ?: false,
        )
    }
}
