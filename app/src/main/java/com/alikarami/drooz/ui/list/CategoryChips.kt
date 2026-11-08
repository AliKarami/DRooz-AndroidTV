package com.alikarami.drooz.ui.list

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alikarami.drooz.ui.common.PillShape
import com.alikarami.drooz.ui.theme.DroozColors

/** Height the chip row takes above the grid (chip + room for its focus scale). */
val ChipRowHeight = 52.dp

/**
 * The Series / Films page's categories as one horizontal row (RTL: the default listing on the right). OK
 * selects a chip; entering the row from the grid lands on the selected chip, not the nearest one.
 */
@Suppress("DEPRECATION") // focusProperties.enter: the same redirect the rail and the title tabs use
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryChipRow(chips: List<CategoryChip>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val selectedRequester = remember { FocusRequester() }
    Row(
        modifier
            // A focus group, so moving between chips is not seen as re-entering (that would snap back).
            .focusProperties { enter = { selectedRequester } }
            .focusGroup()
            .horizontalScroll(rememberScrollState())
            .padding(start = GridContentStart, end = GridContentEnd, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { i, chip ->
            CategoryChipItem(
                chip.label, i == selected, onClick = { onSelect(i) },
                modifier = if (i == selected) Modifier.focusRequester(selectedRequester) else Modifier,
            )
        }
    }
}

/** Translucent pill; the selected one is red; focused is white with black text (as the title page's pills). */
@Composable
private fun CategoryChipItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val rest = if (selected) DroozColors.Accent else Color.White.copy(alpha = 0.12f)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(PillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = rest,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.85f),
            pressedContentColor = Color.Black,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label, Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1, softWrap = false,
            )
        }
    }
}
