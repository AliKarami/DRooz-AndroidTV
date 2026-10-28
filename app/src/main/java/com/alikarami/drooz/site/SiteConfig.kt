package com.alikarami.drooz.site

object SiteConfig {
    const val HOST = "robin.robert-redford.net"
    const val BASE_URL = "https://$HOST"
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
    const val LOGIN_PATH = "/user/login/"

    /** Cookie the site normally sets from JavaScript; without it every page is a JS redirect stub. */
    const val SCR_COOKIE = "scr"
    const val SCR_VALUE = "1"
}
