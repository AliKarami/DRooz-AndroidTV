package com.alikarami.drooz.ui.home

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.res.stringResource
import com.alikarami.drooz.R
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Text
import androidx.tv.material3.DrawerState
import com.alikarami.drooz.AppGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.scrollBy
import com.alikarami.drooz.data.HomeRows
import com.alikarami.drooz.data.RowSource
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.ErrorState
import com.alikarami.drooz.ui.common.PivotSpec
import com.alikarami.drooz.ui.common.bringIntoViewOnFocus
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.common.LandscapeCard
import com.alikarami.drooz.ui.common.PosterCard
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors

class HomeViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(graph.siteApi, graph.titleInfo, homeCache = graph.homeCache) as T
}

/** Width of the collapsed rail; rows are clipped here so cards never slide under its icons. */
private val RailWidth = 80.dp
/** Distance from the rail-side (right) screen edge to the hero text and the first card. */
private val ContentStart = 96.dp
/** [ContentStart] measured inside the rows area, which already starts after the rail. */
private val RowStart = ContentStart - RailWidth
private val ContentEnd = 48.dp
private val RowHeader = 34.dp
/** Room above/below cards so the focus scale and ring are not clipped by the row. */
private val RowPadV = 12.dp
private val CardGap = 16.dp
private val LandscapeWidth = 220.dp
private val PosterWidth = 120.dp

private const val EXIT_WINDOW_MS = 2_000L

private val RowUi.isHidden get() = requested && !loading && !error && items.isEmpty()
private val recentKeys = HomeRows.rows.filter { it.source is RowSource.Recent }.map { it.key }.toSet()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpen: (TitleRef) -> Unit,
    onExit: () -> Unit,
    onRailSelect: (RailItem) -> Unit = {},
) {
    // Read through derived states: a hero change (every focus move) must not recompose the rows and
    // their cards — on the TV that cost a ~90 ms frame per key press. Only [HomeHero] reads the hero.
    val state = vm.uiState.collectAsStateWithLifecycle()
    val allRows by remember { derivedStateOf(referentialEqualityPolicy()) { state.value.rows } }
    val error by remember { derivedStateOf(structuralEqualityPolicy()) { state.value.error } }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // Not saveable: home's state is kept across pushed pages, but it must come back with the rail closed
    // (a rail pick leaves the drawer open at the moment home is saved).
    val drawerState = remember { DrawerState(DrawerValue.Closed) }
    var focusedRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastBack by remember { mutableLongStateOf(0L) }
    // Back on home after a play: «ادامه تماشا» is revalidated in place (focus stays on the same title).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }

    BackHandler {
        if (drawerState.currentValue == DrawerValue.Open) {
            focusManager.moveFocus(FocusDirection.Left)
            return@BackHandler
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < EXIT_WINDOW_MS) onExit()
        else {
            lastBack = now
            Toast.makeText(context, R.string.home_exit_hint, Toast.LENGTH_SHORT).show()
        }
    }

    val rows = remember(allRows) { allRows.filterNot { it.isHidden } }
    var entered by remember { mutableStateOf(false) }
    // Where focus lands on entry: the last focused row (coming back from a title) or the first row with cards.
    // Once entered it is no longer needed, and not reading focusedRowKey here keeps row changes cheap.
    val entryRow = if (entered) null else rows.firstOrNull { it.key == focusedRowKey && it.items.isNotEmpty() }
        ?: rows.firstOrNull { it.items.isNotEmpty() }
    val entryIndex = entryRow?.let { vm.focusedIndex(it.key).coerceIn(0, it.items.lastIndex) } ?: 0
    val entryRequester = remember { FocusRequester() }
    val placeholderRequester = remember { FocusRequester() }
    // Each composed row's focus slot, so Up/Down go straight to the neighbouring row's remembered card
    // (Netflix-style) instead of a geometric search that misfires while the column is scrolling.
    val slots = remember { mutableStateMapOf<String, FocusRequester>() }
    // Row that most recently gained focus; a swapped-out placeholder only reclaims focus if it still owns it.
    val focusOwner = remember { mutableStateOf<String?>(null) }
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Only a row that is laid out in the viewport can take focus usefully: a cached-but-unplaced row has
    // a slot, but focusing it would not scroll it into view.
    val placedSlot: (String) -> FocusRequester? = { key ->
        slots[key]?.takeIf { columnState.layoutInfo.visibleItemsInfo.any { it.key == key } }
    }
    // A neighbouring row scrolled out of view: nudge it back in, then focus it.
    val focusRow: (String) -> Unit = { key ->
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) scope.launch {
            // Nudge one pixel so the row re-enters composition; focusing its card then lets the
            // pivot spec animate the column (animateScrollToItem took 1-1.6 s to get there).
            val up = index <= columnState.firstVisibleItemIndex
            columnState.scrollBy(if (up) -1f else 1f)
            val slot = withTimeoutOrNull(200) { snapshotFlow { placedSlot(key) }.filterNotNull().first() }
                ?: run { columnState.scrollToItem(index); snapshotFlow { placedSlot(key) }.filterNotNull().first() }
            slot.requestFocusSoon()
        }
    }
    val homeFailed = error != null && rows.none { it.items.isNotEmpty() || it.loading }
    val homeErrorRequester = remember { FocusRequester() }
    LaunchedEffect(entryRow?.key, entered, homeFailed) {
        if (homeFailed) {
            homeErrorRequester.requestFocusSoon()
            return@LaunchedEffect
        }
        if (entered) return@LaunchedEffect
        // Until the first cards arrive, hold focus on the first row's placeholder so the rail doesn't grab it.
        val target = if (entryRow == null) placeholderRequester else entryRequester
        if (target.requestFocusSoon() && entryRow != null) entered = true
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimBrush = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Black.copy(alpha = 0.9f))),
        drawerContent = {
            HomeRail(RailItem.Home, onSelect = { item ->
                if (item == RailItem.Home) focusManager.moveFocus(FocusDirection.Left)
                onRailSelect(item)
            })
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(DroozColors.Background)) {
            val rowsTop = maxHeight * 0.5f
            HomeHero(
                state,
                rowTitle = { rows.firstOrNull { it.key == focusedRowKey }?.title },
                backdropModifier = Modifier.fillMaxWidth().height(maxHeight * 0.62f),
                infoModifier = Modifier.align(Alignment.TopStart).padding(start = ContentStart, top = 36.dp).width(480.dp),
            )
            if (homeFailed) {
                ErrorState(
                    stringResource(R.string.home_failed), stringResource(R.string.retry), vm::refresh,
                    Modifier.padding(top = rowsTop).focusRequester(homeErrorRequester).focusGroup(),
                )
            } else {
                val density = LocalDensity.current
                val columnSpec = remember(density) { PivotSpec(with(density) { (RowHeader + RowPadV).toPx() }) }
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val rowSpec = remember(density, rtl) { PivotSpec(with(density) { RowStart.toPx() }, fromRight = rtl) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides columnSpec) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(top = rowsTop, start = RailWidth),
                        state = columnState,
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        itemsIndexed(rows, key = { _, r -> r.key }) { rowIndex, row ->
                            LaunchedEffect(row.key) { vm.loadRowIfNeeded(row.key) }
                            val rowSlot = remember { FocusRequester() }
                            DisposableEffect(row.key) {
                                slots[row.key] = rowSlot
                                onDispose { slots.remove(row.key) }
                            }
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                HomeRow(
                                    row = row,
                                    initialIndex = vm.focusedIndex(row.key),
                                    entryIndex = if (row.key == entryRow?.key) entryIndex else -1,
                                    entryRequester = entryRequester,
                                    placeholderRequester = placeholderRequester.takeIf { rowIndex == 0 },
                                    slotRequester = rowSlot,
                                    slots = placedSlot,
                                    upKey = rows.getOrNull(rowIndex - 1)?.key,
                                    downKey = rows.getOrNull(rowIndex + 1)?.key,
                                    focusRow = focusRow,
                                    focusOwner = focusOwner,
                                    focusedIndex = { vm.focusedIndex(row.key) },
                                    drawerClosed = { drawerState.currentValue == DrawerValue.Closed },
                                    onFocus = { index ->
                                        focusedRowKey = row.key
                                        vm.onFocus(row.key, index)
                                        // Start loading the next rows before the user reaches them.
                                        rows.subList(rowIndex + 1, minOf(rowIndex + 3, rows.size))
                                            .forEach { vm.loadRowIfNeeded(it.key) }
                                    },
                                    onOpen = onOpen,
                                    onRetry = { vm.retryRow(row.key) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The only reader of the hero state, so a focus move recomposes the hero and nothing else. */
@Composable
private fun HomeHero(
    state: State<HomeUiState>,
    rowTitle: () -> String?,
    backdropModifier: Modifier,
    infoModifier: Modifier,
) {
    val hero = rememberSettledHero(state.value.hero)
    HeroBackdrop(hero, backdropModifier)
    HeroInfo(hero, rowTitle(), infoModifier)
}

private enum class Slot { Cards, Error, Skeleton }

@Composable
private fun HomeRow(
    row: RowUi,
    initialIndex: Int,
    entryIndex: Int,
    entryRequester: FocusRequester,
    placeholderRequester: FocusRequester?,
    slotRequester: FocusRequester,
    slots: (String) -> FocusRequester?,
    upKey: String?,
    downKey: String?,
    focusRow: (String) -> Unit,
    focusOwner: MutableState<String?>,
    focusedIndex: () -> Int,
    drawerClosed: () -> Boolean,
    onFocus: (Int) -> Unit,
    onOpen: (TitleRef) -> Unit,
    onRetry: () -> Unit,
) {
    val landscape = row.items.firstOrNull()?.landscape ?: (row.key in recentKeys)
    val slot = when {
        row.items.isNotEmpty() -> Slot.Cards
        row.error -> Slot.Error
        else -> Slot.Skeleton
    }
    // [slotRequester] is the row's focus target, whatever currently fills it: the last focused card,
    // the retry button or the skeleton.
    val nav = remember(upKey, downKey, slots) {
        Modifier.focusProperties {
            down = downKey?.let(slots) ?: FocusRequester.Default
            up = upKey?.let(slots) ?: FocusRequester.Default
        }
    }
    // True while focus sits on a skeleton/error that gets swapped out, so it follows into the replacement.
    var holdFocus by remember { mutableStateOf(false) }
    val currentSlot by rememberUpdatedState(slot)
    val placeholderFocus: (Slot, Boolean) -> Unit = { mine, focused ->
        if (focused) {
            holdFocus = true
            focusOwner.value = row.key
        } else if (currentSlot == mine) holdFocus = false
    }
    LaunchedEffect(slot) {
        if (!holdFocus || focusOwner.value != row.key) return@LaunchedEffect
        slotRequester.requestFocusSoon()
        if (slot == Slot.Cards) holdFocus = false
    }
    Column(
        Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val target = when (e.key) {
                Key.DirectionUp -> upKey
                Key.DirectionDown -> downKey
                else -> null
            }
            if (target == null || slots(target) != null) return@onPreviewKeyEvent false
            focusRow(target)
            true
        },
    ) {
        Box(Modifier.height(RowHeader).padding(start = RowStart), contentAlignment = Alignment.BottomStart) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
        }
        when (slot) {
            Slot.Cards -> {
                // Focus was on a card a refresh removed if this row owned focus and the rail is closed.
                val reclaim = { focusOwner.value == row.key && drawerClosed() }
                CardsRow(row, landscape, initialIndex, entryIndex, entryRequester, slotRequester, nav, focusedIndex, reclaim, onOpen) { i ->
                    focusOwner.value = row.key
                    onFocus(i)
                }
            }
            Slot.Error -> RowError(onRetry, slotRequester, nav) { placeholderFocus(Slot.Error, it) }
            Slot.Skeleton ->
                SkeletonRow(landscape, slotRequester, placeholderRequester, nav) { placeholderFocus(Slot.Skeleton, it) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CardsRow(
    row: RowUi,
    landscape: Boolean,
    initialIndex: Int,
    entryIndex: Int,
    entryRequester: FocusRequester,
    slot: FocusRequester,
    nav: Modifier,
    focusedIndex: () -> Int,
    reclaim: () -> Boolean,
    onOpen: (TitleRef) -> Unit,
    onFocus: (Int) -> Unit,
) {
    val start = initialIndex.coerceIn(0, row.items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    // [slot] always sits on the row's last focused card, so entering the row (Up/Down or a slot
    // hand-over) lands there; it is also the focusRestorer fallback.
    // Tracked by title, not position: a background refresh may reorder the row under the user.
    var last by remember { mutableStateOf(row.items.getOrNull(start)?.ref) }
    val slotRef = last?.takeIf { ref -> row.items.any { it.ref == ref } } ?: row.items.firstOrNull()?.ref
    val currentReclaim by rememberUpdatedState(reclaim)
    // A refresh removed the last focused card: the slot moves to the card now in its place (HomeViewModel
    // keeps that position), and if focus was on the removed card it follows, so the D-pad keeps working.
    LaunchedEffect(row.items) {
        val gone = last ?: return@LaunchedEffect
        if (row.items.any { it.ref == gone }) return@LaunchedEffect
        last = row.items.getOrNull(focusedIndex())?.ref ?: row.items.firstOrNull()?.ref
        if (currentReclaim()) slot.requestFocusSoon()
    }
    // Cards keep the same callbacks and modifiers across recompositions, so a row update skips them.
    val currentOnFocus by rememberUpdatedState(onFocus)
    val currentOnOpen by rememberUpdatedState(onOpen)
    LazyRow(
        state = listState,
        // Entering the row by any route lands on [slot]. (focusRestorer mis-restored rows that had been
        // scrolled out of composition, picking an unrelated card.)
        modifier = Modifier.fillMaxWidth().focusProperties { enter = { slot } }.focusGroup(),
        contentPadding = PaddingValues(start = RowStart, end = ContentEnd, top = RowPadV, bottom = RowPadV),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        // Keyed by title (titles are unique per row, see HomeViewModel.setItems): a reordering refresh moves
        // the focused card instead of recreating it, so focus and the hero stay on the same title.
        itemsIndexed(row.items, key = { _, c -> "${c.ref.kind}:${c.ref.postId}" }) { i, card ->
            val index by rememberUpdatedState(i)
            // Focus only scrolls a card that is partly off-screen; ask explicitly so the pivot
            // specs place every focused card (and its row) at the fixed focus position.
            val isSlot = card.ref == slotRef
            val isEntry = i == entryIndex
            val m = remember(nav, isSlot, isEntry) {
                nav
                    .then(if (isSlot) Modifier.focusRequester(slot) else Modifier)
                    .then(if (isEntry) Modifier.focusRequester(entryRequester) else Modifier)
                    .bringIntoViewOnFocus { last = card.ref; currentOnFocus(index) }
            }
            val open = remember(card.ref) { { currentOnOpen(card.ref) } }
            if (landscape) {
                LandscapeCard(card.title, card.subtitle, card.posterUrl, progress = null, onClick = open, modifier = m)
            } else {
                PosterCard(card.title, card.posterUrl, onClick = open, modifier = m, width = PosterWidth)
            }
        }
    }
}

@Composable
private fun RowError(onRetry: () -> Unit, requester: FocusRequester, nav: Modifier, onFocusChanged: (Boolean) -> Unit) {
    Row(
        Modifier.padding(start = RowStart, top = RowPadV, bottom = RowPadV)
            .focusRequester(requester)
            .focusGroup()
            .bringIntoViewOnFocus()
            .onFocusChanged { onFocusChanged(it.hasFocus) },
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.home_row_failed), style = MaterialTheme.typography.bodyMedium,
            color = DroozColors.TextSecondary)
        Button(onClick = onRetry, modifier = nav) { Text(stringResource(R.string.retry)) }
    }
}

/**
 * Pulsing placeholders with the real card geometry, so rows don't jump when items arrive. The row is
 * focusable (ring on its first placeholder) so D-pad moves into a row that is still loading.
 */
@Composable
private fun SkeletonRow(
    landscape: Boolean,
    requester: FocusRequester,
    entryRequester: FocusRequester?,
    nav: Modifier,
    onFocusChanged: (Boolean) -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeletonAlpha",
    )
    var focused by remember { mutableStateOf(false) }
    val (w, h) = if (landscape) LandscapeWidth to 124.dp else PosterWidth to PosterWidth * 1.5f
    Row(
        Modifier.padding(start = RowStart, top = RowPadV, bottom = RowPadV)
            .focusRequester(requester)
            .then(if (entryRequester != null) Modifier.focusRequester(entryRequester) else Modifier)
            .bringIntoViewOnFocus()
            .onFocusChanged { focused = it.isFocused; onFocusChanged(it.isFocused) }
            .then(nav)
            .focusable(),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        repeat(if (landscape) 5 else 8) { i ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(w, h)
                        .then(if (i == 0 && focused) Modifier.border(3.dp, Color.White, DroozCardShape) else Modifier)
                        .background(DroozColors.Surface.copy(alpha = if (i == 0 && focused) 1f else pulse), DroozCardShape),
                )
                Box(Modifier.size(w * 0.6f, 12.dp).alpha(pulse).background(DroozColors.Surface, DroozCardShape))
            }
        }
    }
}
