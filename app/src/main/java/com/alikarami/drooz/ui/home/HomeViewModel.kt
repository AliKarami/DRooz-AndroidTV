package com.alikarami.drooz.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.HomeRowConfig
import com.alikarami.drooz.data.HomePageCache
import com.alikarami.drooz.data.HomeRows
import com.alikarami.drooz.data.RowSource
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.safeLaunch
import com.alikarami.drooz.ui.common.toPersianDigits
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class CardUi(
    val ref: TitleRef,
    val title: String,
    val posterUrl: String?,
    val subtitle: String?,
    val landscape: Boolean,
)

data class RowUi(
    val key: String,
    val title: String,
    val items: List<CardUi>,
    val loading: Boolean,
    val error: Boolean,
    /** False only for lazy list rows that have not been asked to load yet (distinguishes "not yet" from "empty"). */
    val requested: Boolean = true,
)

data class HeroUi(
    val ref: TitleRef,
    val title: String,
    val meta: List<String>,
    val chips: List<String>,
    val synopsis: String?,
    val backdrop: Backdrop,
    /** IMDb score, rendered as a Latin-digit badge ("IMDb 7.9"); not part of [meta]. */
    val imdb: Double? = null,
    val genres: List<String> = emptyList(),
    /** Blurred poster to show while [backdrop] loads, or if it fails. */
    val posterBackdrop: Backdrop.BlurredPoster? = null,
)

data class HomeUiState(val rows: List<RowUi>, val hero: HeroUi?, val error: String?)

/** What the hero needs about a focusable item; [card] is null for poster-only (recently streamed) items. */
private class Entry(val ref: TitleRef, val card: TitleCard?, val posterHash: String?)

class HomeViewModel(
    private val api: SiteApi,
    private val titleInfo: TitleInfoRepository,
    private val dwellMs: Long = 300,
    private val homeCache: HomePageCache? = null,
) : ViewModel() {
    private val configs: Map<String, HomeRowConfig> = HomeRows.rows.associateBy { it.key }

    private val _state = MutableStateFlow(HomeUiState(initialRows(), null, null))
    val uiState: StateFlow<HomeUiState> = _state

    private val entries = HashMap<String, List<Entry>>()
    /** Last focused title per row, by identity: a refresh may reorder the row. */
    private val focus = HashMap<String, TitleRef>()
    /** Position of that title when last focused: if a refresh removes it, focus goes to the card now there. */
    private val focusPos = HashMap<String, Int>()
    private val started = HashSet<String>()
    private var featured: Map<TitleRef, String> = emptyMap()

    /** Home's featured backdrop hash for [ref] (only home knows it), so the title page can reuse it. */
    fun featuredBackdrop(ref: TitleRef): String? = featured[ref]
    private val heroes = HeroController(viewModelScope, titleInfo, dwellMs, { featured }) { h ->
        _state.update { it.copy(hero = h) }
    }
    private var refreshJob: Job? = null
    private val listJobs = HashMap<String, Job>()

    init { loadHome(useCache = true) }

    private fun initialRows() = HomeRows.rows.map {
        RowUi(it.key, it.title, emptyList(), loading = it.source !is RowSource.ListPath, error = false,
            requested = it.source !is RowSource.ListPath)
    }

    /** Plays seen so far ([TitleInfoRepository.invalidations]); a new one makes «ادامه تماشا» stale. */
    private var seenPlays = titleInfo.invalidations.value

    /** Home is showing again (e.g. Back from a title): after a play, revalidate in place (no skeleton). */
    fun onResume() {
        val plays = titleInfo.invalidations.value
        if (plays == seenPlays) return
        seenPlays = plays
        val showing = _state.value.rows.any { isHomeRow(it.key) && it.items.isNotEmpty() }
        loadHome(useCache = false, quiet = showing)
    }

    /** Reloads the home() sources only; lazily loaded list rows keep their state. */
    fun refresh() = loadHome(useCache = false)

    /**
     * [useCache]: a fresh cached page (warm start) is shown at once, with no skeleton, and then revalidated
     * in the background; a failed revalidation keeps the cached rows quietly. [quiet]: the rows showing
     * stay as they are (no skeleton) until the new page replaces them; a failure keeps them.
     */
    private fun loadHome(useCache: Boolean, quiet: Boolean = false) {
        refreshJob?.cancel()
        val cached = homeCache?.takeIf { useCache }?.fresh()
        val keep = cached != null || quiet
        if (cached != null) applyHome(cached)
        else if (!quiet) _state.update { s ->
            s.copy(
                rows = s.rows.map { if (isHomeRow(it.key)) it.copy(loading = true, error = false) else it },
                error = null,
            )
        }
        refreshJob = viewModelScope.safeLaunch(onError = { e ->
            if (!keep) _state.update { s ->
                s.copy(rows = s.rows.map { if (isHomeRow(it.key)) it.copy(loading = false) else it },
                    error = e.message ?: "error")
            }
        }) {
            val page = api.home()
            homeCache?.put(page)
            applyHome(page)
        }
    }

    private fun applyHome(page: HomePage) {
        featured = page.featured.associate { it.ref to it.backdropHash }
        for (cfg in HomeRows.rows) {
            when (val src = cfg.source) {
                is RowSource.Recent -> setItems(cfg.key, page.recentlyStreamed.map { Entry(it.ref, null, it.posterHash) })
                is RowSource.HomeSection ->
                    setItems(cfg.key, page.sections.firstOrNull { it.title == src.title }?.items.orEmpty()
                        .map { Entry(it.ref, it, it.posterHash) })
                is RowSource.ListPath -> Unit
            }
        }
    }

    private fun isHomeRow(key: String) = configs[key]?.source !is RowSource.ListPath

    fun loadRowIfNeeded(rowKey: String) {
        val src = configs[rowKey]?.source as? RowSource.ListPath ?: return
        if (started.add(rowKey)) loadList(rowKey, src.path)
    }

    fun retryRow(rowKey: String) {
        when (val src = configs[rowKey]?.source) {
            is RowSource.ListPath -> { started.add(rowKey); loadList(rowKey, src.path) }
            null -> Unit
            else -> if (refreshJob?.isActive != true) refresh()
        }
    }

    private fun loadList(key: String, path: String) {
        if (listJobs[key]?.isActive == true) return
        updateRow(key) { it.copy(loading = true, error = false, requested = true) }
        listJobs[key] = viewModelScope.safeLaunch(onError = { updateRow(key) { r -> r.copy(loading = false, error = true) } }) {
            val page = api.list(path)
            setItems(key, page.items.map { Entry(it.ref, it, it.posterHash) })
        }
    }

    /** Titles are unique within a row: the UI keys cards (and focus) by title identity. */
    private fun setItems(key: String, all: List<Entry>) {
        val list = all.distinctBy { it.ref }
        entries[key] = list
        focus[key]?.let { ref ->
            val at = list.indexOfFirst { it.ref == ref }
            if (at >= 0) focusPos[key] = at
            else if (list.isNotEmpty()) {
                val nearest = (focusPos[key] ?: 0).coerceIn(0, list.lastIndex)
                focus[key] = list[nearest].ref
                focusPos[key] = nearest
            }
        }
        val landscape = configs[key]?.source is RowSource.Recent
        val items = list.map { e -> cardUi(e, landscape) }
        // An unchanged revalidation leaves the row (and so the screen) untouched.
        if (_state.value.rows.any { it.key == key && it.items == items && !it.loading && !it.error }) return
        updateRow(key) { it.copy(items = items, loading = false, error = false) }
    }

    private fun cardUi(e: Entry, landscape: Boolean): CardUi {
        if (!landscape && e.card != null) return e.card.toPosterCardUi()
        val title = cardTitle(e.ref, e.card?.title)
        val url = if (landscape) {
            (featured[e.ref]?.let { ImageUrls.backdrop(it, 440, 248) }
                ?: e.posterHash?.let { ImageUrls.poster(it, 440, 248) })
        } else e.posterHash?.let { ImageUrls.poster(it, 240, 360) }
        val subtitle = e.card?.episodeLabel ?: e.card?.year?.toString()?.toPersianDigits()
        return CardUi(e.ref, title, url, subtitle, landscape)
    }

    private fun updateRow(key: String, f: (RowUi) -> RowUi) =
        _state.update { s -> s.copy(rows = s.rows.map { if (it.key == key) f(it) else it }) }

    /** Where the row's last focused title is now (0 if it is gone or the row was never focused). */
    fun focusedIndex(rowKey: String): Int {
        val ref = focus[rowKey] ?: return 0
        return entries[rowKey]?.indexOfFirst { it.ref == ref }?.takeIf { it >= 0 } ?: 0
    }

    fun onFocus(rowKey: String, index: Int) {
        val entry = entries[rowKey]?.getOrNull(index) ?: return
        focus[rowKey] = entry.ref
        focusPos[rowKey] = index
        heroes.focus(entry.ref, entry.card, entry.posterHash)
    }
}
