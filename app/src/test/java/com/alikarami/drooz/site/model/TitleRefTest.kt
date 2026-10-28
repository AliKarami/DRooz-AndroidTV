package com.alikarami.drooz.site.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleRefTest {
    @Test fun refFromPath() = assertEquals(
        TitleRef(TitleKind.SERIES, 5735053, "pluribus"), TitleRef.fromPath("/post/series/5735053/pluribus/"))

    @Test fun refFromAbsoluteUrl() = assertEquals(
        TitleRef(TitleKind.FILM, 5744530, "once-upon-a-time-in-the-middle-east"),
        TitleRef.fromPath("https://robin.robert-redford.net/post/film/5744530/once-upon-a-time-in-the-middle-east/"))

    @Test fun refPathRoundTrip() {
        val ref = TitleRef(TitleKind.FILM, 5744530, "once-upon-a-time-in-the-middle-east")
        assertEquals(ref, TitleRef.fromPath(ref.path))
        assertEquals("/post/film/5744530/once-upon-a-time-in-the-middle-east/", ref.path)
    }

    @Test fun nonTitlePath() = assertNull(TitleRef.fromPath("/archive/series/"))
}
