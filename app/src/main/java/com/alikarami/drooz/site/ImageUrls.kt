package com.alikarami.drooz.site

/** The site resizes `/img/W-H/<hash>` to any requested size; always request exactly what is drawn. */
object ImageUrls {
    fun poster(hash: String, w: Int, h: Int): String = "${SiteConfig.BASE_URL}/img/$w-$h/$hash"
    fun backdrop(hash: String, w: Int, h: Int): String = "${SiteConfig.BASE_URL}/img/$w-$h/$hash"
    fun trailerThumb(hash: String, w: Int, h: Int): String = "${SiteConfig.BASE_URL}/img/$w-$h/trailer/$hash"

    private val HASH = Regex("""/img/\d+-\d+/(?:trailer/)?([^/'"\s?]+)""")

    /** Extracts the image hash from an `/img/W-H/<hash>` path, or null. */
    fun hashOf(path: String): String? = HASH.find(path)?.groupValues?.get(1)
}
