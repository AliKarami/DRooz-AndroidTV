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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.R
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.PivotSpec
import com.alikarami.drooz.ui.common.PosterCard
import com.alikarami.drooz.ui.common.bringIntoViewOnFocus
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.home.CardUi
import com.alikarami.drooz.ui.theme.DroozCardShape
import com.alikarami.drooz.ui.theme.DroozColors

/** The poster grid's geometry, shared by list grids and search results. */
const val GridColumns = 7
/** Same right-edge inset as home's hero text and first card, so the screens line up. */
val GridContentStart = 96.dp
val GridContentEnd = 48.dp
private val CardGap = 16.dp
private val RowGap = 20.dp
/** Room above the focused row so its focus scale and ring are not clipped by the grid. */
val GridPadTop = 12.dp

fun gridCellWidth(maxWidth: Dp): Dp = (maxWidth - GridContentStart - GridContentEnd - CardGap * (GridColumns - 1)) / GridColumns

/** Pins every focused grid row to the top of the grid (Netflix-style fixed focus line). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideGridPivot(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density) { PivotSpec(with(density) { GridPadTop.toPx() }) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * A 7-column poster grid (RTL: the first column is on the right). Focus enters at [entryIndex] on first
 * composition when [takeFocus]; [firstCard] (optional) lets the caller move focus to the first card later.
 * Use inside [ProvideGridPivot].
 */
@Composable
fun PosterGrid(
    items: List<CardUi>,
    cellWidth: Dp,
    onOpen: (TitleRef) -> Unit,
    onFocusIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    entryIndex: Int = 0,
    takeFocus: Boolean = true,
    firstCard: FocusRequester? = null,
    loadingMore: Boolean = false,
    pageError: Boolean = false,
    onRetry: () -> Unit = {},
) {
    // Kept across the title page round-trip (saveable), so Back lands on the same scroll position…
    val gridState: LazyGridState = rememberLazyGridState()
    // …and on the card that was focused.
    val entry = remember { entryIndex.coerceIn(0, items.lastIndex) }
    val entryRequester = remember { FocusRequester() }
    var entered by remember { mutableStateOf(!takeFocus) }
    // Retry replaces the focused button; hand focus to the last card first so it is never lost.
    val lastRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == entry }) gridState.scrollToItem(entry)
        if (entryRequester.requestFocusSoon()) entered = true
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(GridColumns),
        state = gridState,
        modifier = modifier.focusGroup(),
        contentPadding = PaddingValues(start = GridContentStart, end = GridContentEnd, top = GridPadTop, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        itemsIndexed(items, key = { _, c -> "${c.ref.kind}:${c.ref.postId}" }) { i, card ->
            PosterCard(
                card.title, card.posterUrl, onClick = { onOpen(card.ref) },
                modifier = (if (i == entry && !entered) Modifier.focusRequester(entryRequester) else Modifier)
                    .then(if (i == 0 && firstCard != null) Modifier.focusRequester(firstCard) else Modifier)
                    .then(if (i == items.lastIndex) Modifier.focusRequester(lastRequester) else Modifier)
                    // Focus only scrolls a card that is partly off-screen; ask explicitly so the pivot
                    // spec pins every focused row to the top of the grid.
                    .bringIntoViewOnFocus { onFocusIndex(i) },
                width = cellWidth,
            )
        }
        if (loadingMore) {
            items(GridColumns, key = { "more:$it" }) { SkeletonCard(cellWidth, pulse()) }
        }
        if (pageError) {
            item(key = "page-error", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.home_row_failed), style = MaterialTheme.typography.bodyMedium,
                        color = DroozColors.TextSecondary)
                    Button(onClick = { runCatching { lastRequester.requestFocus() }; onRetry() }, modifier = Modifier.bringIntoViewOnFocus()) {
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
fun SkeletonGrid(cellWidth: Dp, modifier: Modifier = Modifier, rows: Int = 2) {
    val alpha = pulse()
    Column(
        modifier.padding(start = GridContentStart, end = GridContentEnd, top = GridPadTop),
        verticalArrangement = Arrangement.spacedBy(RowGap),
    ) {
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                repeat(GridColumns) { SkeletonCard(cellWidth, alpha) }
            }
        }
    }
}
