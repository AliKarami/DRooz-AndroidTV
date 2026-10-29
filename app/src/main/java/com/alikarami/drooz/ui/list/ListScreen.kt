package com.alikarami.drooz.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.alikarami.drooz.AppGraph
import com.alikarami.drooz.R
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.ui.common.ErrorState
import com.alikarami.drooz.ui.common.requestFocusSoon
import com.alikarami.drooz.ui.home.HeroBackdrop
import com.alikarami.drooz.ui.home.HeroInfo
import com.alikarami.drooz.ui.theme.DroozColors

class ListViewModelFactory(private val graph: AppGraph, private val path: String, private val title: String) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ListViewModel(graph.siteApi, graph.titleInfo, path, title) as T
}

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
        val cellWidth = gridCellWidth(maxWidth)
        HeroBackdrop(state.hero, Modifier.fillMaxWidth().height(maxHeight * 0.5f))
        Column(
            Modifier.align(Alignment.TopStart).padding(start = GridContentStart, top = 24.dp).width(520.dp),
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
            state.items.isNotEmpty() -> ProvideGridPivot {
                PosterGrid(
                    state.items, cellWidth, onOpen, vm::onFocusIndex, content,
                    entryIndex = vm.focusedIndex,
                    loadingMore = state.loadingMore, pageError = state.pageError, onRetry = vm::retry,
                )
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

