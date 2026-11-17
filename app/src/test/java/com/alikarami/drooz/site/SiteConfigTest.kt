package com.alikarami.drooz.site

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteConfigTest {
    private fun n(s: String) = SiteConfig.normalize(s)?.toString()

    @Test fun bareHostGetsHttps() = assertEquals("https://site.example/", n("site.example"))
    @Test fun pathQueryAndSpacesDropped() = assertEquals("https://site.example/", n("  https://site.example/post/1/?a=b#c "))
    @Test fun explicitSchemeAndPortKept() = assertEquals("http://site.example:8080/", n("http://site.example:8080"))
    @Test fun hostIsLowercased() = assertEquals("https://site.example/", n("Site.Example"))
    @Test fun rejectsGarbage() {
        assertNull(n(""))
        assertNull(n("not a site"))
        assertNull(n("ftp://site.example"))
        assertNull(n("nodot"))
    }
}
