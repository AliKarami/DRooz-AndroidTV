package com.alikarami.drooz.site.auth

import com.alikarami.drooz.site.http.PersistentCookieJar
import com.alikarami.drooz.site.http.SiteHttp
import com.alikarami.drooz.testutil.fixture
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var jar: PersistentCookieJar
    private lateinit var creds: CredentialStore
    private lateinit var auth: AuthService

    private val box = object : SecretBox {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray) = cipher.reversedArray()
    }

    @Before fun start() {
        server.start()
        jar = PersistentCookieJar(tmp.newFile("cookies"), siteHost = server.hostName)
        creds = CredentialStore(tmp.root.resolve("creds.bin"), box)
        auth = AuthService(SiteHttp(server.url("/"), jar), jar, creds)
    }

    @After fun stop() = server.close()

    private fun page(name: String, vararg cookies: String) = MockResponse.Builder().body(fixture(name))
        .apply { cookies.forEach { addHeader("Set-Cookie", "$it; Max-Age=31536000; path=/") } }.build()

    /** Every login starts with a GET of the form page. */
    private fun form() = server.enqueue(page("login_form.html"))

    private fun fields(body: String) = Regex("name=\"(\\w+)\"").findAll(body).map { it.groupValues[1] }.toList()

    @Test fun loginLoadsFormBeforePostingLikeABrowser() = runTest {
        server.enqueue(page("login_form.html", "sf=dev1"))
        server.enqueue(page("login_wrong.html"))
        auth.login("testuser", "bad", null)
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertEquals("/user/login/", first.target)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertTrue(post.headers["Cookie"]!!.contains("sf=dev1"))
    }

    @Test fun loginWhileAlreadyLoggedInLogsOutFirstKeepingDevice() = runTest {
        // An existing session makes /user/login/ redirect to the user panel.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/user/panel/")
            .addHeader("Set-Cookie", "sf=dev1; Max-Age=31536000; path=/")
            .addHeader("Set-Cookie", "cf=old; Max-Age=31536000; path=/").build())
        server.enqueue(MockResponse.Builder().body("<html>panel</html>").build())
        server.enqueue(MockResponse.Builder().body("bye").build())             // /user/logout/
        server.enqueue(page("login_form.html"))                                 // form again
        server.enqueue(page("login_success.html", "cf=new"))
        server.enqueue(MockResponse.Builder().body("ok").build())              // device report

        assertEquals(LoginResult.Success, auth.login("testuser", "secret", null))

        val targets = List(server.requestCount) { server.takeRequest().let { "${it.method} ${it.url.encodedPath}" } }
        assertEquals(listOf("GET /user/login/", "GET /user/panel/", "GET /user/logout/", "GET /user/login/",
            "POST /user/login/", "GET /json/device/"), targets)
        assertEquals("dev1", jar.cookie("sf"))
        assertEquals("new", jar.cookie("cf"))
        assertEquals(Credentials("testuser", "secret", null), creds.load())
    }

    @Test fun twoStepLoginPostsMobileAndRecaptchaPass() = runTest {
        form()
        server.enqueue(page("login_needs_mobile.html", "sf=dev1"))
        server.enqueue(page("login_success.html", "cf=sess1"))
        server.enqueue(MockResponse.Builder().body("ok").build())

        val result = auth.login("testuser", "secret", "09120000000")

        assertEquals(LoginResult.Success, result)
        assertTrue(auth.isLoggedIn)
        server.takeRequest() // form GET
        assertEquals(listOf("username", "password"), fields(server.takeRequest().body!!.utf8()))
        val second = server.takeRequest().body!!.utf8()
        assertEquals(listOf("username", "password", "recaptchaPass", "mobile"), fields(second))
        assertTrue(second.contains("000000000000000000000000000000001800000000"))
        assertTrue(second.contains("09120000000"))
    }

    @Test fun needsMobileWithoutMobileReturnsHint() = runTest {
        form()
        server.enqueue(page("login_needs_mobile.html"))
        assertEquals(LoginResult.NeedsMobile("0912***0000"), auth.login("testuser", "secret", null))
        assertEquals(2, server.requestCount)
        assertNull(creds.load())
    }

    // The login page is parsed off the caller's (main) thread, like every other site page.
    @Test fun loginParsesOnParseDispatcher() = runTest {
        var dispatches = 0
        val parse = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = true
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatches++
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val auth = AuthService(SiteHttp(server.url("/"), jar), jar, creds, parseDispatcher = parse)
        form()
        server.enqueue(page("login_wrong.html"))
        assertEquals(LoginResult.WrongCredentials, auth.login("testuser", "bad", null))
        assertTrue(dispatches > 0)
    }

    @Test fun wrongPasswordReturnsWrongCredentials() = runTest {
        form()
        server.enqueue(page("login_wrong.html"))
        assertEquals(LoginResult.WrongCredentials, auth.login("testuser", "bad", null))
        assertFalse(auth.isLoggedIn)
    }

    @Test fun successSavesCredentials() = runTest {
        form()
        server.enqueue(page("login_success.html", "cf=sess1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        auth.login("testuser", "secret", "09120000000")
        assertEquals(Credentials("testuser", "secret", "09120000000"), creds.load())
    }

    @Test fun reportsDeviceAfterSuccess() = runTest {
        form()
        server.enqueue(page("login_success.html", "cf=sess1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        auth.login("testuser", "secret", null)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/json/device/?width=3840&height=2160&pixelRatio=1", server.takeRequest().target)
    }

    @Test fun reloginKeepsSfCookie() = runTest {
        form()
        server.enqueue(page("login_success.html", "sf=dev1", "cf=sess1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        auth.login("testuser", "secret", null)

        form()
        server.enqueue(page("login_success.html", "cf=sess2"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        assertTrue(auth.relogin())
        assertEquals("dev1", jar.cookie("sf"))
        assertEquals("sess2", jar.cookie("cf"))
    }

    @Test fun reloginPropagatesNetworkErrorsInsteadOfReportingLoggedOut() {
        creds.save(Credentials("testuser", "secret", null))
        server.close()
        org.junit.Assert.assertThrows(com.alikarami.drooz.site.SiteException.NetworkError::class.java) {
            kotlinx.coroutines.runBlocking { auth.relogin() }
        }
    }

    @Test fun reloginWithoutStoredCredentialsFails() = runTest {
        assertFalse(auth.relogin())
        assertEquals(0, server.requestCount)
    }

    @Test fun logoutKeepsDeviceCookieOnly() = runTest {
        form()
        server.enqueue(page("login_success.html", "sf=dev1", "cf=sess1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        auth.login("testuser", "secret", null)
        server.enqueue(MockResponse.Builder().body("bye").build())
        auth.logout()
        assertEquals("dev1", jar.cookie("sf"))
        assertFalse(auth.isLoggedIn)
        assertNull(creds.load())
    }
}
