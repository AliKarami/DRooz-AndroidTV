package com.alikarami.drooz.ui.home

import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.BackdropResolver
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.prettifySlug
import com.alikarami.drooz.ui.common.safeLaunch
import com.alikarami.drooz.ui.common.toPersianDigits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** Display title for a card: the site title, or a prettified slug when the site only gave the slug. */
fun cardTitle(ref: TitleRef, title: String?): String = title?.takeIf { it != ref.slug } ?: prettifySlug(ref.slug)

/** A 2:3 poster card for [card]. */
fun TitleCard.toPosterCardUi(): CardUi = CardUi(
    ref, cardTitle(ref, title), posterHash?.let { ImageUrls.poster(it, 240, 360) },
    episodeLabel ?: year?.toString()?.toPersianDigits(), landscape = false,
)

/**
 * The focus-following hero shared by home and list grids: shows list data at once, then after [dwellMs]
 * on the same title fetches its detail (cached in [titleInfo]) and upgrades the hero (backdrop, chips).
 */
class HeroController(
    private val scope: CoroutineScope,
    private val titleInfo: TitleInfoRepository,
    private val dwellMs: Long,
    private val featured: () -> Map<TitleRef, String> = { emptyMap() },
    private val onHero: (HeroUi) -> Unit,
) {
    private var dwellJob: Job? = null
    private var focusedRef: TitleRef? = null

    /** [card] is null for poster-only items (recently streamed). */
    fun focus(ref: TitleRef, card: TitleCard?, posterHash: String?) {
        dwellJob?.cancel()
        focusedRef = ref
        val cached = titleInfo.peek(ref)
        onHero(hero(ref, card, posterHash, cached))
        if (cached != null) return
        dwellJob = scope.safeLaunch(onError = { /* keep the list-data hero */ }) {
            delay(dwellMs)
            val detail = titleInfo.get(ref)
            if (focusedRef == ref) onHero(hero(ref, card, posterHash, detail))
        }
    }

    /** Forget the focused title: a pending detail fetch no longer updates the hero. */
    fun clear() {
        dwellJob?.cancel()
        focusedRef = null
    }

    private fun hero(ref: TitleRef, listCard: TitleCard?, posterHash: String?, detail: TitleDetail?): HeroUi {
        val card = detail?.card ?: listCard
        val meta = buildList {
            card?.year?.let { add(it.toString().toPersianDigits()) }
            card?.durationMin?.let { add(UiStrings.minutes(it)) }
            card?.episodeLabel?.let { add(it) }
        }
        val chips = buildList {
            val episodes = detail?.catalog?.main.orEmpty().flatMap { it.episodes }
            if (detail?.catalog?.dub?.isNotEmpty() == true) add(UiStrings.DUB)
            if (episodes.any { it.subtitles.isNotEmpty() }) add(UiStrings.SUBTITLE)
            if (episodes.any { ep -> ep.videos.any { it.height >= 2160 } }) add("4K")
        }
        val backdrop = BackdropResolver.resolve(ref, featured(), titleInfo.playedBackdrops.value, detail, posterHash)
        val poster = (detail?.card?.posterHash ?: posterHash)?.let { Backdrop.BlurredPoster(ImageUrls.poster(it, 240, 360)) }
        return HeroUi(ref, cardTitle(ref, card?.title), meta, chips, card?.synopsis, backdrop, card?.imdb?.score,
            card?.genres.orEmpty(), poster)
    }
}
