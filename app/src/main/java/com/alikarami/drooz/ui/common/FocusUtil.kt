package com.alikarami.drooz.ui.common

import androidx.compose.runtime.withFrameNanos
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
