package com.alikarami.drooz.ui

import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.serialization.Serializable

@Serializable data object Login
@Serializable data object Home
@Serializable data object Search

/**
 * A title page. [featuredBackdrop] is home's featured image hash for it (only home knows it), so the page
 * opens on the same picture; [autoPlay] is the debug `debug_play` launch shortcut.
 */
@Serializable
data class Title(
    val kind: TitleKind,
    val postId: Long,
    val slug: String,
    val featuredBackdrop: String? = null,
    val autoPlay: Boolean = false,
) {
    val ref: TitleRef get() = TitleRef(kind, postId, slug)

    companion object {
        fun of(ref: TitleRef, featuredBackdrop: String? = null, autoPlay: Boolean = false) =
            Title(ref.kind, ref.postId, ref.slug, featuredBackdrop, autoPlay)
    }
}

/** A site list (`/archive/...`) grid under [title]. */
@Serializable data class ListRoute(val title: String, val path: String, val emptyText: String? = null)
