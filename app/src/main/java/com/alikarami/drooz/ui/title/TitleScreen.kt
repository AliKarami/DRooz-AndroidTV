package com.alikarami.drooz.ui.title

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.alikarami.drooz.player.PlayerArgs
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.R
import com.alikarami.drooz.data.Backdrop
import com.alikarami.drooz.data.BackdropResolver
import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.BackdropImage
import com.alikarami.drooz.ui.common.DroozImage
import com.alikarami.drooz.ui.common.EpisodeTile
import com.alikarami.drooz.ui.common.ErrorState
import com.alikarami.drooz.ui.common.FitRow
import com.alikarami.drooz.ui.common.ImageSizes
import com.alikarami.drooz.ui.common.ImdbBadge
import com.alikarami.drooz.ui.common.MetaChip
import com.alikarami.drooz.ui.common.MetaText
import com.alikarami.drooz.ui.common.TitleText
import com.alikarami.drooz.ui.common.UiStrings
import com.alikarami.drooz.ui.common.posterUrl
import com.alikarami.drooz.ui.common.prettifySlug
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.common.toPersianDigits
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors
import com.alikarami.drooz.ui.theme.FocusScale
import com.alikarami.drooz.ui.theme.droozFocus
import com.alikarami.drooz.ui.theme.droozSurfaceColors
import com.alikarami.drooz.ui.theme.droozSurfaceScale

class TitleViewModelFactory(private val graph: AppGraph, private val ref: TitleRef) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TitleViewModel(ref, graph.siteApi, graph.titleInfo, graph.titlePrefs, graph.watchStore) as T
}

/** Distance from the right (start) screen edge to the content; there is no rail on this page. */
private val ContentStart = 64.dp
private val ContentEnd = 48.dp
/** Width of the info column (mockup `.info`, 42% of the screen). */
private val InfoWidth = 440.dp
/** Room above/below tiles and posters so the focus scale and ring are not clipped by their row. */
private val RowPadV = 12.dp
private val SuggestionWidth = 84.dp
private val SecondaryText = Color(0xFFE3E5EA)

private sealed interface Tab {
    data class Season(val number: Int) : Tab
    data object Suggestions : Tab
}

/**
 * Title page (mockup A, "big numbered tiles"): backdrop, info column with the primary / «لیست من» /
 * language buttons, then season tabs + episode tiles and an «آثار پیشنهادی» tab with posters.
 * [featuredBackdropHash] is home's featured image for this title, when it has one.
 */
@Composable
fun TitleScreen(
    ref: TitleRef,
    vm: TitleViewModel,
    onStartPlayer: (PlayerArgs) -> Unit,
    onOpenTitle: (TitleRef) -> Unit,
    featuredBackdropHash: String? = null,
    playedBackdrops: Map<TitleRef, String> = emptyMap(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val startPlayer by rememberUpdatedState(onStartPlayer)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Only a resumed page opens the player: a stream that resolves after the user left never starts it.
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.playEvents.collect { e ->
                when (e) {
                    is PlayEvent.Start -> startPlayer(e.args)
                }
            }
        }
    }
    // Back from the player: the page (played ✓, next episode) is revalidated in place.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    val detail = state.detail
    LaunchedEffect(state.error) {
        if (detail != null && state.error != null) {
            Toast.makeText(context, R.string.title_watchlist_failed, Toast.LENGTH_SHORT).show()
            vm.onErrorShown() // once: not again when the page comes back
        }
    }

    val featured = remember(ref, featuredBackdropHash) { featuredBackdropHash?.let { mapOf(ref to it) }.orEmpty() }
    val backdrop = remember(ref, featured, playedBackdrops, detail) {
        detail?.let { BackdropResolver.resolve(ref, featured, playedBackdrops, it) }
            ?: featured[ref]?.let { BackdropResolver.resolve(ref, featured, playedBackdrops, null) }
    }
    val poster = detail?.card?.posterHash?.let { Backdrop.BlurredPoster(ImageUrls.poster(it, 240, 360)) }

    BoxWithConstraints(Modifier.fillMaxSize().background(DroozColors.Background)) {
        val screenHeight = maxHeight
        TitleBackdrop(ref, backdrop, poster, Modifier.fillMaxWidth().height(screenHeight * 0.75f))
        PageShade()
        when {
            detail == null && state.error != null -> {
                val retry = remember { FocusRequester() }
                LaunchedEffect(Unit) { retry.requestFocusSoon() }
                ErrorState(
                    stringResource(R.string.title_failed), stringResource(R.string.retry), vm::load,
                    Modifier.focusRequester(retry).focusGroup(),
                )
            }
            detail == null -> LoadingInfo(ref, Modifier.padding(start = ContentStart, top = 40.dp).width(InfoWidth))
            else -> Loaded(ref, detail, state, vm, onOpenTitle, screenHeight)
        }
    }
}

@Composable
private fun TitleBackdrop(ref: TitleRef, backdrop: Backdrop?, poster: Backdrop.BlurredPoster?, modifier: Modifier) {
    val bg = DroozColors.Background
    Box(modifier) {
        BackdropImage(ref, backdrop, poster, Modifier.fillMaxSize())
        // Mockup `.shadeR` (absolute direction): dark behind the info column on the right.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.3f to Color.Transparent,
                    0.62f to bg.copy(alpha = 0.7f),
                    1f to bg.copy(alpha = 0.97f),
                ),
            ),
        )
    }
}

/** Mockup `.shadeB` over the whole page: the backdrop melts into the background above the tiles. */
@Composable
private fun PageShade() {
    val bg = DroozColors.Background
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.3f to Color.Transparent,
                0.58f to bg.copy(alpha = 0.8f),
                0.7f to bg,
                1f to bg,
            ),
        ),
    )
}

@Composable
private fun LoadingInfo(ref: TitleRef, modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "titleSkeleton").animateFloat(
        0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "titleSkeletonAlpha",
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TitleText(prettifySlug(ref.slug), titleStyle(prettifySlug(ref.slug)), Modifier.fillMaxWidth(), color = Color.White, maxLines = 2)
        listOf(0.7f, 1f, 1f, 0.85f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(14.dp).alpha(pulse).background(DroozColors.Surface, DroozCardShape))
        }
    }
}

/** Long titles get a smaller size so two lines don't push the synopsis and buttons down. */
@Composable
private fun titleStyle(title: String = "") = MaterialTheme.typography.displayMedium.copy(
    fontSize = if (title.length > 22) 32.sp else 38.sp,
    lineHeight = if (title.length > 22) 36.sp else 42.sp,
    fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Right,
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Loaded(
    ref: TitleRef,
    detail: TitleDetail,
    state: TitleUiState,
    vm: TitleViewModel,
    onOpenTitle: (TitleRef) -> Unit,
    screenHeight: androidx.compose.ui.unit.Dp,
) {
    val isFilm = ref.kind == TitleKind.FILM
    val hasSuggestions = detail.suggestions.isNotEmpty()
    // Films have a single episode: the primary button plays it, so the bottom shows only suggestions.
    var suggestionsTab by rememberSaveable { mutableStateOf(false) }
    val showSuggestions = showSuggestions(isFilm, suggestionsTab, hasSuggestions)
    // Suggestion opened last, so coming Back lands on it.
    var openedSuggestion by rememberSaveable { mutableIntStateOf(-1) }

    val primaryRequester = remember { FocusRequester() }
    val tabsRequester = remember { FocusRequester() }
    val returnRequester = remember { FocusRequester() }
    val rowRequester = remember { FocusRequester() }

    val tabs = buildList {
        if (!isFilm) state.seasons.forEach { add(Tab.Season(it)) }
        if (hasSuggestions) add(Tab.Suggestions)
    }
    // A lone tab is only a heading: Down from the buttons goes straight to its row.
    val tabsFocusable = tabs.size > 1
    // Films never get a tile row: the primary button plays the film.
    val showEpisodes = !isFilm && !showSuggestions && state.episodes.isNotEmpty()
    val hasRow = showSuggestions || showEpisodes
    val downTarget = if (tabsFocusable) tabsRequester else rowRequester.takeIf { hasRow }
    LaunchedEffect(Unit) {
        if (openedSuggestion >= 0 && showSuggestions && returnRequester.requestFocusSoon()) return@LaunchedEffect
        if (!primaryRequester.requestFocusSoon()) downTarget?.requestFocusSoon()
    }
    val selectedTab = if (showSuggestions) Tab.Suggestions else Tab.Season(state.selectedSeason)

    // Two resting positions when the page is taller than the screen: the top while the buttons have
    // focus, the bottom while the tabs / rows do. (TV's default pivot scrolling would cut the title.)
    val scroll = rememberScrollState()
    var bottomFocused by remember { mutableStateOf(false) }
    LaunchedEffect(bottomFocused, scroll.maxValue) { scroll.animateScrollTo(if (bottomFocused) scroll.maxValue else 0) }
    val rowSpec = LocalBringIntoViewSpec.current

    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll) {
      Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Column(Modifier.fillMaxWidth().heightIn(min = screenHeight), verticalArrangement = Arrangement.SpaceBetween) {
            Info(ref, detail, state, vm, primaryRequester, downTarget)
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp).animateContentSize(tween(200))
                    .onFocusChanged { bottomFocused = it.hasFocus },
            ) {
              CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                if (tabs.isNotEmpty()) {
                    Tabs(tabs, selectedTab, tabsFocusable, tabsRequester, primaryRequester) { tab ->
                        when (tab) {
                            is Tab.Season -> { suggestionsTab = false; vm.selectSeason(tab.number) }
                            Tab.Suggestions -> suggestionsTab = true
                        }
                    }
                }
                if (showSuggestions) {
                    Suggestions(detail, openedSuggestion, returnRequester, rowRequester) { i, r ->
                        openedSuggestion = i
                        onOpenTitle(r)
                    }
                } else if (showEpisodes) {
                    // A fresh row (and scroll position) per season / language.
                    key(state.selectedSeason, state.dubSelected) { Episodes(state, vm, rowRequester) }
                }
              }
            }
        }
      }
    }
}

/** Vertical page scrolling is driven explicitly (see Loaded), not by focus. */
@OptIn(ExperimentalFoundationApi::class)
private object NoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun Info(
    ref: TitleRef,
    detail: TitleDetail,
    state: TitleUiState,
    vm: TitleViewModel,
    primaryRequester: FocusRequester,
    downRequester: FocusRequester?,
) {
    val card = detail.card
    val isFilm = ref.kind == TitleKind.FILM
    val labelStyle = MaterialTheme.typography.labelMedium
    Column(
        Modifier.padding(start = ContentStart, top = 36.dp).width(InfoWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = card.title.takeIf { it.isNotBlank() } ?: prettifySlug(ref.slug)
        TitleText(title, titleStyle(title), Modifier.fillMaxWidth(), color = Color.White, maxLines = 2)
        MetaLine(detail, isFilm)
        detail.awards.firstOrNull()?.let {
            Text(
                "🏆 $it", color = DroozColors.ImdbBadge, style = labelStyle,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        card.synopsis?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp),
                color = SecondaryText, maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
        }
        val directorLabel = if (isFilm) R.string.title_director else R.string.title_creator
        val context = LocalContext.current
        val people = creditsLine(card.director, card.stars) { context.getString(directorLabel, it) }
        if (people.isNotEmpty()) {
            Text(people, style = labelStyle, color = DroozColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Buttons(state, vm, primaryRequester, downRequester, Modifier.padding(top = 8.dp))
    }
}

/** IMDb, year, channel (series) or duration (film), genres, season count, then chips; overflow drops from the end. */
@Composable
private fun MetaLine(detail: TitleDetail, isFilm: Boolean) {
    val card = detail.card
    val episodes = detail.catalog.main.flatMap { it.episodes }
    val subtitleChip = when {
        episodes.any { ep -> ep.subtitles.any { it.lang == "fa" } } -> stringResource(R.string.title_persian_subtitle)
        episodes.any { it.subtitles.isNotEmpty() } -> stringResource(R.string.title_subtitle)
        else -> null
    }
    val seasonCount = detail.catalog.main.size.takeIf { !isFilm && it > 0 }
        ?.let { stringResource(R.string.title_season_count, it.toString().toPersianDigits()) }
    val plain = listOfNotNull(
        card.year?.toString()?.toPersianDigits(),
        if (isFilm) card.durationMin?.let(UiStrings::minutes) else detail.channel,
        card.genres.take(2).joinToString(" · ").takeIf { it.isNotEmpty() },
        seasonCount,
    )
    val chips = listOfNotNull(
        subtitleChip,
        UiStrings.DUB.takeIf { detail.catalog.dub.isNotEmpty() },
        "4K".takeIf { episodes.any { ep -> ep.videos.any { it.height >= 2160 } } },
    )
    val style = MaterialTheme.typography.labelMedium
    FitRow(Modifier.fillMaxWidth(), gap = 10.dp) {
        card.imdb?.let { ImdbBadge(it.score, style) }
        plain.forEach { MetaText(it, style) }
        chips.forEach { MetaChip(it, MaterialTheme.typography.labelSmall) }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Buttons(
    state: TitleUiState,
    vm: TitleViewModel,
    primaryRequester: FocusRequester,
    downRequester: FocusRequester?,
    modifier: Modifier,
) {
    val down = Modifier.focusProperties { if (downRequester != null) this.down = downRequester }
    // The buttons may be wider than the info column; they extend into the open (left) side, never squeeze.
    Row(
        modifier.wrapContentWidth(Alignment.Start, unbounded = true).focusRestorer(primaryRequester).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hasPrimary = state.primaryLabel.isNotEmpty()
        if (hasPrimary) {
            PillButton(
                "▶  ${state.primaryLabel}", { vm.play() }, down.focusRequester(primaryRequester),
            )
        }
        // Unknown state (the page didn't say): a plain label that claims neither.
        val myList = stringResource(R.string.title_my_list)
        val watchlistLabel = when (state.inWatchlist) {
            true -> "♥  $myList"
            false -> "♡  $myList"
            null -> myList
        }
        PillButton(
            watchlistLabel, vm::toggleWatchlist,
            if (hasPrimary) down else down.focusRequester(primaryRequester),
        )
        if (state.dubAvailable) LanguageSwitch(state.dubSelected, vm::toggleDub, down)
    }
}

private val PillShape = RoundedCornerShape(8.dp)

/** Mockup `.btn`: translucent pill, white with black text when focused. */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.85f),
            pressedContentColor = Color.Black,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text, Modifier.padding(horizontal = 20.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, softWrap = false,
            )
        }
    }
}

/** Mockup `.seg`: «زبان اصلی | دوبله»; one focus stop, a click switches language. */
@Composable
private fun LanguageSwitch(dub: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onToggle,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = PillShape)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.12f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.28f),
            focusedContentColor = Color.White,
        ),
    ) {
        Row(Modifier.padding(3.dp)) {
            Segment(stringResource(R.string.title_original), !dub)
            Segment(stringResource(R.string.title_dubbed), dub)
        }
    }
}

@Composable
private fun Segment(text: String, on: Boolean) {
    Text(
        text,
        Modifier
            .background(if (on) Color.White.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = if (on) Color.Black else Color.White,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Medium),
        maxLines = 1, softWrap = false,
    )
}

/** Season tabs + «آثار پیشنهادی»; focusing a tab selects it (TV tab behaviour). */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Tabs(
    tabs: List<Tab>,
    selected: Tab,
    focusable: Boolean,
    requester: FocusRequester,
    upRequester: FocusRequester,
    onSelect: (Tab) -> Unit,
) {
    val selectedRequester = remember { FocusRequester() }
    Row(
        Modifier.padding(start = ContentStart - 12.dp)
            .focusRequester(requester)
            // canFocus stays on the chips: a focusable group takes focus itself instead of entering.
            .focusProperties { enter = { selectedRequester } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            val label = when (tab) {
                is Tab.Season -> stringResource(R.string.title_season, tab.number.toString().toPersianDigits())
                Tab.Suggestions -> stringResource(R.string.title_suggestions)
            }
            TabChip(
                label, isSelected,
                Modifier
                    .then(if (isSelected) Modifier.focusRequester(selectedRequester) else Modifier)
                    .focusProperties { up = upRequester; canFocus = focusable }
                    .onFocusChanged { if (it.isFocused && !isSelected) onSelect(tab) },
            )
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, modifier: Modifier) {
    Surface(
        onClick = {},
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) Color.White else DroozColors.TextSecondary,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                maxLines = 1, softWrap = false,
            )
            Box(
                Modifier.width(24.dp).height(2.dp)
                    .background(if (selected) DroozColors.Accent else Color.Transparent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/** The selected season's tiles; entering the row lands on the next episode to watch (else the first). */
@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Episodes(state: TitleUiState, vm: TitleViewModel, requester: FocusRequester) {
    val eps = state.episodes
    val target = eps.indexOfFirst { it.episode.number == state.initialFocusEpisode }.coerceAtLeast(0)
    var last by remember { mutableIntStateOf(target) }
    val slot = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (target - 2).coerceAtLeast(0))
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().focusRequester(requester).focusProperties { enter = { slot } }.focusGroup(),
        contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, top = RowPadV, bottom = RowPadV),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(eps, key = { _, e -> e.episode.season * 10_000 + e.episode.number }) { i, e ->
            EpisodeTile(
                number = e.episode.number,
                minutes = e.minutes,
                watched = e.watched,
                progress = e.progress,
                onClick = { vm.play(e.episode) },
                modifier = Modifier
                    .then(if (i == last.coerceIn(0, eps.lastIndex)) Modifier.focusRequester(slot) else Modifier)
                    .onFocusChanged { if (it.isFocused) last = i },
            )
        }
    }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Suggestions(
    detail: TitleDetail,
    returnTo: Int,
    returnRequester: FocusRequester,
    requester: FocusRequester,
    onOpen: (Int, TitleRef) -> Unit,
) {
    val items = detail.suggestions
    if (items.isEmpty()) return
    var last by remember { mutableIntStateOf(returnTo.coerceIn(0, items.lastIndex)) }
    val slot = remember { FocusRequester() }
    LazyRow(
        modifier = Modifier.fillMaxWidth().focusRequester(requester).focusProperties { enter = { slot } }.focusGroup(),
        contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, top = RowPadV, bottom = RowPadV),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(items, key = { _, r -> "${r.kind}:${r.postId}" }) { i, r ->
            val m = Modifier
                .then(if (i == last) Modifier.focusRequester(slot) else Modifier)
                .then(if (i == returnTo) Modifier.focusRequester(returnRequester) else Modifier)
                .onFocusChanged { if (it.hasFocus) last = i }
            SuggestionCard(r, detail.suggestionPosters[r], { onOpen(i, r) }, m)
        }
    }
}

/** Poster with a one-line caption (the site gives suggestions only a link and a poster). */
@Composable
private fun SuggestionCard(ref: TitleRef, posterHash: String?, onClick: () -> Unit, modifier: Modifier) {
    Column(Modifier.width(SuggestionWidth), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            modifier = modifier.droozFocus(FocusScale.CARD).width(SuggestionWidth).height(SuggestionWidth * 1.5f),
            shape = ClickableSurfaceDefaults.shape(DroozCardShape),
            scale = droozSurfaceScale,
            colors = droozSurfaceColors(),
        ) {
            DroozImage(
                posterHash?.let(::posterUrl), ImageSizes.POSTER_W, ImageSizes.POSTER_H,
                Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
        TitleText(
            prettifySlug(ref.slug), MaterialTheme.typography.labelSmall, Modifier.zIndex(10f),
            color = DroozColors.TextSecondary, maxLines = 1,
        )
    }
}
