package com.alikarami.drooz.data

import com.alikarami.drooz.player.isFinished
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.episodeLabel

/** One «ادامه تماشا» card. play is null until the title's detail resolved; then OK plays it directly. */
data class ContinueItem(
    val ref: TitleRef,
    val posterHash: String?,
    val subtitle: String?,
    val progress: Float?,
    val play: ContinuePlay?,
)

data class ContinuePlay(val streamId: String)

object ContinueWatching {
    /** At most this many titles come from local history alone (no server listing). */
    const val LOCAL_ONLY_MAX = 10

    /**
     * Titles in row order: server order, then local-only titles newest first; de-duplicated by postId.
     * Local-only titles are capped at [LOCAL_ONLY_MAX], and a local-only film whose newest entry is
     * finished is left out (it would only resolve to nothing).
     * [hidden] (postId → when the user removed it from the row, see [HiddenTitles]) leaves a title out,
     * server-listed or local-only, until one of its entries is newer than the hide (played again).
     */
    fun order(
        server: List<PosterRef>,
        local: List<WatchEntry>,
        hidden: Map<Long, Long> = emptyMap(),
    ): List<Pair<TitleRef, String?>> {
        val newest = local.mapNotNull { e -> TitleRef.fromPath(e.refPath)?.let { it to e } }
            .groupBy({ it.first.postId }, { it })
            .values
            .map { group -> group.maxBy { it.second.updatedAt } }
        val lastPlayed = newest.associate { (ref, e) -> ref.postId to e.updatedAt }
        fun isHidden(postId: Long): Boolean {
            val at = hidden[postId] ?: return false
            return (lastPlayed[postId] ?: Long.MIN_VALUE) <= at
        }
        val result = LinkedHashMap<Long, Pair<TitleRef, String?>>()
        server.filterNot { isHidden(it.ref.postId) }.forEach { result.putIfAbsent(it.ref.postId, it.ref to it.posterHash) }
        newest
            .sortedByDescending { (_, e) -> e.updatedAt }
            .filter { (ref, e) ->
                ref.postId !in result && !isHidden(ref.postId) && !(ref.kind == TitleKind.FILM && e.isDone())
            }
            .take(LOCAL_ONLY_MAX)
            .forEach { (ref, _) -> result[ref.postId] = ref to null }
        return result.values.toList()
    }

    private fun WatchEntry.isDone() = finished || isFinished(positionMs, durationMs)

    /**
     * The card for a resolved title, or null when it's done (series fully watched, film watched).
     * dub selects the catalog.
     */
    fun resolve(
        ref: TitleRef,
        posterHash: String?,
        detail: TitleDetail,
        entries: Map<String, WatchEntry>,
        dub: Boolean,
    ): ContinueItem? {
        val seasons = if (dub) detail.catalog.dub else detail.catalog.main
        val next = nextUp(seasons, entries) ?: return null
        val ep = next.episode
        val film = ref.kind == TitleKind.FILM
        val label = episodeLabel(ep.season, ep.number)
        val (subtitle, progress) = when (next) {
            is NextUp.Again -> return null
            is NextUp.Start -> (if (film) null else label) to null
            is NextUp.Next -> label to null
            is NextUp.Resume -> {
                val text = if (film) UiStrings.remaining(next.state.remainingMin ?: 0) else label
                text to next.state.progress
            }
        }
        return ContinueItem(ref, posterHash, subtitle, progress, ContinuePlay(ep.streamId))
    }

    /** The card before its detail is known. */
    fun pending(ref: TitleRef, posterHash: String?): ContinueItem =
        ContinueItem(ref, posterHash, subtitle = null, progress = null, play = null)
}
