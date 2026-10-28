package com.alikarami.drooz.ui.debug

import com.alikarami.drooz.site.auth.LoginResult
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.HomePage
import com.alikarami.drooz.site.model.PosterRef
import com.alikarami.drooz.site.model.Season
import com.alikarami.drooz.site.model.StreamCatalog
import com.alikarami.drooz.site.model.StreamSession
import com.alikarami.drooz.site.model.TitleCard
import com.alikarami.drooz.site.model.TitleDetail
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebugViewModelTest {
    private val ref = TitleRef(TitleKind.SERIES, 5735053, "pluribus")
    private val home = HomePage(emptyList(), listOf(PosterRef(ref, "p.webp")), emptyList())

    private fun ep(n: Int, played: Boolean) = Episode(1, n, "s$n", emptyList(), emptyList(), emptyList(), played)

    private val detail = TitleDetail(
        TitleCard(ref, "Pluribus", 2025, null, null, null, null, emptyList(), emptyList(), null, emptyList(), null, null, null),
        null, emptyList(),
        StreamCatalog(listOf(Season(1, listOf(ep(1, true), ep(2, true), ep(3, true), ep(4, false), ep(5, false)))), emptyList()),
        emptyList(), null,
    )

    private inner class FakeBackend(var loggedIn: Boolean, val loginResult: LoginResult = LoginResult.Success) : DebugBackend {
        val opened = mutableListOf<Triple<String, String, Int>>()
        override val isLoggedIn get() = loggedIn
        override suspend fun login(username: String, password: String, mobile: String?): LoginResult {
            if (loginResult == LoginResult.Success) loggedIn = true
            return loginResult
        }
        override suspend fun home() = home
        override suspend fun title(ref: TitleRef) = detail
        override suspend fun openStream(episode: Episode, ref: TitleRef, lang: String, height: Int): StreamSession {
            opened += Triple(episode.streamId, lang, height)
            return StreamSession("Pluribus", "https://x/master.m3u8", null, emptyList(), emptyMap(), emptyList(), null, null, emptyList(), episode.streamId)
        }
    }

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun startsAtLoginWhenNoCookie() {
        val vm = DebugViewModel(FakeBackend(loggedIn = false))
        assertEquals(DebugState.Login(mobileHint = null, error = null), vm.state.value)
    }

    @Test fun startsAtHomeWhenLoggedIn() {
        val vm = DebugViewModel(FakeBackend(loggedIn = true))
        assertEquals(DebugState.Home(home), vm.state.value)
    }

    @Test fun needsMobileShowsHint() {
        val vm = DebugViewModel(FakeBackend(false, LoginResult.NeedsMobile("0912***0000")))
        vm.login("testuser", "secret", null)
        assertEquals(DebugState.Login(mobileHint = "0912***0000", error = null), vm.state.value)
    }

    @Test fun wrongCredentialsShowsError() {
        val vm = DebugViewModel(FakeBackend(false, LoginResult.WrongCredentials))
        vm.login("testuser", "bad", null)
        assertEquals(DebugState.Login(mobileHint = null, error = "نام کاربری یا رمز عبور اشتباه است"), vm.state.value)
    }

    @Test fun successLoadsHome() {
        val vm = DebugViewModel(FakeBackend(false))
        vm.login("testuser", "secret", "09120000000")
        val s = vm.state.value as DebugState.Home
        assertEquals(5735053L, s.page.recentlyStreamed.first().ref.postId)
    }

    @Test fun playFetchesStreamSessionForFirstUnplayedEpisode() {
        val backend = FakeBackend(true)
        val vm = DebugViewModel(backend)
        vm.openTitle(ref)
        vm.play()
        assertEquals(listOf(Triple("s4", "original", 1080)), backend.opened)
        assertEquals("s4", vm.playback.value?.streamId)
    }

    @Test fun openAndPlayStartsFirstUnplayedEpisode() {
        val backend = FakeBackend(true)
        val vm = DebugViewModel(backend)
        vm.openAndPlay(ref)
        assertEquals(listOf(Triple("s4", "original", 1080)), backend.opened)
    }

    @Test fun allPlayedFallsBackToFirstEpisode() =
        assertEquals(1, firstUnplayed(StreamCatalog(listOf(Season(1, listOf(ep(1, true), ep(2, true)))), emptyList()))?.number)
}
