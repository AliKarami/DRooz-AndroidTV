package com.alikarami.drooz.data

import com.alikarami.drooz.site.SiteRepository
import com.alikarami.drooz.site.auth.AuthService
import com.alikarami.drooz.site.auth.LoginResult
import com.alikarami.drooz.site.model.*

/** The slice of [SiteRepository] the UI uses; lets view models be tested with fakes. */
interface SiteApi {
    suspend fun home(): HomePage
    suspend fun list(path: String, page: Int = 1): ListPage
    suspend fun search(query: String): List<TitleCard>
    suspend fun title(ref: TitleRef): TitleDetail
    suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession
    suspend fun setWatchlist(postId: Long, state: String)
    suspend fun subtitle(url: String): String
    suspend fun reportStreamError(streamId: String, event: String, msg: String)
}

interface AuthApi {
    val isLoggedIn: Boolean
    suspend fun login(username: String, password: String, mobile: String?): LoginResult
}

class SiteApiAdapter(private val repo: SiteRepository) : SiteApi {
    override suspend fun home() = repo.home()
    override suspend fun list(path: String, page: Int) = repo.list(path, page)
    override suspend fun search(query: String) = repo.search(query)
    override suspend fun title(ref: TitleRef) = repo.title(ref)
    override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) =
        repo.openStream(episode, ref, lang, height)
    override suspend fun setWatchlist(postId: Long, state: String) = repo.setWatchlist(postId, state)
    override suspend fun subtitle(url: String) = repo.subtitle(url)
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) =
        repo.reportStreamError(streamId, event, msg)
}

class AuthApiAdapter(private val auth: AuthService) : AuthApi {
    override val isLoggedIn: Boolean get() = auth.isLoggedIn
    override suspend fun login(username: String, password: String, mobile: String?) =
        auth.login(username, password, mobile)
}
