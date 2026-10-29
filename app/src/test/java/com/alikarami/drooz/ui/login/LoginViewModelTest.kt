package com.alikarami.drooz.ui.login

import com.alikarami.drooz.data.AuthApi
import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.site.auth.LoginResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeAuth(var answer: () -> LoginResult) : AuthApi {
        val calls = mutableListOf<Triple<String, String, String?>>()
        var gate: CompletableDeferred<Unit>? = null
        override val isLoggedIn = false
        override val username: String? = null
        override suspend fun logout() {}
        override suspend fun login(username: String, password: String, mobile: String?): LoginResult {
            calls += Triple(username, password, mobile)
            gate?.await()
            return answer()
        }
    }

    private fun LoginViewModel.fill(user: String = "ali", pass: String = "secret") {
        onUsername(user)
        onPassword(pass)
    }

    @Test fun needsMobileShowsHintAndKeepsFields() = runTest(dispatcher) {
        val auth = FakeAuth { LoginResult.NeedsMobile("09xx***1234") }
        val vm = LoginViewModel(auth)
        vm.fill()
        vm.submit(); advanceUntilIdle()
        val s = vm.uiState.value
        assertEquals("09xx***1234", s.mobileHint)
        assertEquals("ali", s.username)
        assertEquals("secret", s.password)
        assertFalse(s.busy)
        assertNull(s.error)
        assertEquals(listOf(Triple("ali", "secret", null)), auth.calls)

        // The second step sends the mobile with the same credentials (Persian digits normalised).
        auth.answer = { LoginResult.Success }
        vm.onMobile("۰۹۱۲۱۲۳۱۲۳۴")
        vm.submit(); advanceUntilIdle()
        assertEquals(Triple("ali", "secret", "09121231234"), auth.calls.last())
    }

    @Test fun wrongCredentialsMessage() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth { LoginResult.WrongCredentials })
        vm.fill()
        vm.submit(); advanceUntilIdle()
        assertEquals("نام کاربری یا رمز عبور اشتباه است", vm.uiState.value.error)
        assertEquals("ali", vm.uiState.value.username)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun networkErrorMessage() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth { throw SiteException.NetworkError(IOException("reset")) })
        vm.fill()
        vm.submit(); advanceUntilIdle()
        assertEquals("اتصال به سایت برقرار نشد؛ دوباره تلاش کنید", vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
    }

    @Test fun successEmitsLoggedIn() = runTest(dispatcher) {
        val auth = FakeAuth { LoginResult.Success }
        auth.gate = CompletableDeferred()
        val vm = LoginViewModel(auth)
        val events = mutableListOf<Unit>()
        backgroundScope.launch(dispatcher) { vm.loggedIn.collect { events += it } }
        vm.fill()
        vm.submit(); runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.submit(); runCurrent() // a repeat press while busy is ignored
        // runCurrent: advanceUntilIdle does not wait for backgroundScope collectors.
        auth.gate!!.complete(Unit); advanceUntilIdle(); runCurrent()
        assertEquals(1, auth.calls.size)
        assertEquals(1, events.size)
        assertNull(vm.uiState.value.error)
    }

    @Test fun emptyFieldsAreNotSent() = runTest(dispatcher) {
        val auth = FakeAuth { LoginResult.Success }
        val vm = LoginViewModel(auth)
        vm.fill(pass = "")
        vm.submit(); advanceUntilIdle()
        assertTrue(auth.calls.isEmpty())
        assertEquals("نام کاربری و رمز عبور را وارد کنید", vm.uiState.value.error)
    }

    @Test fun siteFailureMessageShown() = runTest(dispatcher) {
        val vm = LoginViewModel(FakeAuth { LoginResult.Failed("شماره موبایل پذیرفته نشد") })
        vm.fill()
        vm.submit(); advanceUntilIdle()
        assertEquals("شماره موبایل پذیرفته نشد", vm.uiState.value.error)
    }
}
