package com.alikarami.drooz.player

import com.alikarami.drooz.site.model.TitleKind
import com.alikarami.drooz.site.model.TitleRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** android.content.Intent extras are no-ops in JVM tests; toIntent/from delegate to these helpers. */
class PlayerArgsTest {
    private val args = PlayerArgs(TitleRef(TitleKind.SERIES, 1234, "some-show"), "371517", true)

    @Test fun intentRoundTrip() {
        val extras = args.toExtras()
        assertEquals(setOf("ref_path", "stream_id", "dub"), extras.keys)
        assertEquals(args, PlayerArgs.fromExtras { extras[it] })
    }

    @Test fun missingExtrasGiveNull() {
        val extras = args.toExtras()
        assertNull(PlayerArgs.fromExtras { null })
        assertNull(PlayerArgs.fromExtras { if (it == "stream_id") null else extras[it] })
        assertNull(PlayerArgs.fromExtras { if (it == "dub") null else extras[it] })
        assertNull(PlayerArgs.fromExtras { if (it == "ref_path") "/not/a/title/" else extras[it] })
        assertNull(PlayerArgs.fromExtras { if (it == "stream_id") "" else extras[it] })
    }
}
