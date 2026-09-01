package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderSettingsNavigationTest {
    @Test
    fun `stepped slider releases dpad at both boundaries`() {
        assertNull(steppedSliderValue(0, 0, 80, 5, increase = false))
        assertEquals(5, steppedSliderValue(0, 0, 80, 5, increase = true))
        assertEquals(75, steppedSliderValue(80, 0, 80, 5, increase = false))
        assertNull(steppedSliderValue(80, 0, 80, 5, increase = true))
    }

    @Test
    fun `discrete slider releases dpad at both boundaries`() {
        val values = listOf(5, 10, 15)

        assertNull(adjacentSliderValue(values, 5, increase = false))
        assertEquals(10, adjacentSliderValue(values, 5, increase = true))
        assertEquals(10, adjacentSliderValue(values, 15, increase = false))
        assertNull(adjacentSliderValue(values, 15, increase = true))
    }
}
