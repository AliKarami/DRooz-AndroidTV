package com.alikarami.drooz

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun applicationIdIsDrooz() {
        assertEquals("com.alikarami.drooz", BuildConfig.APPLICATION_ID)
    }
}
