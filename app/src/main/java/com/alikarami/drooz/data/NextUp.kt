package com.alikarami.drooz.data

import com.alikarami.drooz.player.isFinished
import com.alikarami.drooz.player.resumePositionMs
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import kotlin.math.ceil

/** Local and server state of one episode. progress is in 0..1, non-null only while resumable (not watched). */
data class EpisodeState(val watched: Boolean, val progress: Float?, val resumeMs: Long, val remainingMin: Int?)

fun episodeState(ep: Episode, entry: WatchEntry?): EpisodeState {
    val watched = ep.played || entry?.finished == true ||
        (entry != null && isFinished(entry.positionMs, entry.durationMs))
    val resumeMs = if (entry != null && !watched) {
        resumePositionMs(entry.positionMs, entry.durationMs, entry.finished)
    } else {
        0L
    }
    if (entry == null || watched || resumeMs <= 0L) return EpisodeState(watched, null, 0L, null)
    val progress = (entry.positionMs.toDouble() / entry.durationMs).toFloat()
    val remainingMin = ceil((entry.durationMs - entry.positionMs) / 60_000.0).toInt()
    return EpisodeState(watched = false, progress = progress, resumeMs = resumeMs, remainingMin = remainingMin)
}

sealed interface NextUp {
    val episode: Episode

    /** Nothing watched yet: the first episode. */
    data class Start(override val episode: Episode) : NextUp

    /** An episode is part-watched locally (the most recently updated one wins). */
    data class Resume(override val episode: Episode, val state: EpisodeState) : NextUp

    /** The episode after the last watched one, across seasons. */
    data class Next(override val episode: Episode) : NextUp

    /** Everything watched: offers the first episode again. */
    data class Again(override val episode: Episode) : NextUp
}

/** null when seasons has no episodes. entries are keyed by streamId. */
fun nextUp(seasons: List<Season>, entries: Map<String, WatchEntry>): NextUp? {
    val ordered = seasons.sortedBy { it.number }.flatMap { s -> s.episodes.sortedBy { it.number } }
    val first = ordered.firstOrNull() ?: return null
    val states = ordered.map { it to episodeState(it, entries[it.streamId]) }

    states.filter { it.second.progress != null }
        .maxByOrNull { entries.getValue(it.first.streamId).updatedAt }
        ?.let { (ep, state) -> return NextUp.Resume(ep, state) }

    val lastWatched = states.indexOfLast { it.second.watched }
    if (lastWatched < 0) return NextUp.Start(first)
    val following = ordered.getOrNull(lastWatched + 1)
    return if (following != null) NextUp.Next(following) else NextUp.Again(first)
}
