package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeDataStoreDimmerTest {
    @Test
    fun `app dimmer defaults to off and clamps unsafe values`() {
        assertEquals(0, ThemeDataStore.DEFAULT_APP_DIM_PERCENT)
        assertEquals(0, ThemeDataStore.normalizeAppDimPercent(-25))
        assertEquals(45, ThemeDataStore.normalizeAppDimPercent(45))
        assertEquals(
            ThemeDataStore.MAX_APP_DIM_PERCENT,
            ThemeDataStore.normalizeAppDimPercent(100)
        )
    }
}
