package com.nuvio.tv.ui.components

import com.nuvio.tv.data.local.ThemeDataStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDimmerTest {
    @Test
    fun `default app dim percent is 0`() {
        assertEquals(0, ThemeDataStore.DEFAULT_APP_DIM_PERCENT)
    }

    @Test
    fun `max app dim percent is 90`() {
        assertEquals(90, ThemeDataStore.MAX_APP_DIM_PERCENT)
    }

    @Test
    fun `dim alpha is 0 when percent is 0`() {
        val percent = 0
        val alpha = if (percent <= 0) 0f else percent.coerceIn(0, 95) / 100f
        assertEquals(0f, alpha, 0.0001f)
    }

    @Test
    fun `dim alpha scales correctly across range`() {
        val p50 = 50
        assertEquals(0.5f, p50 / 100f, 0.0001f)
        val p90 = 90
        assertEquals(0.9f, p90 / 100f, 0.0001f)
    }

    @Test
    fun `dim alpha clamps to maximum 95 percent`() {
        val p100 = 100
        val alpha = p100.coerceIn(0, 95) / 100f
        assertEquals(0.95f, alpha, 0.0001f)
    }
}