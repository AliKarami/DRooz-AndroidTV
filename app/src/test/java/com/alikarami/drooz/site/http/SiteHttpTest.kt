package com.alikarami.drooz.site.http

import com.alikarami.drooz.site.SiteConfig
import com.alikarami.drooz.site.SiteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteHttpTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var http: SiteHttp
    private val okHttpClient = OkHttpClient()

    @Before fun start() {
        server.start()
        http = SiteHttp(server.url("/"), PersistentCookieJar(tmp.newFile(), siteHost = server.hostName), okHttpClient)
    }

    @After fun stop() = server.close()

    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    @Test fun sendsChromeUserAgent() = runTest {
        server.enqueue(ok("<html></html>"))
        http.get("/")
        assertEquals(SiteConfig.USER_AGENT, server.takeRequest().headers["User-Agent"])
    }

    @Test fun returnsBodyAndFinalPath() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/archive/series/").build())
        server.enqueue(ok("<html>list</html>"))
        val r = http.get("/series")
        assertEquals("/archive/series/", r.finalPath)
        assertEquals("<html>list</html>", r.body)
    }

    @Test fun redirectToLoginOnAuthPageThrowsSessionExpired() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/user/login/").build())
        server.enqueue(ok("<html>login form</html>"))
        assertThrows(SiteException.SessionExpired::class.java) { kotlinx.coroutines.runBlocking { http.get("/") } }
    }

    @Test fun loginPathDoesNotThrowSessionExpired() = runTest {
        server.enqueue(ok("<html>login form</html>"))
        assertEquals("<html>login form</html>", http.get("/user/login/", requiresAuth = false).body)
    }

    @Test fun non200ThrowsHttpError() {
        server.enqueue(MockResponse.Builder().code(500).body("oops").build())
        val e = assertThrows(SiteException.HttpError::class.java) { kotlinx.coroutines.runBlocking { http.get("/") } }
        assertEquals(500, e.code)
    }

    @Test fun cloudflareChallengeThrowsBlocked() {
        server.enqueue(MockResponse.Builder().code(403).body("<title>Just a moment...</title><div id=cf-chl-widget>").build())
        assertThrows(SiteException.Blocked::class.java) { kotlinx.coroutines.runBlocking { http.get("/") } }
    }

    @Test fun jsRedirectStubWithoutScrIsNotReturnedAsPage() {
        server.enqueue(ok("<script>function setCookie(cvalue){} if(window.devicePixelRatio) setCookie(window.devicePixelRatio);</script>"))
        val e = assertThrows(SiteException.ParseError::class.java) { kotlinx.coroutines.runBlocking { http.get("/") } }
        assertEquals("scr-stub", e.what)
    }

    @Test fun networkFailureThrowsNetworkError() {
        server.close()
        assertThrows(SiteException.NetworkError::class.java) { kotlinx.coroutines.runBlocking { http.get("/") } }
    }

    @Test fun postsMultipartFields() = runTest {
        server.enqueue(ok("done"))
        http.postMultipart("/user/login/", listOf("username" to "testuser", "password" to "secret"))
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        val body = req.body!!.utf8()
        assertEquals(true, body.contains("name=\"username\"") && body.contains("testuser"))
    }

    private fun fastHttp(postTimeoutMs: Long = 300) =
        SiteHttp(server.url("/"), PersistentCookieJar(tmp.newFile(), siteHost = server.hostName),
            timeoutMs = 300, maxAttempts = 3, postTimeoutMs = postTimeoutMs)

    private fun hung() = MockResponse.Builder().headersDelay(2, java.util.concurrent.TimeUnit.SECONDS).body("late").build()

    @Test fun getRetriesWhenServerNeverAnswers() = runTest {
        server.enqueue(hung())
        server.enqueue(ok("<html>second try</html>"))
        assertEquals("<html>second try</html>", fastHttp().get("/").body)
        assertEquals(2, server.requestCount)
    }

    @Test fun getRetriesOnGatewayErrors() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())
        server.enqueue(MockResponse.Builder().code(504).body("timeout").build())
        server.enqueue(ok("<html>third</html>"))
        assertEquals("<html>third</html>", fastHttp().get("/").body)
    }

    @Test fun getGivesUpAfterMaxAttempts() {
        repeat(3) { server.enqueue(hung()) }
        assertThrows(SiteException.NetworkError::class.java) { kotlinx.coroutines.runBlocking { fastHttp().get("/") } }
        assertEquals(3, server.requestCount)
    }

    private fun timedHttp(callTimeoutMs: Long = 20_000, retryDelayMs: Long = 0) =
        SiteHttp(server.url("/"), PersistentCookieJar(tmp.newFile(), siteHost = server.hostName),
            timeoutMs = 300, maxAttempts = 3, callTimeoutMs = callTimeoutMs, retryDelayMs = retryDelayMs)

    private fun elapsedMs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    // A body that drips in (each byte inside the read timeout) is cut off by the whole-call limit (D9).
    @Test fun drippingBodyIsCutOffByTheCallTimeout() {
        repeat(3) {
            server.enqueue(MockResponse.Builder().body("x".repeat(100)).throttleBody(1, 100, TimeUnit.MILLISECONDS).build())
        }
        val ms = elapsedMs {
            assertThrows(SiteException.NetworkError::class.java) { runBlocking { timedHttp(callTimeoutMs = 500).get("/") } }
        }
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test fun retriesWaitBetweenAttempts() {
        repeat(2) { server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build()) }
        server.enqueue(ok("third"))
        val ms = elapsedMs { assertEquals("third", runBlocking { timedHttp(retryDelayMs = 300).get("/").body }) }
        assertTrue("took $ms ms", ms >= 600)
    }

    @Test fun tooManyRequestsIsRetriedAfterRetryAfter() {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "1").body("slow down").build())
        server.enqueue(ok("fine"))
        val ms = elapsedMs { assertEquals("fine", runBlocking { timedHttp().get("/").body }) }
        assertEquals(2, server.requestCount)
        assertTrue("took $ms ms", ms >= 1_000)
    }

    @Test fun tooManyRequestsWithALongRetryAfterIsNotRetried() {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "3600").body("slow down").build())
        server.enqueue(ok("must not be used"))
        val e = assertThrows(SiteException.HttpError::class.java) { runBlocking { timedHttp().get("/") } }
        assertEquals(429, e.code)
        assertEquals(1, server.requestCount)
    }

    @Test fun getWithRetryDisabledFailsAfterOneAttempt() {
        server.enqueue(hung())
        server.enqueue(ok("must not be used"))
        assertThrows(SiteException.NetworkError::class.java) {
            kotlinx.coroutines.runBlocking { fastHttp().get("/json/userWatchlist/1/watch/", retry = false) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun slowLoginPostIsAllowedMoreTimeThanGets() = runTest {
        server.enqueue(MockResponse.Builder().headersDelay(1, java.util.concurrent.TimeUnit.SECONDS).body("logged in").build())
        assertEquals("logged in", fastHttp(postTimeoutMs = 5_000).postMultipart("/user/login/", listOf("a" to "b")).body)
    }

    @Test fun postIsNeverRetried() {
        server.enqueue(hung())
        server.enqueue(ok("must not be used"))
        assertThrows(SiteException.NetworkError::class.java) {
            kotlinx.coroutines.runBlocking { fastHttp().postMultipart("/user/login/", listOf("a" to "b")) }
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        server.enqueue(MockResponse.Builder().body("x").headersDelay(5, TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.Default) { http.get("/slow/") }
        delay(300)
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        assertTrue((System.nanoTime() - t0) < 1_000_000_000)
        // The cancelled call leaves OkHttp's dispatcher once its failure callback has returned (a moment later
        // on a loaded machine); it must be long before the 5 s response would have come.
        val deadline = System.nanoTime() + 1_000_000_000
        while (okHttpClient.dispatcher.runningCallsCount() != 0 && System.nanoTime() < deadline) delay(10)
        assertTrue(okHttpClient.dispatcher.runningCallsCount() == 0)
    }
}
