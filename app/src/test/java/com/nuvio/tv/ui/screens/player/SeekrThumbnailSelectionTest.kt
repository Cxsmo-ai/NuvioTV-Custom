package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekrThumbnailSelectionTest {
    @Test
    fun keepsFloorCueBeforeMidpoint() {
        assertFalse(preferSuccessorCue(14_999, 10_000, 20_000))
        assertFalse(preferSuccessorCue(15_000, 10_000, 20_000))
    }

    @Test
    fun choosesSuccessorAfterMidpoint() {
        assertTrue(preferSuccessorCue(15_001, 10_000, 20_000))
    }

    @Test
    fun rejectsMalformedOrOpenEndedCue() {
        assertFalse(preferSuccessorCue(20_000, 20_000, 20_000))
        assertFalse(preferSuccessorCue(20_000, 30_000, 20_000))
    }
}
