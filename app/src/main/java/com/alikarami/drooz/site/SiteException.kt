package com.alikarami.drooz.site

sealed class SiteException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HttpError(val code: Int) : SiteException("HTTP $code")
    class NetworkError(cause: Throwable) : SiteException("network: ${cause.message}", cause)
    class Blocked : SiteException("blocked by Cloudflare challenge")
    class SessionExpired : SiteException("session expired")
    class NotLoggedIn : SiteException("not logged in")
    class ParseError(val what: String) : SiteException("could not parse $what")
}
