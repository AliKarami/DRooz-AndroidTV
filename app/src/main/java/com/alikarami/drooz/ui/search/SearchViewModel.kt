package com.alikarami.drooz.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.ui.common.safeLaunch
import com.alikarami.drooz.ui.home.CardUi
import com.alikarami.drooz.ui.home.HeroController
import com.alikarami.drooz.ui.home.HeroUi
import com.alikarami.drooz.ui.home.toPosterCardUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class SearchUiState(
    /** What the field shows (untrimmed). */
    val query: String = "",
    val items: List<CardUi> = emptyList(),
    /** A search is in flight; the previous results stay on screen until it lands. */
    val loading: Boolean = false,
    val error: String? = null,
    /** The trimmed query [items] answer, or null when nothing has been searched (idle / too short). */
    val searchedFor: String? = null,
    val hero: HeroUi? = null,
) {
    /** A finished search that found nothing (not idle, not loading, not failed). */
    val isEmptyResult: Boolean get() = searchedFor != null && items.isEmpty() && !loading && error == null
}

class SearchViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchViewModel(graph.siteApi, graph.titleInfo) as T
}

/** Search-as-you-type (debounced) plus immediate searches for the IME's Search key and voice results. */
class SearchViewModel(
    private val api: SiteApi,
    titleInfo: TitleInfoRepository,
    private val debounceMs: Long = 400,
    dwellMs: Long = 300,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _state

    private var cards: List<TitleCard> = emptyList()
    private var job: Job? = null
    private val heroes = HeroController(viewModelScope, titleInfo, dwellMs) { h -> _state.update { it.copy(hero = h) } }

    /** Last focused result, so returning from a title page restores focus. */
    var focusedIndex: Int = 0
        private set

    /** Whether focus was last in the results (true) or on the field / mic (false): where Back lands. */
    var focusInResults: Boolean = false
        private set

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        schedule(text.trim(), debounceMs)
    }

    /** The IME's Search key: run now instead of waiting out the debounce. */
    fun submit() = schedule(_state.value.query.trim(), 0)

    fun onVoiceResult(text: String) {
        _state.update { it.copy(query = text) }
        schedule(text.trim(), 0)
    }

    fun retry() = schedule(_state.value.query.trim(), 0, force = true)

    fun onFocusIndex(index: Int) {
        val card = cards.getOrNull(index) ?: return
        focusedIndex = index
        focusInResults = true
        heroes.focus(card.ref, card, card.posterHash)
    }

    fun onFieldFocused() {
        focusInResults = false
    }

    private fun schedule(q: String, delayMs: Long, force: Boolean = false) {
        job?.cancel()
        if (q.length < MIN_QUERY) {
            show(emptyList(), searchedFor = null)
            return
        }
        val s = _state.value
        // Same query already answered (a trailing space typed, Search pressed after the debounce ran):
        // keep the results and focus instead of fetching them again.
        if (!force && q == s.searchedFor && s.error == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        job = viewModelScope.safeLaunch(onError = { e ->
            _state.update { it.copy(loading = false, error = e.message ?: "error") }
        }) {
            delay(delayMs)
            _state.update { it.copy(loading = true, error = null) }
            val found = api.search(q)
            // Cancellation already drops superseded searches; this also guards a non-cancellable call.
            if (_state.value.query.trim() == q) show(found, searchedFor = q)
        }
    }

    private fun show(found: List<TitleCard>, searchedFor: String?) {
        val unique = found.distinctBy { it.ref.kind to it.ref.postId }
        val changed = unique.map { it.ref } != cards.map { it.ref }
        cards = unique
        if (changed) {
            focusedIndex = 0
            heroes.clear()
        }
        _state.update {
            it.copy(
                items = unique.map { c -> c.toPosterCardUi() }, loading = false, error = null, searchedFor = searchedFor,
                hero = if (changed) null else it.hero,
            )
        }
    }

    private companion object {
        const val MIN_QUERY = 2
    }
}
