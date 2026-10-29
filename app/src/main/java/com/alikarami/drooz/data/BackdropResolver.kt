package com.alikarami.drooz.data

import com.alikarami.drooz.site.ImageUrls
import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleRef

sealed interface Backdrop {
    data class Wide(val url: String) : Backdrop
    data class BlurredPoster(val url: String) : Backdrop
    data object None : Backdrop
}

object BackdropResolver {
    /** [posterHash] covers cards whose [detail] is not loaded yet. [w]×[h] sizes the resizable wide images. */
    fun resolve(
        ref: TitleRef,
        featured: Map<TitleRef, String>,
        played: Map<TitleRef, String>,
        detail: TitleDetail?,
        posterHash: String? = null,
        w: Int = 1920,
        h: Int = 1080,
    ): Backdrop {
        featured[ref]?.let { return Backdrop.Wide(ImageUrls.backdrop(it, w, h)) }
        played[ref]?.let { return Backdrop.Wide(SiteConfig.BASE_URL + it) }
        detail?.trailerThumbHash?.let { return Backdrop.Wide(ImageUrls.trailerThumb(it, w, h)) }
        (detail?.card?.posterHash ?: posterHash)?.let { return Backdrop.BlurredPoster(ImageUrls.poster(it, 240, 360)) }
        return Backdrop.None
    }
}
