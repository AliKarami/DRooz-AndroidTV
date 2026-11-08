package com.alikarami.drooz.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.ui.common.safeLaunch
import com.alikarami.drooz.ui.home.CardUi
import com.alikarami.drooz.ui.home.HeroController
import com.alikarami.drooz.ui.home.HeroUi
import com.alikarami.drooz.ui.home.toPosterCardUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ListUiState(
    val title: String,
    val items: List<CardUi> = emptyList(),
    /** First page in flight (nothing to show yet). */
    val loading: Boolean = true,
    /** A further page is in flight. */
    val loadingMore: Boolean = false,
    /** First page failed: whole-screen error. */
    val error: String? = null,
    /** A further page failed: items stay, the grid ends with a retry. */
    val pageError: Boolean = false,
    val endReached: Boolean = false,
    val hero: HeroUi? = null,
) {
    /** Loaded with nothing to show and no error. */
    val empty: Boolean get() = !loading && error == null && items.isEmpty()
}

/** A site list (`/archive/...`) as an infinite grid: pages load as focus nears the end. */
class ListViewModel(
    private val api: SiteApi,
    private val titleInfo: TitleInfoRepository,
    private val path: String,
    title: String,
    dwellMs: Long = 300,
    /** Load the next page once focus is within this many items of the end (two rows of the 7-column grid). */
    private val prefetch: Int = 14,
    /** «لیست من»: a title page that toggled the watchlist makes the grid reload when it shows again. */
    private val reloadOnWatchlistChange: Boolean = false,
) : ViewModel() {
    private val _state = MutableStateFlow(ListUiState(title))
    val uiState: StateFlow<ListUiState> = _state

    private val cards = ArrayList<TitleCard>()
    /** Identity used to drop repeats: kind + postId, the same as the grid key (slugs can differ for one post). */
    private val seen = HashSet<Pair<TitleKind, Long>>()
    private var loadedPage = 0
    private var lastPage = 1
    private var job: Job? = null
    private var seenWatchlist = titleInfo.watchlistChanges.value
    private val heroes = HeroController(viewModelScope, titleInfo, dwellMs) { h -> _state.update { it.copy(hero = h) } }

    /** Last focused grid index, so returning from a title page restores focus. */
    var focusedIndex: Int = 0
        private set

    init { loadNext() }

    fun onFocusIndex(index: Int) {
        val card = cards.getOrNull(index) ?: return
        focusedIndex = index
        heroes.focus(card.ref, card, card.posterHash)
        val s = _state.value
        if (index >= cards.size - prefetch && !s.pageError) loadNext()
    }

    /**
     * The grid is showing again (Back from a title): after a watchlist toggle, the loaded pages replace the
     * grid (no skeleton; a failure keeps it). Focus stays at its index and the hero moves to the card now there.
     */
    fun onResume() {
        if (!reloadOnWatchlistChange) return
        val changes = titleInfo.watchlistChanges.value
        if (changes == seenWatchlist) return
        seenWatchlist = changes
        if (loadedPage == 0) {
            loadNext() // the first page never arrived: the usual path (skeleton or error screen)
            return
        }
        job?.cancel()
        val pages = loadedPage
        job = viewModelScope.safeLaunch(onError = { /* keep the grid */ }) {
            val loaded = ArrayList<TitleCard>()
            var n = 0
            var last: Int
            do {
                val page = api.list(path, ++n)
                loaded += page.items
                last = maxOf(page.lastPage, n)
            } while (n < pages && n < last)
            cards.clear()
            seen.clear()
            cards += loaded.filter { seen.add(it.ref.kind to it.ref.postId) }
            loadedPage = n
            lastPage = last
            focusedIndex = focusedIndex.coerceAtMost(maxOf(cards.lastIndex, 0))
            _state.update {
                it.copy(
                    items = cards.map { c -> c.toPosterCardUi() }, loading = false, loadingMore = false,
                    error = null, pageError = false, endReached = loadedPage >= lastPage,
                    hero = if (cards.isEmpty()) null else it.hero,
                )
            }
            val focused = cards.getOrNull(focusedIndex)
            if (focused != null) heroes.focus(focused.ref, focused, focused.posterHash) else heroes.clear()
        }
    }

    /** Retries whatever failed: the first page (screen error) or the next page (grid-end retry). */
    fun retry() {
        _state.update { it.copy(pageError = false) }
        loadNext()
    }

    private fun loadNext() {
        if (job?.isActive == true || loadedPage > 0 && loadedPage >= lastPage) return
        val first = loadedPage == 0
        _state.update { it.copy(loading = first, loadingMore = !first, error = null, pageError = false) }
        job = viewModelScope.safeLaunch(onError = { e ->
            _state.update {
                // Only a failed first page is a screen error; later pages keep their items and end in a retry.
                if (loadedPage == 0) it.copy(loading = false, error = e.message ?: "error")
                else it.copy(loadingMore = false, pageError = true)
            }
        }) {
            // Keep going while the newest page brought nothing new (the list shifted) or focus is still near
            // the end: focus reached it while this page was in flight, and no further focus event will come.
            do {
                val next = loadedPage + 1
                val page = api.list(path, next)
                loadedPage = next
                lastPage = maxOf(page.lastPage, next)
                val added = page.items.filter { seen.add(it.ref.kind to it.ref.postId) }
                cards += added
                val more = loadedPage < lastPage && (added.isEmpty() || focusedIndex >= cards.size - prefetch)
                _state.update {
                    it.copy(
                        items = it.items + added.map { c -> c.toPosterCardUi() },
                        loading = false, loadingMore = more, endReached = loadedPage >= lastPage,
                    )
                }
            } while (more)
        }
    }
}
