package com.alikarami.drooz.ui.list

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.R
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.ErrorState
import com.alikarami.drooz.ui.common.PivotSpec
import com.alikarami.drooz.ui.common.PosterCard
import com.alikarami.drooz.ui.common.bringIntoViewOnFocus
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.home.HeroBackdrop
import com.alikarami.drooz.ui.home.HeroInfo
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors

class ListViewModelFactory(private val graph: AppGraph, private val path: String, private val title: String) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ListViewModel(graph.siteApi, graph.titleInfo, path, title) as T
}

private const val Columns = 7
/** Same right-edge inset as home's hero text and first card, so the two screens line up. */
private val ContentStart = 96.dp
private val ContentEnd = 48.dp
private val CardGap = 16.dp
private val RowGap = 20.dp
/** Room above the focused row so its focus scale and ring are not clipped by the grid. */
private val GridPadTop = 12.dp
/** The compact hero strip: the grid starts at this fraction of the screen height. */
private const val HeroFraction = 0.35f

/**
 * A site list as a 7-column poster grid (RTL: the first column is on the right) under a compact
 * focus-following hero. Focus pins the focused row to the top of the grid; pages load as it nears the end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListScreen(vm: ListViewModel, onOpen: (TitleRef) -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize().background(DroozColors.Background)) {
        val gridTop = maxHeight * HeroFraction
        val cellWidth = (maxWidth - ContentStart - ContentEnd - CardGap * (Columns - 1)) / Columns
        HeroBackdrop(state.hero, Modifier.fillMaxWidth().height(maxHeight * 0.5f))
        Column(
            Modifier.align(Alignment.TopStart).padding(start = ContentStart, top = 24.dp).width(520.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                state.title, color = DroozColors.Accent, fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.labelLarge, maxLines = 1,
            )
            HeroInfo(state.hero, rowTitle = null, compact = true)
        }
        val content = Modifier.fillMaxSize().padding(top = gridTop)
        when {
            state.items.isNotEmpty() -> {
                val density = LocalDensity.current
                val spec = remember(density) { PivotSpec(with(density) { GridPadTop.toPx() }) }
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    PosterGrid(vm, state, cellWidth, onOpen, content)
                }
            }
            state.error != null -> {
                val requester = remember { FocusRequester() }
                LaunchedEffect(Unit) { requester.requestFocusSoon() }
                ErrorState(
                    stringResource(R.string.list_failed), stringResource(R.string.retry), vm::retry,
                    content.focusRequester(requester).focusGroup(),
                )
            }
            state.loading -> SkeletonGrid(cellWidth, content)
            else -> Box(content, contentAlignment = Alignment.TopCenter) {
                Text(stringResource(R.string.list_empty), Modifier.padding(top = 48.dp),
                    style = MaterialTheme.typography.titleMedium, color = DroozColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun PosterGrid(vm: ListViewModel, state: ListUiState, cellWidth: Dp, onOpen: (TitleRef) -> Unit, modifier: Modifier) {
    // Kept across the title page round-trip (saveable), so Back lands on the same scroll position…
    val gridState: LazyGridState = rememberLazyGridState()
    // …and on the card that was focused.
    val entryIndex = remember { vm.focusedIndex.coerceIn(0, state.items.lastIndex) }
    val entryRequester = remember { FocusRequester() }
    var entered by remember { mutableStateOf(false) }
    // Retry replaces the focused button; hand focus to the last card first so it is never lost.
    val lastRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == entryIndex }) gridState.scrollToItem(entryIndex)
        if (entryRequester.requestFocusSoon()) entered = true
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(Columns),
        state = gridState,
        modifier = modifier.focusGroup(),
        contentPadding = PaddingValues(start = ContentStart, end = ContentEnd, top = GridPadTop, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        itemsIndexed(state.items, key = { _, c -> "${c.ref.kind}:${c.ref.postId}" }) { i, card ->
            PosterCard(
                card.title, card.posterUrl, onClick = { onOpen(card.ref) },
                modifier = (if (i == entryIndex && !entered) Modifier.focusRequester(entryRequester) else Modifier)
                    .then(if (i == state.items.lastIndex) Modifier.focusRequester(lastRequester) else Modifier)
                    // Focus only scrolls a card that is partly off-screen; ask explicitly so the pivot
                    // spec pins every focused row to the top of the grid.
                    .bringIntoViewOnFocus { vm.onFocusIndex(i) },
                width = cellWidth,
            )
        }
        if (state.loadingMore) {
            items(Columns, key = { "more:$it" }) { SkeletonCard(cellWidth, pulse()) }
        }
        if (state.pageError) {
            item(key = "page-error", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.home_row_failed), style = MaterialTheme.typography.bodyMedium,
                        color = DroozColors.TextSecondary)
                    Button(onClick = { runCatching { lastRequester.requestFocus() }; vm.retry() }, modifier = Modifier.bringIntoViewOnFocus()) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun pulse(): Float {
    val p by rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.35f, 0.7f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeletonAlpha",
    )
    return p
}

@Composable
private fun SkeletonCard(width: Dp, alpha: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width, width * 1.5f).alpha(alpha).background(DroozColors.Surface, DroozCardShape))
        Box(Modifier.size(width * 0.6f, 12.dp).alpha(alpha).background(DroozColors.Surface, DroozCardShape))
    }
}

/** First-page placeholder with the real grid geometry, so nothing jumps when the cards arrive. */
@Composable
private fun SkeletonGrid(cellWidth: Dp, modifier: Modifier) {
    val alpha = pulse()
    Column(
        modifier.padding(start = ContentStart, end = ContentEnd, top = GridPadTop),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                repeat(Columns) { SkeletonCard(cellWidth, alpha) }
            }
        }
    }
}
