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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import androidx.tv.material3.Button
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.alikarami.drooz.AppGraph
import kotlinx.coroutines.launch
import com.alikarami.drooz.data.HomeRows
import com.alikarami.drooz.data.RowSource
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.ErrorState
import com.alikarami.drooz.ui.common.LandscapeCard
import com.alikarami.drooz.ui.common.PosterCard
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors

class HomeViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(graph.siteApi, graph.titleInfo) as T
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
private const val EXIT_HINT = "برای خروج دوباره بازگشت را بزنید"
private const val ROW_FAILED = "بارگذاری نشد"
private const val RETRY = "تلاش دوباره"
private const val HOME_FAILED = "صفحه اصلی بارگذاری نشد"

/**
 * Scrolls so the focused child sits [pivotPx] from the container's leading edge (Netflix-style fixed
 * focus). Offsets arrive in absolute (left-based) coordinates, so in RTL the leading edge is the right.
 */
@OptIn(ExperimentalFoundationApi::class)
private class PivotSpec(private val pivotPx: Float, private val fromRight: Boolean = false) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (fromRight) offset - (containerSize - pivotPx - size) else offset - pivotPx
}

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
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var focusedRowKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastBack by remember { mutableLongStateOf(0L) }

    BackHandler {
        if (drawerState.currentValue == DrawerValue.Open) {
            focusManager.moveFocus(FocusDirection.Left)
            return@BackHandler
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < EXIT_WINDOW_MS) onExit()
        else {
            lastBack = now
            Toast.makeText(context, EXIT_HINT, Toast.LENGTH_SHORT).show()
        }
    }

    val rows = state.rows.filterNot { it.isHidden }
    // Where focus lands on entry: the last focused row (coming back from a title) or the first row with cards.
    val entryRow = rows.firstOrNull { it.key == focusedRowKey && it.items.isNotEmpty() }
        ?: rows.firstOrNull { it.items.isNotEmpty() }
    val entryIndex = entryRow?.let { vm.focusedIndex(it.key).coerceIn(0, it.items.lastIndex) } ?: 0
    val entryRequester = remember { FocusRequester() }
    val placeholderRequester = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(entryRow?.key, entered) {
        if (entered) return@LaunchedEffect
        // Until the first cards arrive, hold focus on a placeholder so the rail doesn't grab it.
        val target = if (entryRow == null) placeholderRequester else entryRequester
        // The target may attach a frame or two after the rows arrive; keep trying briefly.
        repeat(30) {
            withFrameNanos { }
            if (runCatching { target.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) {
                if (entryRow != null) entered = true
                return@LaunchedEffect
            }
        }
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
            HeroBackdrop(state.hero?.backdrop, Modifier.fillMaxWidth().height(maxHeight * 0.62f))
            HeroInfo(
                state.hero,
                rows.firstOrNull { it.key == focusedRowKey }?.title,
                Modifier.align(Alignment.TopStart).padding(start = ContentStart, top = 36.dp).width(440.dp),
            )
            val homeFailed = state.error != null && rows.none { it.items.isNotEmpty() || it.loading }
            if (homeFailed) {
                ErrorState(HOME_FAILED, RETRY, vm::refresh, Modifier.padding(top = rowsTop))
            } else {
                val density = LocalDensity.current
                val columnSpec = remember(density) { PivotSpec(with(density) { (RowHeader + RowPadV).toPx() }) }
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val rowSpec = remember(density, rtl) { PivotSpec(with(density) { RowStart.toPx() }, fromRight = rtl) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides columnSpec) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(top = rowsTop, start = RailWidth),
                        contentPadding = PaddingValues(bottom = 48.dp),
                    ) {
                        itemsIndexed(rows, key = { _, r -> r.key }) { rowIndex, row ->
                            LaunchedEffect(row.key) { vm.loadRowIfNeeded(row.key) }
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                HomeRow(
                                    row = row,
                                    initialIndex = vm.focusedIndex(row.key),
                                    entryIndex = if (row.key == entryRow?.key) entryIndex else -1,
                                    entryRequester = entryRequester,
                                    placeholderRequester = placeholderRequester.takeIf { rowIndex == 0 },
                                    onFocus = { index -> focusedRowKey = row.key; vm.onFocus(row.key, index) },
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

@Composable
private fun HomeRow(
    row: RowUi,
    initialIndex: Int,
    entryIndex: Int,
    entryRequester: FocusRequester,
    placeholderRequester: FocusRequester?,
    onFocus: (Int) -> Unit,
    onOpen: (TitleRef) -> Unit,
    onRetry: () -> Unit,
) {
    val landscape = row.items.firstOrNull()?.landscape ?: (row.key in recentKeys)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.height(RowHeader).padding(start = RowStart), contentAlignment = Alignment.BottomStart) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
        }
        when {
            row.items.isNotEmpty() -> CardsRow(row, landscape, initialIndex, entryIndex, entryRequester, onFocus, onOpen)
            row.error -> RowError(onRetry)
            else -> SkeletonRow(landscape, placeholderRequester)
        }
    }
}

@Composable
private fun CardsRow(
    row: RowUi,
    landscape: Boolean,
    initialIndex: Int,
    entryIndex: Int,
    entryRequester: FocusRequester,
    onFocus: (Int) -> Unit,
    onOpen: (TitleRef) -> Unit,
) {
    val start = initialIndex.coerceIn(0, row.items.lastIndex)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
    val restore = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().focusRestorer(restore),
        contentPadding = PaddingValues(start = RowStart, end = ContentEnd, top = RowPadV, bottom = RowPadV),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        itemsIndexed(row.items, key = { i, c -> "$i:${c.ref.kind}:${c.ref.postId}" }) { i, card ->
            // Focus only scrolls a card that is partly off-screen; ask explicitly so the pivot
            // specs place every focused card (and its row) at the fixed focus position.
            val bringIntoView = remember { BringIntoViewRequester() }
            val m = Modifier
                .then(if (i == start) Modifier.focusRequester(restore) else Modifier)
                .then(if (i == entryIndex) Modifier.focusRequester(entryRequester) else Modifier)
                .bringIntoViewRequester(bringIntoView)
                .onFocusChanged {
                    if (it.hasFocus) {
                        onFocus(i)
                        scope.launch { bringIntoView.bringIntoView() }
                    }
                }
            if (landscape) {
                LandscapeCard(card.title, card.subtitle, card.posterUrl, progress = null, onClick = { onOpen(card.ref) },
                    modifier = m)
            } else {
                PosterCard(card.title, card.posterUrl, onClick = { onOpen(card.ref) }, modifier = m, width = PosterWidth)
            }
        }
    }
}

@Composable
private fun RowError(onRetry: () -> Unit) {
    Row(
        Modifier.padding(start = RowStart, top = RowPadV, bottom = RowPadV),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ROW_FAILED, style = MaterialTheme.typography.bodyMedium, color = DroozColors.TextSecondary)
        Button(onClick = onRetry) { Text(RETRY) }
    }
}

/** Pulsing placeholders with the real card geometry, so rows don't jump when items arrive. */
@Composable
private fun SkeletonRow(landscape: Boolean, focusRequester: FocusRequester?) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeletonAlpha",
    )
    val (w, h) = if (landscape) LandscapeWidth to 124.dp else PosterWidth to PosterWidth * 1.5f
    Row(
        Modifier.padding(start = RowStart, top = RowPadV, bottom = RowPadV).alpha(pulse)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester).focusable() else Modifier),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        repeat(if (landscape) 5 else 8) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(w, h).background(DroozColors.Surface, DroozCardShape))
                Box(Modifier.size(w * 0.6f, 12.dp).background(DroozColors.Surface, DroozCardShape))
            }
        }
    }
}
