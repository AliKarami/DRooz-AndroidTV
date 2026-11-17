package com.alikarami.drooz.site

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SiteConfig {
    /**
     * The site's address, typed by the user on the login screen (see [ServerAddress]); nothing is built in.
     * Null until one has been entered.
     */
    @Volatile var base: HttpUrl? = null

    /** `https://host` without a trailing slash; empty until an address has been entered. */
    val baseUrl: String get() = base?.toString()?.removeSuffix("/") ?: ""
    val host: String? get() = base?.host

    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
    const val LOGIN_PATH = "/user/login/"

    /** Cookie the site normally sets from JavaScript; without it every page is a JS redirect stub. */
    const val SCR_COOKIE = "scr"
    const val SCR_VALUE = "1"

    /**
     * What the user typed → the site root (`https://host[:port]/`), or null if it is not a usable address.
     * A bare host gets `https://`; any path, query or fragment is dropped.
     */
    fun normalize(input: String): HttpUrl? {
        val t = input.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (!url.host.contains('.') && url.host != "localhost") return null
        return url.newBuilder().encodedPath("/").query(null).fragment(null).username("").password("").build()
    }
}
