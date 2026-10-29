package com.alikarami.drooz.ui

import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.HomePageCache
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.data.SessionEvents
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.auth.LoginResult
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.StreamCatalog
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.data.TitleInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(override val isLoggedIn: Boolean) : AuthApi {
        override val username: String? = null
        override suspend fun login(username: String, password: String, mobile: String?) = LoginResult.Success
        override suspend fun logout() {}
    }

    private fun TestScope.collect(vm: AppViewModel): MutableList<AppNav> {
        val got = mutableListOf<AppNav>()
        backgroundScope.launch(dispatcher) { vm.navigation.collect { got += it } }
        runCurrent()
        return got
    }

    @Test fun startsAtLoginWhenLoggedOut() {
        assertEquals(Login, AppViewModel(FakeAuth(false), SessionEvents()).startDestination)
    }

    @Test fun startsAtHomeWhenLoggedIn() {
        assertEquals(Home, AppViewModel(FakeAuth(true), SessionEvents()).startDestination)
    }

    // A cached home from before a lost session / re-login (maybe another account) must never be shown.
    @Test fun cacheFromPreviousSessionIsNotUsed() = runTest(dispatcher) {
        val events = SessionEvents()
        val cache = HomePageCache(ttlMs = 600_000) { 0L }
        val vm = AppViewModel(FakeAuth(true), events, homeCache = cache)
        runCurrent()
        cache.put(HomePage(emptyList(), emptyList(), emptyList()))
        events.report(SiteException.NotLoggedIn())
        runCurrent()
        assertNull(cache.fresh())

        cache.put(HomePage(emptyList(), emptyList(), emptyList()))
        vm.onLoggedIn()
        assertNull(cache.fresh())
    }

    private class NoApi : com.alikarami.drooz.data.SiteApi {
    override suspend fun subtitle(url: String): String = error("unused")
    override suspend fun reportStreamError(streamId: String, event: String, msg: String) {}
        override suspend fun home() = error("unused")
        override suspend fun list(path: String, page: Int) = error("unused")
        override suspend fun search(query: String) = error("unused")
        override suspend fun title(ref: TitleRef) = TitleDetail(
            TitleCard(ref, "t", null, null, null, null, null, emptyList(), emptyList(), null, emptyList(), null, null, null),
            null, emptyList(), StreamCatalog(emptyList(), emptyList()), emptyList(), null,
        )
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int) = error("unused")
        override suspend fun setWatchlist(postId: Long, state: String) = error("unused")
    }

    // Played ✓ marks and backdrops of the previous account must not survive a session change.
    @Test fun titleInfoFromPreviousSessionIsCleared() = runTest(dispatcher) {
        val events = SessionEvents()
        val repo = TitleInfoRepository(NoApi(), fetchScope = backgroundScope)
        val r = TitleRef(TitleKind.FILM, 1, "a")
        val vm = AppViewModel(FakeAuth(true), events, titleInfo = repo)
        runCurrent()
        repo.get(r); repo.remember(r, "/bd.jpg")
        events.report(SiteException.NotLoggedIn())
        runCurrent()
        assertNull(repo.lastKnown(r))
        assertTrue(repo.playedBackdrops.value.isEmpty())

        repo.get(r); repo.remember(r, "/bd.jpg")
        vm.onLoggedIn()
        assertNull(repo.lastKnown(r))
        assertTrue(repo.playedBackdrops.value.isEmpty())
    }

    @Test fun onLoggedOut_clearsCaches() = runTest(dispatcher) {
        val cache = HomePageCache(ttlMs = 600_000) { 0L }
        val repo = TitleInfoRepository(NoApi(), fetchScope = backgroundScope)
        val r = TitleRef(TitleKind.FILM, 1, "a")
        val vm = AppViewModel(FakeAuth(true), SessionEvents(), homeCache = cache, titleInfo = repo)
        runCurrent()
        cache.put(HomePage(emptyList(), emptyList(), emptyList()))
        repo.get(r); repo.remember(r, "/bd.jpg")
        vm.onLoggedOut()
        assertNull(cache.fresh())
        assertNull(repo.lastKnown(r))
        assertTrue(repo.playedBackdrops.value.isEmpty())
        // A debug play never waits on a login that will not come from this launch.
        assertEquals(null, vm.takeDebugPlay())
    }

    @Test fun notLoggedInRoutesToLogin() = runTest(dispatcher) {
        val events = SessionEvents()
        val vm = AppViewModel(FakeAuth(true), events)
        val got = collect(vm)
        // Several screens fail at once (home rows, hero fetches): one trip to Login.
        repeat(3) { events.report(SiteException.NotLoggedIn()) }
        runCurrent()
        assertEquals(listOf(AppNav.ToLogin), got)

        // Other failures never route.
        events.report(SiteException.NetworkError(RuntimeException("x")))
        runCurrent()
        assertEquals(listOf(AppNav.ToLogin), got)

        // After logging in again, a new expiry routes again.
        vm.onLoggedIn()
        events.report(SiteException.NotLoggedIn())
        runCurrent()
        assertEquals(listOf(AppNav.ToLogin, AppNav.ToLogin), got)
    }

    // Debug launch extras (debug builds only): the decision lives here so it is JVM-testable.

    private val creds = DebugLogin("u", "p", null)

    @Test fun debugLoginStartsAtLoginEvenWhenLoggedIn() {
        val vm = AppViewModel(FakeAuth(true), SessionEvents(), DebugLaunch(login = creds))
        assertEquals(Login, vm.startDestination)
    }

    @Test fun debugLoginIsHandedOutOnce() {
        val vm = AppViewModel(FakeAuth(false), SessionEvents(), DebugLaunch(login = creds))
        assertEquals(creds, vm.takeDebugLogin())
        assertEquals(null, vm.takeDebugLogin())
    }

    @Test fun debugPlayWaitsForDebugLogin() {
        val path = "/post/series/5735053/pluribus/"
        val vm = AppViewModel(FakeAuth(false), SessionEvents(), DebugLaunch(login = creds, playPath = path))
        assertEquals(null, vm.takeDebugPlay()) // still on Login
        vm.onLoggedIn()
        assertEquals(TitleRef.fromPath(path), vm.takeDebugPlay())
        assertEquals(null, vm.takeDebugPlay())
    }

    @Test fun debugPlayRunsAtOnceWhenLoggedIn() {
        val path = "/post/series/5735053/pluribus/"
        val vm = AppViewModel(FakeAuth(true), SessionEvents(), DebugLaunch(playPath = path))
        assertEquals(Home, vm.startDestination)
        assertEquals(TitleRef.fromPath(path), vm.takeDebugPlay())
    }
}
