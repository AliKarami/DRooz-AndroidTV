@file:OptIn(UnstableApi::class)

package com.alikarami.drooz.player.engine

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class EngineMappingTest {
    /** android.net.Uri is a stub on the JVM, so build a DataSpec without running its constructor. */
    private fun blankDataSpec(): DataSpec {
        val f = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return (f.get(null) as sun.misc.Unsafe).allocateInstance(DataSpec::class.java) as DataSpec
    }

    @Test fun httpStatusFoundInCauseChain() {
        val http = InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), blankDataSpec(), ByteArray(0))
        val wrapped = RuntimeException(
            PlaybackException("x", http, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
        assertEquals(403, httpStatusOf(wrapped))
        assertNull(httpStatusOf(IOException()))
        assertNull(httpStatusOf(null))
    }

    @Test fun audioLabelPrefersLabelThenIdThenLanguage() {
        assertEquals("original-6CH", audioLabel(Format.Builder().setLabel("original-6CH").setId("a").build()))
        assertEquals("dubbed-fa", audioLabel(Format.Builder().setId("dubbed-fa").build()))
        assertEquals("en", audioLabel(Format.Builder().setLanguage("en").build()))
        assertEquals("und", audioLabel(Format.Builder().build()))
    }
}
