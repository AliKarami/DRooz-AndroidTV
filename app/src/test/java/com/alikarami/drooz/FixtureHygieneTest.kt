package com.alikarami.drooz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The repo is public: fixtures must never carry the real user's identifiers. */
class FixtureHygieneTest {
    private val dir = File("src/test/resources/fixtures")
    private val forbidden = listOf(
        Regex("/dln/(?!USERTOKEN)[0-9a-f]{30,}/"),
        Regex("/json/m3u8/\\d+/\\d+/(?!USERTOKEN)"),
        Regex("key=(?!KEY)[0-9a-f]{8,}"),
        Regex("09\\d\\d\\*\\*\\*(?!0000)\\d{4}"),
        Regex("avatar/(?!100001_|no-avatar\\.)"),
        Regex("/play/(?!0{64})[0-9a-f]{64}"),
        Regex("name=\"password\" value=\"(?!secret\")[^\"]+\""),
    )

    private fun fixtures() = dir.listFiles { f -> f.extension == "html" }.orEmpty().toList()

    @Test
    fun fixturesExist() {
        assertTrue("no fixtures in ${dir.absolutePath}", fixtures().size >= 10)
    }

    @Test
    fun noPersonalIdentifiers() {
        for (file in fixtures()) {
            val text = file.readText()
            for (pattern in forbidden) {
                assertFalse("${file.name} matches $pattern", pattern.containsMatchIn(text))
            }
        }
    }

    @Test
    fun streamPageUsesPlaceholderToken() {
        assertTrue(File(dir, "stream_film_ouatime.html").readText().contains("USERTOKEN"))
    }
}
