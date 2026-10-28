package com.alikarami.drooz.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester

/** Retries for a few frames: a node that just appeared may attach a frame late. */
suspend fun FocusRequester.requestFocusSoon(): Boolean {
    repeat(30) {
        withFrameNanos { }
        if (runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)) return true
    }
    return false
}

/**
 * Scrolls so the focused child sits [pivotPx] from the container's leading edge (Netflix-style fixed
 * focus). Offsets arrive in absolute (left-based) coordinates, so in RTL the leading edge is the right.
 */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val pivotPx: Float, private val fromRight: Boolean = false) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (fromRight) offset - (containerSize - pivotPx - size) else offset - pivotPx
}

/** Pivot the focused element of a row (and the row itself) into the fixed focus position. */
fun Modifier.bringIntoViewOnFocus(onFocused: () -> Unit = {}): Modifier = composed {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    bringIntoViewRequester(requester).onFocusChanged {
        if (it.hasFocus) {
            onFocused()
            scope.launch { requester.bringIntoView() }
        }
    }
}
