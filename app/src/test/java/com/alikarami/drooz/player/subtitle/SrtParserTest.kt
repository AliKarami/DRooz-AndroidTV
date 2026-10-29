package com.alikarami.drooz.player.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtParserTest {
    @Test fun parsesBasicBlocks() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,500\nHello\nthere\n\n2\n00:01:00,000 --> 00:01:01,000\nBye\n")
        assertEquals(listOf(SubCue(1000, 2500, "Hello\nthere"), SubCue(60000, 61000, "Bye")), cues)
    }

    @Test fun handlesBomCrlfAndDotMillis() {
        val cues = SrtParser.parse("﻿1\r\n00:00:01.000 --> 00:00:02.000\r\nA\r\n")
        assertEquals(listOf(SubCue(1000, 2000, "A")), cues)
    }

    @Test fun stripsFormattingTags() {
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n<i>Hi</i> <font color=\"#fff\">you</font> {\\an8}x\n")
        assertEquals("Hi you x", cues.single().text)
    }

    @Test fun skipsMalformedBlocksKeepsRest() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:xx --> bad\nZ\n\n3\n00:00:03,000 --> 00:00:04,000\nB\n"
        assertEquals(listOf("A", "B"), SrtParser.parse(srt).map { it.text })
    }

    @Test fun missingIndexLinesStillParse() {
        val cues = SrtParser.parse("00:00:01,000 --> 00:00:02,000\nA\n\n00:00:03,000 --> 00:00:04,000\nB")
        assertEquals(2, cues.size)
        assertEquals("B", cues[1].text)
    }

    @Test fun htmlInsteadOfSrtGivesNoCues() {
        assertEquals(emptyList<SubCue>(), SrtParser.parse("<!DOCTYPE html><html><body>login</body></html>"))
    }

    @Test fun persianTextKeptVerbatim() {
        val text = "من فقط می‌خواهم"
        val cues = SrtParser.parse("1\n00:00:01,000 --> 00:00:02,000\n$text\n")
        assertEquals(text, cues.single().text)
    }
}
