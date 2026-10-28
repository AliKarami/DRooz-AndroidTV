package com.alikarami.drooz.data

import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** App-wide "the session is gone" signal: any screen's request that ends in [SiteException.NotLoggedIn]. */
class SessionEvents {
    private val _lost = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val lost: SharedFlow<Unit> = _lost

    /** Reports [e] if it means the session is gone (stored-credential renewal already failed). */
    fun report(e: Throwable) {
        if (e is SiteException.NotLoggedIn) _lost.tryEmit(Unit)
    }
}

/** Passes every call through, reporting a lost session to [events] before rethrowing. */
class SessionGuardedSiteApi(private val api: SiteApi, private val events: SessionEvents) : SiteApi {
    private suspend inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: SiteException.NotLoggedIn) {
            events.report(e)
            throw e
        }

    override suspend fun home() = guard { api.home() }
    override suspend fun list(path: String, page: Int) = guard { api.list(path, page) }
    override suspend fun search(query: String) = guard { api.search(query) }
    override suspend fun title(ref: TitleRef) = guard { api.title(ref) }
    override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) =
        guard { api.openStream(episode, ref, lang, height) }
    override suspend fun setWatchlist(postId: Long, state: String) = guard { api.setWatchlist(postId, state) }
}
