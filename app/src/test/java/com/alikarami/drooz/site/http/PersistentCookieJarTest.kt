package com.alikarami.drooz.site.http

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PersistentCookieJarTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun call(jar: PersistentCookieJar, response: MockResponse) {
        server.enqueue(response)
        OkHttpClient.Builder().cookieJar(jar).build()
            .newCall(Request.Builder().url(server.url("/")).build()).execute().close()
    }

    @Test
    fun persistsCookiesAcrossInstances() {
        val file = tmp.newFile("cookies.jsonl")
        val jar = PersistentCookieJar(file, siteHost = server.hostName)
        call(jar, MockResponse.Builder()
            .addHeader("Set-Cookie", "sf=abc123; Max-Age=31536000; path=/")
            .addHeader("Set-Cookie", "cf=session42; Max-Age=31536000; path=/")
            .build())

        val reloaded = PersistentCookieJar(file, siteHost = server.hostName)
        assertEquals("session42", reloaded.cookie("cf"))
        assertEquals("abc123", reloaded.cookie("sf"))
    }

    @Test
    fun alwaysSendsScrCookie() {
        val jar = PersistentCookieJar(tmp.newFile(), siteHost = server.hostName)
        call(jar, MockResponse.Builder().build())
        assertTrue(server.takeRequest().headers["Cookie"]!!.contains("scr=1"))
    }

    @Test
    fun scrIsNeverPersisted() {
        val file = tmp.newFile()
        val jar = PersistentCookieJar(file, siteHost = server.hostName)
        call(jar, MockResponse.Builder().addHeader("Set-Cookie", "scr=2; path=/").build())
        assertNull(PersistentCookieJar(file, siteHost = server.hostName).cookie("scr"))
    }

    @Test
    fun expiredCookiesAreDropped() {
        val file = tmp.newFile()
        val jar = PersistentCookieJar(file, siteHost = server.hostName)
        call(jar, MockResponse.Builder().addHeader("Set-Cookie", "cf=old; Max-Age=0; path=/").build())
        assertNull(jar.cookie("cf"))
    }

    @Test
    fun clearKeepsOnly() {
        val jar = PersistentCookieJar(tmp.newFile(), siteHost = server.hostName)
        call(jar, MockResponse.Builder()
            .addHeader("Set-Cookie", "sf=keep; Max-Age=1000; path=/")
            .addHeader("Set-Cookie", "cf=drop; Max-Age=1000; path=/")
            .build())
        jar.clear(keep = setOf("sf"))
        assertEquals("keep", jar.cookie("sf"))
        assertNull(jar.cookie("cf"))
    }

    private fun line(name: String, value: String, domain: String) =
        """{"name":"$name","value":"$value","domain":"$domain","path":"/","expiresAt":${Long.MAX_VALUE},""" +
            """"secure":true,"httpOnly":true,"hostOnly":true}"""

    // The jar is built in Application.onCreate: one bad line must not crash every launch (D3).
    @Test
    fun invalidStoredCookieIsSkippedAndTheRestKept() {
        val file = tmp.newFile("cookies.jsonl")
        file.writeText(listOf(line("sf", "dev1", server.hostName), line("cf", "x", ""), line(" bad", "x", server.hostName),
            "{not json").joinToString("\n"))
        val jar = PersistentCookieJar(file, siteHost = server.hostName)
        assertEquals("dev1", jar.cookie("sf"))
        assertNull(jar.cookie("cf"))
    }

    @Test
    fun unreadableFileStartsEmpty() {
        val dir = tmp.newFolder("cookies.jsonl")
        dir.resolve("child").writeText("x")
        assertNull(PersistentCookieJar(dir, siteHost = server.hostName).cookie("sf"))
    }

    // A disk error while saving must not fail the HTTP call that delivered the cookie (D5).
    @Test
    fun failedPersistKeepsTheCookieInMemory() {
        val file = tmp.root.resolve("cookies.jsonl")
        tmp.newFolder("cookies.jsonl.tmp").resolve("child").writeText("x")
        val jar = PersistentCookieJar(file, siteHost = server.hostName)
        call(jar, MockResponse.Builder().addHeader("Set-Cookie", "cf=s1; Max-Age=1000; path=/").build())
        assertEquals("s1", jar.cookie("cf"))
    }

    @Test
    fun persistLeavesNoTempFile() {
        val file = tmp.root.resolve("cookies.jsonl")
        val jar = PersistentCookieJar(file, siteHost = server.hostName)
        call(jar, MockResponse.Builder().addHeader("Set-Cookie", "cf=s1; Max-Age=1000; path=/").build())
        assertTrue(file.exists())
        assertTrue(!tmp.root.resolve("cookies.jsonl.tmp").exists())
    }

    private val url = "https://example.test/".toHttpUrl()
    private fun cookie(name: String, value: String) = Cookie.Builder().name(name).value(value)
        .hostOnlyDomain("example.test").path("/").expiresAt(Long.MAX_VALUE).build()

    // Most responses re-send the same cookies: a synced write for each would be wasted disk time (M1).
    @Test
    fun unchangedCookiesAreNotRewritten() {
        var writes = 0
        val jar = PersistentCookieJar(tmp.root.resolve("c.jsonl"), siteHost = "example.test") { _, _ -> writes++ }
        jar.saveFromResponse(url, listOf(cookie("cf", "s1")))
        jar.saveFromResponse(url, listOf(cookie("cf", "s1")))
        assertEquals(1, writes)
        jar.saveFromResponse(url, listOf(cookie("cf", "s2")))
        assertEquals(2, writes)
    }

    // The synced write must not hold the monitor every request needs for its cookies (M1).
    @Test
    fun persistDoesNotBlockRequests() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jar = PersistentCookieJar(tmp.root.resolve("c.jsonl"), siteHost = "example.test") { _, _ ->
            writing.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val saver = thread { jar.saveFromResponse(url, listOf(cookie("cf", "s1"))) }
        assertTrue(writing.await(5, TimeUnit.SECONDS))
        val loaded = CountDownLatch(1)
        thread { jar.loadForRequest(url); loaded.countDown() }
        val unblocked = loaded.await(2, TimeUnit.SECONDS)
        release.countDown()
        saver.join()
        assertTrue(unblocked)
    }
}
