package com.alikarami.drooz.site

import com.alikarami.drooz.site.http.PersistentCookieJar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** An install from before the address was asked for keeps its session: the address comes from its cookies. */
class LegacySessionAddressTest {
    @get:Rule val tmp = TemporaryFolder()

    @After fun reset() { SiteConfig.base = null }

    private fun jarWith(vararg lines: String): PersistentCookieJar {
        val f = File(tmp.root, "cookies.jsonl")
        f.writeText(lines.joinToString("\n"))
        return PersistentCookieJar(f, siteHost = { SiteConfig.host })
    }

    private fun cookie(name: String, domain: String) =
        """{"name":"$name","value":"v","domain":"$domain","path":"/","expiresAt":${Long.MAX_VALUE / 2},""" +
            """"secure":true,"httpOnly":true,"hostOnly":true}"""

    @Test
    fun noSavedAddressTakesTheSessionCookiesSite() {
        val jar = jarWith(cookie("sf", "old.site.example"), cookie("cf", "old.site.example"))
        val file = File(tmp.root, "server.txt")
        var changed = 0
        val address = FileServerAddress(file, onChanged = { changed++ })

        adoptLegacySessionAddress(address, jar, sessionCookie = "cf")

        assertEquals("https://old.site.example/", address.current.toString())
        assertEquals("https://old.site.example/", file.readText())
        assertEquals("the session is kept", 0, changed)
        assertEquals("v", jar.cookie("cf"))
        assertEquals("v", jar.cookie("sf"))
    }

    @Test
    fun savedAddressIsLeftAlone() {
        val jar = jarWith(cookie("cf", "old.site.example"))
        val file = File(tmp.root, "server.txt").apply { writeText("https://new.site.example/") }
        val address = FileServerAddress(file)

        adoptLegacySessionAddress(address, jar, sessionCookie = "cf")

        assertEquals("https://new.site.example/", address.current.toString())
    }

    @Test
    fun noSessionMeansNoAddress() {
        val jar = jarWith(cookie("sf", "old.site.example"))
        val address = FileServerAddress(File(tmp.root, "server.txt"))

        adoptLegacySessionAddress(address, jar, sessionCookie = "cf")

        assertNull(address.current)
        assertEquals("the device id survives", "v", jar.cookie("sf"))
    }
}
