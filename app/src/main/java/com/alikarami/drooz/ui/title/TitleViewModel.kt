package com.alikarami.drooz.ui.title

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.NextUp
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.data.episodeState
import com.alikarami.drooz.data.nextUp
import com.alikarami.drooz.player.PlayerArgs
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.Season
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.episodeLabel
import com.alikarami.drooz.ui.common.safeLaunch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class EpisodeUi(val episode: Episode, val label: String, val minutes: Int?, val watched: Boolean, val progress: Float?)

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

sealed interface PlayEvent {
    data class Start(val args: PlayerArgs) : PlayEvent
}

class TitleViewModel(
    private val ref: TitleRef,
    private val api: SiteApi,
    private val titleInfo: TitleInfoRepository,
    private val prefs: TitlePrefs,
    private val watchStore: WatchStore,
) : ViewModel() {
    private var detail: TitleDetail? = null
    private var seasonOverride: Int? = null
    private var dub = false
    private var inWatchlist: Boolean? = null
    private var error: String? = null
    private var watchlistInFlight = false
    private var dubRestored = false
    /** This title's local watch entries by streamId. */
    private var local: Map<String, WatchEntry> = emptyMap()
    /** A player start was emitted and the page hasn't been resumed since. */
    private var startPending = false
    /** Set by a play: the page (played ✓, next episode) changed and is refetched on return. */
    private var playedSinceLoad = false

    private val _state = MutableStateFlow(build())
    val uiState: StateFlow<TitleUiState> = _state

    private val _play = Channel<PlayEvent>(Channel.BUFFERED)
    /** One-shot: open the player. Collect only while the page is resumed. */
    val playEvents: Flow<PlayEvent> = _play.receiveAsFlow()

    init { load() }

    /**
     * Shows the cached page at once (even one a play has invalidated: no skeleton), then the current one.
     * Once a page is showing, a failed refresh keeps it quietly.
     */
    fun load() {
        error = null
        publish()
        viewModelScope.safeLaunch(onError = { if (detail == null) { error = it.message ?: "error"; publish() } }) {
            readLocal()
            if (detail == null) titleInfo.lastKnown(ref)?.let { show(it) }
            show(titleInfo.get(ref))
        }
    }

    /** A failing store just means no local state. Matched by postId: links to one title may differ in slug. */
    private suspend fun readLocal() {
        local = runCatching { watchStore.all() }.getOrNull().orEmpty()
            .filter { TitleRef.fromPath(it.refPath)?.postId == ref.postId }.associateBy { it.streamId }
    }

    private suspend fun show(d: TitleDetail) {
        if (!dubRestored) {
            dubRestored = true
            // A failing prefs read must not hide the title.
            val saved = runCatching { prefs.dubSelected(ref.postId) }.getOrNull()
                ?: runCatching { prefs.defaultDub() }.getOrNull()
            dub = saved == true && d.catalog.dub.isNotEmpty()
        }
        if (dub && d.catalog.dub.isEmpty()) dub = false
        // A refresh never moves the page under the user: the season showing (and its focused tile) stays.
        if (detail != null && seasonOverride == null) seasonOverride = _state.value.selectedSeason
        if (!watchlistInFlight) inWatchlist = d.inWatchlist
        detail = d
        publish()
    }

    /** The page is showing again (e.g. back from the player): after a play, revalidate it in place. */
    fun onResume() {
        startPending = false
        if (playedSinceLoad && detail != null) {
            playedSinceLoad = false
            load()
        } else {
            // The player writes locally even when the server page is unchanged.
            viewModelScope.safeLaunch(onError = { }) { readLocal(); publish() }
        }
    }

    /** The watchlist failure toast was shown: don't show it again when the page comes back. */
    fun onErrorShown() {
        if (detail != null && error != null) { error = null; publish() }
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

    /**
     * The site's toggle flips the state server-side. A known state flips (and the cached page with it);
     * an unknown one (page didn't say) stays unknown: the control never claims a state it can't know.
     */
    fun toggleWatchlist() {
        // Overlapping requests could invert the real state.
        if (watchlistInFlight) return
        watchlistInFlight = true
        val previous = inWatchlist
        inWatchlist = previous?.not()
        error = null
        publish()
        viewModelScope.safeLaunch(onError = {
            watchlistInFlight = false
            inWatchlist = previous; error = it.message ?: "error"; publish()
        }) {
            api.setWatchlist(ref.postId, "watch")
            watchlistInFlight = false
            val now = inWatchlist
            titleInfo.patch(ref) { it.copy(inWatchlist = now) }
        }
    }

    /**
     * Hands [episode] (null: the primary button's episode) to the player, which opens the stream itself
     * (and invalidates / remembers the backdrop then). Marks the page for revalidation on return.
     */
    fun play(episode: Episode? = null) {
        // One start per visit to the page: a second press before it pauses must not stack a second player.
        if (startPending) return
        val ep = episode ?: _state.value.primaryEpisode ?: return
        startPending = true
        playedSinceLoad = true
        _play.trySend(PlayEvent.Start(PlayerArgs(ref, ep.streamId, dub)))
    }

    private fun publish() = _state.update { build() }

    private fun build(): TitleUiState {
        val d = detail
        val seasons: List<Season> = d?.let { if (dub) it.catalog.dub else it.catalog.main }.orEmpty()
            .sortedBy { it.number }
        val up = nextUp(seasons, local)
        val isFilm = ref.kind == TitleKind.FILM
        val label = when (up) {
            null -> ""
            is NextUp.Start -> if (isFilm) UiStrings.FILM else UiStrings.playEpisode(up.episode.season, up.episode.number)
            is NextUp.Resume -> if (isFilm) UiStrings.continueFilm(up.state.remainingMin ?: 0)
                else UiStrings.continueWatching(up.episode.season, up.episode.number)
            is NextUp.Next -> UiStrings.continueWatching(up.episode.season, up.episode.number)
            is NextUp.Again -> UiStrings.WATCH_AGAIN
        }
        val primary = up?.episode
        val numbers = seasons.map { it.number }
        val selected = seasonOverride?.takeIf { it in numbers } ?: primary?.season ?: numbers.firstOrNull() ?: 1
        val minutes = d?.card?.durationMin
        val eps = seasons.firstOrNull { it.number == selected }?.episodes.orEmpty().sortedBy { it.number }
        val states = eps.map { it to episodeState(it, local[it.streamId]) }
        return TitleUiState(
            detail = d,
            primaryLabel = label,
            primaryEpisode = primary,
            seasons = numbers,
            selectedSeason = selected,
            episodes = states.map { (e, st) -> EpisodeUi(e, episodeLabel(e.season, e.number), minutes, st.watched, st.progress) },
            initialFocusEpisode = primary?.takeIf { p -> eps.any { it.season == p.season && it.number == p.number } }?.number
                ?: states.firstOrNull { !it.second.watched }?.first?.number,
            dubAvailable = d?.catalog?.dub?.isNotEmpty() == true,
            dubSelected = dub,
            inWatchlist = inWatchlist,
            error = error,
        )
    }
}

