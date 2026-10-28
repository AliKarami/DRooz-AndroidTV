package com.alikarami.drooz.ui.title

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.episodeLabel
import com.alikarami.drooz.ui.common.safeLaunch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class EpisodeUi(val episode: Episode, val label: String, val minutes: Int?, val watched: Boolean)

data class TitleUiState(
    val detail: TitleDetail?,
    val primaryLabel: String,
    val primaryEpisode: Episode?,
    val seasons: List<Int>,
    val selectedSeason: Int,
    val episodes: List<EpisodeUi>,
    val initialFocusEpisode: Int?,
    val dubAvailable: Boolean,
    val dubSelected: Boolean,
    val inWatchlist: Boolean?,
    val error: String?,
)

/** [lang] is "original" or "dubbed-fa". */
data class PlayRequest(val episode: Episode, val ref: TitleRef, val lang: String)

class TitleViewModel(
    private val ref: TitleRef,
    private val api: SiteApi,
    private val titleInfo: TitleInfoRepository,
    private val prefs: TitlePrefs,
) : ViewModel() {
    private var detail: TitleDetail? = null
    private var seasonOverride: Int? = null
    private var dub = false
    private var inWatchlist: Boolean? = null
    private var error: String? = null
    private var watchlistInFlight = false

    private val _state = MutableStateFlow(build())
    val uiState: StateFlow<TitleUiState> = _state

    private val _play = MutableSharedFlow<PlayRequest>(extraBufferCapacity = 1)
    val playRequests: SharedFlow<PlayRequest> = _play

    init { load() }

    fun load() {
        error = null
        publish()
        viewModelScope.safeLaunch(onError = { error = it.message ?: "error"; publish() }) {
            val d = titleInfo.get(ref)
            // A failing prefs read must not hide the title.
            val saved = runCatching { prefs.dubSelected(ref.postId) }.getOrNull()
            dub = saved == true && d.catalog.dub.isNotEmpty()
            detail = d
            publish()
        }
    }

    fun selectSeason(n: Int) { seasonOverride = n; publish() }

    fun toggleDub() {
        if (detail?.catalog?.dub.isNullOrEmpty()) return
        dub = !dub
        publish()
        val chosen = dub
        viewModelScope.safeLaunch(onError = { /* choice just won't persist */ }) {
            prefs.setDubSelected(ref.postId, chosen)
        }
    }

    fun toggleWatchlist() {
        // The site toggles server-side: overlapping requests could invert the real state.
        if (watchlistInFlight) return
        watchlistInFlight = true
        val previous = inWatchlist
        inWatchlist = previous?.not() ?: true
        error = null
        publish()
        viewModelScope.safeLaunch(onError = {
            watchlistInFlight = false
            inWatchlist = previous; error = it.message ?: "error"; publish()
        }) {
            api.setWatchlist(ref.postId, "watch")
            watchlistInFlight = false
        }
    }

    fun play(episode: Episode? = null) {
        val ep = episode ?: _state.value.primaryEpisode ?: return
        _play.tryEmit(PlayRequest(ep, ref, if (dub) "dubbed-fa" else "original"))
    }

    private fun publish() = _state.update { build() }

    private fun build(): TitleUiState {
        val d = detail
        val seasons: List<Season> = d?.let { if (dub) it.catalog.dub else it.catalog.main }.orEmpty()
            .sortedBy { it.number }
        val all = seasons.flatMap { s -> s.episodes.sortedBy { it.number } }
        val next = all.firstOrNull { !it.played }
        val anyPlayed = all.any { it.played }
        val isFilm = ref.kind == TitleKind.FILM
        val (label, primary) = when {
            all.isEmpty() -> "" to null
            next == null -> UiStrings.WATCH_AGAIN to all.first()
            isFilm -> UiStrings.FILM to next
            !anyPlayed -> UiStrings.PLAY_EPISODE_1 to next
            else -> UiStrings.continueWatching(next.season, next.number) to next
        }
        val numbers = seasons.map { it.number }
        val selected = seasonOverride?.takeIf { it in numbers } ?: primary?.season ?: numbers.firstOrNull() ?: 1
        val minutes = d?.card?.durationMin
        val eps = seasons.firstOrNull { it.number == selected }?.episodes.orEmpty().sortedBy { it.number }
        return TitleUiState(
            detail = d,
            primaryLabel = label,
            primaryEpisode = primary,
            seasons = numbers,
            selectedSeason = selected,
            episodes = eps.map { EpisodeUi(it, episodeLabel(it.season, it.number), minutes, it.played) },
            initialFocusEpisode = eps.firstOrNull { !it.played }?.number,
            dubAvailable = d?.catalog?.dub?.isNotEmpty() == true,
            dubSelected = dub,
            inWatchlist = inWatchlist,
            error = error,
        )
    }
}
