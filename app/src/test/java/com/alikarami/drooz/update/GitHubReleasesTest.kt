package com.alikarami.drooz.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest

class GitHubReleasesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var gh: GitHubReleases

    @Before fun start() {
        server.start()
        gh = GitHubReleases(OkHttpClient(), server.url("/latest").toString())
    }

    @After fun stop() = server.close()

    private val apkBytes = "fake apk bytes".toByteArray()
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    private fun release(sha: String?) = Release(AppVersion(9, 0, 0), "", server.url("/DRooz.apk").toString(), sha, apkBytes.size.toLong())

    @Test fun fetchesLatestWithUserAgent() = runTest {
        server.enqueue(MockResponse.Builder().body(
            """{"tag_name":"v1.3.0","assets":[{"name":"DRooz.apk","size":3,"browser_download_url":"https://x/DRooz.apk"}]}""",
        ).build())
        assertEquals(AppVersion(1, 3, 0), gh.latest()!!.version)
        assertEquals("DRooz-AndroidTV", server.takeRequest().headers["User-Agent"])
    }

    @Test fun noReleaseYetIsNull() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(gh.latest())
    }

    @Test fun serverErrorThrows() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())
        try { gh.latest(); fail() } catch (_: IOException) {}
    }

    @Test fun downloadsAndVerifiesTheHash() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
        val target = tmp.root.resolve("u/DRooz.apk")
        var last = 0f
        gh.download(release(apkSha), target) { last = it }
        assertEquals(String(apkBytes), target.readText())
        assertEquals(1f, last)
        assertFalse(tmp.root.resolve("u/DRooz.apk.part").exists())
    }

    @Test fun hashMismatchLeavesNoFile() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
        val target = tmp.root.resolve("DRooz.apk")
        try { gh.download(release("00".repeat(32)), target) {}; fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("hash"))
        }
        assertFalse(target.exists())
        assertFalse(tmp.root.resolve("DRooz.apk.part").exists())
    }
}
