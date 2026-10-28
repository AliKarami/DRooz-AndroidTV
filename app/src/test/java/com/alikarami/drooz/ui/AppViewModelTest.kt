package com.alikarami.drooz.ui

import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.data.SessionEvents
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.auth.LoginResult
import com.alikarami.drooz.site.model.TitleRef
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(override val isLoggedIn: Boolean) : AuthApi {
        override suspend fun login(username: String, password: String, mobile: String?) = LoginResult.Success
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
