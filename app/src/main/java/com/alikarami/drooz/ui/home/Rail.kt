package com.alikarami.drooz.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.alikarami.drooz.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import com.alikarami.drooz.ui.theme.DroozColors

/** Top-level destinations in the side rail, in on-screen order (top to bottom). */
enum class RailItem(@StringRes val label: Int, val icon: ImageVector) {
    Search(R.string.rail_search, railIcon("Search", SEARCH)),
    Home(R.string.rail_home, railIcon("Home", HOME)),
    Series(R.string.rail_series, railIcon("Series", TV)),
    Films(R.string.rail_films, railIcon("Films", MOVIE)),
    MyList(R.string.rail_my_list, railIcon("MyList", BOOKMARK)),
    Settings(R.string.rail_settings, railIcon("Settings", SETTINGS)),
}

/** Behind the rail: dark at the screen edge fading into the backdrop (mockup `.rail`). */
val RailBackground = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))

/** Expanded: solid enough that labels never sit on top of the hero text. */
private val RailOpenBackground = Brush.horizontalGradient(
    0f to DroozColors.Background.copy(alpha = 0.6f),
    0.5f to DroozColors.Background.copy(alpha = 0.92f),
    1f to DroozColors.Background,
)

/** Content of the tv-material drawer: icons when collapsed, icons + labels once focus enters it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NavigationDrawerScope.HomeRail(selected: RailItem, onSelect: (RailItem) -> Unit, modifier: Modifier = Modifier) {
    val selectedRequester = remember { FocusRequester() }
    Column(
        modifier
            .fillMaxHeight()
            .background(if (hasFocus) RailOpenBackground else RailBackground)
            .padding(horizontal = 12.dp)
            // Entering the rail from anywhere lands on the current destination, not the nearest icon. A focus
            // group, so moving between items is not seen as re-entering (that snapped Up/Down back to it).
            .focusProperties { enter = { selectedRequester } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        RailItem.entries.forEach { item ->
            val isSelected = item == selected
            NavigationDrawerItem(
                selected = isSelected,
                onClick = { onSelect(item) },
                leadingContent = { Icon(item.icon, contentDescription = null, Modifier.size(24.dp)) },
                modifier = (if (isSelected) Modifier.focusRequester(selectedRequester) else Modifier)
                    .then(if (isSelected && !hasFocus) Modifier.selectedMarker() else Modifier),
                colors = NavigationDrawerItemDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    inactiveContentColor = Color(0xFFAAAAAA),
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                    pressedContainerColor = Color.White,
                    pressedContentColor = Color.Black,
                    selectedContainerColor = Color.White.copy(alpha = 0.12f),
                    selectedContentColor = Color.White,
                    focusedSelectedContainerColor = Color.White,
                    focusedSelectedContentColor = Color.Black,
                    pressedSelectedContainerColor = Color.White,
                    pressedSelectedContentColor = Color.Black,
                ),
            ) {
                Text(stringResource(item.label), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
    }
}

/** Short red bar on the screen-edge side of the selected item (mockup `.rail .on:after`). */
private fun Modifier.selectedMarker() = drawWithContent {
    drawContent()
    val w = 3.dp.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
    drawRoundRect(
        DroozColors.Accent,
        topLeft = Offset(x, size.height * 0.2f),
        size = Size(w, size.height * 0.6f),
        cornerRadius = CornerRadius(w / 2),
    )
}

private fun railIcon(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(pathData), fill = SolidColor(Color.White))
        .build()

// 24x24 glyph outlines (Material Design icon geometry, Apache 2.0).
private const val SEARCH = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 " +
    "5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5" +
    "S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
private const val HOME = "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z"
private const val TV = "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5" +
    "c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z"
private const val MOVIE = "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2" +
    "h16c1.1,0 2,-0.9 2,-2V4h-4z"
private const val BOOKMARK = "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z"
private const val SETTINGS = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
    "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 " +
    "-1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35" +
    "C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48" +
    "l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32" +
    "c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84" +
    "c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22" +
    "l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6" +
    "s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
