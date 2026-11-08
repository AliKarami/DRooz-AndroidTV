package com.alikarami.drooz.site.auth

import com.alikarami.drooz.site.http.PersistentCookieJar
import com.alikarami.drooz.site.http.SiteHttp
import com.alikarami.drooz.testutil.fixture
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.RecordedRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /** Serves the login flow; a POST answers [post] (after [onPost], which may block). Records "METHOD path". */
    private fun serve(onPost: (Int) -> Unit = {}, post: () -> MockResponse = { page("login_success.html", "cf=sess2") }): MutableList<String> {
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        val posts = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                log += "${request.method} ${request.url.encodedPath}"
                return when {
                    request.method == "POST" -> {
                        onPost(posts.incrementAndGet())
                        post()
                    }
                    request.url.encodedPath == "/user/login/" -> page("login_form.html", "sf=dev1")
                    else -> MockResponse.Builder().body("ok").build()
                }
            }
        }
        return log
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))

    // Home's view model can hit an expired session and relogin while Settings logs out: the
    // relogin must not bring the stored credentials or a session cookie back (D2).
    @Test fun reloginInFlightAcrossLogoutLeavesNoCredentialsOrSession() = runBlocking {
        creds.save(Credentials("testuser", "secret", null))
        val postArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = serve(onPost = { postArrived.countDown(); release.await(5, TimeUnit.SECONDS) })
        val relogin = async(Dispatchers.IO) { auth.relogin() }
        await(postArrived)
        val logout = async(Dispatchers.IO) { auth.logout() }
        // The logout drops the credentials at once, then waits for the login in flight to finish.
        while (creds.load() != null) Thread.sleep(10)
        release.countDown()

        assertFalse(relogin.await())
        logout.await()
        assertNull(creds.load())
        assertNull(jar.cookie("cf"))
        assertFalse(auth.isLoggedIn)
        assertEquals("dev1", jar.cookie("sf"))
        // The site logout comes after the voided login, so it ends the session that login opened.
        assertEquals("GET /user/logout/", log.last())
    }

    // A logout that lands between relogin's credential read and its login must void it (D2).
    @Test fun logoutWhileReloginReadsCredentialsVoidsIt() = runBlocking {
        val decrypting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowBox = object : SecretBox {
            override fun encrypt(plain: ByteArray) = box.encrypt(plain)
            override fun decrypt(cipher: ByteArray): ByteArray {
                decrypting.countDown()
                release.await(5, TimeUnit.SECONDS)
                return box.decrypt(cipher)
            }
        }
        val slowCreds = CredentialStore(tmp.root.resolve("creds.bin"), slowBox)
        slowCreds.save(Credentials("testuser", "secret", null))
        val auth = AuthService(SiteHttp(server.url("/"), jar), jar, slowCreds)
        val log = serve()

        val relogin = async(Dispatchers.IO) { auth.relogin() }
        await(decrypting)
        auth.logout()
        release.countDown()

        assertFalse(relogin.await())
        assertTrue(tmp.root.resolve("creds.bin").let { !it.exists() })
        assertNull(jar.cookie("cf"))
        assertTrue("no login after the logout: $log", log.none { it.startsWith("POST") })
    }

    // A manual login and a background relogin share one jar: they run one after the other (D2).
    @Test fun loginAndReloginNeverInterleave() = runBlocking {
        creds.save(Credentials("testuser", "secret", null))
        val firstPost = CountDownLatch(1)
        val log = serve(onPost = { n -> if (n == 1) { firstPost.countDown(); Thread.sleep(500) } })
        val login = async(Dispatchers.IO) { auth.login("testuser", "secret", null) }
        await(firstPost)
        val relogin = async(Dispatchers.IO) { auth.relogin() }

        assertEquals(LoginResult.Success, login.await())
        assertTrue(relogin.await())
        assertEquals(listOf("GET /user/login/", "POST /user/login/", "GET /json/device/",
            "GET /user/login/", "POST /user/login/", "GET /json/device/"), log.toList())
    }

    // Leaving the screen mid-login must not strand a session cookie without its credentials (D7).
    @Test fun cancellingALoginInFlightStillCompletesIt() = runBlocking {
        val postArrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        serve(onPost = { postArrived.countDown(); release.await(5, TimeUnit.SECONDS) })
        val job = launch(Dispatchers.IO) { auth.login("testuser", "secret", null) }
        await(postArrived)
        job.cancel()
        release.countDown()
        job.join()
        assertEquals("sess2", jar.cookie("cf"))
        assertEquals(Credentials("testuser", "secret", null), creds.load())
    }

    @Test fun logoutCompletesEvenWhenItsCallerIsCancelled() = runBlocking {
        creds.save(Credentials("testuser", "secret", null))
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(5, TimeUnit.SECONDS)
                return MockResponse.Builder().addHeader("Set-Cookie", "cf=late; Max-Age=1000; path=/").body("bye").build()
            }
        }
        val job = launch(Dispatchers.IO) { auth.logout() }
        await(arrived)
        job.cancel()
        release.countDown()
        job.join()
        assertNull(creds.load())
        assertNull(jar.cookie("cf"))
    }

    private fun loggedIn() {
        form()
        server.enqueue(page("login_success.html", "sf=dev1", "cf=sess1"))
        server.enqueue(MockResponse.Builder().body("ok").build())
        runBlocking { assertEquals(LoginResult.Success, auth.login("testuser", "secret", null)) }
    }

    // A refused relogin leaves no dead session behind, so the next launch starts at Login instead of
    // trying the same credentials again (D6); a wrong password also drops the stored credentials (D1).
    @Test fun reloginRefusedForWrongPasswordDropsSessionAndCredentials() = runTest {
        loggedIn()
        form()
        server.enqueue(page("login_wrong.html"))
        assertFalse(auth.relogin())
        assertFalse(auth.isLoggedIn)
        assertNull(creds.load())
        assertEquals("dev1", jar.cookie("sf"))
    }

    @Test fun reloginRefusedOtherwiseDropsSessionKeepsCredentials() = runTest {
        loggedIn()
        form()
        server.enqueue(page("login_form.html"))
        assertFalse(auth.relogin())
        assertFalse(auth.isLoggedIn)
        assertEquals(Credentials("testuser", "secret", null), creds.load())
        assertEquals("dev1", jar.cookie("sf"))
    }
}
