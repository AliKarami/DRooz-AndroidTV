package com.alikarami.drooz.site.model

import com.alikarami.drooz.site.SiteConfig

enum class TitleKind(val pathSegment: String) { FILM("film"), SERIES("series") }

data class TitleRef(val kind: TitleKind, val postId: Long, val slug: String) {
    val path: String get() = "/post/${kind.pathSegment}/$postId/$slug/"

    companion object {
        private val PATH = Regex("""/post/(film|series)/(\d+)/([^/?#]+)/?""")

        fun fromPath(path: String): TitleRef? {
            val m = PATH.find(path.removePrefix(SiteConfig.BASE_URL)) ?: return null
            val kind = if (m.groupValues[1] == "film") TitleKind.FILM else TitleKind.SERIES
            return TitleRef(kind, m.groupValues[2].toLong(), m.groupValues[3])
        }
    }
}

/** IMDb score with absolute vote count (۱۸۱ هزار → 181000). */
data class Rating(val score: Double, val votes: Int)

data class TitleCard(
    val ref: TitleRef,
    val title: String,
    val year: Int?,
    val posterHash: String?,
    val durationMin: Int?,
    val imdb: Rating?,
    val criticsScore: Int?,
    val genres: List<String>,
    val countries: List<String>,
    val director: String?,
    val stars: List<String>,
    val synopsis: String?,
    /** Series badge, e.g. «قسمت ۰۷ فصل سوم». */
    val episodeLabel: String?,
    /** Series status, e.g. «در حال پخش», «پایان فصل». */
    val status: String?,
)

data class FeaturedItem(val ref: TitleRef, val backdropHash: String)

/** Home "recently streamed" items carry only a link and a poster. */
data class PosterRef(val ref: TitleRef, val posterHash: String)

data class HomeSection(val title: String, val morePath: String?, val items: List<TitleCard>)

data class HomePage(val featured: List<FeaturedItem>, val recentlyStreamed: List<PosterRef>, val sections: List<HomeSection>)

data class ListPage(val items: List<TitleCard>, val page: Int, val lastPage: Int)

data class VideoVariant(val id: String, val width: Int, val height: Int, val size: String)

/** [lang] is "original" or "dubbed-fa". */
data class AudioTrack(val id: String, val lang: String, val channels: Int)

/** [lang] is "fa" or "en". */
data class SubtitleRef(val id: String, val lang: String)

data class Episode(
    val season: Int,
    val number: Int,
    val streamId: String,
    val videos: List<VideoVariant>,
    val audios: List<AudioTrack>,
    val subtitles: List<SubtitleRef>,
    val played: Boolean,
)

data class Season(val number: Int, val episodes: List<Episode>)

/** Films are one Season(1) with one Episode(1); [dub] is empty when there is no Persian dub. */
data class StreamCatalog(val main: List<Season>, val dub: List<Season>)

data class TitleDetail(
    val card: TitleCard,
    val channel: String?,
    val awards: List<String>,
    val catalog: StreamCatalog,
    val suggestions: List<TitleRef>,
    val trailerThumbHash: String?,
)

/** Labels as on the site: Farsi, English, English + Farsi, Farsi for Dub. */
data class SubtitleTrack(val label: String, val url: String)

data class StreamSession(
    val title: String,
    val masterUrl: String,
    val backdropPath: String?,
    val subtitles: List<SubtitleTrack>,
    /** Keyed by bitrate in kbps, e.g. 2475 → "1080 [ 2.48 GB ]". */
    val qualityLabels: Map<Int, String>,
    /** (segment seconds, gain) loudness map used by Audio Boost. */
    val gains: List<Pair<Double, Double>>,
    val minGain: Double?,
    val maxGain: Double?,
    /** Keyframe times per quality level. */
    val iframes: List<List<Double>>,
    val streamId: String,
)
