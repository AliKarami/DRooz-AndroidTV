package com.alikarami.drooz.site.parse

import com.alikarami.drooz.site.SiteException
import com.alikarami.drooz.testutil.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPageParserTest {
    private val html = fixture("stream_film_ouatime.html")
    private val s = StreamPageParser.parse(html, "371517")

    @Test fun masterUrl() = assertEquals(
        "https://robin.robert-redford.net/json/m3u8/371517/5744530/USERTOKEN/master.m3u8?key=KEY&expire=1800000000&load",
        s.masterUrl)

    @Test fun subtitles() {
        assertEquals(listOf("Farsi", "English", "English + Farsi"), s.subtitles.map { it.label })
        assertEquals(
            "https://robin.robert-redford.net/json/subtitle/582055/5744530/sub.srt?stream=371517&second=582056",
            s.subtitles[2].url)
    }

    @Test fun qualityLabels() = assertEquals(
        mapOf(2475 to "1080 [ 2.48 GB ]", 1100 to "720 [ 1.14 GB ]", 488 to "480 [ 553 MB ]"), s.qualityLabels)

    @Test fun gains() {
        assertEquals(841, s.gains.size)
        assertEquals(10.0078 to 7.81, s.gains[0])
        assertEquals(0.64, s.minGain)
        assertEquals(1.93, s.maxGain)
    }

    @Test fun iframes() {
        assertEquals(3, s.iframes.size)
        assertEquals(listOf(0.0, 6.5, 8.208), s.iframes[0].take(3))
    }

    @Test fun titleAndBackdrop() {
        assertEquals("Once Upon a Time in the Middle East", s.title)
        assertEquals("/img/1920-1080/ac89a599f201a486ad141d4d2defd72f.jpg.webp", s.backdropPath)
        assertEquals("371517", s.streamId)
    }

    @Test fun pageWithoutGainsOrIframesStillParses() {
        val stripped = html.lines().filterNot { "Gains" in it || "minGain" in it || "maxGain" in it || "IFrames[" in it }
            .joinToString("\n")
        val p = StreamPageParser.parse(stripped, "371517")
        assertTrue(p.gains.isEmpty())
        assertNull(p.minGain)
        assertTrue(p.iframes.isEmpty())
        assertEquals(s.masterUrl, p.masterUrl)
    }

    @Test fun pageWithoutPlaylistThrows() {
        val e = assertThrows(SiteException.ParseError::class.java) { StreamPageParser.parse("<html></html>", "1") }
        assertEquals("m3u8", e.what)
    }
}
