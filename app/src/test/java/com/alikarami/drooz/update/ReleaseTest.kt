package com.alikarami.drooz.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    @Test fun parsesVersions() {
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3"))
        assertEquals(AppVersion(2, 0, 0), AppVersion.parse("v2.0"))
        assertEquals(AppVersion(1, 3, 0), AppVersion.parse("1.3.0-beta1"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse(""))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9))
        assertTrue(AppVersion(1, 2, 4) > AppVersion(1, 2, 3))
        assertTrue(AppVersion(2, 0, 0) > AppVersion(1, 99, 99))
        assertEquals(0, AppVersion(1, 2, 3).compareTo(AppVersion.parse("v1.2.3")!!))
    }

    private fun release(tag: String = "v1.2.4", extra: String = "", assets: String = APK) =
        """{"tag_name":"$tag","name":"x","body":"## What's new\n\n- **Faster.** Done (#5, thanks @a)\n\nRequires Android TV 9 or newer.",
           $extra "assets":[$assets],"html_url":"https://example"}"""

    private val APK = """{"name":"DRooz.apk","size":5861971,"browser_download_url":"https://g/DRooz.apk",
        "digest":"sha256:05132DE4aa1c9d4c267aaafdddf1f34fe33d2a0b0a82a4c346b87c16ebf55ff7"}"""

    @Test fun parsesGitHubLatestRelease() {
        val r = parseLatestRelease(release())!!
        assertEquals(AppVersion(1, 2, 4), r.version)
        assertEquals("https://g/DRooz.apk", r.apkUrl)
        assertEquals("05132de4aa1c9d4c267aaafdddf1f34fe33d2a0b0a82a4c346b87c16ebf55ff7", r.sha256)
        assertEquals(5861971L, r.size)
        assertEquals("• Faster. Done", r.notes)
    }

    @Test fun skipsWhatCantBeInstalled() {
        assertNull(parseLatestRelease(release(extra = "\"prerelease\":true,")))
        assertNull(parseLatestRelease(release(extra = "\"draft\":true,")))
        assertNull(parseLatestRelease(release(tag = "nightly")))
        assertNull(parseLatestRelease(release(assets = """{"name":"other.zip","browser_download_url":"u"}""")))
    }

    @Test fun missingDigestMeansNoHashCheck() {
        val r = parseLatestRelease(release(assets = """{"name":"DRooz.apk","size":1,"browser_download_url":"u"}"""))!!
        assertNull(r.sha256)
    }
}
