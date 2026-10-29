package com.alikarami.drooz.ui.theme

import androidx.tv.material3.ClickableSurfaceScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FocusTest {
    @Test fun focusScalesMatchSpec() {
        assertEquals(1.08f, FocusScale.CARD, 0f)
        assertEquals(1.1f, FocusScale.TILE, 0f)
    }

    /** Guards against double scaling: Surface's default focused scale is 1.1x. */
    @Test fun surfaceOwnScaleIsDisabled() {
        assertSame(ClickableSurfaceScale.None, droozSurfaceScale)
    }
}
