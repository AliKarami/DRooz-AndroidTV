package com.alikarami.drooz.site

import com.alikarami.drooz.site.auth.AuthService
import com.alikarami.drooz.site.http.SiteHttp
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.ListPage
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.site.parse.HomeParser
import com.alikarami.drooz.site.parse.ListParser
import com.alikarami.drooz.site.parse.SearchParser
import com.alikarami.drooz.site.parse.StreamPageParser
import com.alikarami.drooz.site.parse.TitleParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/** Everything the UI asks of the site. An expired session is renewed once, transparently. */
class SiteRepository(private val http: SiteHttp, private val auth: AuthService) {

    suspend fun home(): HomePage = authed { HomeParser.parse(http.get("/").body) }

    /** Page 1 is [path] itself; page N inserts `N/` before any query string. */
    suspend fun list(path: String, page: Int = 1): ListPage = authed {
        val target = if (page <= 1) path else {
            val base = path.substringBefore('?').let { if (it.endsWith("/")) it else "$it/" }
            val query = path.substringAfter('?', "")
            "$base$page/" + if (query.isNotEmpty()) "?$query" else ""
        }
        ListParser.parse(http.get(target).body)
    }

    suspend fun search(query: String): List<TitleCard> = authed {
        SearchParser.parse(http.get("/search/?s=${URLEncoder.encode(query, "UTF-8")}").body)
    }

    suspend fun title(ref: TitleRef): TitleDetail = authed { TitleParser.parse(http.get(ref.path).body, ref) }

    /** Opening the stream page marks the title as streamed on the account; call only right before playback. */
    suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession = authed {
        val body = http.get("/stream/${episode.streamId}/${ref.postId}/?lang=$lang&h=$height").body
        StreamPageParser.parse(body, episode.streamId)
    }

    /** [state] is one of `watch`, `favorite`, `watched`. It toggles on the site, so it is never retried. */
    suspend fun setWatchlist(postId: Long, state: String) {
        authed { http.get("/json/userWatchlist/$postId/$state/", retry = false) }
    }

    suspend fun reportStreamError(streamId: String, event: String, msg: String) {
        runCatching {
            http.get("/json/logStreamError/$streamId/?event=$event&msg=${URLEncoder.encode(msg, "UTF-8")}", requiresAuth = false, retry = false)
        }
    }

    private val reloginLock = Mutex()

    /**
     * Runs [block]; on an expired session renews it once and retries. Concurrent expiries share a
     * single login: a caller that finds the session already renewed by another just retries.
     */
    private suspend fun <T> authed(block: suspend () -> T): T {
        val tokenBefore = auth.sessionToken
        try {
            return block()
        } catch (_: SiteException.SessionExpired) {
        }
        val renewed = reloginLock.withLock {
            val current = auth.sessionToken
            (current != null && current != tokenBefore) || auth.relogin()
        }
        if (!renewed) throw SiteException.NotLoggedIn()
        try {
            return block()
        } catch (_: SiteException.SessionExpired) {
            throw SiteException.NotLoggedIn()
        }
    }
}
