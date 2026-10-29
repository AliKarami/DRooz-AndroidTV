package com.alikarami.drooz.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.BackdropResolver
import com.alikarami.drooz.data.ContinueItem
import com.alikarami.drooz.data.ContinueWatching
import com.alikarami.drooz.data.HomeRowConfig
import com.alikarami.drooz.data.HomePageCache
import com.alikarami.drooz.data.HomeRows
import com.alikarami.drooz.data.RowSource
import com.alikarami.drooz.data.SiteApi
import com.alikarami.drooz.data.TitleInfoRepository
import com.alikarami.drooz.data.TitlePrefs
import com.alikarami.drooz.data.WatchEntry
import com.alikarami.drooz.data.WatchStore
import com.alikarami.drooz.player.PlayerArgs
import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.TitleDetail
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
    /** Part-watched share (0..1) for a «ادامه تماشا» card; null hides the bar. */
    val progress: Float? = null,
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

/** What OK on a home card does. */
sealed interface HomeAction {
    /** A resolved «ادامه تماشا» card: straight into the player. */
    data class Play(val args: PlayerArgs) : HomeAction
    data class Open(val ref: TitleRef) : HomeAction
}

/**
 * What the hero needs about a focusable item; [card] is null for poster-only (recently streamed) items.
 * [cont] is the «ادامه تماشا» state, with [detail] once the title resolved.
 */
private class Entry(
    val ref: TitleRef,
    val card: TitleCard?,
    val posterHash: String?,
    val cont: ContinueItem? = null,
    val detail: TitleDetail? = null,
)

class HomeViewModel(
    private val api: SiteApi,
    private val titleInfo: TitleInfoRepository,
    private val dwellMs: Long = 300,
    private val homeCache: HomePageCache? = null,
    /** Null: «ادامه تماشا» is the server's recently streamed list as is (no local state, no resolving). */
    private val watchStore: WatchStore? = null,
    private val titlePrefs: TitlePrefs? = null,
) : ViewModel() {
    private val configs: Map<String, HomeRowConfig> = HomeRows.rows.associateBy { it.key }
    private val recentKey = HomeRows.rows.firstOrNull { it.source is RowSource.Recent }?.key

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

    // «ادامه تماشا»: the server list last applied (null before the first), local watch state, and the
    // details resolved so far, kept so [onResume] rebuilds the row without refetching.
    private var recentServer: List<PosterRef>? = null
    private var local: List<WatchEntry> = emptyList()
    private val resolved = HashMap<TitleRef, TitleDetail>()
    private val dubs = HashMap<TitleRef, Boolean>()
    private val resolving = HashMap<TitleRef, Job>()
    private var recentJob: Job? = null
    /** A Play was handed out and home has not resumed since: a second OK is a double press. */
    private var playPending = false

    init { loadHome(useCache = true) }

    private fun initialRows() = HomeRows.rows.map {
        RowUi(it.key, it.title, emptyList(), loading = it.source !is RowSource.ListPath, error = false,
            requested = it.source !is RowSource.ListPath)
    }

    /** Plays seen so far ([TitleInfoRepository.invalidations]); a new one makes «ادامه تماشا» stale. */
    private var seenPlays = titleInfo.invalidations.value

    /** Home is showing again (e.g. Back from a title): after a play, revalidate in place (no skeleton). */
    fun onResume() {
        playPending = false
        val plays = titleInfo.invalidations.value
        if (plays == seenPlays) {
            // The player writes locally even when nothing reached the server: progress may have moved.
            recentKey?.let { key -> recentServer?.let { showRecent(key, it, refetch = false) } }
            return
        }
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
                is RowSource.Recent -> showRecent(cfg.key, page.recentlyStreamed, refetch = true)
                is RowSource.HomeSection ->
                    setItems(cfg.key, page.sections.firstOrNull { it.title == src.title }?.items.orEmpty()
                        .map { Entry(it.ref, it, it.posterHash) })
                is RowSource.ListPath -> Unit
            }
        }
    }

    /**
     * Shows «ادامه تماشا» at once from what is known, then rereads local state and resolves each title
     * through its title page. [refetch]: every title is asked again (the repository serves fresh ones from
     * its cache); otherwise only titles not resolved yet are fetched.
     */
    private fun showRecent(key: String, server: List<PosterRef>, refetch: Boolean) {
        recentServer = server
        val store = watchStore
        if (store == null) {
            setItems(key, server.map { Entry(it.ref, null, it.posterHash) })
            return
        }
        renderRecent(key)
        recentJob?.cancel()
        recentJob = viewModelScope.safeLaunch(onError = { }) {
            // A failing store just means no local state.
            local = runCatching { store.all() }.getOrNull().orEmpty()
            for ((ref, d) in resolved) dubs[ref] = dubFor(ref, d)
            renderRecent(key)
            for ((ref, _) in ContinueWatching.order(server, local)) {
                if (refetch || ref !in resolved) resolve(key, ref, again = refetch)
            }
        }
    }

    /** A failed fetch leaves the title's pending card. */
    private fun resolve(key: String, ref: TitleRef, again: Boolean) {
        if (!again && resolving[ref]?.isActive == true) return
        resolving[ref]?.cancel()
        resolving[ref] = viewModelScope.safeLaunch(onError = { }) {
            val d = titleInfo.get(ref)
            dubs[ref] = dubFor(ref, d)
            resolved[ref] = d
            renderRecent(key)
        }
    }

    /** The title's saved choice, else the global default; only when the title has a dub at all. */
    private suspend fun dubFor(ref: TitleRef, d: TitleDetail): Boolean {
        val prefs = titlePrefs ?: return false
        if (d.catalog.dub.isEmpty()) return false
        // A failing prefs read must not drop the card.
        val saved = runCatching { prefs.dubSelected(ref.postId) }.getOrNull()
            ?: runCatching { prefs.defaultDub() }.getOrNull()
        return saved == true
    }

    private fun renderRecent(key: String) {
        val server = recentServer ?: return
        val byStream = local.associateBy { it.streamId }
        val list = ContinueWatching.order(server, local).mapNotNull { (ref, hash) ->
            val d = resolved[ref] ?: return@mapNotNull Entry(ref, null, hash, ContinueWatching.pending(ref, hash))
            ContinueWatching.resolve(ref, hash ?: d.card.posterHash, d, byStream, dubs[ref] ?: false)
                ?.let { Entry(ref, null, it.posterHash, it, d) }
        }
        setItems(key, list)
    }

    /**
     * OK on a card: a resolved «ادامه تماشا» card plays its next episode, anything else opens the title.
     * Null for a second Play before home [onResume]d (a double press while the player starts), and for
     * a title no longer in the row.
     */
    fun activate(rowKey: String, ref: TitleRef): HomeAction? {
        // By title, not position: the row may have lost a card since the screen last recomposed.
        val e = entries[rowKey]?.firstOrNull { it.ref == ref } ?: return null
        val play = e.cont?.play ?: return HomeAction.Open(e.ref)
        if (playPending) return null
        playPending = true
        return HomeAction.Play(PlayerArgs(e.ref, play.streamId, dubs[e.ref] ?: false))
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
            // A resolved title may have a wide image of its own (played backdrop, trailer still).
            val wide = e.detail?.let {
                BackdropResolver.resolve(e.ref, featured, titleInfo.playedBackdrops.value, it, e.posterHash, 440, 248)
            } as? Backdrop.Wide
            wide?.url
                ?: featured[e.ref]?.let { ImageUrls.backdrop(it, 440, 248) }
                ?: e.posterHash?.let { ImageUrls.poster(it, 440, 248) }
        } else e.posterHash?.let { ImageUrls.poster(it, 240, 360) }
        val subtitle = e.cont?.subtitle ?: e.card?.episodeLabel ?: e.card?.year?.toString()?.toPersianDigits()
        return CardUi(e.ref, title, url, subtitle, landscape, e.cont?.progress)
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
