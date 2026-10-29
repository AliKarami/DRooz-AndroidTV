package com.alikarami.drooz.site

import com.alikarami.drooz.site.auth.AuthService
import com.alikarami.drooz.site.auth.CredentialStore
import com.alikarami.drooz.site.auth.Credentials
import com.alikarami.drooz.site.auth.SecretBox
import com.alikarami.drooz.site.http.PersistentCookieJar
import com.alikarami.drooz.site.http.SiteHttp
import com.alikarami.drooz.site.model.Episode
import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import com.alikarami.drooz.testutil.fixture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.CoroutineContext

class SiteRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var creds: CredentialStore
    private lateinit var repo: SiteRepository

    private val box = object : SecretBox {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray) = cipher.reversedArray()
    }

    @Before fun start() {
        server.start()
        val jar = PersistentCookieJar(tmp.newFile("cookies"), siteHost = server.hostName)
        val http = SiteHttp(server.url("/"), jar)
        creds = CredentialStore(tmp.root.resolve("creds.bin"), box)
        repo = SiteRepository(http, AuthService(http, jar, creds))
    }

    @After fun stop() = server.close()

    private fun page(name: String, vararg cookies: String) = MockResponse.Builder().body(fixture(name))
        .apply { cookies.forEach { addHeader("Set-Cookie", "$it; Max-Age=31536000; path=/") } }.build()

    private fun ok(body: String = "ok") = MockResponse.Builder().body(body).build()

    private fun expired() {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/user/login/").build())
        server.enqueue(page("login_form.html"))
    }

    private fun requests(): List<RecordedRequest> = List(server.requestCount) { server.takeRequest() }

    /** Counts dispatches, so a test can tell whether work was moved onto it. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatches = 0
        override fun isDispatchNeeded(context: CoroutineContext) = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            Dispatchers.IO.dispatch(context, block)
        }
    }

    // HTML parsing is heavy on a TV CPU: it must run on the parse dispatcher, never the caller's (main) thread.
    @Test fun parsingRunsOnParseDispatcher() = runTest {
        val parse = CountingDispatcher()
        val jar = PersistentCookieJar(tmp.newFile("cookies2"), siteHost = server.hostName)
        val http = SiteHttp(server.url("/"), jar)
        val repo = SiteRepository(http, AuthService(http, jar, creds), parseDispatcher = parse)
        server.enqueue(page("home.html"))
        server.enqueue(page("list_new_films.html"))
        server.enqueue(page("search_pluribus.html"))
        server.enqueue(page("title_series_pluribus.html"))
        repo.home()
        val afterHome = parse.dispatches
        repo.list("/archive/category/new-films/")
        val afterList = parse.dispatches
        repo.search("pluribus")
        val afterSearch = parse.dispatches
        repo.title(TitleRef(TitleKind.SERIES, 5735053, "pluribus"))
        assertTrue("home", afterHome > 0)
        assertTrue("list", afterList > afterHome)
        assertTrue("search", afterSearch > afterList)
        assertTrue("title", parse.dispatches > afterSearch)
    }

    @Test fun homeParsesFixture() = runTest {
        server.enqueue(page("home.html"))
        assertEquals(6, repo.home().featured.size)
    }

    @Test fun listPage3Path() = runTest {
        server.enqueue(page("list_new_films.html"))
        repo.list("/archive/category/new-films/", 3)
        assertEquals("/archive/category/new-films/3/", server.takeRequest().target)
    }

    @Test fun listPage1UsesPathAsIs() = runTest {
        server.enqueue(page("list_new_films.html"))
        repo.list("/archive/category/new-films/")
        assertEquals("/archive/category/new-films/", server.takeRequest().target)
    }

    @Test fun listPageKeepsQuery() = runTest {
        server.enqueue(page("list_new_films.html"))
        repo.list("/archive/category/anime/?type=film", 2)
        assertEquals("/archive/category/anime/2/?type=film", server.takeRequest().target)
    }

    @Test fun searchEncodesQuery() = runTest {
        server.enqueue(page("search_pluribus.html"))
        assertEquals(1, repo.search("plur ibus").size)
        assertEquals("/search/?s=plur+ibus", server.takeRequest().target)
    }

    @Test fun titleUsesRefPath() = runTest {
        server.enqueue(page("title_series_pluribus.html"))
        val ref = TitleRef(TitleKind.SERIES, 5735053, "pluribus")
        assertEquals(9, repo.title(ref).catalog.main.single().episodes.size)
        assertEquals("/post/series/5735053/pluribus/", server.takeRequest().target)
    }

    @Test fun openStreamRequestsCorrectPath() = runTest {
        server.enqueue(page("stream_film_ouatime.html"))
        val ep = Episode(1, 1, "371517", emptyList(), emptyList(), emptyList(), false)
        val ref = TitleRef(TitleKind.FILM, 5744530, "once-upon-a-time-in-the-middle-east")
        val s = repo.openStream(ep, ref, "original", 720)
        assertEquals("/stream/371517/5744530/?lang=original&h=720", server.takeRequest().target)
        assertEquals("371517", s.streamId)
    }

    @Test fun setWatchlistPath() = runTest {
        server.enqueue(ok())
        repo.setWatchlist(5735053, "watch")
        assertEquals("/json/userWatchlist/5735053/watch/", server.takeRequest().target)
    }

    @Test fun watchlistToggleIsNeverRetried() {
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())
        server.enqueue(ok())
        assertThrows(SiteException.HttpError::class.java) { runBlocking { repo.setWatchlist(5735053, "watch") } }
        assertEquals(1, server.requestCount)
    }

    @Test fun relogsInOnceOnExpiredSession() = runTest {
        creds.save(Credentials("testuser", "secret", "09120000000"))
        expired()
        server.enqueue(page("login_form.html"))
        server.enqueue(page("login_success.html", "cf=sess2"))
        server.enqueue(ok())
        server.enqueue(page("home.html"))

        assertEquals(6, repo.home().featured.size)
        assertEquals(1, requests().count { it.method == "POST" })
    }

    @Test fun givesUpAfterSecondExpiry() {
        creds.save(Credentials("testuser", "secret", "09120000000"))
        expired()
        server.enqueue(page("login_form.html"))
        server.enqueue(page("login_success.html", "cf=sess2"))
        server.enqueue(ok())
        expired()

        assertThrows(SiteException.NotLoggedIn::class.java) { runBlocking { repo.home() } }
        assertEquals(1, requests().count { it.method == "POST" })
    }

    @Test fun serverErrorDuringReloginIsNotReportedAsLoggedOut() {
        creds.save(Credentials("testuser", "secret", "09120000000"))
        expired()
        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        val e = assertThrows(SiteException.HttpError::class.java) { runBlocking { repo.home() } }
        assertEquals(500, e.code)
    }

    @Test fun concurrentExpiriesTriggerOneLogin() {
        creds.save(Credentials("testuser", "secret", "09120000000"))
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val loggedIn = request.headers["Cookie"].orEmpty().contains("cf=new")
                val path = request.url.encodedPath
                return when {
                    request.method == "POST" -> page("login_success.html", "cf=new")
                    path.startsWith("/json/device/") -> ok()
                    path == "/user/login/" -> page("login_form.html")
                    loggedIn -> page("home.html")
                    else -> MockResponse.Builder().code(302).addHeader("Location", "/user/login/").build()
                }
            }
        }
        val pages = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            List(3) { async { repo.home() } }.awaitAll()
        }
        assertEquals(3, pages.size)
        assertEquals(1, requests().count { it.method == "POST" })
    }

    @Test fun noStoredCredentialsThrowsNotLoggedIn() {
        expired()
        assertThrows(SiteException.NotLoggedIn::class.java) { runBlocking { repo.home() } }
        assertEquals(0, requests().count { it.method == "POST" })
    }

    @Test fun subtitleFetchesAbsoluteUrlThroughTheSiteClient() = runBlocking {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHi\n"
        server.enqueue(ok(srt))
        assertEquals(srt, repo.subtitle(server.url("/json/subtitle/1/2/sub.srt?stream=3").toString()))
        assertEquals("/json/subtitle/1/2/sub.srt?stream=3", server.takeRequest().target)
    }

    @Test fun subtitleRetriesDroppedRequests() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build())
        server.enqueue(ok("srt"))
        assertEquals("srt", repo.subtitle(server.url("/json/subtitle/1/2/sub.srt").toString()))
        assertEquals(2, server.requestCount)
    }
}
