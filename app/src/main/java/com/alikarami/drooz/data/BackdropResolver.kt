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
    /** [posterHash] covers cards whose [detail] is not loaded yet. */
    fun resolve(
        ref: TitleRef,
        featured: Map<TitleRef, String>,
        played: Map<TitleRef, String>,
        detail: TitleDetail?,
        posterHash: String? = null,
    ): Backdrop {
        featured[ref]?.let { return Backdrop.Wide(ImageUrls.backdrop(it, 1920, 1080)) }
        played[ref]?.let { return Backdrop.Wide(SiteConfig.BASE_URL + it) }
        detail?.trailerThumbHash?.let { return Backdrop.Wide(ImageUrls.trailerThumb(it, 1920, 1080)) }
        (detail?.card?.posterHash ?: posterHash)?.let { return Backdrop.BlurredPoster(ImageUrls.poster(it, 240, 360)) }
        return Backdrop.None
    }
}
